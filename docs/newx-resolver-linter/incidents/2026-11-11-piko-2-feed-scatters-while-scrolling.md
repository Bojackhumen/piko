# Piko 2.0 magazine feed: cards scatter and re-appear while scrolling

Target: package `com.twitter.android`, X `12.40.1-prod.01`, on the reporter's Samsung SM-X930
(Android 16, four columns). Reported against `d0814c6` on branch `piko-2.0-magazine`, the first
build with `e06ada1` ("Piko 2.0 feed keeps card views only near the screen"). MPP and extension:
the `Build Piko 2.0 patches` workflow artifact, patched and installed by the reporter.

## Report

"Timelines get scattered, disorganized, refreshed whenever I keep scrolling." No patch failure
and no crash. The build before `e06ada1` did not do this.

## Cause

Classification: runtime behavior (layout), introduced by `e06ada1`. Nothing to do with
resolvers, the linter or the cardinality helpers, and not APK drift.

Not reproduced by the agent (no device interaction, see AGENTS.md). The cause below is from
reading the code as shipped against what `e06ada1` changed, and from a simulation that has what
the first one lacked, a clock. Confidence: high that the build could behave as reported, for
the reasons in 1 and 2; the weight of each item on the reporter's device is not known.

1. A card away from the screen was no longer built. Its slot was sized by an estimate (text
   lines from a character count, pictures from their shape) and the card was built, and got its
   real height, when the reader came within a screen and a half. The real height moves every
   card under it in its column. That only stays out of sight while building keeps ahead of the
   reader.
2. It did not keep ahead. All cards coming into range were built inside the scroll step, as
   many as there were, on the way down as well, since a new page was no longer built when it
   arrived. A fling moves by elapsed time: a frame that builds cards is long, so the next step
   is longer and has more to build. Past half a screen in one step the feed counted as "racing"
   and built nothing at all. The reader then saw empty placeholders at estimated sizes, and
   when the fling slowed every card in view was built at once and took its real height there:
   cards jump in each column ("scattered"), placeholders turn into cards ("refreshed"). The
   first simulation stepped the scroll by fixed amounts and gave building no cost, so it never
   saw this.
3. Views were added and removed from the scroll listener when `isInLayout()` was false. Compose
   lays an `AndroidView` out from its own draw pass, where `isInLayout()` is false although a
   layout is running. A `requestLayout()` made there stops at the scroll view, which is still
   being laid out, and is cleared when its layout ends: the grid is left marked as wanting a
   layout that nobody will run, and later requests stop at it. This needs the scroll view to
   change its own scroll position during a layout (its saved place on the first layout, or a
   clamp when the content got shorter), so it is the rarer path.
4. The reader's place was put back from an `OnGlobalLayoutListener`. A layout Compose runs from
   its draw pass is not a global layout, so the listener could fire long after it was set, when
   the reader had scrolled on, and pull the feed back to the old place. `e06ada1` added a
   trigger (a new width) and made the grid depend on the remembered place.

## Fix

In `MagazineFeed` (extension only; the patch is unchanged), `2980d69` and `099191d`:

- A post is built and measured when it arrives, as before `e06ada1`, so it is placed by its
  real height and scrolling down builds nothing. Empty slots keep the height their card was
  measured at; the estimate is gone. Cards are let go six screens away and come back two
  screens ahead, so going back a few screens builds nothing either.
- Cards that come back are built in the frame's animation phase, never from the scroll
  listener, a layout or a draw, nearest first, for 4 ms a frame while the feed moves (12 ms at
  rest, one card at least). The "racing" rule is gone.
- The scroll listener only records the place and posts; `checkLoadMore` is posted too.
- Home from deep in the feed: the first screen of the feed counts as on screen while the feed
  glides there, so its cards and pictures are ready when it lands (`099191d`).
- The reader's place is put back in the scroll view's own layout listener, in the same pass.
  A touch cancels a place still waiting. A layout request that was lost (3) is asked for again.
- A new width (rotation, resize) is the one case left with stand-in heights, because building
  every card of a long timeline at once would stop the app for seconds: built cards are
  measured again, empty slots near the reader have their cards built to be measured, the rest
  take their last real height adjusted for the pictures and the text lines at the new width.
  When such a card is built above the reader, its column gives way upwards instead of pushing
  the cards in view (columns kept within a quarter of a screen of each other).

## Known limits

- After turning or resizing a long timeline, going all the way back up can still nudge a card
  now and then (when a column has given way as far as it may), and the columns can start up to
  a quarter of a screen apart at the very top until the feed is next refreshed, re-created, or
  Home is tapped.
- A new page is built in one go when it arrives (as before `e06ada1`): one long frame per page.
- Going back up more than six screens faster than cards are rebuilt shows card-shaped
  placeholders of the right size until they are; nothing moves when they fill in.

## Validation

- `slots_sim2.py` (agent scratch, not committed): the measure, window, restore and stand-in
  logic with a clock (building costs 6 to 20 ms, 60 to 120 Hz, flings that move by elapsed
  time). 930 posts read down with paging and hard flings, flings back up, re-sent posts, a
  re-created view, a refresh with new posts on top, two rotations, Home from deep in the feed:
  no card on screen moved other than by the scroll and no empty slot was drawn, except
  placeholders during the glide to the top and the nudges listed under known limits (0 to
  about 250 card positions over some 850 stand-ins, by how good the stand-ins are; about 2500
  without the columns giving way). At most about 110 card views alive.
- Local: syntax check, and the NestedScrollView link check against the 12.40.1 dex
  (`problems: 0`). Compiled and linted by the `Build Piko 2.0 patches` workflow: run
  43055270231 for `2980d69`, run 43056243922 for `099191d`, both green.
- No agent device interaction. Whether the report is resolved on the SM-X930 is the reporter's
  to confirm.
