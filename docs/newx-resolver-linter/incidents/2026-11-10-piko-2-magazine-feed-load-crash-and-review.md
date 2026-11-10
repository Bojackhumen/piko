# Piko 2.0 magazine feed: load crash, action masks, and review hardening

Target: package `com.twitter.android`, X `12.40.1-prod.01`, on the reporter's Samsung SM-X930
(Android 16). Bytecode evidence was read from the reporter's
`X-v12_40_1-prod_01-Piko-v3_74_0.apk` (a Piko 3.74.0 build of that release; version code not
recorded). Branch `piko-2.0-magazine`. Every MPP and extension the reporter installed came from
the `Build Piko 2.0 patches` workflow run for the commit named below, patched and installed by the
reporter. The agent environment cannot build or patch (GitHub Packages, the Android SDK and Maven
are unreachable), so local validation is limited to what each section states.

## 1. Crash whenever the Piko 2.0 layout was switched on

Reported with two Piko crash reports (2026-11-08 17:46 and 17:48). The error, as recorded during
the session (the reports themselves were not kept, and the class path was shortened in the record):

```
java.lang.LinkageError: Method void …MagazineFeed$MagazineView.onAttachedToWindow() overrides
final method in class Landroidx/core/widget/NestedScrollView;
```

Source: `12d87dcc67fd3ad83b1b421ebe16640f874d871c`, which made `MagazineView.onAttachedToWindow`
public after CI rejected it as a weaker-access override. Failing patch: none; the patch run
succeeded and the class failed to load on the device.

Classification: runtime behavior. No resolver, linter or cardinality helper is involved. The
extension compiles against stock androidx, but its scroll containers
(`MagazineFeed$MagazineView`, `ProfilePhotosGallery$GalleryView`) load against the target's own
R8-optimized `androidx.core.widget.NestedScrollView`:

- 48 of its public/protected methods that keep their names are `final`, among them
  `onAttachedToWindow`, `onLayout`, `onMeasure`, `onScrollChanged`, `onSizeChanged`,
  `onTouchEvent`, `onInterceptTouchEvent`, `computeScroll`, `draw`, `requestLayout`, `scrollTo`
  and every `addView`.
- 29 of its 87 methods carry short obfuscated names; `smoothScrollTo`, which `75fb1c4` had
  started calling, is not there under its name. `setFillViewport(Z)V` is.

Fix: `9e4f3d626c0bac2deadabdd0e6f19e7946a35d92`. The override became an
`OnAttachStateChangeListener`, and the glide to the top a `ValueAnimator` over View's `scrollTo`.

Guard added with this entry: `requireScrollContainersLink` in `MagazineFeedPatch.kt` checks both
containers against the exact target at patch time and throws `PatchException` when one overrides
a method an ancestor defined by the target marks `final`, or calls a NestedScrollView-only method
the target's copy does not define. The same algorithm was run in Python (androguard) over the
12.40.1 dex files: `GalleryView` passes, and both controls are reported (an added
`onAttachedToWindow()V` override; a `smoothScrollTo(II)V` call). `MagazineView` is not in that
APK; its source was checked for overrides and NestedScrollView-only calls against the same
class. The Kotlin itself has only been compiled by CI, not executed on an APK by the agent.

## 2. Likes sent from a card never registered

Found while moving card actions from links to X's post-actions dispatcher (the report was "like
and repost navigate into the post"). The first dispatcher bridge passed default-argument mask 24
(scribe map and post id both defaulted) for every action. X's own 12.40.1 action bar passes a
built scribe map with mask 16 for like, unlike and repost, mask 24 for bookmark and unbookmark,
and mask 8 for undo-repost. Classification: resolver logic (the bridge assumed one call shape for
all actions). Fixed in `ca7a81d`; undo-repost was added in `75fb1c4`. The reporter's later
reports concern how a liked post is drawn, so likes do register; undo repost and the quote link
are still unconfirmed on the device.

## 3. Review hardening (this commit)

Runtime behavior of the extension, none of it resolver or linter related:

- Posts inside a timeline module (a conversation) were read as one card whose item was the
  module, so `postResultOf` could not cast it and like/repost fell back to opening the post, and
  the module's other posts were not shown. Modules are now opened through the timeline filter's
  existing model bridges (`NewXTimelineFilter.modulePosts`); the patch depends on
  `newXTimelineModelAdapterPatch` for them.
- Every timeline update printed and parsed every item again on the main thread. Cards are now
  read once per item instance; the timeline and post models are immutable in 12.40.1 (all
  instance fields of `UrtTimelinePost`, `UrtTimelineModule`, `UrtTimelineModuleItem`,
  `ContextualPost`, `CanonicalPost` and `InlineActionEntry` are final).
- A refresh rebuilt every card and fetched every picture again; every card kept its bitmap for
  as long as its post was in the timeline. Card views now survive a reorder, and only cards
  within one screen of the visible part hold pictures (released beyond two screens).
- Card text showed the API's `&amp;`, `&lt;`, `&gt;` escapes, which X undoes when it draws text.
- The inline video kept playing, with sound, after the app left the screen, played over other
  apps' audio, and let the screen time out mid-video.

## Validation

No agent device interaction was performed. Extension changes were parse-checked with
`javac -proc:none` and compiled by the `Build Piko 2.0 patches` workflow for the commit that adds
this entry. `./gradlew :patches:lintNewxResolvers` could not be run by the agent. On-device
results are the reporter's.
