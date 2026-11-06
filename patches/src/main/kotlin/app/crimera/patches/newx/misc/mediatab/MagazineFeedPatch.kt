package app.crimera.patches.newx.misc.mediatab

import app.crimera.patches.newx.misc.extension.newXExtensionPatch
import app.crimera.patches.newx.settings.Categories
import app.crimera.patches.newx.settings.newXToggle
import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.crimera.patches.newx.utils.unsupportedOnMount
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/**
 * Piko 2.0: the X Magazine userscript's card grid for the home timeline.
 *
 * The divert itself lives in the shared URT timeline-body preamble that
 * [newXProfilePhotosGalleryPatch] emits, because both features replace the same composable with an
 * AndroidView and reuse its paging and item-click bridges. This patch owns the setting and proves,
 * after the gallery patch has run, that the home-timeline branch was actually emitted.
 */
@Suppress("unused")
val newXMagazineFeedPatch =
    bytecodePatch(
        name = "NewX: Piko 2.0 magazine layout",
        description = "Adds a Piko 2.0 option that shows the home timeline as a magazine-style grid of post cards.",
    ) {
        unsupportedOnMount()
        compatibleWith(COMPATIBILITY_NEW_X)
        dependsOn(newXExtensionPatch, newXProfilePhotosGalleryPatch)

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
        }
    }
