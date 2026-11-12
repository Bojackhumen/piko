# Piko 2.0 magazine feed: posts still repeat; the "mark seen" bridge cast the wrong class

Target: package `com.twitter.android`, X `12.40.1-prod.01`, on the reporter's Samsung SM-X930
(Android 16). Reported against `e0ccf85` on branch `piko-2.0-magazine` ("fix(newx): Piko 2.0
tells X which posts were seen"), MPP `patches-3.11.0-dev.1.mpp` from the `Build Piko 2.0 patches`
workflow, patched and installed by the reporter.

Bytecode evidence was read with androguard from the reporter's
`X-v12_40_1-prod_01-Piko-v3_74_0.apk`. That file is **not a stock APK**: it already contains
Piko's extension classes (`app/morphe/extension/newx/…`) and patched methods
(`com.x.urt.a0->piko$newx…`). The classes this entry is about are X's own and are not touched by
those patches, but every cardinality below still has to be confirmed on an unpatched 12.40.1 and on
one older declared target. The earlier entry `2026-11-11-piko-2-feed-repeats-posts.md` took its
counts from the same file.

## Report

"I'm still facing the same issue of repetitive posts." No patch failure and no crash.

## Cause

Classification: resolver logic. The receiver the bridge cast to was resolved by cardinality and
was never proven to be the object the feed holds (AGENTS.md, "Prove that a resolved receiver …
belong[s] to the actual behavior path"). Not the linter, not the cardinality helpers, not APK
drift. Not reproduced by the agent (no device interaction); read from the dex. Confidence: high.

1. `patchSeenPosts` resolved the class whose constructor creates the linger tracker
   (`com.x.urt.a0`, the default timeline component) and made the bridge
   `markPostSeen(component, item)` do `((a0) component).tracker.markSeen(item)`.
2. The component the feed hands that bridge is the one behind the timeline body's callback
   (`ProfilePhotosGallery.urtComponentOf`). On Home that is not `a0`. The home pager creates
   `com.x.home.foryou.x` (For You) and `com.x.home.following.n` (Following) and stores them as
   the page's component (`com.x.home.h.a`, `com.x.dms.di.r->invoke`); the home page passes that
   field unchanged to the timeline composable (`com.x.home.tabbed.a->g` → `com.x.urt.ui.b->h`),
   whose lambdas capture it (`com.x.urt.ui.n0.b`). Both classes implement the component interface
   `com.x.urt.u0` by forwarding every call to the timeline component they wrap
   (`foryou.x->D()`, `following.n->C()`). Twenty classes implement `u0`.
3. So the bridge's `check-cast Lcom/x/urt/a0;` threw `ClassCastException` on every call from a Home
   timeline, `tellSeen` counted three failures in the first second and switched itself off for the
   process. The patch step applied cleanly, which is why the build looked right.
4. Home re-select worked in the same build because its bridge casts to the interface
   (`invoke-interface Lcom/x/urt/u0;->k(Lcom/x/urt/t1;)V`, as X's own home handler does).

Found on the way:

- "Mark seen" is only one of the things a row's report sets off. X's rows send
  `ItemPositioned(item, rect, index)` each time they move and `ItemDisposed(item)` when they go;
  the list sends `ScrollableContainerPositioned(rect)`. The component hands these to the linger
  tracker (`com.x.urt.linger.y`, visibility ≥ 0.5 of the row or of the screen, thresholds from
  `legacy_deciders_scribe_status_linger_minimum/maximum_threshold`), which calls "mark seen", and to
  the impression scribes (`com.x.urt.scribing.e`: `…:tweet:impression`, `…:impression`;
  `com.x.urt.scribing.l`: `…:stream::results`). None of that ran for a card.
- The linger tracker is switched by the feature switch `legacy_deciders_scribe_linger` (default
  false in the client). Where it is off, X's own list marks nothing as seen either, and only the
  impression events tell the server what was shown. Calling "mark seen" directly would not have
  given the cards what a row gives.
- The seen ids are taken and cleared by every home request (`com.x.repositories.urt.q0->a()`), so
  they are "seen since the last request", not a running list of 200.
- The tracker is switched on and off by the component's own lifecycle (`com.x.urt.d` lambdas
  subscribed in `a0.<init>`), not by the list, so it runs with the card feed in place.

## Fix

- Patch (`MagazineFeedPatch.kt`): `patchSeenPosts` is replaced by `patchRowReports`, which fills
  three bridges that build X's own events and send them through the component interface's event
  method: `reportContainer`, `reportCardPositioned`, `reportCardDisposed`. The step is no longer
  optional: unresolved, the Piko 2.0 patch fails with a `PatchException` instead of shipping a feed
  that repeats posts.
- Extension (`MagazineFeed`): cards on screen are reported the way rows are, in window
  coordinates (X's rows use `RelativeLayoutBounds.boundsInWindow`): the feed's place below the top
  inset, each card that moved (at most every 100 ms while the feed moves), and each card that left
  the screen, was let go, or left the timeline. X's tracker only re-evaluates a row when it hears
  from it, and a row at rest says nothing, so a feed at rest reports its cards again 1.5 s, 3 s
  and 4.5 s after it last moved. Reporting stops after three failures in a row.

### Resolver evidence (12.40.1-prod.01, the reporter's already-patched copy)

| What | Anchor | Cardinality |
| --- | --- | --- |
| ItemPositioned | class under `Lcom/x/urt/` whose `toString` has `"ItemPositioned(item="` | exactly one (`b1`), ctor `(I, rect, item)` |
| ItemDisposed | same, `"ItemDisposed(item="` | exactly one (`a1`), ctor `(item)` |
| ScrollableContainerPositioned | same, `"ScrollableContainerPositioned(position="` | exactly one (`m1`), ctor `(rect)` |
| Rect | class under `Lcom/x/models/` whose `toString` has `"KMPRect(left="` | exactly one (`p4`), ctor `(FFFF)` = left, top, right, bottom |
| Event interface | the interface the three events share | exactly one (`t1`) |
| Component event method | interface under `Lcom/x/urt/` with a `(event interface)V` method | exactly one (`u0->k`); no other interface in the app has one |
| Timeline item type | ItemDisposed's parameter; the resolved UrtTimelinePost implements it | `o0` |

Discarded: the tracker class, its field on the component and its "mark seen" method (the receiver
problem above); the lambdas X hands its list (`com.x.urt.ui.o0` and a merged lambda class), since
the events and the interface method are the contract those lambdas are built on.

Known limits:

- Checked against one target, read from an already-patched APK. Not patch-tested by the agent.
- The index sent with a card is its place among the cards; X's rows send their place in the list,
  which also counts rows that are not posts.
- A quoted post inside a card is not reported separately (X's rows send QuotedItemPositioned).
- When a post's content changes, its card is rebuilt and reported as gone and back; X's row
  would have stayed.
- Whether the server then repeats fewer posts is not something the APK shows.

## Validation

- Local: syntax check of the extension source. Bytecode shapes above counted with androguard.
- Compile: the `Build Piko 2.0 patches` workflow run for the commit that adds this entry.
- No agent device interaction. To confirm on the device: with the feed on, logcat must not show
  `[PikoNewX][Magazine] could not report … to X`; after reading a few screens, a refresh should
  bring mostly posts not seen before.
