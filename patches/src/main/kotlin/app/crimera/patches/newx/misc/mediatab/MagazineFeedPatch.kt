package app.crimera.patches.newx.misc.mediatab

import app.crimera.bytecode.Block
import app.crimera.bytecode.fieldReference
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.newx.misc.extension.newXExtensionPatch
import app.crimera.patches.newx.models.newXInlineActionModelResolutionPatch
import app.crimera.patches.newx.models.newXPostModelResolutionPatch
import app.crimera.patches.newx.models.newXTimelineModelResolutionPatch
import app.crimera.patches.newx.models.resolvedNewXInlineActionModels
import app.crimera.patches.newx.models.resolvedNewXPostModels
import app.crimera.patches.newx.models.resolvedNewXTimelineModels
import app.crimera.patches.newx.settings.Categories
import app.crimera.patches.newx.settings.newXToggle
import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.crimera.patches.newx.utils.unsupportedOnMount
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MAGAZINE_EXTENSION = "Lapp/morphe/extension/newx/misc/MagazineFeed;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP_BUILDER = "Lkotlin/collections/builders/MapBuilder;"
private const val CAPTURE_DESCRIPTOR = "$MAGAZINE_EXTENSION->capturePostActions($OBJECT)V"

// Kotlin default-argument mask of the dispatcher: bit 3 defaults the scribe map, bit 4 the post
// id. X's own bookmark calls pass exactly this (24); like passes 16 with a scribe map.
private const val DEFAULT_MAP_AND_ID_MASK = 24

/** One card action: the extension bridge, X's action-type constant and the action's name. */
private data class CardAction(val bridge: String, val actionType: String, val actionName: String)

// Names as X's own action bar passes them (12.40.1 InlineActionBar presenter).
private val CARD_ACTIONS = listOf(
    CardAction("likePost", "Favorite", "LIKE"),
    CardAction("unlikePost", "Unfavorite", "UNLIKE"),
    CardAction("bookmarkPost", "AddToBookmarks", "BOOKMARK"),
    CardAction("unbookmarkPost", "RemoveFromBookmarks", "UNBOOKMARK"),
)

/**
 * X's post-actions service: the class whose static, fire-and-forget dispatcher
 * `(service, ContextualPost, actionType, name, MapBuilder, postId, mask)V` the action bar calls for
 * like, unlike, bookmark and unbookmark.
 */
private data class ResolvedPostActions(
    val serviceType: String,
    val dispatcher: Method,
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
    return ResolvedPostActions(dispatcher.definingClass, dispatcher, actionFields)
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

    // repostedOf(contextualPost): the reposted original, so a repost card acts on the post it shows.
    val repostedField = resolvedNewXPostModels().contextualRepostedPostField
    replaceBridge("repostedOf", listOf(OBJECT), OBJECT, totalRegisters = 2) { p0 ->
        move(0, p0, OBJECT)
        checkCast(0, contextualPost)
        iget(0, 0, repostedField)
        returnObject(0)
    }

    // likePost/unlikePost/bookmarkPost/unbookmarkPost(service, post): X's own dispatcher call,
    // with the scribe map and post id left to their defaults.
    CARD_ACTIONS.forEach { action ->
        replaceBridge(action.bridge, listOf(OBJECT, OBJECT), "V", totalRegisters = 9) { p0 ->
            move(0, p0, OBJECT)
            checkCast(0, actions.serviceType)
            move(1, p0 + 1, OBJECT)
            checkCast(1, contextualPost)
            sget(2, fieldReference(actions.actionFields.getValue(action.actionType)))
            constString(3, action.actionName)
            constInt(4, 0)
            constInt(5, 0)
            constInt(6, DEFAULT_MAP_AND_ID_MASK)
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

/**
 * Piko 2.0: the X Magazine userscript's card grid for the home timeline.
 *
 * The divert itself lives in the shared URT timeline-body preamble that
 * [newXProfilePhotosGalleryPatch] emits, because both features replace the same composable with an
 * AndroidView and reuse its paging bridge. This patch owns the setting, proves that the
 * home-timeline branch was emitted, and wires one-tap like/unlike/bookmark to X's own
 * post-actions service. That wiring is optional: when it cannot be resolved the cards keep using
 * X's in-app action links, so the layout still ships.
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
            // Optional by design (see the patch KDoc): the extension treats unpatched bridges as
            // "use the link instead".
            runCatching { patchCardActions(resolvePostActions()) }
                .onFailure { failure ->
                    System.err.println("NewX Piko 2.0: one-tap actions unavailable, cards use links: ${failure.message}")
                }
        }
    }
