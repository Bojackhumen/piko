# Piko 2.0 magazine feed: like on a repost card opens the post

Target: package `com.twitter.android`, X `12.40.1-prod.01`, on the reporter's Samsung SM-X930
(Android 16). Reported after `3c6b1dfcbc02739cf83a7a327c921a5f700ccca8` on branch
`piko-2.0-magazine`; the unwrap described below has been there since the one-tap actions were
added (`63cb9f4`). MPP and extension: the `Build Piko 2.0 patches` workflow artifact, patched and
installed by the reporter. Bytecode evidence was read from the reporter's
`X-v12_40_1-prod_01-Piko-v3_74_0.apk`.

## Report

"Liking some cards works great but for some cards, it opens up the post." No patch failure: the
patch run succeeded and the action bridges were filled.

## Cause

Classification: resolver logic (a wrong assumption about a model type), not the linter or the
cardinality helpers, and not APK drift.

`MagazineFeed.runAction` unwrapped a repost row before calling X's post-actions dispatcher: it
read `ContextualPost.rePostedPost` through the `repostedOf` bridge and passed that object as the
post. In 12.40.1 that field is not a `ContextualPost`:

```
ContextualPost(canonicalPost=CanonicalPost, quotedPost=PostResult, rePostedPost=RePostedPost, ...)
RePostedPost(canonicalPost=, quotedPost=)        // Lcom/x/models/q8;, implements only the post interface
```

The action bridges cast their post argument to `ContextualPost` (the dispatcher's parameter
type), so every action on a repost card threw `ClassCastException`, which `runAction` catches to
fall back to opening the post. Cards of plain posts, quotes and replies were unaffected, which is
the "some cards" of the report.

The unwrap was never needed. `ContextualPost` keeps the post a row shows in its own first field,
set in both constructors as `rePostedPost ?: canonicalPost`, and the dispatcher's coroutine reads
the action's target id from that field (`post.a.getId()`; `RePostedPost.getId()` is the original
post's id). X's own action bar passes the row's `ContextualPost` as it is.

## Fix

`runAction` passes the row's `ContextualPost` for every row. The `repostedOf` bridge and its
patch-side fill are removed; `resolvedNewXPostModels().contextualRepostedPostField` is no longer
used by this patch.

## Also reported: "the like button has a big hit box when clicked"

Classification: runtime behavior (layout), nothing to do with resolvers. Reply, repost and like
were each stretched over a third of the action row (layout weight 1 on the button itself), so a
tap anywhere in that third counted as the button, and the theme's borderless ripple spread in a
circle as wide as the stretched button, over the neighbouring buttons and the text. Each button
is now as wide as its icon and count inside an equal, inert share of the row, with a pill-shaped
ripple bounded by the button (`bd12fdf`).

## Validation

Read from the 12.40.1 dex files with androguard: `ContextualPost.toString` (label to field
mapping), both `ContextualPost` constructors, `RePostedPost`, and the dispatcher's launched
coroutine. Compiled by the `Build Piko 2.0 patches` workflow for the commit that adds this entry.
No agent device interaction; the on-device result is the reporter's to confirm.
