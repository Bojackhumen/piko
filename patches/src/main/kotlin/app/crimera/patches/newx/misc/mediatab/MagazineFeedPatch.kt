package app.crimera.patches.newx.misc.mediatab

import app.crimera.bytecode.Block
import app.crimera.bytecode.fieldReference
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.common.scopedMatchAll
import app.crimera.patches.newx.misc.extension.newXExtensionPatch
import app.crimera.patches.newx.models.newXInlineActionModelResolutionPatch
import app.crimera.patches.newx.models.newXPostModelResolutionPatch
import app.crimera.patches.newx.models.newXTimelineModelAdapterPatch
import app.crimera.patches.newx.models.newXTimelineModelResolutionPatch
import app.crimera.patches.newx.models.resolvedNewXInlineActionModels
import app.crimera.patches.newx.models.resolvedNewXPostModels
import app.crimera.patches.newx.models.resolvedNewXTimelineModels
import app.crimera.patches.newx.settings.Categories
import app.crimera.patches.newx.settings.newXToggle
import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.crimera.patches.newx.utils.unsupportedOnMount
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val MAGAZINE_EXTENSION = "Lapp/morphe/extension/newx/misc/MagazineFeed;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP_BUILDER = "Lkotlin/collections/builders/MapBuilder;"
private const val CAPTURE_DESCRIPTOR = "$MAGAZINE_EXTENSION->capturePostActions($OBJECT)V"
private const val HOME_RESELECTED_DESCRIPTOR = "$MAGAZINE_EXTENSION->onHomeReselected()V"

/**
 * One card action, as X's own 12.40.1 inline action bar issues it to the post-actions dispatcher.
 *
 * [mask] is the dispatcher's Kotlin default-argument mask: bit 3 (value 8) defaults the scribe map
 * to X's empty map, bit 4 (value 16) defaults the post id to null. X's create actions (like,
 * unlike, repost) pass a real, built scribe map and mask 16; its bookmark/unbookmark pass mask 24
 * (both defaulted) and its undo-repost mask 8. [realScribe] therefore builds the scribe map through
 * X's own builder for the mask-16 actions, and passes null for the others. Earlier Piko 2.0 builds
 * sent mask 24 for every action, so likes went down the empty-map path and never registered.
 */
private data class CardAction(
    val bridge: String,
    val actionType: String,
    val actionName: String,
    val mask: Int,
    val realScribe: Boolean,
)

// Names and action-type constants as X's own action bar passes them (12.40.1 InlineActionBar
// presenter / post-actions service).
private val CARD_ACTIONS = listOf(
    CardAction("likePost", "Favorite", "LIKE", mask = 16, realScribe = true),
    CardAction("unlikePost", "Unfavorite", "UNLIKE", mask = 16, realScribe = true),
    CardAction("repostPost", "Retweet", "REPOST", mask = 16, realScribe = true),
    // X's undo passes the retweet's own id only on the viewer's own retweet card; for the post
    // itself it sends no id (mask 8: empty scribe map, the null id kept), which is this call.
    CardAction("unrepostPost", "UndoRetweet", "UNREPOST", mask = 8, realScribe = false),
    CardAction("bookmarkPost", "AddToBookmarks", "BOOKMARK", mask = 24, realScribe = false),
    CardAction("unbookmarkPost", "RemoveFromBookmarks", "UNBOOKMARK", mask = 24, realScribe = false),
)

/**
 * X's post-actions service: the class whose static, fire-and-forget dispatcher
 * `(service, ContextualPost, actionType, name, MapBuilder, postId, mask)V` the action bar calls for
 * like, unlike, repost, bookmark and unbookmark, plus the static `(scribeSource)MapBuilder` helper
 * X uses to build the scribe map.
 */
private data class ResolvedPostActions(
    val serviceType: String,
    val dispatcher: Method,
    val scribeBuilder: String,
    val actionFields: Map<String, String>,
)

private fun Method.parameterDescriptors(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.smaliReference(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

context(context: BytecodePatchContext)
private fun resolvePostActions(): ResolvedPostActions {
    val contextualPost = resolvedNewXPostModels().contextualPostDescriptor
    val actionType = resolvedNewXInlineActionModels().inlineActionTypeField.type
    val classDefs = buildList<ClassDef> { context.classDefForEach { add(it) } }
    val dispatchers = classDefs.flatMap { classDef ->
        classDef.methods.filter { method ->
            val parameters = method.parameterDescriptors()
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                parameters.size == 7 &&
                parameters[0] == classDef.type &&
                parameters[1] == contextualPost &&
                parameters[2] == actionType &&
                parameters[3] == STRING &&
                parameters[4] == MAP_BUILDER &&
                parameters[5].startsWith("L") &&
                parameters[6] == "I"
        }
    }
    val dispatcher = requireExactlyOne("NewX post-actions dispatcher", dispatchers)
    val serviceClass = classDefs.single { it.type == dispatcher.definingClass }

    // X builds the scribe map with a static `(scribeSource)MapBuilder` helper on the same service
    // (it writes an "impression_id" entry, or returns an empty built map when the source is null).
    // Like/unlike/repost go through it; passing null yields the empty-but-built map X itself sends
    // when there is no impression context.
    val scribeBuilder = requireExactlyOne(
        "NewX post-actions scribe-map builder",
        serviceClass.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.returnType == MAP_BUILDER &&
                method.parameterDescriptors().let { it.size == 1 && it[0].startsWith("L") }
        },
    ).smaliReference()

    val enumClass = classDefs.single { it.type == actionType }
    val actionFields = CARD_ACTIONS.associate { action ->
        val field = requireExactlyOne(
            "NewX post action type ${action.actionType}",
            enumClass.fields.filter { field ->
                field.name == action.actionType &&
                    AccessFlags.STATIC.isSet(field.accessFlags) &&
                    field.type == actionType
            },
        )
        action.actionType to "${field.definingClass}->${field.name}:${field.type}"
    }
    return ResolvedPostActions(dispatcher.definingClass, dispatcher, scribeBuilder, actionFields)
}

/** Replaces an extension placeholder's body wholesale, growing its frame to [totalRegisters]. */
context(context: BytecodePatchContext)
private fun replaceBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    totalRegisters: Int,
    body: Block.(p0: Int) -> Unit,
) {
    val extension = context.mutableClassDefBy(MAGAZINE_EXTENSION)
    val placeholder = requireExactlyOne(
        "Piko 2.0 bridge $name",
        extension.methods.filter { method ->
            method.name == name &&
                method.returnType == returnType &&
                method.parameterDescriptors() == parameters &&
                AccessFlags.STATIC.isSet(method.accessFlags)
        },
    )
    val implementation = placeholder.implementation
        ?: throw PatchException("Piko 2.0 bridge $name has no implementation")
    val helper =
        if (implementation.registerCount >= totalRegisters) {
            placeholder
        } else {
            placeholder.cloneMutable(
                additionalRegisters = totalRegisters - implementation.registerCount,
            ).also { expanded ->
                extension.methods.remove(placeholder)
                extension.methods.add(expanded)
            }
        }
    val helperImplementation = helper.implementation
        ?: throw PatchException("Piko 2.0 bridge $name has no implementation")
    while (helperImplementation.instructions.isNotEmpty()) {
        helperImplementation.removeInstruction(helperImplementation.instructions.lastIndex)
    }
    // The stub body is replaced wholesale; v0.. are the locals the frame growth reserved below
    // the parameters, so the block never touches a parameter before reading it.
    helper.insertHook(0, relocateBranchTargets = false) { body(helper.p0Register) }
}

context(context: BytecodePatchContext)
private fun patchCardActions(actions: ResolvedPostActions) {
    val contextualPost = resolvedNewXPostModels().contextualPostDescriptor
    val timeline = resolvedNewXTimelineModels()

    // postResultOf(item): the UrtTimelinePost's postResult, which is the ContextualPost when the
    // post is available. A non-post item fails the cast and the extension falls back to links.
    replaceBridge("postResultOf", listOf(OBJECT), OBJECT, totalRegisters = 2) { p0 ->
        move(0, p0, OBJECT)
        checkCast(0, timeline.postDescriptor)
        iget(0, 0, timeline.postResultField)
        returnObject(0)
    }

    // likePost/unlikePost/repostPost/bookmarkPost/unbookmarkPost(service, post): X's own dispatcher
    // call, reproducing X's exact scribe map and mask per action (post id always left to default).
    // The post is always the row's ContextualPost, also on a repost row: the dispatcher reads the
    // target id from the ContextualPost's own "shown post" (rePostedPost ?: canonicalPost). The
    // reposted post is a different class (RePostedPost) and must not be passed here.
    CARD_ACTIONS.forEach { action ->
        replaceBridge(action.bridge, listOf(OBJECT, OBJECT), "V", totalRegisters = 9) { p0 ->
            move(0, p0, OBJECT)
            checkCast(0, actions.serviceType)
            move(1, p0 + 1, OBJECT)
            checkCast(1, contextualPost)
            sget(2, fieldReference(actions.actionFields.getValue(action.actionType)))
            constString(3, action.actionName)
            if (action.realScribe) {
                // Build X's scribe map (empty, as X does without an impression id) and pass it,
                // matching X's mask-16 create path exactly.
                constInt(4, 0)
                invokeStatic(methodReference(actions.scribeBuilder), 4)
                moveResult(4, MAP_BUILDER)
            } else {
                // bookmark/unbookmark: the mask-24 path substitutes X's empty map, so pass null.
                constInt(4, 0)
            }
            constInt(5, 0)
            constInt(6, action.mask)
            invokeStatic(methodReference(actions.dispatcher.smaliReference()), 0, 1, 2, 3, 4, 5, 6)
            returnVoid()
        }
    }

    // Capture the service when X's DI creates it: right after the superclass constructor call,
    // where `this` is initialized and still in p0.
    val serviceClass = context.mutableClassDefBy(actions.serviceType)
    val constructors = serviceClass.methods.filter { it.name == "<init>" }
    var hooked = 0
    constructors.forEach { constructor ->
        val instructions = constructor.instructions.toList()
        val superCall = instructions.indexOfFirst { instruction ->
            (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                instruction.getReference<MethodReference>()?.let { reference ->
                    reference.name == "<init>" && reference.definingClass == serviceClass.superclass
                } == true &&
                instruction.registersUsed.firstOrNull() == constructor.p0Register
        }
        // Constructors that delegate to this(...) reach the super call through another one.
        if (superCall < 0) return@forEach
        constructor.insertHook(superCall + 1, relocateBranchTargets = false) {
            invokeStatic(methodReference(CAPTURE_DESCRIPTOR), constructor.p0Register)
        }
        hooked++
    }
    if (hooked == 0) {
        throw PatchException("NewX post-actions service ${actions.serviceType} has no super-calling constructor")
    }
}

// X's home scroll-to-top / refresh handler (the one Piko's refresh setting also guards): on the
// home tabbed component, (ZZ)Z, reading the foreground auto-refresh timeout. It sends X's
// timeline refresh and scroll-to-top events, which is how both events are identified below.
private object MagazineHomeTopHandlerFingerprint : Fingerprint(
    definingClass = "Lcom/x/home/tabbed/",
    parameters = listOf("Z", "Z"),
    returnType = "Z",
    filters = listOf(
        string("timeline_auto_refresh_on_foreground_timeout_millis"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            name = "getInt",
            parameters = listOf("Ljava/lang/String;", "I"),
            returnType = "I",
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
    ),
)

/** One `timelineComponent.event(SINGLETON)` call: X's way of asking a URT timeline to act. */
private data class UrtEventCall(val call: String, val callOwner: String, val event: String)

private fun Method.urtEventCalls(): List<UrtEventCall> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@mapIndexedNotNull null
        val call = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
        if (call.returnType != "V" || call.parameterTypes.size != 1) return@mapIndexedNotNull null
        val argument = (instruction as? FiveRegisterInstruction)?.registerD ?: return@mapIndexedNotNull null
        // The event is a singleton object read just before the call.
        val writer = (index - 1 downTo maxOf(0, index - 8))
            .map { instructions[it] }
            .firstOrNull { (it as? OneRegisterInstruction)?.registerA == argument }
            ?: return@mapIndexedNotNull null
        if (writer.opcode != Opcode.SGET_OBJECT) return@mapIndexedNotNull null
        val event = writer.getReference<FieldReference>() ?: return@mapIndexedNotNull null
        if (event.type != event.definingClass) return@mapIndexedNotNull null
        UrtEventCall(
            call = "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}",
            callOwner = call.definingClass,
            event = "${event.definingClass}->${event.name}:${event.type}",
        )
    }
}

/**
 * Home re-selected (or the top bar tapped) sends X's scroll-to-top event to the current home page's
 * URT component, which only the native list listens to. With the card feed in its place, the
 * request is forwarded to the feed at the start of that handler; and the feed's "already at the
 * top" case refreshes through X's own refresh event, sent by [requestTimelineRefresh].
 */
context(context: BytecodePatchContext)
private fun patchHomeReselect() {
    val handler = requireExactlyOne(
        "NewX home scroll-to-top/refresh handler",
        MagazineHomeTopHandlerFingerprint.scopedMatchAll(),
    ).method
    val handlerEvents = handler.urtEventCalls().map { it.event }.toSet()
    if (handlerEvents.isEmpty()) {
        throw PatchException("NewX home handler ${handler.definingClass} sends no timeline event")
    }

    // The home component's no-argument method that sends exactly one timeline event, the same
    // scroll-to-top event the back handler sends (Piko's own two-event refresh is not it).
    val homeClass = context.mutableClassDefBy(handler.definingClass)
    val scrollTopHandler = requireExactlyOne(
        "NewX home scroll-to-top handler",
        homeClass.methods.filter { method ->
            method.returnType == "V" &&
                method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.name != "<init>" &&
                method.urtEventCalls().let { calls -> calls.size == 1 && calls[0].event in handlerEvents }
        },
    )
    val scrollTop = scrollTopHandler.urtEventCalls().single()
    // X's own timeline refresh: the back handler's other event.
    val refreshEvent = requireExactlyOne(
        "NewX timeline refresh event",
        handlerEvents.filter { it != scrollTop.event },
    )

    replaceBridge("requestTimelineRefresh", listOf(OBJECT), "V", totalRegisters = 3) { p0 ->
        move(0, p0, OBJECT)
        checkCast(0, scrollTop.callOwner)
        sget(1, fieldReference(refreshEvent))
        invokeInterface(methodReference(scrollTop.call), 0, 1)
        returnVoid()
    }
    scrollTopHandler.insertHook(0, relocateBranchTargets = false) {
        invokeStatic(methodReference(HOME_RESELECTED_DESCRIPTOR))
    }
}

// X's record of the posts the reader has seen, sent with the home timeline requests as
// seen_tweet_ids: the class that keeps "<user id>_recent_seen_post_ids".
private object SeenPostsCacheFingerprint : Fingerprint(
    definingClass = "Lcom/x/repositories/urt/",
    filters = listOf(string("_recent_seen_post_ids")),
)

private const val SEEN_QUEUE = "Ljava/util/concurrent/ConcurrentLinkedQueue;"
private const val URT_SCOPE = "Lcom/x/urt/"

/** True for "mark this timeline item's post as seen": tells posts apart and adds to the cache's queue. */
private fun Method.addsToSeenQueue(cacheTypes: Set<String>, timelinePost: String): Boolean {
    val instructions = implementation?.instructions ?: return false
    var tellsPosts = false
    var readsQueue = false
    var adds = false
    for (instruction in instructions) {
        when (instruction.opcode) {
            Opcode.INSTANCE_OF, Opcode.CHECK_CAST -> {
                if (instruction.getReference<TypeReference>()?.type == timelinePost) tellsPosts = true
            }
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE -> {
                val call = instruction.getReference<MethodReference>()
                if (call != null) {
                    if (call.definingClass in cacheTypes && call.returnType == SEEN_QUEUE) readsQueue = true
                    if (call.definingClass == SEEN_QUEUE && call.name == "add" && call.returnType == "Z") adds = true
                }
            }
            else -> Unit
        }
    }
    return tellsPosts && readsQueue && adds
}

/**
 * X takes a post for seen when its row has lingered in the native list, and the home timeline
 * requests carry the ids of the posts seen, so the server leaves them out of what it sends next.
 * The card feed replaces the list: nothing lingers, nothing was ever marked, and every refresh and
 * every further page brought back posts the reader had been shown. [markPostSeen] is X's own
 * "mark seen", on the tracker the timeline component creates for itself; the feed calls it for a
 * card that has stayed on screen.
 */
context(context: BytecodePatchContext)
private fun patchSeenPosts() {
    val timelinePost = resolvedNewXTimelineModels().postDescriptor
    val cacheType = requireExactlyOne(
        "NewX seen-posts cache",
        SeenPostsCacheFingerprint.scopedMatchAll().map { it.method.definingClass }.distinct(),
    )
    val cacheTypes = context.classDefBy(cacheType).interfaces.toSet() + cacheType

    val urtClasses = buildList<ClassDef> {
        context.classDefForEach { if (it.type.startsWith(URT_SCOPE)) add(it) }
    }
    // The one public instance method (timelineItem)V in the timeline's own code that adds a
    // post's id to the cache's queue of seen ids.
    val markSeen = requireExactlyOne(
        "NewX mark-post-seen method",
        urtClasses.flatMap { classDef ->
            classDef.methods.filter { method ->
                method.returnType == "V" &&
                    method.parameterTypes.size == 1 &&
                    AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                    !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.addsToSeenQueue(cacheTypes, timelinePost)
            }
        },
    ) { it.smaliReference() }
    val trackerType = markSeen.definingClass

    // The timeline component is the class that creates the tracker for itself, in its
    // constructor, and keeps it in a field of the tracker's own type.
    val component = requireExactlyOne(
        "NewX timeline component that creates the seen-posts tracker",
        urtClasses.filter { classDef ->
            classDef.type != trackerType && classDef.methods.any { method ->
                method.name == "<init>" && method.implementation?.instructions?.any { instruction ->
                    (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                        instruction.getReference<MethodReference>()?.let { call ->
                            call.name == "<init>" && call.definingClass == trackerType
                        } == true
                } == true
            }
        },
    ) { it.type }
    val trackerField = requireExactlyOne(
        "NewX timeline component's seen-posts tracker field",
        component.fields.filter { field ->
            field.type == trackerType && !AccessFlags.STATIC.isSet(field.accessFlags)
        },
    ) { "${it.definingClass}->${it.name}:${it.type}" }
    // The bridge lives in the extension, another package: everything it touches must be public.
    val trackerClass = context.classDefBy(trackerType)
    if (!AccessFlags.PUBLIC.isSet(component.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(trackerClass.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(trackerField.accessFlags)
    ) {
        throw PatchException(
            "NewX seen-posts tracker ${trackerField.definingClass}->${trackerField.name} is not reachable from the extension",
        )
    }

    replaceBridge("markPostSeen", listOf(OBJECT, OBJECT), "V", totalRegisters = 4) { p0 ->
        move(0, p0, OBJECT)
        checkCast(0, component.type)
        iget(0, 0, trackerField)
        move(1, p0 + 1, OBJECT)
        checkCast(1, markSeen.parameterTypes[0].toString())
        invokeVirtual(methodReference(markSeen.smaliReference()), 0, 1)
        returnVoid()
    }
}

private const val NESTED_SCROLL_VIEW = "Landroidx/core/widget/NestedScrollView;"

/**
 * The extension's scroll containers, each with the outer class whose code drives it. They extend
 * the target's own copy of androidx NestedScrollView.
 */
private val NESTED_SCROLL_CONTAINERS = mapOf(
    "Lapp/morphe/extension/newx/misc/MagazineFeed\$MagazineView;" to
        "Lapp/morphe/extension/newx/misc/MagazineFeed",
    "Lapp/morphe/extension/newx/misc/ProfilePhotosGallery\$GalleryView;" to
        "Lapp/morphe/extension/newx/misc/ProfilePhotosGallery",
)

/**
 * What stock NestedScrollView declares and the framework's View classes do not: a call to one of
 * these on a scroll container can only resolve in the target's copy of the class.
 */
private val NESTED_SCROLL_VIEW_ONLY_METHODS = setOf(
    "arrowScroll",
    "executeKeyEvent",
    "fling",
    "fullScroll",
    "getMaxScrollAmount",
    "isFillViewport",
    "isSmoothScrollingEnabled",
    "pageScroll",
    "setFillViewport",
    "setSmoothScrollingEnabled",
    "smoothScrollBy",
    "smoothScrollTo",
)

/**
 * The extension is compiled against stock androidx, but its scroll containers load against the
 * target's R8-optimized copy, where methods X never overrides are final and methods X never calls
 * are renamed or removed. Overriding or calling one of those compiles, patches and installs
 * cleanly, and then fails on the device: `LinkageError: ... overrides final method` as soon as the
 * card feed is switched on (the 2026-11-08 Piko 2.0 crash), or `NoSuchMethodError` at the call.
 * Both are checked here against the exact target instead, so such a build fails at patch time.
 */
context(context: BytecodePatchContext)
private fun requireScrollContainersLink() {
    val problems = mutableListOf<String>()
    NESTED_SCROLL_CONTAINERS.forEach { (containerType, ownerPrefix) ->
        val container = context.classDefBy(containerType)
        // The ancestors the target defines itself; the chain ends where the framework begins.
        val ancestors = generateSequence(
            container.superclass?.let { context.classDefByOrNull(it) },
        ) { ancestor -> ancestor.superclass?.let { context.classDefByOrNull(it) } }.toList()
        if (ancestors.none { it.type == NESTED_SCROLL_VIEW }) {
            throw PatchException(
                "NewX Piko 2.0: $containerType does not extend the target's $NESTED_SCROLL_VIEW, " +
                    "so its link check no longer applies",
            )
        }

        val overridable = container.methods.filter { method ->
            !method.name.startsWith("<") &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                !AccessFlags.PRIVATE.isSet(method.accessFlags)
        }
        ancestors.forEach { ancestor ->
            ancestor.methods.forEach { inherited ->
                val isOverridden = AccessFlags.FINAL.isSet(inherited.accessFlags) &&
                    !AccessFlags.STATIC.isSet(inherited.accessFlags) &&
                    (
                        AccessFlags.PUBLIC.isSet(inherited.accessFlags) ||
                            AccessFlags.PROTECTED.isSet(inherited.accessFlags)
                    ) &&
                    overridable.any { method ->
                        method.name == inherited.name &&
                            method.returnType == inherited.returnType &&
                            method.parameterDescriptors() == inherited.parameterDescriptors()
                    }
                if (isOverridden) {
                    problems += "$containerType overrides final ${inherited.smaliReference()}"
                }
            }
        }

        val hierarchy = ancestors.map { it.type }.toSet() + containerType
        val defined = (ancestors + container)
            .flatMap { classDef -> classDef.methods.map { it.name to it.parameterDescriptors() } }
            .toSet()
        context.classDefForEach { classDef ->
            if (!classDef.type.startsWith(ownerPrefix)) return@classDefForEach
            classDef.methods.forEach { method ->
                method.implementation?.instructions?.forEach { instruction ->
                    val call = instruction.getReference<MethodReference>() ?: return@forEach
                    if (call.definingClass !in hierarchy || call.name !in NESTED_SCROLL_VIEW_ONLY_METHODS) {
                        return@forEach
                    }
                    val signature = call.name to call.parameterTypes.map(CharSequence::toString)
                    if (signature !in defined) {
                        problems += "${classDef.type}->${method.name} calls ${call.definingClass}->" +
                            "${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}, " +
                            "which the target's NestedScrollView does not have"
                    }
                }
            }
        }
    }
    if (problems.isNotEmpty()) {
        throw PatchException(
            "NewX Piko 2.0: the card feed would not load on this target: " +
                problems.distinct().joinToString("; "),
        )
    }
}

/**
 * Piko 2.0: the X Magazine userscript's card grid for the home timeline.
 *
 * The divert itself lives in the shared URT timeline-body preamble that
 * [newXProfilePhotosGalleryPatch] emits, because both features replace the same composable with an
 * AndroidView and reuse its paging bridge. This patch owns the setting, proves that the
 * home-timeline branch was emitted and that the extension's scroll containers link against the
 * target's own NestedScrollView ([requireScrollContainersLink]), and wires one-tap
 * like/unlike/repost/bookmark to X's own post-actions service. That wiring is optional: when it
 * cannot be resolved the cards keep using X's in-app action links, so the layout still ships.
 * So are Home re-select ([patchHomeReselect]) and telling X which posts the cards showed
 * ([patchSeenPosts]).
 */
@Suppress("unused")
val newXMagazineFeedPatch =
    bytecodePatch(
        name = "NewX: Piko 2.0 magazine layout",
        description = "Adds a Piko 2.0 option that shows the home timeline as a magazine-style grid of post cards.",
    ) {
        unsupportedOnMount()
        compatibleWith(COMPATIBILITY_NEW_X)
        dependsOn(
            newXExtensionPatch,
            newXProfilePhotosGalleryPatch,
            newXPostModelResolutionPatch,
            newXInlineActionModelResolutionPatch,
            newXTimelineModelResolutionPatch,
            // Fills the timeline filter's module accessors, which the feed uses to give each post
            // inside a module (a conversation) its own card and its own live post item.
            newXTimelineModelAdapterPatch,
        )

        newXToggle(
            id = "newx.appearance.piko_2_magazine",
            category = Categories.APPEARANCE,
            strings = settingStrings("piko_newx_piko_2_magazine"),
            order = 90,
            defaultValue = false,
            rebootApp = true,
        )

        execute {
            if (magazineHomeTimelineFields.isEmpty()) {
                throw PatchException(
                    "NewX Piko 2.0: the timeline enum that carries USER_PROFILE_PHOTOS does not " +
                        "expose every home timeline constant, so the magazine branch was not emitted",
                )
            }
            // Required: a container that cannot load crashes the app the moment the feed is shown.
            requireScrollContainersLink()
            // Optional by design (see the patch KDoc): the extension treats unpatched bridges as
            // "use the link instead".
            runCatching { patchCardActions(resolvePostActions()) }
                .onFailure { failure ->
                    System.err.println("NewX Piko 2.0: one-tap actions unavailable, cards use links: ${failure.message}")
                }
            // Also optional: without it, Home re-select simply does not move the card feed.
            runCatching { patchHomeReselect() }
                .onFailure { failure ->
                    System.err.println("NewX Piko 2.0: Home re-select not forwarded to the cards: ${failure.message}")
                }
            // Also optional: without it X is not told which posts the cards showed, and keeps
            // sending them again (the state of every build before this step existed).
            runCatching { patchSeenPosts() }
                .onFailure { failure ->
                    System.err.println("NewX Piko 2.0: seen posts are not reported to X: ${failure.message}")
                }
        }
    }
