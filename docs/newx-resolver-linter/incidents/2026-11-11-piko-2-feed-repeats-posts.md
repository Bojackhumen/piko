# Piko 2.0 magazine feed: refresh and further pages bring back posts already shown

Target: package `com.twitter.android`, X `12.40.1-prod.01`, on the reporter's Samsung SM-X930
(Android 16). Reported against `df3377c` on branch `piko-2.0-magazine`; an earlier report of the
same kind ("I see a lot of the same post") led to `9d39322`, which only stopped one post from
appearing twice in one feed. MPP and extension: the `Build Piko 2.0 patches` workflow artifact,
patched and installed by the reporter. Bytecode evidence was read with androguard from the
reporter's `X-v12_40_1-prod_01-Piko-v3_74_0.apk`.

## Report

"The timeline is still repetitive even when I click Home or pull down to refresh. 7 old to 3 new
is the ratio. Even when I scroll down I see posts that I previously liked moments ago." No patch
failure and no crash.

## Cause

Classification: runtime behavior, a missing integration. The card feed never did what X's list
does to tell the server which posts were seen. Not resolver logic, the linter, the cardinality
helpers or APK drift. Not reproduced by the agent (no device interaction); the chain below is
read from the 12.40.1 dex. Confidence: high that nothing was ever marked seen with the card
feed on; that the server then repeats posts is its documented purpose for the field, not
something observable from the APK.

- The home timeline queries (`HomeTimeline`, `HomeTimelineLatest`) have a `seen_tweet_ids`
  variable. The home repository fills it from the seen-posts cache's queue
  (`com.x.repositories.home.h.b` reads `SeenPostsCacheImpl.a(user)`, then builds the query).
- The cache (`com.x.repositories.urt.y0`, the class with `"_recent_seen_post_ids"`) keeps the
  last 200 seen post ids per user, in memory and on disk.
- Only the linger tracker writes to it: `com.x.urt.linger.a0.k(timelineItem)`, called from the
  tracker's own coroutines once a post row has lingered in the list longer than a threshold.
- The tracker is fed by the native list's rows. Piko 2.0 replaces the list's composable with
  its own view, so no row is ever composed and nothing lingers.

So with the card feed on, X sent every home request with no (or stale) seen ids, and For You
handed out posts the reader had just been shown, on refresh and on further pages.

A second, smaller thing found on the way: a view that had no place of its own (the reader at
the top) looked for one among the places remembered for other views whenever X replaced the
timeline, so a refresh could in principle scroll away from the new posts.

## Fix

- Patch (`MagazineFeedPatch.kt`, `patchSeenPosts`, optional like the other wiring in that
  patch): fills the extension bridge `markPostSeen(component, item)` with
  `((Component) component).tracker.markSeen((TimelineItem) item)`.
- Extension (`MagazineFeed`): a card that has been at least half on screen for one second is
  reported, once per slot, off the main thread (X's method reads a disk-backed cache). Only
  while the feed is in front of the reader. After three failures in a row it stops trying.
- Extension: only a newly created view adopts a remembered place.

### Resolver evidence (12.40.1-prod.01)

| What | Anchor | Cardinality |
| --- | --- | --- |
| Seen-posts cache | class under `Lcom/x/repositories/urt/` with const-string `_recent_seen_post_ids` | exactly one (`y0`) |
| Mark seen | under `Lcom/x/urt/`: public instance `(L…;)V` that has an `instance-of`/`check-cast` of the resolved UrtTimelinePost, calls a cache (or cache interface) method returning `ConcurrentLinkedQueue`, and calls `ConcurrentLinkedQueue.add` | exactly one (`linger/a0.k`) |
| Timeline component | under `Lcom/x/urt/`, not the tracker itself: a class with a constructor that calls the tracker's `<init>` | exactly one (`urt/a0`) |
| Tracker field | non-static field of the tracker's type in that class | exactly one (`q0`) |

Counted with androguard before writing the resolver: one cache class, one mark-seen candidate
(the only other readers of the queue are not `(L)V` instance methods), one constructing class,
one field. Twelve other classes hold a field of the tracker's type (its coroutine lambdas),
which is why the field is taken from the class that constructs the tracker. Class, tracker and
field must be public (they are); otherwise the step fails at patch time.

Discarded: obfuscated names; the queue's size constant (200); the linger threshold (a feature
switch value).

Known limits:

- Only 12.40.1-prod.01 was available to the agent. On a target where the step cannot be
  resolved the patch run prints `NewX Piko 2.0: seen posts are not reported to X: …` and the
  feed behaves as before.
- Only the seen ids are reported. X's impression scribing (view counts) is not.
- X keeps the last 200 seen ids; a screen of the grid holds 12 to 16 cards.
- Posts already loaded stay under the new ones after a refresh, as in X's list.

## Validation

- Local: syntax check of the extension source, the NestedScrollView link check (`problems: 0`).
- Compiled and linted by the `Build Piko 2.0 patches` workflow for the commit that adds this
  entry.
- Not patch-tested by the agent: the patcher cannot be run in the agent's environment (no
  access to the build's dependencies). The applied-patch report on the reporter's machine is
  the first real run of `patchSeenPosts`.
- No agent device interaction. Whether refreshes now bring mostly new posts is the reporter's
  to confirm.
