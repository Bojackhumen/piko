# Piko 2.0 magazine feed: GIFs do not play in the enlarged card

Target: package `com.twitter.android`, X `12.40.1-prod.01`, on the reporter's Samsung SM-X930
(Android 16). Reported against `04bf438` on branch `piko-2.0-magazine`, MPP from the
`Build Piko 2.0 patches` workflow run `43306035200`, patched onto the reporter's
`X-v12_40_1-prod_01-Piko-v3_74_0.apk` (the same already-patched file as before) and installed by
the reporter.

## Report

"The GIFs stopped playing in card view." Asked for detail, the reporter said: it is the enlarged
card; the GIF is a still picture with the play triangle on it; ordinary videos still play there;
the base APK is the same file as for the builds before.

## Cause

**Not established.** Classification: runtime behavior, unknown origin. Not reproduced by the agent
(no device interaction, no logcat from the device).

What the code says:

- The inline player (`VideoTile`) is the same for GIFs and videos except for looping, the missing
  controls and the screen-on flag. Its play triangle starts hidden and is only shown by
  `setPlaying(false)` (the player's error or completion callback, or a pause by the reader) or by
  the `catch` around starting the player. With the preview picture still showing, the player failed
  for the GIF's clip: an error callback, or an exception while starting.
- Nothing in `da6204f` or `04bf438` changes that path. The diff against `e0ccf85` inside
  `VideoTile` is five content descriptions; the choice between an inline player and a still tile
  and the reading of the clip's address (`playableMp4`) are untouched. X's handling of the row
  reports added in `da6204f` (`ItemPositioned` and the others) calls only its linger tracker and
  its scribes, no player.
- So the failure is in what the system player does with a GIF's clip
  (`https://video.twimg.com/tweet_video/<id>.mp4`, read straight from that address) on this
  device and network, which the APK cannot show. The player's error code would; the reporter has
  no logcat at hand.

Found on the way, and real: `CardViews.rebind` put the card tap back to "enlarged card" whenever X
re-sent a post (after a like, for one), ignoring the new "open post on tap" setting from `04bf438`.

## Change

- A GIF is played from a copy in the app's cache (`piko_magazine_clips`, at most 16 clips of at
  most 16 MB), fetched with the same kind of request the card pictures use. If the copy cannot be
  made or does not play, the player reads the address itself, as before.
- When a clip cannot be played either way, the tile says so with the reason on it (the player's
  `what/extra`, or the exception's name), and a tap opens X's own player. Before, the tile showed
  a play triangle that did nothing.
- The card tap honours the setting after a refresh as well (`CardViews.cardTap`).

## Validation

- Local: syntax check of the extension source. Compile: the workflow run for the commit that adds
  this entry.
- No agent device interaction. To confirm on the device: a GIF in the enlarged card loops. If it
  does not, the tile shows "Can't play here (…)"; the text in the brackets is what this entry is
  missing.
