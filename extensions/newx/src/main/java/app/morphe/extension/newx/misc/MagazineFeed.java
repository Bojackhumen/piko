package app.morphe.extension.newx.misc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.widget.NestedScrollView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.newx.settings.NewXLogger;
import app.morphe.extension.newx.ui.LoadingIndicatorView;
import app.morphe.extension.newx.ui.Theme;
import app.morphe.extension.newx.utils.NewXUtils;
import app.morphe.extension.newx.utils.ToStringParser;

import kotlin.jvm.functions.Function1;

/**
 * Piko 2.0: renders the home timeline (For You / Following) as a magazine-style masonry grid of
 * post cards, after the X Magazine userscript's grid view.
 *
 * <p>Wired through the same URT timeline-body preamble as {@link ProfilePhotosGallery}: the patch
 * swaps the native list for an AndroidView when the timeline type is a home timeline and this
 * setting is on. Paging reuses the gallery's bottom-paginator bridge.
 *
 * <p>Every action goes through X itself. Like, repost and bookmark (and undoing them) run in
 * place through X's own post-actions service, which the patch captures and bridges to (see
 * {@link #runAction}); the buttons show X's counts and the reader's own state, read from the
 * post's inline action entries. Reply, quote, profiles and the full post open by in-app x.com
 * links that X's deep-link router handles ({@code /intent/{action}}, verified in 12.40.1's
 * {@code com.x.android.deeplink} handler). Where the bridges are not patched, like and bookmark
 * fall back to opening the post and repost to X's own repost screen.
 *
 * <p>To stay light on long timelines, timeline items are read once per instance
 * ({@link PostCard#extract}), and only the cards within a few screens of the reader exist as
 * views and hold their pictures: every post keeps a place in the grid ({@link CardSlot}), which
 * is empty while the post is far away ({@link MasonryGrid#updateWindow}). A place is as tall
 * as its card really is: a card is built and measured before it is first placed, so nothing
 * in the grid moves when cards are let go or come back.
 */
public final class MagazineFeed {
    public static final String SETTING_ID = "newx.appearance.piko_2_magazine";

    // Narrowest a column may get. Phones in portrait get two columns, tablets up to MAX_COLUMNS.
    private static final float MIN_COLUMN_DP = 160f;
    private static final int MAX_COLUMNS = 4;
    private static final float GAP_DP = 12f;
    private static final float CARD_RADIUS_DP = 16f;
    private static final int MAX_TEXT_LINES = 12;
    private static final int MAX_TEXT_LINES_WITH_MEDIA = 6;
    private static final int MAX_MEDIA = 4;
    // As in the userscript: media keeps its real shape between these ratios (width / height).
    // Anything more extreme is shown whole (letterboxed) at the nearest limit instead of cropped.
    private static final float MIN_ASPECT = 0.5f;
    private static final float MAX_ASPECT = 3f;
    private static final float UNKNOWN_ASPECT = 16f / 9f;
    private static final String LOG_PREFIX = "[PikoNewX][Magazine] ";
    // Piko's "Show sensitive media". Off: X's warnings are kept, so cards keep them too.
    private static final String SHOW_SENSITIVE_SETTING = "newx.content.show_sensitive_media";

    private MagazineFeed() {
    }

    public static boolean isEnabled() {
        return SettingsRegistry.getBooleanOrDefault(SETTING_ID, false);
    }

    public static Function1<Object, Object> createFactory(
            List<?> items,
            Object callback,
            Object itemClickCallback,
            float topPaddingDp,
            float ignoredBottomPaddingDp
    ) {
        return context -> new MagazineView((Context) context, items, callback, topPaddingDp);
    }

    public static Function1<Object, Object> createUpdater(
            List<?> items,
            Object callback,
            Object itemClickCallback,
            float topPaddingDp,
            float ignoredBottomPaddingDp
    ) {
        return view -> {
            if (view instanceof MagazineView magazineView) {
                magazineView.update(items, callback, topPaddingDp);
            }
            // AndroidView ignores the updater result; avoid the host's obfuscated Unit singleton.
            return null;
        };
    }

    private static void log(String message) {
        NewXLogger.printInfo(() -> LOG_PREFIX + message);
    }

    // ---------- One-tap actions through X's own post-actions service ----------

    // X's post-actions service, captured by the patch when X's DI creates it. Null until then.
    private static volatile Object postActions;

    /** Patch hook: called from X's post-actions service constructor with the new instance. */
    public static void capturePostActions(Object service) {
        if (postActions == null) log("post actions service ready");
        postActions = service;
    }

    // Patch-time bridges (bodies replaced by the patch). Unpatched, they throw, and the cards fall
    // back to X's in-app links / the post screen.
    private static Object postResultOf(Object timelineItem) {
        throw new IllegalStateException("Piko 2.0 postResultOf bridge was not patched");
    }

    private static void likePost(Object service, Object post) {
        throw new IllegalStateException("Piko 2.0 likePost bridge was not patched");
    }

    private static void unlikePost(Object service, Object post) {
        throw new IllegalStateException("Piko 2.0 unlikePost bridge was not patched");
    }

    private static void repostPost(Object service, Object post) {
        throw new IllegalStateException("Piko 2.0 repostPost bridge was not patched");
    }

    private static void unrepostPost(Object service, Object post) {
        throw new IllegalStateException("Piko 2.0 unrepostPost bridge was not patched");
    }

    /** Patch-time bridge: sends X's own timeline refresh event to the URT timeline component. */
    private static void requestTimelineRefresh(Object urtComponent) {
        throw new IllegalStateException("Piko 2.0 requestTimelineRefresh bridge was not patched");
    }

    /**
     * Patch-time bridges: what a row of X's own list tells its timeline, sent for a card
     * instead. X's list reports where its scrolling area is, where each row is as it moves
     * (ScrollableContainerPositioned and ItemPositioned, in window coordinates) and when a row
     * is gone (ItemDisposed). Everything X does about a post being looked at hangs off those
     * reports: how long it stayed on screen, the ids of the posts seen that the next home
     * request carries (seen_tweet_ids), and the view it counts for the post. Each bridge sends
     * the event through the timeline component's own event method, as X's rows do.
     */
    private static void reportContainer(Object urtComponent, float left, float top, float right, float bottom) {
        throw new IllegalStateException("Piko 2.0 reportContainer bridge was not patched");
    }

    private static void reportCardPositioned(Object urtComponent, Object timelineItem,
                                             float left, float top, float right, float bottom, int index) {
        throw new IllegalStateException("Piko 2.0 reportCardPositioned bridge was not patched");
    }

    private static void reportCardDisposed(Object urtComponent, Object timelineItem) {
        throw new IllegalStateException("Piko 2.0 reportCardDisposed bridge was not patched");
    }

    // ---------- Reporting cards to X the way its own list rows report themselves ----------

    // The cards replace X's list, so no row ever reported itself: X took no post for seen or
    // viewed, and every refresh and every further page could bring back posts the reader had
    // just been shown. An earlier fix called X's "mark seen" on the timeline component's
    // tracker directly; on Home the feed holds the For You / Following component, which only
    // forwards to the one that owns the tracker, so that call failed every time. The reports
    // go through the component's own event method instead, which every component has.
    private static final int MAX_REPORT_FAILURES = 3;
    // Main thread only, as everything below is.
    private static int reportFailures;

    /** False once reporting has failed a few times running: not patched, or not what it was taken for. */
    private static boolean canReport() {
        return reportFailures < MAX_REPORT_FAILURES;
    }

    private static void reportFailed(String what, Throwable t) {
        int failures = ++reportFailures;
        NewXLogger.printException(() -> LOG_PREFIX + "could not report " + what + " to X ("
                + failures + " of " + MAX_REPORT_FAILURES + " tries)", t);
    }

    private static boolean tellContainer(Object urtComponent, int left, int top, int right, int bottom) {
        if (!canReport()) return false;
        try {
            reportContainer(urtComponent, left, top, right, bottom);
            reportFailures = 0;
            return true;
        } catch (Throwable t) {
            reportFailed("the feed's place", t);
            return false;
        }
    }

    private static boolean tellPositioned(Object urtComponent, Object timelineItem,
                                          int left, int top, int right, int bottom, int index) {
        if (!canReport()) return false;
        try {
            reportCardPositioned(urtComponent, timelineItem, left, top, right, bottom, index);
            reportFailures = 0;
            return true;
        } catch (Throwable t) {
            reportFailed("a card's place", t);
            return false;
        }
    }

    private static void tellDisposed(Object urtComponent, Object timelineItem) {
        if (!canReport()) return;
        try {
            reportCardDisposed(urtComponent, timelineItem);
            reportFailures = 0;
        } catch (Throwable t) {
            reportFailed("a card that left the screen", t);
        }
    }

    private static void bookmarkPost(Object service, Object post) {
        throw new IllegalStateException("Piko 2.0 bookmarkPost bridge was not patched");
    }

    private static void unbookmarkPost(Object service, Object post) {
        throw new IllegalStateException("Piko 2.0 unbookmarkPost bridge was not patched");
    }

    private enum PostAction { LIKE, UNLIKE, REPOST, UNREPOST, BOOKMARK, UNBOOKMARK }

    /**
     * Runs the action through X's own post-actions service, exactly as X's inline action bar does:
     * like/unlike and repost carry X's scribe map (dispatcher mask 16), bookmark/unbookmark use X's
     * empty-map path (mask 24). Returns false when that is not possible yet (service not created,
     * not a plain post, or the bridge was left unpatched); callers then fall back to X's link or the
     * post screen.
     */
    private static boolean runAction(PostCard card, PostAction action) {
        Object service = postActions;
        if (service == null) {
            log("post actions service not ready; using the link for " + action);
            return false;
        }
        try {
            // X's newest copy of the post: this card may have been built before X re-sent it.
            Object item = freshCard(card).item;
            if (item == null) return false;
            // The row's own post, also for a repost: X's service acts on the post the row shows.
            // (Its ContextualPost keeps "rePostedPost ?: canonicalPost" as the post actions go
            // to, and the service reads the id from there.) The reposted post itself is a
            // RePostedPost, not a ContextualPost; handing that over failed the bridge's cast,
            // so every repost card opened the post instead of acting in place.
            Object post = postResultOf(item);
            if (post == null) return false;
            switch (action) {
                case LIKE -> likePost(service, post);
                case UNLIKE -> unlikePost(service, post);
                case REPOST -> repostPost(service, post);
                case UNREPOST -> unrepostPost(service, post);
                case BOOKMARK -> bookmarkPost(service, post);
                case UNBOOKMARK -> unbookmarkPost(service, post);
            }
            log(action + " sent for " + card.postId);
            return true;
        } catch (Throwable t) {
            NewXLogger.printException(() -> LOG_PREFIX + action + " failed; using the link", t);
            return false;
        }
    }

    // ---------- What the reader just did, until X confirms it ----------

    // Optimistic engagement per post after a one-tap action. X re-sends the post a moment later
    // with the real state; until then every view of the post (the grid card, its enlarged card, a
    // rebuilt card) shows what the reader just did, so the views never disagree. Main thread only.
    private static final java.util.Map<String, PendingEngagement> pendingEngagement = new java.util.HashMap<>();
    // A re-send that still disagrees this long after the action is X's real state (the action
    // failed, or was undone elsewhere); earlier ones are re-sends X made before applying it.
    // There is no plain timeout: if X never re-sends the post, the reader's action stays shown.
    private static final long PENDING_GRACE_MS = 20_000L;

    private static final class PendingEngagement {
        final Engagement engagement;
        final long at = android.os.SystemClock.uptimeMillis();

        PendingEngagement(Engagement engagement) {
            this.engagement = engagement;
        }
    }

    /** The engagement to show for a card: the reader's pending action, else X's own. */
    private static Engagement shownEngagement(PostCard card) {
        PendingEngagement pending = card.postId == null ? null : pendingEngagement.get(card.postId);
        return pending == null ? card.engagement : pending.engagement;
    }

    private static void rememberAction(PostCard card, Engagement next) {
        if (card.postId != null) pendingEngagement.put(card.postId, new PendingEngagement(next));
    }

    /**
     * Called with each copy of a post X sends. Drops the pending action once X's state agrees with
     * it (X's counts win from then on), or once X still disagrees after the grace period.
     */
    private static void reconcile(PostCard card) {
        PendingEngagement pending = card.postId == null ? null : pendingEngagement.get(card.postId);
        if (pending == null) return;
        Engagement x = card.engagement;
        Engagement mine = pending.engagement;
        boolean agrees = x.liked == mine.liked && x.reposted == mine.reposted && x.bookmarked == mine.bookmarked;
        if (agrees || android.os.SystemClock.uptimeMillis() - pending.at > PENDING_GRACE_MS) {
            pendingEngagement.remove(card.postId);
        }
    }

    /**
     * The newest copy of a card's post in the live feeds: X's latest state and timeline item. A
     * card's tap and media hold the copy they were built with, which X may have replaced since.
     */
    private static PostCard freshCard(PostCard card) {
        if (card == null || card.postId == null) return card;
        for (MagazineView view : new ArrayList<>(liveViews)) {
            PostCard fresh = view.cardFor(card.postId);
            if (fresh != null) return fresh;
        }
        return card;
    }

    // ---------- Home re-select ----------

    // Card feeds currently attached; X's pager can keep For You and Following alive together.
    private static final java.util.Set<MagazineView> liveViews =
            Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Patch hook, at the start of X's home scroll-to-top handler (Home tab re-selected, or the top
     * bar tapped). X sends that request to the native list, which the card feed replaces, so the
     * feed actually on screen handles it here. Switching to Home from another tab does not call it.
     */
    public static void onHomeReselected() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            MagazineView target = visibleFeed();
            if (target != null) target.onReselected();
        });
    }

    /** The attached feed showing the most of itself on screen (the current pager page). */
    private static MagazineView visibleFeed() {
        MagazineView best = null;
        long bestArea = 0;
        android.graphics.Rect rect = new android.graphics.Rect();
        for (MagazineView view : new ArrayList<>(liveViews)) {
            if (!view.isShown() || !view.getGlobalVisibleRect(rect)) continue;
            long area = (long) rect.width() * rect.height();
            if (area > bestArea) {
                bestArea = area;
                best = view;
            }
        }
        return best;
    }

    /**
     * Redraws every live grid card of this post with what it should show now: after the focus
     * view closes, and after an action (the same post can sit in For You and Following, or twice
     * in one feed). {@code except}: a view inside the card the action came from, which is skipped
     * since it already shows the action (rebuilding it would cut the tap's press feedback).
     */
    private static void refreshGridCard(String postId, View except) {
        if (postId == null) return;
        for (MagazineView view : new ArrayList<>(liveViews)) view.refreshCard(postId, except);
    }

    private static boolean isInside(View view, View ancestor) {
        for (View current = view; current != null;
             current = current.getParent() instanceof View parent ? parent : null) {
            if (current == ancestor) return true;
        }
        return false;
    }

    // ---------- Opening things in X ----------

    /** Opens an x.com link inside X itself; X's deep-link router picks the screen. */
    private static void openInX(Context context, String url) {
        if (url == null) return;
        dismissFocus();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage(context.getPackageName());
        // In-app navigation, not an outside post link: Piko's refresh gate must not let this
        // refresh the home timeline (it would with "Disable automatic timeline refresh" on).
        intent.putExtra(app.morphe.extension.newx.timeline.TimelineRefreshGate.INTERNAL_NAVIGATION_EXTRA, true);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            NewXLogger.printException(() -> LOG_PREFIX + "could not open " + url, e);
        }
    }

    /** The post link with Piko's sharing settings applied (custom domain, permanent links, tracking). */
    private static String shareLink(PostCard card) {
        return ShareUrlResolver.rewriteSharedUrl(card.postLink());
    }

    // ---------- Focus view: the card enlarged over a blurred timeline ----------

    private static android.app.Dialog focusDialog;

    private static void dismissFocus() {
        if (focusDialog != null) {
            try {
                focusDialog.dismiss();
            } catch (RuntimeException ignored) {
            }
            focusDialog = null;
        }
    }

    /**
     * Pops the card out, enlarged in the middle of the screen, with the timeline blurred (or
     * dimmed where the device has no window blur) behind it. Tapping outside, back, or the close
     * button returns to the timeline untouched; nothing is navigated.
     */
    private static void showFocus(Context context, PostCard tapped) {
        // The tapped card or image may hold an older copy of the post (from before a like that X
        // has since confirmed); the enlarged card shows the newest one.
        PostCard card = freshCard(tapped);
        Activity activity = NewXUtils.findActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            openInX(context, card.postLink());
            return;
        }
        dismissFocus();
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        FrameLayout scrim = new FrameLayout(activity);
        scrim.setOnClickListener(view -> dialog.dismiss());
        scrim.setContentDescription(Labels.of(activity, "Close", "close"));
        scrim.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                // Also reached when the activity goes away with the card still open (the system
                // removes the window without a dismiss), which would leave a dead dialog here.
                if (focusDialog == dialog) focusDialog = null;
            }
        });

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int margin = Theme.dpToPx(activity, 16f);
        int width = Math.min(screenWidth - 2 * margin, Theme.dpToPx(activity, 720f));

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, margin * 2, 0, margin * 2);

        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        // Short cards sit in the middle; empty space around the card closes, like the scrim.
        column.setGravity(Gravity.CENTER_VERTICAL);
        column.setOnClickListener(view -> dialog.dismiss());

        TextView close = CardViews.text(activity, "\u2715", 18f, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(Labels.of(activity, "Close", "close"));
        GradientDrawable closeBackground = new GradientDrawable();
        closeBackground.setShape(GradientDrawable.OVAL);
        closeBackground.setColor(0x66000000);
        close.setBackground(closeBackground);
        close.setOnClickListener(view -> dialog.dismiss());
        int closeSize = Theme.dpToPx(activity, 40f);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(closeSize, closeSize);
        closeParams.gravity = Gravity.END;
        closeParams.bottomMargin = Theme.dpToPx(activity, 8f);
        column.addView(close, closeParams);

        View enlarged = CardViews.build(activity, card, true);
        column.addView(enlarged, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView openFull = CardViews.text(activity,
                Labels.of(activity, "Open full post \u00B7 replies", "view_tweet_text"), 15f, Theme.onPrimaryAccent(activity));
        CardViews.bold(openFull);
        openFull.setGravity(Gravity.CENTER);
        int buttonPadding = Theme.dpToPx(activity, 12f);
        openFull.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
        openFull.setBackground(CardViews.rounded(Theme.primaryAccent(activity), Theme.dpToPx(activity, 24f)));
        CardViews.clipToRoundRect(openFull, Theme.dpToPx(activity, 24f));
        openFull.setForeground(CardViews.pressRipple(activity));
        openFull.setOnClickListener(view -> openInX(activity, card.postLink()));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openParams.topMargin = Theme.dpToPx(activity, 12f);
        column.addView(openFull, openParams);

        scroll.addView(column, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrim.addView(scroll, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        dialog.setContentView(scrim, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.45f;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.view.WindowManager windowManager = activity.getSystemService(android.view.WindowManager.class);
                // Cross-window blur can be off (battery saver, some devices); dimming remains then.
                if (windowManager != null && windowManager.isCrossWindowBlurEnabled()) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    attributes.setBlurBehindRadius(Theme.dpToPx(activity, 28f));
                    attributes.dimAmount = 0.25f;
                }
            }
            window.setAttributes(attributes);
        }

        // Pop out from slightly smaller, as a card lifted off the timeline.
        enlarged.setScaleX(0.92f);
        enlarged.setScaleY(0.92f);
        enlarged.setAlpha(0f);
        dialog.setOnShowListener(d -> enlarged.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start());
        dialog.setOnDismissListener(d -> {
            if (focusDialog == dialog) focusDialog = null;
            // A like/repost/bookmark done in the enlarged card shows on the grid card at once.
            refreshGridCard(card.postId, null);
        });
        focusDialog = dialog;
        dialog.show();
    }

    private static void sharePost(Context context, PostCard card) {
        String link = shareLink(card);
        if (link == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, link);
        Intent chooser = Intent.createChooser(send, null);
        if (!(context instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooser);
        } catch (RuntimeException e) {
            NewXLogger.printException(() -> LOG_PREFIX + "share failed", e);
        }
    }

    private static void copyLink(Context context, PostCard card) {
        copy(context, "Post link", shareLink(card), "Link copied");
    }

    private static void copyText(Context context, PostCard card) {
        copy(context, "Post text", card.text, "Text copied");
    }

    private static void copy(Context context, String label, String value, String confirmation) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (value == null || clipboard == null) return;
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        } catch (RuntimeException e) {
            NewXLogger.printException(() -> LOG_PREFIX + "copy failed", e);
            return;
        }
        // Android 13 and later show their own "copied" preview; a toast on top would say it twice.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, Labels.of(context, confirmation, "copied_to_clipboard"), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Labels in X's own words ----------

    /**
     * Card labels in the app's language. In English they are the words written here. In any
     * other language they are X's own strings: X ships each of these, translated, under the
     * resource name given beside it (names and translations read from 12.40.1's resources). A
     * name a later X no longer has falls back to the English. Main thread only.
     */
    private static final class Labels {
        private static final java.util.Map<String, String> found = new java.util.HashMap<>();
        private static java.util.Locale foundFor;

        private Labels() {
        }

        static boolean english(Context context) {
            try {
                java.util.Locale locale = context.getResources().getConfiguration().getLocales().get(0);
                return locale == null || "en".equals(locale.getLanguage());
            } catch (RuntimeException e) {
                return true;
            }
        }

        static String of(Context context, String english, String name) {
            try {
                android.content.res.Resources resources = context.getResources();
                java.util.Locale locale = resources.getConfiguration().getLocales().get(0);
                if (locale == null || "en".equals(locale.getLanguage())) return english;
                if (!locale.equals(foundFor)) {
                    found.clear();
                    foundFor = locale;
                }
                // "" stands for a name X does not have: the English differs between callers.
                String label = found.get(name);
                if (label == null) {
                    int id = resources.getIdentifier(name, "string", context.getPackageName());
                    String value = id == 0 ? null : resources.getString(id);
                    label = value == null || value.trim().isEmpty() ? "" : value;
                    found.put(name, label);
                }
                return label.isEmpty() ? english : label;
            } catch (RuntimeException e) {
                return english;
            }
        }

        /** A label with one value in it, such as "%s reposted". */
        static String with(Context context, String english, String name, String value) {
            try {
                return String.format(of(context, english, name), value);
            } catch (RuntimeException e) {
                return String.format(english, value);
            }
        }
    }

    // ---------- Links in a card's text ----------

    /** A tappable stretch of a card's text: a link, an @mention, a #hashtag or a $cashtag. */
    private static final class TextLink {
        final int start;
        final int end;
        final String url;

        TextLink(int start, int end, String url) {
            this.start = start;
            this.end = end;
            this.url = url;
        }
    }

    /**
     * Follows a link from a card's text. X's own addresses (profiles, hashtags, searches, other
     * posts) open inside X; anything else goes to the browser, as a link in a post does.
     */
    private static void openLink(Context context, String url) {
        if (url == null) return;
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("x.com") || host.endsWith(".x.com")
                || host.equals("twitter.com") || host.endsWith(".twitter.com")) {
            openInX(context, url);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            NewXLogger.printException(() -> LOG_PREFIX + "could not open " + url, e);
        }
    }

    private static final class LinkSpan extends android.text.style.ClickableSpan {
        private final String url;
        private final int color;

        LinkSpan(String url, int color) {
            this.url = url;
            this.color = color;
        }

        @Override
        public void onClick(View widget) {
            openLink(widget.getContext(), url);
        }

        @Override
        public void updateDrawState(android.text.TextPaint paint) {
            paint.setColor(color);
            paint.setUnderlineText(false);
        }
    }

    /** The link under a touch on a text view, or null when the touch is on plain text or beside it. */
    private static android.text.style.ClickableSpan linkAt(TextView view, MotionEvent event) {
        CharSequence shown = view.getText();
        android.text.Layout layout = view.getLayout();
        if (!(shown instanceof android.text.Spanned spanned) || layout == null) return null;
        float x = event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
        float y = event.getY() - view.getTotalPaddingTop() + view.getScrollY();
        if (y < 0 || y >= layout.getHeight()) return null;
        int line = layout.getLineForVertical((int) y);
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
        int offset = layout.getOffsetForHorizontal(line, x);
        android.text.style.ClickableSpan[] links =
                spanned.getSpans(offset, offset, android.text.style.ClickableSpan.class);
        return links.length == 0 ? null : links[0];
    }

    /**
     * Marks the card's links, @mentions and #hashtags in its text and makes them tappable. Only
     * a touch that starts on one is taken; any other touch goes on as before, to the card (which
     * pops out) or, in the enlarged card, to text selection. A movement method would make the
     * whole text swallow every touch.
     */
    private static void showLinks(TextView view, PostCard card) {
        if (card.text == null || card.links.isEmpty()) return;
        android.text.SpannableString marked = new android.text.SpannableString(card.text);
        int color = Theme.primaryAccent(view.getContext());
        for (TextLink link : card.links) {
            if (link.end > marked.length()) continue;
            marked.setSpan(new LinkSpan(link.url, color), link.start, link.end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(marked);
        android.text.style.ClickableSpan[] pressed = new android.text.style.ClickableSpan[1];
        view.setOnTouchListener((touched, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressed[0] = linkAt(view, event);
                return pressed[0] != null;
            }
            if (pressed[0] == null) return false;
            if (action == MotionEvent.ACTION_UP) {
                android.text.style.ClickableSpan link = pressed[0];
                pressed[0] = null;
                if (linkAt(view, event) == link) link.onClick(view);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                pressed[0] = null;
            }
            return true;
        });
    }

    // ---------- Scroll container ----------

    private static final class MagazineView extends NestedScrollView {
        // One id per process so recreated AndroidView instances share saved scroll state.
        private static final int VIEW_STATE_ID = View.generateViewId();

        /**
         * Where the reader was in one timeline: the post at the top of the screen, how far it
         * was scrolled past, and the posts after it. Kept across view recreation (leaving for a
         * post and coming back can rebuild the AndroidView) and across X re-sending the
         * timeline. For You and Following each have their own; a single shared one made the
         * timeline read second take over the first one's place.
         */
        private static final class Anchor {
            final String postId;
            // Tell timelines apart: both can hold the same post, but not with the same posts after it.
            final List<String> following;
            int offset;
            // How tall the post's card was then. At another width (a rotation) it is not, and
            // the same share of it is scrolled past instead of the same number of pixels.
            int cardHeight;

            Anchor(String postId, List<String> following) {
                this.postId = postId;
                this.following = following;
            }

            int offsetFor(int cardHeightNow) {
                if (cardHeight <= 0 || cardHeightNow == cardHeight) return offset;
                return Math.round(offset * (cardHeightNow / (float) cardHeight));
            }
        }

        // Most recent first, one per timeline the reader has scrolled. Main thread only.
        private static final List<Anchor> anchors = new ArrayList<>();
        private static final int MAX_ANCHORS = 6;
        private static final int ANCHOR_FOLLOWING = 4;

        // This view's own place (also in the list above); null at the top of the timeline.
        private Anchor anchor;

        private final MasonryGrid grid;
        private final LoadingIndicatorView loadingIndicator;
        // Timeline items already read into cards, so an update only reads what is new.
        private final PostCard.ReadItems readItems = new PostCard.ReadItems();
        private boolean windowPosted;
        private final Runnable windowPass = () -> {
            windowPosted = false;
            updateWindow();
        };
        // Where the feed was when the cards near the screen were last looked at (see updateWindow).
        private int lastWindowY;
        private boolean loadMoreCheckPosted;
        private final Runnable loadMoreCheck = () -> {
            loadMoreCheckPosted = false;
            checkLoadMore();
        };
        // The reader's place is about to be put back (or cannot be found yet, while the posts
        // are not here): until then scrolling is not taken for the reader's own.
        private boolean restorePending;
        // The place the next layout puts back; null when none is waiting for one.
        private Anchor restoreTarget;
        // Layouts that place has waited through already; it is not waited for without end.
        private int restoreTries;
        private static final int MAX_RESTORE_TRIES = 3;
        // Layouts pushed through by hand since the reader last touched the feed (see updateWindow).
        private int layoutRepairs;
        // The cards on screen are reported to X as its own rows report themselves (see
        // reportRows). While the feed moves, at most this often: X's rows report as they
        // move too, and X only needs to hear when a card comes on screen, that it is still
        // there, and when it leaves.
        private static final long REPORT_EVERY_MS = 100L;
        // X decides that a post was seen when it hears from a row that has stayed on screen
        // past its threshold (a second, unless X's servers say otherwise). Its rows are only
        // heard from when they move; a card that is being read does not. So a feed at rest
        // reports its cards again a few times, this far apart.
        private static final long REPORT_AGAIN_MS = 1500L;
        private static final int REPORTS_AT_REST = 3;
        private int reportsAtRestLeft;
        private long lastReportAt;
        // A pass was put off because the one before it was too recent; the timer makes up for it.
        private boolean reportOwed;
        private final Runnable reportCheck = () -> reportRows(true);
        // Scratch for reportRows.
        private final int[] placeInWindow = new int[2];
        private final android.graphics.Rect shownPart = new android.graphics.Rect();
        // The timeline component the cards are reported to, and the callback it was found on.
        private Object rowComponent;
        private Object rowComponentCallback;
        // The feed's own place as X was last told it (see reportRows); none while containerTold is false.
        private boolean containerTold;
        private int containerLeft;
        private int containerTop;
        private int containerRight;
        private int containerBottom;
        // A new view takes over the place remembered for its timeline (see findAnchor), once.
        private boolean adoptsPlace = true;
        private Object callback;
        private int currentItemCount = -1;
        private boolean loadMoreInFlight;
        private boolean paginationArmed;
        private int autoLoadItemCount = -1;
        private float downScreenTop = Float.NaN;

        MagazineView(Context context, List<?> items, Object callback, float topPaddingDp) {
            // Same constructor shape the Photos gallery relies on in the target APK.
            super(context, null);
            setId(VIEW_STATE_ID);
            this.callback = callback;
            applyTopPadding(topPaddingDp);
            setFillViewport(true);
            setBackgroundColor(Color.TRANSPARENT);
            setClipToPadding(false);
            setVerticalScrollBarEnabled(false);
            // A listener, not an onAttachedToWindow override: that method is final in X's
            // NestedScrollView, and overriding it makes the class fail to load (LinkageError).
            addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    liveViews.add(MagazineView.this);
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    liveViews.remove(MagazineView.this);
                    if (topAnimator != null) topAnimator.cancel();
                    // The cards are gone from the screen with the feed: X is told, as it is
                    // when its own rows go.
                    stopReporting();
                }
            });
            // The listeners only call methods: the grid they work on is made further down.
            setOnScrollChangeListener((View.OnScrollChangeListener) (view, x, y, oldX, oldY) -> onScrolled(y, oldY));
            addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> afterLayout());

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int gap = Theme.dpToPx(context, GAP_DP);
            content.setPadding(gap, gap, gap, gap);

            grid = new MasonryGrid(context);
            grid.afterLayout = this::postWindow;
            // A new width moves every card; the post being read goes back to the top of the screen.
            grid.onWidthChanged = () -> {
                if (anchor != null) restoreAnchorAfterLayout();
            };
            content.addView(grid, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            loadingIndicator = new LoadingIndicatorView(context);
            loadingIndicator.setVisibility(View.GONE);
            LinearLayout.LayoutParams loadingParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Theme.dpToPx(context, 32f)));
            loadingParams.topMargin = gap;
            content.addView(loadingIndicator, loadingParams);

            addView(content, new NestedScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            restorePending = !anchors.isEmpty();
            setItems(items);
        }

        private void onScrolled(int y, int oldY) {
            // The top edge of this view in the grid's coordinates; the content starts below
            // the top inset, and shows through it when scrolled.
            grid.setViewportTop(y - getPaddingTop() - grid.getTop());
            if (!restorePending) rememberAnchor();
            // Nothing that adds or removes a view is done from in here. A scroll step can come
            // from inside a layout (the scroll view putting its place back) or a draw (a
            // fling), and Compose lays this view out from its own draw, where isInLayout() is
            // false: a layout asked for there can be lost for good.
            postWindow();
            if (y <= oldY || loadMoreInFlight) return;
            paginationArmed = true;
            postLoadMoreCheck();
        }

        /** This view was laid out: the cards have their places. */
        private void afterLayout() {
            // First the reader's own place: one waiting to be put back is put back here, in
            // the same pass, before anything is drawn.
            boolean restoring = restoreTarget != null;
            finishRestore();
            // Cards above the reader took their real heights and all cards moved by this much
            // with them (see MasonryGrid.onMeasure): the feed moves along, so that nothing on
            // screen does. Not when the reader's place was just put back, or is about to be:
            // that goes by the cards' new places already.
            int shift = grid.takeScrollShift();
            if (shift != 0 && !restoring) scrollBy(0, shift);
            // Then the cards near the screen are built and get their pictures.
            postWindow();
            if (!loadMoreInFlight) postLoadMoreCheck();
        }

        private void rememberAnchor() {
            int top = getScrollY();
            int bestIndex = -1;
            int bestTop = Integer.MIN_VALUE;
            // The card whose top is closest above the screen's top edge, in any column.
            for (int i = 0; i < grid.getChildCount(); i++) {
                int cardTop = grid.getTop() + grid.getChildAt(i).getTop();
                if (cardTop <= top + 1 && cardTop > bestTop) {
                    bestTop = cardTop;
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) {
                if (top == 0) forgetAnchor();
                return;
            }
            String postId = grid.postIdAt(bestIndex);
            if (postId == null) return;
            if (anchor == null || !postId.equals(anchor.postId)) {
                anchors.remove(anchor);
                anchor = new Anchor(postId, grid.postIdsAfter(bestIndex, ANCHOR_FOLLOWING));
            }
            anchor.offset = top - bestTop;
            anchor.cardHeight = grid.getChildAt(bestIndex).getHeight();
            // The timeline being read is the first one looked at when a view comes back.
            if (anchors.isEmpty() || anchors.get(0) != anchor) {
                anchors.remove(anchor);
                anchors.add(0, anchor);
                while (anchors.size() > MAX_ANCHORS) anchors.remove(anchors.size() - 1);
            }
            grid.setFocus(anchor.postId);
        }

        private void forgetAnchor() {
            anchors.remove(anchor);
            anchor = null;
            grid.setFocus(null);
        }

        /**
         * For a view that has no place of its own yet (X re-created it): the remembered place
         * that belongs to this timeline. That is the one whose post is here with most of the
         * same posts after it; the most recent one wins a tie. A post that is here without any
         * of the posts that followed it is another timeline's place (a timeline still at its
         * top has none of its own), so it is not taken.
         */
        private Anchor findAnchor() {
            Anchor best = null;
            int bestScore = -1;
            for (Anchor candidate : anchors) {
                int index = grid.indexOfPost(candidate.postId);
                if (index < 0) continue;
                int score = grid.countAfter(index, candidate.following);
                if (score == 0 && !candidate.following.isEmpty()) continue;
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            return best;
        }

        /**
         * When the cards are next laid out, puts the remembered post back at the top of the
         * screen (see finishRestore). Not on the window's next global layout, as it used to be:
         * Compose lays this view out without one, so that could come seconds later, after the
         * reader had scrolled on, and pull the feed back.
         */
        private void restoreAnchorAfterLayout() {
            restoreTarget = null;
            restoreTries = 0;
            if (grid.isEmpty()) {
                // No posts to find the place among yet; tried again when they arrive.
                restorePending = anchor != null || (adoptsPlace && !anchors.isEmpty());
                return;
            }
            // Only a view X has just made looks for a place among the remembered ones. A view
            // with no place of its own is at the top of its timeline: when X replaces the
            // timeline under it (a refresh), it stays there, with the new posts.
            if (anchor == null && adoptsPlace) anchor = findAnchor();
            adoptsPlace = false;
            restorePending = anchor != null;
            if (anchor == null) return;
            restoreTarget = anchor;
            // The layout that follows has the cards around this post built, not the top's.
            grid.buildAround(anchor.postId);
        }

        /** Run after each layout of this view: the cards are where they belong. */
        private void finishRestore() {
            Anchor target = restoreTarget;
            if (target == null || grid.getWidth() <= 0) return;
            int index = grid.indexOfPost(target.postId);
            View card = index >= 0 && index < grid.getChildCount() ? grid.getChildAt(index) : null;
            boolean lastTry = ++restoreTries >= MAX_RESTORE_TRIES;
            if (card != null && !card.isLaidOut()) {
                // Not placed yet: this layout was not the grid's own. The next one.
                if (!lastTry) return;
                card = null;
            }
            // When the grid has another layout coming (one was asked for during this one), the
            // place is put back now and once more after that layout.
            if (card == null || lastTry || !grid.isLayoutRequested()) restoreTarget = null;
            if (card != null) {
                int y = grid.getTop() + card.getTop() + target.offsetFor(card.getHeight());
                // Still pending while this scroll is made: it is not the reader's own, and
                // must not be remembered as a new place.
                scrollTo(0, Math.max(0, y));
                log("restored position at post " + target.postId);
            }
            restorePending = restoreTarget != null;
        }

        /** The reader has taken over: a place still waiting to be put back no longer is. */
        private void cancelRestore() {
            if (restoreTarget == null) return;
            restoreTarget = null;
            restorePending = false;
        }

        /**
         * Home re-selected (or the top bar tapped) while this feed is on screen, as on the native
         * list: scrolled down, glide back to the newest post; already at the top, ask X for newer
         * posts. The refresh that follows lands at the top, since the anchor is cleared here.
         */
        void onReselected() {
            forgetAnchor();
            restoreTarget = null;
            restorePending = false;
            grid.settleTop();
            if (getScrollY() > 0) {
                glideToTop();
                return;
            }
            Object component = ProfilePhotosGallery.urtComponentOf(callback);
            if (component == null) {
                log("home re-select: timeline component not found; no refresh");
                return;
            }
            try {
                requestTimelineRefresh(component);
                log("home re-select: refresh requested");
            } catch (Throwable t) {
                NewXLogger.printException(() -> LOG_PREFIX + "home re-select refresh failed", t);
            }
        }

        private void postWindow() {
            if (windowPosted) return;
            windowPosted = true;
            // With the next frame, before it is measured and drawn: outside any layout or
            // draw, and once a frame at most, which is what spreads building over frames.
            postOnAnimation(windowPass);
        }

        private void postLoadMoreCheck() {
            if (loadMoreCheckPosted) return;
            loadMoreCheckPosted = true;
            post(loadMoreCheck);
        }

        // How long cards may be built for in one frame (one card at least). Short while the
        // feed moves, so that it keeps up with the finger; longer at rest, to fill the screen.
        private static final long BUILD_NANOS_MOVING = 4_000_000L;
        private static final long BUILD_NANOS_AT_REST = 12_000_000L;
        private static final int MAX_LAYOUT_REPAIRS = 8;

        /**
         * Tells the grid which part of it is on screen: cards near it are built and hold their
         * pictures, cards far from it are let go. Only ever run from postWindow().
         */
        private void updateWindow() {
            int height = getHeight();
            if (height <= 0) return;
            // A layout that was asked for inside a layout pass Compose ran stops at this view
            // (which was still being laid out) and is then forgotten: the cards wait for a
            // layout nobody knows about, and every later request stops at them. Seen from
            // out here, that is a child wanting a layout while this view does not.
            View content = getChildAt(0);
            if (content != null && content.isLayoutRequested() && !isLayoutRequested()
                    && layoutRepairs < MAX_LAYOUT_REPAIRS) {
                layoutRepairs++;
                log("layout request was lost; asked again");
                requestLayout();
            }
            // While a place waits to be put back, the screen is not where the reader will be.
            if (restorePending) return;
            int y = getScrollY();
            boolean moving = y != lastWindowY;
            lastWindowY = y;
            int top = y - grid.getTop();
            boolean more = grid.updateWindow(top, top + height,
                    moving ? BUILD_NANOS_MOVING : BUILD_NANOS_AT_REST);
            // Cards near the screen are still waiting: the next frame goes on with them.
            if (more) postWindow();
            reportRows(false);
        }

        /**
         * Tells X where the cards on screen are, in the window, the way the rows of its own
         * list tell it (see the report bridges), and which cards have left the screen. X works
         * out the rest as it does for a row: how much of the card shows, how long it has
         * stayed, that the post was seen, the view it counts. Run with every window pass (the
         * feed moved, or was laid out) and from a timer while the feed is at rest.
         */
        private void reportRows(boolean fromTimer) {
            if (!canReport()) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (!fromTimer && now - lastReportAt < REPORT_EVERY_MS) {
                if (!reportOwed) {
                    reportOwed = true;
                    postReportCheck(REPORT_EVERY_MS);
                }
                return;
            }
            // At rest (the timer, with nothing owed) every card on screen is reported again;
            // otherwise only the ones that moved.
            boolean atRest = fromTimer && !reportOwed;
            reportOwed = false;
            lastReportAt = now;
            removeCallbacks(reportCheck);
            // Only while this feed is in front of the reader: X's pager keeps the other
            // timeline's feed alive beside it, off screen.
            boolean shown = isAttachedToWindow() && getWindowVisibility() == View.VISIBLE && isShown()
                    && getGlobalVisibleRect(shownPart)
                    && shownPart.height() * 2 > getHeight() && shownPart.width() * 2 > getWidth();
            Object component = shown ? rowComponent() : null;
            if (component == null) {
                stopReporting();
                return;
            }
            // The feed's place, below the top inset (X's top bar covers that part).
            getLocationInWindow(placeInWindow);
            int left = placeInWindow[0];
            int top = placeInWindow[1] + getPaddingTop();
            int right = placeInWindow[0] + getWidth();
            int bottom = placeInWindow[1] + getHeight();
            if (bottom <= top || right <= left) return;
            // Told when it changes, and again with each pass at rest: the timeline behind a
            // For You or Following component can be replaced, and the new one has not heard.
            if (atRest || !containerTold || left != containerLeft || top != containerTop
                    || right != containerRight || bottom != containerBottom) {
                if (!tellContainer(component, left, top, right, bottom)) return;
                containerTold = true;
                containerLeft = left;
                containerTop = top;
                containerRight = right;
                containerBottom = bottom;
            }
            grid.getLocationInWindow(placeInWindow);
            int onScreen = grid.reportRows(component, placeInWindow[0], placeInWindow[1], top, bottom, atRest);
            if (atRest) reportsAtRestLeft--;
            else reportsAtRestLeft = REPORTS_AT_REST;
            if (onScreen > 0 && reportsAtRestLeft > 0) postReportCheck(REPORT_AGAIN_MS);
        }

        private void postReportCheck(long delayMs) {
            removeCallbacks(reportCheck);
            postDelayed(reportCheck, delayMs);
        }

        /** This feed is no longer in front of the reader: its cards have left the screen. */
        private void stopReporting() {
            removeCallbacks(reportCheck);
            reportOwed = false;
            containerTold = false;
            if (rowComponent != null) grid.disposeReported(rowComponent);
        }

        // Looked for once per callback X hands over, and kept once found: every callback of
        // this feed leads to the same timeline component.
        private Object rowComponent() {
            if (rowComponent == null && rowComponentCallback != callback) {
                rowComponentCallback = callback;
                rowComponent = ProfilePhotosGallery.urtComponentOf(callback);
            }
            return rowComponent;
        }

        void refreshCard(String postId, View except) {
            grid.refreshCard(postId, except);
        }

        PostCard cardFor(String postId) {
            return grid.cardFor(postId);
        }

        // X's bundled NestedScrollView is R8-optimized: methods X never calls (smoothScrollTo,
        // among others) are renamed or gone, and some it does call are final. Only View's own
        // API is used on it here (scrollTo), never NestedScrollView-only methods or overrides.
        private android.animation.ValueAnimator topAnimator;

        private void glideToTop() {
            if (topAnimator != null) topAnimator.cancel();
            int start = getScrollY();
            topAnimator = android.animation.ValueAnimator.ofInt(start, 0);
            topAnimator.setDuration(Math.min(450L, 180L + start / 20L));
            topAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            topAnimator.addUpdateListener(animation -> scrollTo(0, (Integer) animation.getAnimatedValue()));
            topAnimator.start();
        }

        void update(List<?> items, Object callback, float topPaddingDp) {
            this.callback = callback;
            applyTopPadding(topPaddingDp);
            setItems(items);
        }

        private void applyTopPadding(float topPaddingDp) {
            float density = getResources().getDisplayMetrics().density;
            // As in the gallery: the AndroidView already sits inside the host's content area, so
            // only the top inset is re-applied here.
            setPadding(0, Math.round(Math.max(0f, topPaddingDp) * density), 0, 0);
        }

        private void setItems(List<?> items) {
            List<?> next = items == null ? Collections.emptyList() : items;
            List<PostCard> cards = PostCard.extract(next, readItems);
            boolean rebuilt = grid.setCards(cards);
            // A new view, or X replacing the timeline (a refresh): keep the reader on their post.
            // Going to the top is an explicit request only (Home re-select, see onReselected), so
            // coming back from a post never throws the reader to the top.
            if (restorePending || rebuilt) restoreAnchorAfterLayout();
            // From its first posts on, this view's place is its own (see restoreAnchorAfterLayout).
            if (!grid.isEmpty()) adoptsPlace = false;
            if (currentItemCount != next.size()) {
                int previous = currentItemCount;
                log("items=" + next.size() + " cards=" + cards.size() + " previous=" + previous);
                setLoadingMore(false);
            }
            currentItemCount = next.size();
        }

        // NestedScrollView inside Compose: re-anchor touch Y to the DOWN frame so the content
        // tracks the finger (see ProfilePhotosGallery.GalleryView for the full explanation).
        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            float screenTop = ev.getRawY() - ev.getY();
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN || Float.isNaN(downScreenTop)) {
                downScreenTop = screenTop;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // From here on the feed goes where the reader takes it.
                cancelRestore();
                grid.readerTookOver();
                layoutRepairs = 0;
            }
            MotionEvent anchored = MotionEvent.obtain(ev);
            anchored.offsetLocation(0, screenTop - downScreenTop);
            try {
                return super.dispatchTouchEvent(anchored);
            } finally {
                anchored.recycle();
            }
        }

        private void checkLoadMore() {
            if (loadMoreInFlight || getChildCount() == 0) return;
            View child = getChildAt(0);
            if (child.getHeight() <= 0 || getHeight() <= 0) return;
            // Start loading about one screen before the end, like the native list does.
            int remaining = child.getBottom() - (getScrollY() + getHeight() * 2);
            if (remaining > 0) return;
            if (!paginationArmed) {
                if (autoLoadItemCount == currentItemCount) return;
                autoLoadItemCount = currentItemCount;
            }
            paginationArmed = false;
            setLoadingMore(true);
            boolean dispatched = ProfilePhotosGallery.requestLoadMore(callback);
            log("load more dispatched=" + dispatched);
            if (!dispatched) {
                setLoadingMore(false);
                return;
            }
            postDelayed(() -> {
                if (loadMoreInFlight) setLoadingMore(false);
            }, 6000);
        }

        private void setLoadingMore(boolean loading) {
            loadMoreInFlight = loading;
            loadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    // ---------- Masonry grid ----------

    /**
     * Each card goes into the shortest column when it first appears and stays there, so cards do
     * not hop between columns as their images load (the userscript's place() works the same way).
     *
     * <p>Every post has a {@link CardSlot} here for as long as it is in the timeline, in timeline
     * order, but only the slots within a few screens of the reader hold a built card. The rest
     * are empty and exactly as tall as their card was when it was last built, so a timeline of
     * thousands of posts lays out the same and costs a few screens of card views.
     *
     * <p>A post is never first placed by a guessed height. That was tried: a card away from the
     * screen was sized by an estimate and only built when the reader came near. Building then
     * happened while scrolling, a fast fling got ahead of it, and each card that was built
     * late took its real height in view and pushed the cards under it around. Now a new post
     * is built and measured when it arrives, and scrolling builds nothing new. Only a width
     * change leaves stand-in heights, and only on slots far from the reader (see EXACT_ABOVE).
     */
    private static final class MasonryGrid extends ViewGroup {
        // The column each post was dealt, by post and column count. A refreshed feed, and one
        // that X re-created, put every post back in its column instead of dealing them all
        // again, so the card being read never moves sideways.
        private static final android.util.LruCache<String, Integer> DEALT = new android.util.LruCache<>(8000);

        // In screen heights from the visible part. A card that was let go is built again when
        // the reader comes this near, and cards are let go this far behind; their pictures
        // likewise, on a tighter window. Each pair leaves a gap, so a card at the edge is not
        // built and dropped (or its pictures fetched and let go) as the reader moves back and
        // forth. Cards are kept for a long way: going back a few screens builds nothing.
        private static final float BUILD_WITHIN = 2f;
        private static final float RELEASE_BEYOND = 6f;
        private static final float PICTURES_WITHIN = 1f;
        private static final float PICTURES_BEYOND = 2f;
        // When the feed is laid out anew (a new view, a refresh, a new width), the cards the
        // reader is about to see are built during that layout, so the first frame has them:
        // this far above the post at the top of the screen and this far below, in screens.
        private static final float MEASURE_BUILD_ABOVE = 0.5f;
        private static final float MEASURE_BUILD_BELOW = 1.5f;
        // A card that had to be built only to learn its height is let go again at once when it
        // is more than this many posts from the reader's: a long timeline that is all new to
        // the grid (X handing a restored one over) must not hold every card at the same time.
        private static final int KEEP_MEASURED_WITHIN = 120;
        // At a new width (the feed was turned or resized) no height on record applies. The
        // built cards are measured again; the empty slots this many posts before and after
        // the reader's have their cards built to be measured too. The ones further away take
        // a stand-in until the reader comes near: building every card of a long timeline at
        // once would stop the app for seconds.
        private static final int EXACT_ABOVE = 12;
        private static final int EXACT_BELOW = 48;

        private static final java.util.Comparator<CardSlot> NEAREST_FIRST =
                (a, b) -> Integer.compare(a.away, b.away);

        private final int gap;
        private List<PostCard> cards = Collections.emptyList();
        private final List<Integer> columnOf = new ArrayList<>();
        private int assignedColumns = -1;
        // Where each card goes with the heights of this measure; scratch for onMeasure.
        private int[] tops = new int[0];
        // The empty slots near the screen, nearest first; scratch for updateWindow.
        private final List<CardSlot> waiting = new ArrayList<>();
        // The post items of slots that left the grid after X was told their cards were on
        // screen; X is told they went with the next report (see reportRows).
        private final List<Object> disposals = new ArrayList<>();
        // The post at the top of the screen (null at the start of the feed), from the scroll
        // container.
        private String focusPostId;
        // The next measure builds the cards around that post (see MEASURE_BUILD_ABOVE).
        private boolean buildAroundFocus = true;
        private int measuredWidth = -1;
        // Where each column starts. All zero, except after stand-in heights above the reader
        // gave way to real ones (see onMeasure), until the cards are next dealt anew.
        private int[] columnBase = new int[0];
        // The top of the screen in this grid's coordinates, from the scroll container.
        private int viewportTop;
        // How far measuring moved every card at once, down if positive (see onMeasure). The
        // scroll container takes it and moves the feed along.
        private int scrollShift;
        // The feed is gliding to its start, or has arrived there and the reader has not
        // scrolled since (see settleTop). No column gives way meanwhile, or the columns would
        // not start level on arrival; and the cards at the start count as on screen, so they
        // and their pictures are there when the feed arrives.
        private boolean headedToStart;
        // Run when the grid is measured at a new width, before the cards are placed.
        private Runnable onWidthChanged;
        // Run after every layout of the cards; set by the scroll container (see updateWindow).
        private Runnable afterLayout;

        MasonryGrid(Context context) {
            super(context);
            gap = Theme.dpToPx(context, GAP_DP);
            setWillNotDraw(true);
        }

        void setFocus(String postId) {
            focusPostId = postId;
        }

        /** The next layout has the cards around this post built (null: around the start). */
        void buildAround(String postId) {
            focusPostId = postId;
            buildAroundFocus = true;
        }

        void setViewportTop(int top) {
            viewportTop = top;
        }

        /** The reader scrolls by hand again (see headedToStart). */
        void readerTookOver() {
            headedToStart = false;
        }

        /** See scrollShift; zero from then on. */
        int takeScrollShift() {
            int shift = scrollShift;
            scrollShift = 0;
            return shift;
        }

        String postIdAt(int index) {
            return index >= 0 && index < cards.size() ? cards.get(index).postId : null;
        }

        int indexOfPost(String postId) {
            if (postId == null) return -1;
            for (int i = 0; i < cards.size(); i++) if (postId.equals(cards.get(i).postId)) return i;
            return -1;
        }

        boolean isEmpty() {
            return cards.isEmpty();
        }

        /** The ids of up to {@code count} posts that come after the one at {@code index}. */
        List<String> postIdsAfter(int index, int count) {
            List<String> ids = new ArrayList<>(count);
            for (int i = index + 1; i < cards.size() && ids.size() < count; i++) {
                String id = cards.get(i).postId;
                if (id != null) ids.add(id);
            }
            return ids;
        }

        /** How many of {@code ids} are among the posts shortly after the one at {@code index}. */
        int countAfter(int index, List<String> ids) {
            int found = 0;
            int end = Math.min(cards.size(), index + 1 + ids.size() * 3);
            for (int i = index + 1; i < end; i++) {
                if (ids.contains(cards.get(i).postId)) found++;
            }
            return found;
        }

        // Every child is a slot, and slot i holds cards.get(i); setCards keeps it so.
        private CardSlot slotAt(int index) {
            return (CardSlot) getChildAt(index);
        }

        /** Redraws this post's built cards' action rows with what they should show now (see shownEngagement). */
        void refreshCard(String postId, View except) {
            if (postId == null) return;
            for (int i = 0; i < cards.size() && i < getChildCount(); i++) {
                if (!postId.equals(cards.get(i).postId)) continue;
                CardSlot slot = slotAt(i);
                if (except != null && isInside(except, slot)) continue;
                slot.redraw();
            }
        }

        PostCard cardFor(String postId) {
            int index = indexOfPost(postId);
            return index >= 0 ? cards.get(index) : null;
        }

        /** Returns true when the cards were put in a new order rather than appended to. */
        boolean setCards(List<PostCard> next) {
            boolean appendOnly = next.size() >= cards.size()
                    && next.subList(0, cards.size()).equals(cards);
            if (!appendOnly) {
                // Refresh or reorder: positions follow the new order. Posts that are still there
                // keep their slot, with its built card and pictures if it has them; only new
                // posts get a new one. Building every card again blanked the whole feed and
                // fetched every picture again.
                java.util.Map<String, CardSlot> kept = new java.util.HashMap<>();
                for (int i = 0; i < getChildCount(); i++) {
                    CardSlot slot = slotAt(i);
                    if (slot.card().postId != null) kept.put(slot.card().postId, slot);
                }
                removeAllViews();
                columnOf.clear();
                Arrays.fill(columnBase, 0);
                buildAroundFocus = true;
                for (PostCard card : next) {
                    CardSlot slot = card.postId == null ? null : kept.get(card.postId);
                    PostCard old = slot == null ? null : slot.card();
                    Engagement before = old == null ? null : shownEngagement(old);
                    reconcile(card);
                    if (old == null || !old.sameContentAs(card)) {
                        addView(new CardSlot(getContext(), card));
                        continue;
                    }
                    kept.remove(card.postId);
                    slot.update(card, old != card || !before.sameAs(shownEngagement(card)));
                    addView(slot);
                }
                // Cards of posts that are gone stop waiting for their pictures.
                for (CardSlot gone : kept.values()) retire(gone);
            } else {
                // Same posts in the same places: refresh only cards whose shown counts or state
                // changed, e.g. once X re-sends a post the reader just liked. Update the action
                // row in place instead of rebuilding the card, or the media reloads, the grid
                // reflows and every post blinks. A stale re-send (X has not applied the action
                // yet) leaves the reader's pending state on screen rather than flipping it back.
                for (int i = 0; i < cards.size(); i++) {
                    PostCard old = cards.get(i);
                    PostCard card = next.get(i);
                    Engagement before = shownEngagement(old);
                    reconcile(card);
                    CardSlot slot = slotAt(i);
                    if (!old.sameContentAs(card)) {
                        // The post itself changed (an edit, or now shown as someone's repost).
                        retire(slot);
                        removeViewAt(i);
                        addView(new CardSlot(getContext(), card), i);
                    } else {
                        slot.update(card, !before.sameAs(shownEngagement(card)));
                    }
                }
                for (int i = cards.size(); i < next.size(); i++) {
                    reconcile(next.get(i));
                    addView(new CardSlot(getContext(), next.get(i)));
                }
            }
            requestLayout();
            boolean rebuilt = !appendOnly && !cards.isEmpty();
            cards = next;
            return rebuilt;
        }

        /**
         * Builds the cards near the screen that were let go, lets go of the ones far from it,
         * and does the same for pictures on a tighter window. Without this a long timeline kept
         * every card view and every picture, and a feed that X re-created built and fetched all
         * of them again at once, the ones on screen last. Nothing moves in here: an empty slot
         * is as tall as its card. {@code visibleTop}/{@code visibleBottom}: the part of the
         * grid on screen, in the grid's own coordinates. {@code buildNanos}: how long cards may
         * be built for this time; one is built whatever it says. Returns true when cards near
         * the screen are still waiting. Cards as near as each other are built in timeline order.
         */
        boolean updateWindow(int visibleTop, int visibleBottom, long buildNanos) {
            int screen = Math.max(1, visibleBottom - visibleTop);
            int buildWithin = Math.round(screen * BUILD_WITHIN);
            int releaseBeyond = Math.round(screen * RELEASE_BEYOND);
            int picturesWithin = Math.round(screen * PICTURES_WITHIN);
            int picturesBeyond = Math.round(screen * PICTURES_BEYOND);
            waiting.clear();
            for (int i = 0; i < getChildCount(); i++) {
                CardSlot slot = slotAt(i);
                // A slot not laid out yet has no place; it is looked at again after the layout.
                if (!slot.isLaidOut()) continue;
                int top = slot.getTop();
                int bottom = slot.getBottom();
                int away = top > visibleBottom ? top - visibleBottom
                        : bottom < visibleTop ? visibleTop - bottom : 0;
                // On the way to the start of the feed, its first screen counts as on screen.
                if (headedToStart) away = Math.min(away, Math.max(0, top - screen));
                if (away <= buildWithin) {
                    if (!slot.built()) {
                        slot.away = away;
                        waiting.add(slot);
                    }
                } else if (away > releaseBeyond) {
                    slot.release();
                }
                if (away <= picturesWithin) slot.showPictures(true);
                else if (away > picturesBeyond) slot.showPictures(false);
            }
            if (waiting.isEmpty()) return false;
            // The cards on screen first, then outwards.
            Collections.sort(waiting, NEAREST_FIRST);
            long deadline = System.nanoTime() + buildNanos;
            int built = 0;
            while (built < waiting.size() && (built == 0 || System.nanoTime() < deadline)) {
                waiting.get(built++).build(false);
            }
            boolean more = built < waiting.size();
            waiting.clear();
            return more;
        }

        private int columnCount(int width) {
            float density = getResources().getDisplayMetrics().density;
            int min = Math.round(MIN_COLUMN_DP * density);
            int count = (width + gap) / (min + gap);
            return Math.max(1, Math.min(MAX_COLUMNS, count));
        }

        private int columnWidth(int width, int columns) {
            return Math.max(1, (width - gap * (columns - 1)) / columns);
        }

        /** The column a post was dealt before at this column count, else the shortest one now. */
        private static int deal(PostCard card, int columns, int[] nextTop) {
            String key = card.postId == null ? null : card.postId + '/' + columns;
            Integer dealt = key == null ? null : DEALT.get(key);
            if (dealt != null && dealt < columns) return dealt;
            int column = shortest(nextTop);
            if (key != null) DEALT.put(key, column);
            return column;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (width <= 0) {
                // Not sized yet; nothing is dealt, built or remembered for a width that is none.
                setMeasuredDimension(resolveSize(0, widthSpec), resolveSize(0, heightSpec));
                return;
            }
            int columns = columnCount(width);
            if (columns != assignedColumns) {
                // Rotation or resize: columns are dealt again for the new count.
                columnOf.clear();
                assignedColumns = columns;
            }
            if (columnBase.length != columns) columnBase = new int[columns];
            int columnWidth = columnWidth(width, columns);
            int count = getChildCount();
            if (width != measuredWidth) {
                if (measuredWidth > 0) {
                    // Rotation or resize: every card changes shape. The reader's post is put
                    // back at the top of the screen afterwards (the scroll container's part).
                    buildAroundFocus = true;
                    Arrays.fill(columnBase, 0);
                    if (onWidthChanged != null) onWidthChanged.run();
                }
                measuredWidth = width;
            }

            int childWidthSpec = MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            if (tops.length < count) tops = new int[count + 64];
            // Where the next card of each column goes.
            int[] nextTop = columnBase.clone();
            int focus = indexOfPost(focusPostId);
            if (focus >= count) focus = -1;

            // Cards are placed by their real heights. A built card is measured; an empty slot
            // has the height its card was measured at before, at this width. A post that has
            // never been measured is built here and now to be measured, as every card was
            // before there were slots, so a new post is never placed by a guess. The one
            // exception is a new width, see EXACT_ABOVE. New cards are dealt here.
            int reader = Math.max(focus, 0);
            for (int i = 0; i < count; i++) {
                CardSlot slot = slotAt(i);
                // A stand-in this slot was given at an earlier measure; -1 for none.
                int standIn = slot.standInAt(columnWidth);
                boolean builtToMeasure = false;
                if (!slot.built() && standIn < 0 && !slot.knowsHeightAt(columnWidth)) {
                    boolean near = i >= reader - EXACT_ABOVE && i <= reader + EXACT_BELOW;
                    if (near || !slot.guessHeightAt(columnWidth)) {
                        slot.build(true);
                        builtToMeasure = true;
                    }
                }
                slot.measure(childWidthSpec, childHeightSpec);
                if (builtToMeasure && Math.abs(i - reader) > KEEP_MEASURED_WITHIN) {
                    slot.releaseWhileMeasuring();
                }
                if (i >= columnOf.size()) columnOf.add(deal(slot.card(), columns, nextTop));
                int column = columnOf.get(i);
                int grew = standIn < 0 || !slot.hasCard() ? 0 : slot.getMeasuredHeight() - standIn;
                if (grew != 0 && slot.isLaidOut() && slot.getHeight() == standIn
                        && slot.getBottom() <= viewportTop && canGiveWay(column, grew)) {
                    // A card above the reader was built and its real height took the place of
                    // the stand-in. Everything under it in its column would move, the cards on
                    // screen with it. Instead the column gives way upwards: this card's top and
                    // the cards above it move, out of sight, and nothing under it does.
                    columnBase[column] -= grew;
                    nextTop[column] -= grew;
                }
                tops[i] = nextTop[column];
                nextTop[column] = tops[i] + slot.getMeasuredHeight() + gap;
            }

            if (buildAroundFocus) {
                // The cards the reader is about to see are built before this layout is drawn:
                // around the post at the top of the screen, or the start of the feed. (The
                // rest come back as the reader nears them, see updateWindow.)
                buildAroundFocus = false;
                int screen = Math.max(1, getResources().getDisplayMetrics().heightPixels);
                int focusTop = focus < 0 ? 0 : tops[focus];
                int from = focusTop - Math.round(screen * MEASURE_BUILD_ABOVE);
                int to = focusTop + Math.round(screen * MEASURE_BUILD_BELOW);
                for (int i = 0; i < count; i++) {
                    CardSlot slot = slotAt(i);
                    if (slot.built() || tops[i] >= to || tops[i] + slot.getMeasuredHeight() <= from) continue;
                    slot.build(true);
                    slot.measure(childWidthSpec, childHeightSpec);
                }
            }

            // Columns that gave way start at different heights. The highest one is put at the
            // top of the grid, which moves every card by the same amount.
            int lowest = columnBase[0];
            for (int base : columnBase) lowest = Math.min(lowest, base);
            if (lowest != 0) {
                for (int c = 0; c < columns; c++) columnBase[c] -= lowest;
                scrollShift -= lowest;
            }

            int[] end = columnBase.clone();
            for (int i = 0; i < count; i++) {
                end[columnOf.get(i)] += slotAt(i).getMeasuredHeight() + gap;
            }
            int tallest = 0;
            for (int c = 0; c < columns; c++) {
                // A column's cards end one gap before where its next card would go.
                if (end[c] > columnBase[c]) tallest = Math.max(tallest, end[c] - gap);
            }
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(tallest, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int columns = columnCount(width);
            int columnWidth = columnWidth(width, columns);
            int[] nextTop = new int[columns];
            for (int c = 0; c < columns && c < columnBase.length; c++) nextTop[c] = columnBase[c];
            for (int i = 0; i < getChildCount() && i < columnOf.size(); i++) {
                View child = getChildAt(i);
                int column = Math.min(columnOf.get(i), columns - 1);
                int left = column * (columnWidth + gap);
                child.layout(left, nextTop[column], left + columnWidth, nextTop[column] + child.getMeasuredHeight());
                nextTop[column] += child.getMeasuredHeight() + gap;
            }
            // The cards have their places: the scroll container can tell which are near the screen.
            if (afterLayout != null) afterLayout.run();
        }

        /**
         * Tells X where each card on screen is and which cards have left it (see the report
         * bridges). {@code gridLeft}/{@code gridTop}: where this grid is in the window;
         * {@code shownTop}/{@code shownBottom}: the part of the window the feed shows.
         * {@code all}: every card on screen is reported, not only the ones that moved.
         * Returns how many cards are on screen.
         */
        int reportRows(Object component, int gridLeft, int gridTop, int shownTop, int shownBottom, boolean all) {
            flushDisposals(component);
            int onScreen = 0;
            for (int i = 0; i < getChildCount(); i++) {
                CardSlot slot = slotAt(i);
                Object item = slot.card().item;
                int top = gridTop + slot.getTop();
                int bottom = gridTop + slot.getBottom();
                // An empty slot shows no post, and a module read as one card has no post item.
                if (item == null || !slot.isLaidOut() || !slot.hasCard() || bottom <= shownTop || top >= shownBottom) {
                    if (slot.reported != null) {
                        tellDisposed(component, slot.reported);
                        slot.reported = null;
                    }
                    continue;
                }
                onScreen++;
                int left = gridLeft + slot.getLeft();
                int right = gridLeft + slot.getRight();
                if (!all && slot.reported != null && slot.sentLeft == left && slot.sentTop == top
                        && slot.sentRight == right && slot.sentBottom == bottom) {
                    continue;
                }
                // Reporting was switched off after failing: nothing more is sent.
                if (!tellPositioned(component, item, left, top, right, bottom, i)) break;
                slot.reported = item;
                slot.sentLeft = left;
                slot.sentTop = top;
                slot.sentRight = right;
                slot.sentBottom = bottom;
            }
            return onScreen;
        }

        /** The feed has left the screen: every card X was told about is gone from it. */
        void disposeReported(Object component) {
            flushDisposals(component);
            for (int i = 0; i < getChildCount(); i++) {
                CardSlot slot = slotAt(i);
                if (slot.reported == null) continue;
                tellDisposed(component, slot.reported);
                slot.reported = null;
            }
        }

        private void flushDisposals(Object component) {
            if (disposals.isEmpty()) return;
            for (Object item : disposals) tellDisposed(component, item);
            disposals.clear();
        }

        /** A slot that leaves the grid: if X was told about its card, it is told that it went. */
        private void retire(CardSlot slot) {
            if (slot.reported != null) {
                disposals.add(slot.reported);
                slot.reported = null;
            }
            slot.discard();
        }

        /**
         * Whether a column may start {@code by} higher (see columnBase). Columns that gave way
         * by different amounts start at different heights, which shows at the start of the
         * feed; they are kept within a quarter of a screen of each other. Past that the card
         * under a stand-in moves after all, with the cards under it.
         */
        private boolean canGiveWay(int column, int by) {
            if (headedToStart) return false;
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;
            for (int c = 0; c < columnBase.length; c++) {
                int base = c == column ? columnBase[c] - by : columnBase[c];
                lowest = Math.min(lowest, base);
                highest = Math.max(highest, base);
            }
            return highest - lowest <= getResources().getDisplayMetrics().heightPixels / 4;
        }

        /**
         * The reader is on the way to the start of the feed (Home again): whatever is unsettled
         * there is settled now, while the feed races past and it does not show. Columns start
         * level again and stay so until the reader scrolls by hand, and the first cards that
         * only had a stand-in height are built to get their real one, so that nothing moves
         * when the reader arrives.
         */
        void settleTop() {
            headedToStart = true;
            boolean changed = false;
            for (int base : columnBase) changed |= base != 0;
            Arrays.fill(columnBase, 0);
            for (int i = 0; i < getChildCount() && i <= EXACT_BELOW; i++) {
                CardSlot slot = slotAt(i);
                if (!slot.forgetStandIn()) continue;
                slot.build(false);
                changed = true;
            }
            if (changed) requestLayout();
        }

        private static int shortest(int[] heights) {
            int best = 0;
            for (int i = 1; i < heights.length; i++) if (heights[i] < heights[best]) best = i;
            return best;
        }
    }

    /**
     * A card's place in the grid. The card's view is only there while the card is within a few
     * screens of the reader; the rest of the time the slot is empty and keeps the height the
     * card was measured at. Every post keeping its card view made a long timeline slower the
     * longer it got.
     */
    private static final class CardSlot extends ViewGroup {
        // Heights of cards measured this session, by post, content, width and text size. They
        // outlive the view: when X re-creates the feed, cards away from the screen are not
        // built again just to learn how tall they are.
        private static final android.util.LruCache<String, Integer> MEASURED = new android.util.LruCache<>(8000);
        // Each card's latest measurement, at whatever width (the width in the high half, the
        // height in the low one): what a stand-in height at another width is worked out from.
        private static final android.util.LruCache<String, Long> LAST_MEASURED = new android.util.LruCache<>(8000);
        private static final android.graphics.Paint PLACEHOLDER = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        private PostCard card;
        private CardRoot content;
        // The card's height at heightWidth, as measured from the built card (now, or earlier
        // this session). While measured is false the card has never been built at that width,
        // and height is a stand-in (see guessHeightAt) or -1.
        private int height = -1;
        private int heightWidth = -1;
        private boolean measured;
        private boolean picturesWanted;
        // How far from the screen this slot is; scratch for MasonryGrid.updateWindow.
        int away;
        // The post item X was last told is on screen for this slot, and where (in the
        // window); null while X has not been told, or has been told that the card went (see
        // MasonryGrid.reportRows).
        Object reported;
        int sentLeft;
        int sentTop;
        int sentRight;
        int sentBottom;
        // Building this card failed once; it stays an empty placeholder rather than be tried
        // (and fail) on every layout.
        private boolean unbuildable;
        private final int placeholderColor;
        private final float placeholderRadius;
        // What a card that could not be built takes up.
        private final int unbuildableHeight;

        CardSlot(Context context, PostCard card) {
            super(context);
            this.card = card;
            placeholderColor = Theme.surfaceContainer(context);
            placeholderRadius = Theme.dpToPx(context, CARD_RADIUS_DP);
            unbuildableHeight = Theme.dpToPx(context, 96f);
            // The built card's shadow reaches a little past the slot.
            setClipChildren(false);
            setWillNotDraw(false);
        }

        PostCard card() {
            return card;
        }

        /** True when there is nothing (more) to build: the card is there, or it cannot be made. */
        boolean built() {
            return content != null || unbuildable;
        }

        boolean hasCard() {
            return content != null;
        }

        /** The stand-in height this slot has at this width (see guessHeightAt), or -1 for none. */
        int standInAt(int width) {
            return !measured && heightWidth == width && height >= 0 ? height : -1;
        }

        /** Drops a stand-in height, so that the card is measured for real; false if there was none. */
        boolean forgetStandIn() {
            if (content != null || unbuildable || measured || height < 0) return false;
            height = -1;
            heightWidth = -1;
            return true;
        }

        /**
         * A newer copy of the same post with the same words and pictures. {@code redraw}: its
         * counts or the reader's state changed, so a built card's action row is refreshed.
         */
        void update(PostCard next, boolean redraw) {
            card = next;
            if (redraw) redraw();
        }

        void redraw() {
            if (content != null) CardViews.rebind(content, card);
        }

        /** Builds the card's view. {@code duringMeasure}: the grid is being measured right now. */
        void build(boolean duringMeasure) {
            if (content != null || unbuildable) return;
            try {
                content = CardViews.build(getContext(), card);
            } catch (RuntimeException e) {
                // One post that cannot be drawn must not take the whole feed down with it.
                unbuildable = true;
                NewXLogger.printException(() -> LOG_PREFIX + "could not build the card of post " + card.postId, e);
                return;
            }
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (duringMeasure) {
                // No layout request goes up from here (one is already running), so this slot is
                // marked by hand: the measure the grid does next must not reuse the empty size.
                addViewInLayout(content, 0, params, true);
                forceLayout();
            } else {
                addView(content, params);
            }
            if (picturesWanted) content.showPictures(true);
            invalidate();
        }

        /** Lets the built card go, once it has been measured here, so that nothing moves. */
        void release() {
            if (content != null && measured && heightWidth == getWidth()) drop();
        }

        /**
         * For a card the grid built only to measure, while the grid is still being measured:
         * the height stays, the view goes. No layout is asked for; one is running.
         */
        void releaseWhileMeasuring() {
            if (content == null || !measured) return;
            content.showPictures(false);
            removeViewInLayout(content);
            content = null;
        }

        /** The post left the timeline, or its card has to be made anew. */
        void discard() {
            if (content != null) drop();
        }

        private void drop() {
            content.showPictures(false);
            removeView(content);
            content = null;
            invalidate();
        }

        void showPictures(boolean show) {
            picturesWanted = show;
            if (content != null) content.showPictures(show);
        }

        /**
         * True when the card's real height at this width is on record: it is built and was
         * measured at it, or was earlier this session.
         */
        boolean knowsHeightAt(int width) {
            if (heightWidth != width) {
                Integer known = card.postId == null ? null : MEASURED.get(key(width));
                measured = known != null;
                height = measured ? known : -1;
                heightWidth = width;
            }
            return measured;
        }

        /**
         * For an empty slot whose card was measured this session, but not at this width (the
         * feed was turned or resized): takes a stand-in height worked out from that one, until
         * the card is built here. False when there is nothing to go by: a post that has never
         * been measured gets no stand-in, it has to be built.
         */
        boolean guessHeightAt(int width) {
            if (heightWidth == width && height >= 0) return true;
            Long last = card.postId == null ? null : LAST_MEASURED.get(lastKey());
            if (last == null) return false;
            int fromWidth = (int) (last >> 32);
            int fromHeight = (int) (last & 0xFFFFFFFFL);
            if (fromWidth <= 0 || fromHeight <= 0) return false;
            height = CardViews.guessHeight(getContext(), card, fromWidth, fromHeight, width);
            heightWidth = width;
            measured = false;
            // Not a size this slot was measured with before: no measurement on record is reused.
            forceLayout();
            return true;
        }

        private String key(int width) {
            return width + "@" + lastKey();
        }

        // The same card, whatever its width: same post, same content, same text size.
        private String lastKey() {
            int textScale = Math.round(getResources().getDisplayMetrics().scaledDensity * 100f);
            return card.postId + "@" + textScale + "@" + card.contentKey.hashCode();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            if (content != null) {
                content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int real = content.getMeasuredHeight();
                if (!measured || heightWidth != width || height != real) {
                    height = real;
                    heightWidth = width;
                    measured = true;
                    if (card.postId != null) {
                        MEASURED.put(key(width), real);
                        LAST_MEASURED.put(lastKey(), ((long) width << 32) | real);
                    }
                }
                setMeasuredDimension(width, real);
                return;
            }
            // Empty: as tall as the card was when it was last built at this width, or the
            // stand-in the grid asked for. The grid sees to one or the other before it
            // measures a slot, so having neither means the card could not be built.
            knowsHeightAt(width);
            setMeasuredDimension(width, height >= 0 ? height : unbuildableHeight);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (content == null) return;
            boolean first = !content.isLaidOut();
            content.layout(0, 0, right - left, bottom - top);
            // The placeholder drawn until now (see onDraw) is no longer wanted.
            if (first) invalidate();
        }

        // An empty slot shows the shape of its card, as does one whose card has not been laid
        // out yet. It is only seen when the feed moves faster than cards come back: going a
        // long way back up quickly, or the glide to the top from deep in a long timeline.
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (content != null && content.isLaidOut()) return;
            PLACEHOLDER.setColor(placeholderColor);
            canvas.drawRoundRect(0f, 0f, getWidth(), getHeight(), placeholderRadius, placeholderRadius, PLACEHOLDER);
        }
    }

    // ---------- Card views ----------

    private static final class CardViews {
        private CardViews() {
        }

        // Mark the parts of a built card that are found again later: the action row and the
        // "@handle · age · views" line (refreshed in place, see rebind), and the More button
        // (the menu a long press on the card opens from).
        private static final Object ACTIONS_TAG = new Object();
        private static final Object BYLINE_TAG = new Object();
        private static final Object MORE_TAG = new Object();

        /**
         * Refreshes a built grid card for a newer copy of the same post without rebuilding it:
         * the action row (counts and the reader's state) and the byline (age, views). The media
         * is not reloaded and the masonry grid does not reflow. Used when X re-sends a post, e.g.
         * after a like/repost/bookmark; rebuilding the whole card made every post blink.
         */
        static void rebind(View cardRoot, PostCard card) {
            if (cardRoot == null) return;
            Context context = cardRoot.getContext();
            View actionRow = cardRoot.findViewWithTag(ACTIONS_TAG);
            if (actionRow != null && actionRow.getParent() instanceof ViewGroup body) {
                int index = body.indexOfChild(actionRow);
                body.removeViewAt(index);
                body.addView(actions(context, card, Theme.secondaryText(context)), index);
            }
            View line = cardRoot.findViewWithTag(BYLINE_TAG);
            String byline = byline(context, card);
            if (line instanceof TextView text && byline != null) text.setText(byline);
            cardRoot.setContentDescription(card.describe());
            // Keep the card tap (focus view) pointed at the fresh card.
            cardRoot.setOnClickListener(view -> showFocus(context, card));
        }

        /** The theme's touch ripple, to lay over a tappable view; null when the theme has none. */
        static Drawable pressRipple(Context context) {
            TypedValue value = new TypedValue();
            try {
                if (!context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                        || value.resourceId == 0) {
                    return null;
                }
                return context.getDrawable(value.resourceId);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** A light tick and a small bounce on the button: the one-tap action went through. */
        private static void confirm(View button) {
            button.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            button.animate().cancel();
            button.setScaleX(0.8f);
            button.setScaleY(0.8f);
            button.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
        }

        static CardRoot build(Context context, PostCard card) {
            return build(context, card, false);
        }

        /** {@code focal}: the enlarged card of the focus view (full text, larger type). */
        static CardRoot build(Context context, PostCard card, boolean focal) {
            int pad = Theme.dpToPx(context, 14f);
            int ink = Theme.primaryText(context);
            int muted = Theme.secondaryText(context);

            CardRoot root = new CardRoot(context);
            root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cardBackground = rounded(Theme.surfaceContainer(context), Theme.dpToPx(context, CARD_RADIUS_DP));
            cardBackground.setStroke(Math.max(1, Theme.dpToPx(context, 1f)), Theme.dividerColor(context));
            root.setBackground(cardBackground);
            root.setElevation(Theme.dpToPx(context, 1f));
            clipToRoundRect(root, Theme.dpToPx(context, CARD_RADIUS_DP));
            root.setClickable(true);
            // A timeline card pops out into the focus view; the enlarged card itself stays put.
            View.OnClickListener popOut = focal ? null : view -> showFocus(context, card);
            root.setOnClickListener(view -> {
                if (popOut != null) popOut.onClick(view);
            });
            // The card answers the finger; the enlarged card has nothing to open, so it stays still.
            if (!focal) root.setForeground(pressRipple(context));
            // A long press anywhere on the card opens the same menu as its More button.
            root.setOnLongClickListener(view -> {
                View more = root.findViewWithTag(MORE_TAG);
                showMoreMenu(more != null ? more : view, card);
                return true;
            });
            root.setContentDescription(card.describe());

            if (card.repostedBy != null) {
                TextView contextLine = text(context,
                        "\u21BB " + Labels.with(context, "%s reposted", "reposted_by", card.repostedBy), 13f, muted);
                bold(contextLine);
                contextLine.setPadding(pad, pad - Theme.dpToPx(context, 2f), pad, Theme.dpToPx(context, 8f));
                root.addView(contextLine);
            }

            if (card.coverText == null && !card.media.isEmpty()) {
                // In the enlarged card a tall image is capped so the card fits on screen.
                root.addView(card.sensitiveMedia
                        ? sensitiveTile(context, card)
                        : mediaBlock(context, card.media, card, popOut, focal ? focalMediaHeight(context) : 0),
                        matchWidth());
            }

            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(pad, pad, pad, Theme.dpToPx(context, 6f));

            if (card.replyingTo != null) {
                TextView replying = text(context, "\u21A9 " + Labels.with(context, "Replying to %s",
                        "reply_context_to_someone", "@" + card.replyingTo), 13f, muted);
                replying.setOnClickListener(view -> openInX(context, profileLink(card.replyingTo)));
                replying.setPadding(0, 0, 0, Theme.dpToPx(context, 8f));
                body.addView(replying);
            }

            body.addView(header(context, card, ink, muted));

            // A post X covers with a warning shows only that warning; the post opens on tap,
            // where X's own reveal control is.
            String bodyText = card.coverText != null ? "\u26A0 " + card.coverText : card.text;
            if (bodyText != null) {
                TextView text = text(context, bodyText, focal ? 19f : 16f, card.coverText != null ? muted : ink);
                text.setLineSpacing(0f, focal ? 1.25f : 1.2f);
                if (card.coverText == null) showLinks(text, card);
                if (!focal) {
                    text.setMaxLines(card.media.isEmpty() ? MAX_TEXT_LINES : MAX_TEXT_LINES_WITH_MEDIA);
                    text.setEllipsize(TextUtils.TruncateAt.END);
                } else {
                    // Keep the enlarged card within the screen: budget the text by the viewport,
                    // leaving room for the media (capped to the same share, see focalMediaHeight)
                    // and the card chrome, so the focus overlay does not need to scroll.
                    android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                    int lineHeight = Math.max(1, Math.round(19f * 1.25f * metrics.scaledDensity));
                    int reserved = Theme.dpToPx(context, 300f)
                            + (card.media.isEmpty() ? 0 : focalMediaHeight(context));
                    int maxLines = Math.max(4, (metrics.heightPixels - reserved) / lineHeight);
                    text.setTextIsSelectable(true);
                    text.setMaxLines(maxLines);
                    // Selectable text cannot show an ellipsis, so a post longer than the budget
                    // turns into plain text ending in "…", and tapping it opens the full post.
                    text.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(View v, int l, int t, int r, int b,
                                                   int oldL, int oldT, int oldR, int oldB) {
                            if (text.getLayout() == null) return;
                            text.removeOnLayoutChangeListener(this);
                            if (text.getLineCount() <= maxLines) return;
                            text.post(() -> {
                                text.setTextIsSelectable(false);
                                text.setEllipsize(TextUtils.TruncateAt.END);
                                text.setMaxLines(maxLines);
                                text.setOnClickListener(view -> openInX(context, card.postLink()));
                            });
                        }
                    });
                }
                LinearLayout.LayoutParams params = matchWidth();
                params.topMargin = Theme.dpToPx(context, 8f);
                body.addView(text, params);
            }

            if (card.quote != null && card.coverText == null) {
                LinearLayout.LayoutParams params = matchWidth();
                params.topMargin = Theme.dpToPx(context, 12f);
                body.addView(quoteBox(context, card.quote, ink, muted, popOut), params);
            }

            body.addView(actions(context, card, muted));
            root.addView(body);
            root.collectPictures(root);
            // A grid card gets its pictures once it is near the screen (see
            // MasonryGrid.updateWindow); the enlarged card is on screen now.
            if (focal) root.showPictures(true);
            return root;
        }

        /**
         * About how tall a grid card is at {@code width}, going by how tall it really was at
         * {@code fromWidth}. Only for a card away from the reader after the feed was turned or
         * resized (see CardSlot.guessHeightAt); the real height takes over when the card is
         * built. It is the real height, changed by what build() makes differently at another
         * width: pictures keep their shape, so their part is worked out, and the text takes
         * more or fewer lines. The fixed parts stay as they were measured.
         */
        static int guessHeight(Context context, PostCard card, int fromWidth, int fromHeight, int width) {
            if (fromWidth == width || fromWidth <= 0 || width <= 0) return Math.max(1, fromHeight);
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float dp = metrics.density;
            float sp = metrics.scaledDensity;
            float height = fromHeight
                    + picturesHeight(card, width, dp) - picturesHeight(card, fromWidth, dp)
                    + textHeight(card, width, dp, sp) - textHeight(card, fromWidth, dp, sp);
            // Never less than a card's fixed parts take.
            return Math.max(Math.round(80f * dp), Math.round(height));
        }

        /** The height build() gives a grid card's pictures at this width, its own and a quote's. */
        private static float picturesHeight(PostCard card, int width, float dp) {
            if (card.coverText != null) return 0f;
            float height = 0f;
            if (!card.media.isEmpty() && !card.sensitiveMedia) height += mediaHeight(card.media, width, 2f * dp);
            if (card.quote != null && !card.quote.media.isEmpty()) {
                height += mediaHeight(card.quote.media.subList(0, 1), Math.max(1f, width - 28f * dp), 2f * dp);
            }
            return height;
        }

        /** About the height of a grid card's text at this width, its own and a quote's. */
        private static float textHeight(PostCard card, int width, float dp, float sp) {
            float inner = Math.max(1f, width - 28f * dp);
            float height = 0f;
            String shown = card.coverText != null ? card.coverText : card.text;
            if (shown != null) {
                int most = card.media.isEmpty() ? MAX_TEXT_LINES : MAX_TEXT_LINES_WITH_MEDIA;
                height += Math.min(most, lines(shown, inner, 16f * sp)) * 16f * sp * 1.17f * 1.2f;
            }
            if (card.quote != null && card.coverText == null && card.quote.text != null) {
                float quoteInner = Math.max(1f, inner - 24f * dp);
                height += Math.min(5, lines(card.quote.text, quoteInner, 14f * sp)) * 14f * sp * 1.17f;
            }
            return height;
        }

        // A line of text is about 1.17 times its size tall (times the view's line spacing).
        // Latin letters average about half their size in width; CJK characters are a full one.
        private static int lines(String text, float width, float textSize) {
            int lines = 0;
            float used = 0f;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lines++;
                    used = 0f;
                    continue;
                }
                used += (c >= 0x2E80 ? 1f : 0.5f) * textSize;
                if (used > width) {
                    lines++;
                    used = 0f;
                }
            }
            return lines + 1;
        }

        /** The height mediaBlock() gives these tiles: one row, or two rows of two for four. */
        private static float mediaHeight(List<MediaItem> items, float width, float gap) {
            List<MediaItem> shown = items.size() > MAX_MEDIA ? items.subList(0, MAX_MEDIA) : items;
            if (shown.size() == 4) {
                return rowHeight(shown.subList(0, 2), width, gap) + gap + rowHeight(shown.subList(2, 4), width, gap);
            }
            return rowHeight(shown, width, gap);
        }

        private static float rowHeight(List<MediaItem> row, float width, float gap) {
            float sum = 0f;
            for (MediaItem item : row) sum += MediaRow.knownAspect(item);
            return sum <= 0f ? 0f : Math.max(1f, (width - gap * (row.size() - 1)) / sum);
        }

        private static View header(Context context, PostCard card, int ink, int muted) {
            LinearLayout head = new LinearLayout(context);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            int avatarSize = Theme.dpToPx(context, 32f);
            View avatar = avatar(context, card, avatarSize);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(avatarSize, avatarSize);
            avatarParams.setMarginEnd(Theme.dpToPx(context, 10f));
            head.addView(avatar, avatarParams);

            LinearLayout who = new LinearLayout(context);
            who.setOrientation(LinearLayout.VERTICAL);
            // As on X: picture, name and @handle open the author's profile.
            View.OnClickListener openProfile = view -> openInX(context, profileLink(card.handle));
            avatar.setOnClickListener(openProfile);
            who.setOnClickListener(openProfile);
            if (card.name != null) {
                TextView name = text(context, card.name, 14.5f, ink);
                bold(name);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                who.addView(name);
            }
            String sub = byline(context, card);
            if (sub != null) {
                TextView handle = text(context, sub, 13f, muted);
                handle.setSingleLine(true);
                handle.setEllipsize(TextUtils.TruncateAt.END);
                handle.setTag(BYLINE_TAG);
                who.addView(handle);
            }
            head.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View more = iconButton(context, Labels.of(context, "More", "xchat_more"), muted, null,
                    "\u22EF", "ic_vector_overflow", "ic_vector_more");
            more.setTag(MORE_TAG);
            more.setOnClickListener(view -> showMoreMenu(view, card));
            head.addView(more);
            return head;
        }

        /** "@handle \u00B7 5m \u00B7 1.2K views", worked out when it is shown so the age keeps up. */
        private static String byline(Context context, PostCard card) {
            String views = formatCount(card.engagement.views);
            return joinNonNull(joinNonNull(card.handle == null ? null : "@" + card.handle, card.age()),
                    views == null ? null : views + " " + Labels.of(context, "views", "view_count"));
        }

        private static void showMoreMenu(View anchor, PostCard card) {
            Context context = anchor.getContext();
            PopupMenu menu = new PopupMenu(context, anchor);
            menu.getMenu().add(0, 1, 0, Labels.of(context, "Open post", "view_tweet_text"));
            menu.getMenu().add(0, 2, 1, Labels.of(context, "Copy link", "copy_link"));
            // Not for a post X covers with a warning: the card does not show that text either.
            if (card.text != null && card.coverText == null) menu.getMenu().add(0, 5, 2, "Copy text");
            menu.getMenu().add(0, 3, 3, Labels.of(context, "Share via\u2026", "room_settings_system_share_via"));
            if (card.handle != null) {
                menu.getMenu().add(0, 4, 4, (Labels.english(context) ? "View @" : "@") + card.handle);
            }
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1 -> openInX(context, card.postLink());
                    case 2 -> copyLink(context, card);
                    case 3 -> sharePost(context, card);
                    case 4 -> openInX(context, profileLink(card.handle));
                    case 5 -> copyText(context, card);
                    default -> {
                        return false;
                    }
                }
                return true;
            });
            menu.show();
        }

        /**
         * X's repost choices, as its own repost sheet offers them. Repost and undo run in place
         * through X's service; quote opens X's composer with the post attached. If the service
         * is not available, X's repost screen takes over.
         */
        private static void showRepostMenu(View anchor, PostCard card, ToggleState state, Runnable paint) {
            Context context = anchor.getContext();
            PopupMenu menu = new PopupMenu(context, anchor);
            menu.getMenu().add(0, 1, 0, state.active
                    ? Labels.of(context, "Undo repost", "tweets_undo_retweet_vertical")
                    : Labels.of(context, "Repost", "button_action_retweet"));
            menu.getMenu().add(0, 2, 1, Labels.of(context, "Quote", "quote_tweet"));
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1 -> {
                        boolean reposting = !state.active;
                        if (runAction(card, reposting ? PostAction.REPOST : PostAction.UNREPOST)) {
                            state.toggle();
                            paint.run();
                            confirm(anchor);
                            rememberAction(card, shownEngagement(card).withReposted(state.active, state.count));
                            refreshGridCard(card.postId, anchor);
                        } else {
                            openInX(context, card.intentLink("retweet", "tweet_id"));
                        }
                    }
                    case 2 -> openInX(context, card.quoteLink());
                    default -> {
                        return false;
                    }
                }
                return true;
            });
            menu.show();
        }

        private static final int LIKE_COLOR = 0xFFF91880;
        private static final int REPOST_COLOR = 0xFF00BA7C;

        private static View actions(Context context, PostCard card, int muted) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = matchWidth();
            rowParams.topMargin = Theme.dpToPx(context, 6f);
            row.setLayoutParams(rowParams);
            // What the reader just did wins over a not-yet-updated post from X (see shownEngagement).
            Engagement e = shownEngagement(card);
            int accent = Theme.primaryAccent(context);

            String replyLabel = Labels.of(context, "Reply", "button_action_reply");
            String repostLabel = Labels.of(context, "Repost", "button_action_retweet");
            String undoRepostLabel = Labels.of(context, "Undo repost", "tweets_undo_retweet_vertical");
            String likeLabel = Labels.of(context, "Like", "button_action_like");
            String unlikeLabel = Labels.of(context, "Unlike", "sports_play_undo_like_post_action");
            String bookmarkLabel = Labels.of(context, "Bookmark", "sports_play_bookmark_post_action");
            String removeBookmarkLabel = Labels.of(context, "Remove bookmark", "sports_play_remove_bookmark_post_action");

            View reply = iconButton(context, replyLabel, muted, e.replies, "\u21A9",
                    "ic_vector_reply_stroke", "ic_vector_reply");
            reply.setOnClickListener(view -> openInX(context, card.intentLink("post", "in_reply_to")));

            // Repost offers X's own choices (repost or quote; undo once reposted) instead of
            // reposting publicly on a single tap. Repost and undo run in place, like like/bookmark.
            ToggleState repostState = new ToggleState(e.reposted, e.reposts);
            LinearLayout repost = (LinearLayout) iconButton(context, repostLabel, muted, null, "\u21BB", "ic_vector_retweet_stroke");
            Runnable paintRepost = () -> fillButton(repost, repostState.active ? undoRepostLabel : repostLabel,
                    repostState.active ? REPOST_COLOR : muted, repostState.count, "\u21BB",
                    repostState.active ? "ic_vector_retweet" : "ic_vector_retweet_stroke", "ic_vector_retweet");
            paintRepost.run();
            repost.setOnClickListener(view -> showRepostMenu(view, card, repostState, paintRepost));

            // Like, repost and bookmark run through X's own service in one tap; when X re-emits the
            // post with the new state, the card redraws with it.
            ToggleState likeState = new ToggleState(e.liked, e.likes);
            LinearLayout like = (LinearLayout) iconButton(context, likeLabel, muted, null, "\u2661", "ic_vector_heart_stroke");
            Runnable paintLike = () -> fillButton(like, likeState.active ? unlikeLabel : likeLabel,
                    likeState.active ? LIKE_COLOR : muted, likeState.count,
                    likeState.active ? "\u2665" : "\u2661",
                    likeState.active ? "ic_vector_heart" : "ic_vector_heart_stroke", "ic_vector_heart");
            paintLike.run();
            // One tap likes/unlikes through X's own service and updates the heart at once; X then
            // re-emits the post and the card redraws with the real count. If that is not possible,
            // the post screen's own heart likes and unlikes in one tap (X's like link would ask for
            // confirmation every time and cannot unlike).
            like.setOnClickListener(view -> {
                boolean liking = !likeState.active;
                if (runAction(card, liking ? PostAction.LIKE : PostAction.UNLIKE)) {
                    likeState.toggle();
                    paintLike.run();
                    confirm(view);
                    rememberAction(card, shownEngagement(card).withLiked(likeState.active, likeState.count));
                    refreshGridCard(card.postId, view);
                } else {
                    // Say why the screen changes: the tap was a like, not "open the post".
                    Toast.makeText(context, liking ? "Like it from the post" : "Unlike it from the post",
                            Toast.LENGTH_SHORT).show();
                    openInX(context, card.postLink());
                }
            });

            ToggleState bookmarkState = new ToggleState(e.bookmarked, null);
            LinearLayout bookmark = (LinearLayout) iconButton(context, bookmarkLabel, muted, null, "\u2606", "ic_vector_bookmark_stroke");
            Runnable paintBookmark = () -> fillButton(bookmark, bookmarkState.active ? removeBookmarkLabel : bookmarkLabel,
                    bookmarkState.active ? accent : muted, null,
                    bookmarkState.active ? "\u2605" : "\u2606",
                    bookmarkState.active ? "ic_vector_bookmark" : "ic_vector_bookmark_stroke", "ic_vector_bookmark");
            paintBookmark.run();
            bookmark.setOnClickListener(view -> {
                boolean adding = !bookmarkState.active;
                if (runAction(card, adding ? PostAction.BOOKMARK : PostAction.UNBOOKMARK)) {
                    bookmarkState.toggle();
                    paintBookmark.run();
                    confirm(view);
                    rememberAction(card, shownEngagement(card).withBookmarked(bookmarkState.active));
                    refreshGridCard(card.postId, view);
                    Toast.makeText(context, adding
                            ? Labels.of(context, "Bookmarked", "tweet_added_to_your_bookmarks")
                            : Labels.of(context, "Removed from bookmarks", "tweet_removed_from_your_bookmarks"),
                            Toast.LENGTH_SHORT).show();
                } else {
                    // X has no bookmark link; its own bookmark button is on the post screen.
                    Toast.makeText(context, "Bookmark it from the post", Toast.LENGTH_SHORT).show();
                    openInX(context, card.postLink());
                }
            });
            View shareButton = iconButton(context, Labels.of(context, "Share", "button_action_share"), muted, null, "\u2197",
                    "ic_vector_share_android", "ic_vector_share");
            shareButton.setOnClickListener(view -> sharePost(context, card));

            // Reply, repost and like spread out; bookmark and share sit at the end, as on X. Each
            // of the three sits at the start of an equal share of the row but keeps its own
            // size: when the button itself was stretched over its share, a tap anywhere in that
            // third of the card hit it, and its press highlight was as wide.
            for (View button : new View[]{reply, repost, like}) {
                FrameLayout cell = new FrameLayout(context);
                cell.addView(button, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.START | Gravity.CENTER_VERTICAL));
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            row.addView(bookmark);
            row.addView(shareButton);
            // The first and last icons line up with the edges of the text above; the buttons'
            // own padding reaches into the card's padding.
            rowParams.setMarginStart(-Theme.dpToPx(context, BUTTON_PADDING_DP));
            rowParams.setMarginEnd(-Theme.dpToPx(context, BUTTON_PADDING_DP));
            row.setTag(ACTIONS_TAG);
            return row;
        }

        // Space around a button's icon: with the 18dp icon it makes the 40dp touch target.
        private static final float BUTTON_PADDING_DP = 11f;

        /**
         * A 40dp touch target with X's own icon when the app ships it (else a text glyph), and the
         * count beside it when there is one. It is only as wide as its icon and count, and its
         * press highlight stays inside it. The label and count form the spoken description.
         */
        private static View iconButton(Context context, String label, int color, Long count,
                                       String glyph, String... iconNames) {
            int size = Theme.dpToPx(context, 40f);
            LinearLayout button = new LinearLayout(context);
            button.setOrientation(LinearLayout.HORIZONTAL);
            button.setGravity(Gravity.CENTER);
            button.setMinimumWidth(size);
            button.setMinimumHeight(size);
            button.setClickable(true);
            button.setFocusable(true);
            button.setBackground(pillRipple(context, size / 2f));
            int horizontal = Theme.dpToPx(context, BUTTON_PADDING_DP);
            button.setPadding(horizontal, 0, horizontal, 0);
            fillButton(button, label, color, count, glyph, iconNames);
            return button;
        }

        /**
         * A press highlight shaped like the button (a pill) and bounded by it. The theme's
         * borderless ripple spreads in a circle as wide as the view and over its neighbours.
         */
        private static Drawable pillRipple(Context context, float radius) {
            int color = 0x33808080;
            TypedValue value = new TypedValue();
            try {
                if (context.getTheme().resolveAttribute(android.R.attr.colorControlHighlight, value, true)) {
                    if (value.resourceId != 0) {
                        color = context.getColorStateList(value.resourceId).getDefaultColor();
                    } else if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                            && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                        color = value.data;
                    }
                }
            } catch (RuntimeException ignored) {
            }
            return new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(color), null, rounded(Color.WHITE, radius));
        }

        /** (Re)draws a button's icon and count; also used after a one-tap like or bookmark. */
        private static void fillButton(LinearLayout button, String label, int color, Long count,
                                       String glyph, String... iconNames) {
            Context context = button.getContext();
            button.removeAllViews();
            String countText = formatCount(count);
            button.setContentDescription(countText == null ? label : label + ", " + countText);
            Drawable icon = findIcon(context, iconNames);
            View inner;
            if (icon != null) {
                ImageView image = new ImageView(context);
                image.setImageDrawable(icon);
                image.setImageTintList(ColorStateList.valueOf(color));
                int iconSize = Theme.dpToPx(context, 18f);
                button.addView(image, new LinearLayout.LayoutParams(iconSize, iconSize));
                inner = image;
            } else {
                TextView text = text(context, glyph, 17f, color);
                button.addView(text);
                inner = text;
            }
            inner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (countText != null) {
                TextView counter = text(context, countText, 13f, color);
                counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMarginStart(Theme.dpToPx(context, 5f));
                button.addView(counter, params);
            }
        }

        /** A like or bookmark button's own state between X's updates. */
        private static final class ToggleState {
            boolean active;
            Long count;

            ToggleState(boolean active, Long count) {
                this.active = active;
                this.count = count;
            }

            void toggle() {
                active = !active;
                // A missing count means none yet: liking makes it 1, unliking the last one hides it.
                long next = Math.max(0, (count == null ? 0 : count) + (active ? 1 : -1));
                count = next == 0 ? null : next;
            }
        }

        /** 999, 1.2K, 34K, 1.5M, as X abbreviates counts. */
        static String formatCount(Long count) {
            if (count == null || count <= 0) return null;
            long n = count;
            if (n < 1_000) return Long.toString(n);
            if (n < 10_000) return trimZero(n / 1_000.0) + "K";
            if (n < 1_000_000) return (n / 1_000) + "K";
            if (n < 10_000_000) return trimZero(n / 1_000_000.0) + "M";
            return (n / 1_000_000) + "M";
        }

        private static String trimZero(double value) {
            String text = String.format(java.util.Locale.ROOT, "%.1f", Math.floor(value * 10) / 10);
            return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
        }

        private static Drawable findIcon(Context context, String... names) {
            for (String name : names) {
                int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
                if (id == 0) continue;
                try {
                    Drawable drawable = context.getDrawable(id);
                    if (drawable != null) return drawable.mutate();
                } catch (RuntimeException ignored) {
                }
            }
            return null;
        }

        private static View avatar(Context context, PostCard card, int size) {
            FrameLayout frame = new FrameLayout(context);
            frame.setBackground(rounded(Theme.dividerColor(context), size / 2f));
            clipToRoundRect(frame, size / 2f);
            // The first whole character: a name can start with an emoji, which is two chars.
            String initial = card.name == null || card.name.isEmpty()
                    ? "?" : card.name.substring(0, card.name.offsetByCodePoints(0, 1));
            TextView letter = text(context, initial, 14f, Theme.secondaryText(context));
            letter.setGravity(Gravity.CENTER);
            frame.addView(letter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frame.setContentDescription(card.name == null ? "Profile" : card.name + " profile");
            if (card.avatarUrl != null) {
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                // Fetched with the card's other pictures (see CardRoot); the initial shows meanwhile.
                frame.setTag(new RemotePicture(image, largerAvatar(card.avatarUrl),
                        bitmap -> letter.setVisibility(bitmap == null ? View.VISIBLE : View.GONE)));
            }
            return frame;
        }

        /** Stands in for media X marks sensitive; tapping opens the post with X's own warning. */
        private static View sensitiveTile(Context context, PostCard card) {
            TextView tile = text(context, Labels.of(context, "Sensitive media", "settings_sensitive_media_title")
                    + " \u00B7 " + Labels.of(context, "Tap to view", "view_tweet_text"), 14f, Theme.secondaryText(context));
            tile.setGravity(Gravity.CENTER);
            int vertical = Theme.dpToPx(context, 40f);
            tile.setPadding(Theme.dpToPx(context, 12f), vertical, Theme.dpToPx(context, 12f), vertical);
            tile.setBackgroundColor(Theme.dividerColor(context));
            tile.setClickable(true);
            tile.setForeground(pressRipple(context));
            tile.setOnClickListener(view -> openInX(context, card.postLink()));
            return tile;
        }

        private static View quoteBox(Context context, QuotedPost quote, int ink, int muted,
                                     View.OnClickListener popOut) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable outline = new GradientDrawable();
            float radius = Theme.dpToPx(context, 12f);
            outline.setCornerRadius(radius);
            outline.setStroke(Math.max(1, Theme.dpToPx(context, 1f)), Theme.dividerColor(context));
            box.setBackground(outline);
            clipToRoundRect(box, radius);
            box.setClickable(true);
            box.setForeground(pressRipple(context));
            box.setOnClickListener(popOut != null ? popOut : view -> openInX(context, quote.link()));
            box.setContentDescription("Quoted post" + (quote.name == null ? "" : " by " + quote.name));

            if (!quote.media.isEmpty()) {
                box.addView(mediaBlock(context, quote.media.subList(0, 1), quote, popOut), matchWidth());
            }
            LinearLayout inner = new LinearLayout(context);
            inner.setOrientation(LinearLayout.VERTICAL);
            int pad = Theme.dpToPx(context, 10f);
            inner.setPadding(pad + Theme.dpToPx(context, 2f), pad, pad + Theme.dpToPx(context, 2f), pad);
            String who = joinNonNull(quote.name, quote.handle == null ? null : "@" + quote.handle);
            TextView author = text(context, who == null ? "Quoted post" : who, 13.5f, quote.name == null ? muted : ink);
            bold(author);
            author.setSingleLine(true);
            author.setEllipsize(TextUtils.TruncateAt.END);
            inner.addView(author);
            if (quote.text != null) {
                TextView text = text(context, quote.text, 14f, ink);
                text.setMaxLines(5);
                text.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams params = matchWidth();
                params.topMargin = Theme.dpToPx(context, 4f);
                inner.addView(text, params);
            }
            box.addView(inner);
            return box;
        }

        // Share of the screen height the enlarged card's media may take, so the card fits.
        private static final float FOCAL_MEDIA_SHARE = 0.42f;

        private static int focalMediaHeight(Context context) {
            return Math.round(context.getResources().getDisplayMetrics().heightPixels * FOCAL_MEDIA_SHARE);
        }

        private static View mediaBlock(Context context, List<MediaItem> items, LinkSource source,
                                       View.OnClickListener popOut) {
            return mediaBlock(context, items, source, popOut, 0);
        }

        /**
         * Up to four media tiles, as in the userscript: one row, or two rows of two for four
         * items. Each tile keeps its real shape; tiles in a row share one height.
         * {@code maxHeight}: the whole block's height limit in px, or 0 for none.
         */
        private static View mediaBlock(Context context, List<MediaItem> items, LinkSource source,
                                       View.OnClickListener popOut, int maxHeight) {
            List<MediaItem> shown = items.size() > MAX_MEDIA ? items.subList(0, MAX_MEDIA) : items;
            LinearLayout block = new LinearLayout(context);
            block.setOrientation(LinearLayout.VERTICAL);
            int gap = Theme.dpToPx(context, 2f);
            List<List<Integer>> rows = new ArrayList<>();
            if (shown.size() == 4) {
                rows.add(Arrays.asList(0, 1));
                rows.add(Arrays.asList(2, 3));
            } else {
                List<Integer> single = new ArrayList<>();
                for (int i = 0; i < shown.size(); i++) single.add(i);
                rows.add(single);
            }
            int rowMaxHeight = maxHeight > 0
                    ? Math.max(1, (maxHeight - gap * (rows.size() - 1)) / rows.size()) : 0;
            for (int r = 0; r < rows.size(); r++) {
                MediaRow row = new MediaRow(context, gap);
                row.setMaxHeight(rowMaxHeight);
                for (int index : rows.get(r)) {
                    MediaItem item = shown.get(index);
                    // X's photo viewer counts media positions from 1; videos open the player.
                    String path = item.video ? "video/1" : "photo/" + (index + 1);
                    // On the timeline media pops the card out; in the focus view it opens X's viewer.
                    View.OnClickListener open = view -> openInX(context, source.mediaLink(path));
                    if (popOut == null && item.videoUrl != null) {
                        // Focus view: the video plays right here; X's player stays one tap away.
                        row.addVideoTile(context, item, open);
                    } else {
                        row.addTile(context, item, popOut != null ? popOut : open);
                    }
                }
                LinearLayout.LayoutParams params = matchWidth();
                if (r > 0) params.topMargin = gap;
                block.addView(row, params);
            }
            return block;
        }

        private static TextView text(Context context, String value, float sp, int color) {
            TextView view = new TextView(context);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(color);
            view.setIncludeFontPadding(false);
            // Piko's custom or system font, when set; the plain default otherwise.
            view.setTypeface(UpdateFont.customTypefaceOr(Typeface.DEFAULT));
            return view;
        }

        private static void bold(TextView view) {
            view.setTypeface(UpdateFont.customTypefaceOr(Typeface.DEFAULT_BOLD));
        }

        private static GradientDrawable rounded(int color, float radius) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(color);
            drawable.setCornerRadius(radius);
            return drawable;
        }

        private static void clipToRoundRect(View view, float radius) {
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            view.setClipToOutline(true);
        }

        private static LinearLayout.LayoutParams matchWidth() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private static String joinNonNull(String a, String b) {
            if (a == null) return b;
            if (b == null) return a;
            return a + " \u00B7 " + b;
        }
    }

    /**
     * One row of media tiles. The row height is chosen so every tile shows its whole image at its
     * own aspect ratio and the tiles exactly fill the width.
     */
    private static final class MediaRow extends ViewGroup {
        // Real shapes (width / height) of pictures loaded this session, by picture address.
        private static final android.util.LruCache<String, Float> SEEN_ASPECTS = new android.util.LruCache<>(4000);

        private final int gap;
        private final List<Float> aspects = new ArrayList<>();
        // 0 for none; when the natural height is taller, tiles shrink and the row is centered.
        private int maxHeight;
        private int leftOffset;

        MediaRow(Context context, int gap) {
            super(context);
            this.gap = gap;
        }

        void setMaxHeight(int maxHeight) {
            this.maxHeight = Math.max(0, maxHeight);
        }

        /** The shape a tile for this picture gets: its real one if it was seen, else the model's. */
        static float knownAspect(MediaItem item) {
            Float seen = SEEN_ASPECTS.get(item.url);
            float aspect = seen != null ? seen : item.aspect;
            return shownAspect(aspect > 0 ? aspect : UNKNOWN_ASPECT);
        }

        void addTile(Context context, MediaItem item, View.OnClickListener click) {
            int index = getChildCount();
            // A picture seen before has its real shape on record, so its tile is the right size
            // from the start and nothing moves when the picture comes (back) in.
            Float seen = SEEN_ASPECTS.get(item.url);
            float aspect = seen != null ? seen : item.aspect;
            aspects.add(shownAspect(aspect > 0 ? aspect : UNKNOWN_ASPECT));

            FrameLayout tile = new FrameLayout(context);
            tile.setBackgroundColor(Theme.dividerColor(context));
            tile.setClickable(true);
            tile.setForeground(CardViews.pressRipple(context));
            tile.setOnClickListener(click);
            tile.setContentDescription(item.video ? "Video" : "Photo " + (index + 1));
            ImageView image = new ImageView(context);
            image.setScaleType(scaleFor(aspect));
            tile.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (item.video) {
                TextView play = new TextView(context);
                play.setText("\u25B6");
                play.setTextSize(18f);
                play.setTextColor(Color.WHITE);
                play.setGravity(Gravity.CENTER);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(0x8C000000);
                play.setBackground(circle);
                int size = Theme.dpToPx(context, 48f);
                tile.addView(play, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            }
            addView(tile);

            // Fetched with the card's other pictures (see CardRoot). Until then, and if it cannot
            // be fetched, the grey tile stays; tapping still opens X's own viewer.
            String url = item.url;
            tile.setTag(new RemotePicture(image, mediumUrl(url), bitmap -> {
                if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
                // The real shape arrives with the image; the model's size is only a hint.
                float real = bitmap.getWidth() / (float) bitmap.getHeight();
                SEEN_ASPECTS.put(url, real);
                image.setScaleType(scaleFor(real));
                float shown = shownAspect(real);
                if (Math.abs(shown - aspects.get(index)) > 0.01f) {
                    aspects.set(index, shown);
                    requestLayout();
                }
            }));
        }

        void addVideoTile(Context context, MediaItem item, View.OnClickListener openInPlayer) {
            int index = getChildCount();
            aspects.add(shownAspect(item.aspect > 0 ? item.aspect : UNKNOWN_ASPECT));
            VideoTile tile = new VideoTile(context, item, openInPlayer, (width, height) -> {
                float shown = shownAspect(width / (float) height);
                if (Math.abs(shown - aspects.get(index)) > 0.01f) {
                    aspects.set(index, shown);
                    requestLayout();
                }
            });
            addView(tile);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int count = getChildCount();
            if (count == 0) {
                setMeasuredDimension(width, 0);
                return;
            }
            float sum = 0f;
            for (float aspect : aspects) sum += aspect;
            int available = Math.max(1, width - gap * (count - 1));
            int height = Math.max(1, Math.round(available / sum));
            boolean capped = maxHeight > 0 && height > maxHeight;
            if (capped) height = maxHeight;
            int used = 0;
            for (int i = 0; i < count; i++) {
                // Uncapped, the last tile takes the rounding remainder so the row fills the width.
                int tileWidth = !capped && i == count - 1
                        ? available - used : Math.round(aspects.get(i) * height);
                tileWidth = Math.max(1, Math.min(tileWidth, available - used));
                used += tileWidth;
                getChildAt(i).measure(
                        MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            leftOffset = capped ? Math.max(0, (available - used) / 2) : 0;
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int left = leftOffset;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.layout(left, 0, left + child.getMeasuredWidth(), child.getMeasuredHeight());
                left += child.getMeasuredWidth() + gap;
            }
        }

        private static float shownAspect(float aspect) {
            return Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, aspect));
        }

        // Within the limits the tile has the image's own shape, so a crop removes nothing; past
        // them the whole image is fitted inside the tile instead.
        private static ImageView.ScaleType scaleFor(float aspect) {
            if (aspect <= 0) return ImageView.ScaleType.CENTER_CROP;
            boolean clamped = Math.abs(shownAspect(aspect) - aspect) > 0.01f;
            return clamped ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP;
        }

        private static String mediumUrl(String url) {
            try {
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                if (host == null || !(host.equals("twimg.com") || host.endsWith(".twimg.com"))) return url;
                return uri.buildUpon().clearQuery()
                        .appendQueryParameter("format", "jpg")
                        .appendQueryParameter("name", "medium")
                        .build().toString();
            } catch (RuntimeException ignored) {
                return url;
            }
        }
    }

    // ---------- Pictures that come and go with their card ----------

    /**
     * A picture from the network in an ImageView (a media tile or a profile picture). It is not
     * fetched when the view is built: its card asks for it when it nears the screen and lets it
     * go when it is far away again (see {@link CardRoot} and
     * {@link MasonryGrid#updateWindow}). Main thread only.
     */
    private static final class RemotePicture {
        interface Listener {
            /** The picture is in the view, or ({@code null}) was let go. */
            void onPicture(Bitmap bitmap);
        }

        private static final long FADE_IN_MS = 160L;
        // After a fetch gave up on a network error, how long until scrolling by tries it again.
        private static final long RETRY_AFTER_MS = 10_000L;

        private final ImageView image;
        private final String url;
        private final Listener listener;
        // The fetch this picture is waiting on, if any.
        private CardImageLoader.Callback loading;
        private boolean wanted;
        private long retryAfter;

        RemotePicture(ImageView image, String url, Listener listener) {
            this.image = image;
            this.url = url;
            this.listener = listener;
        }

        /** Safe to call again and again while the card is near the screen. */
        void show() {
            wanted = true;
            if (loading != null || image.getDrawable() != null) return;
            if (android.os.SystemClock.uptimeMillis() < retryAfter) return;
            // True while load() is still running: the picture was in memory and is simply set.
            // One that arrives later fades in rather than popping onto the card.
            boolean[] immediate = {true};
            CardImageLoader.Callback callback = new CardImageLoader.Callback() {
                @Override
                public void onLoaded(Bitmap bitmap) {
                    loading = null;
                    if (!wanted || bitmap.isRecycled()) return;
                    image.animate().cancel();
                    image.setImageBitmap(bitmap);
                    if (immediate[0]) {
                        image.setAlpha(1f);
                    } else {
                        image.setAlpha(0f);
                        image.animate().alpha(1f).setDuration(FADE_IN_MS).start();
                    }
                    if (listener != null) listener.onPicture(bitmap);
                }

                @Override
                public void onFailed(boolean transientError) {
                    // The placeholder stays. A network error is tried again when the reader is
                    // still (or again) near the card a little later; a picture that is gone is not.
                    loading = null;
                    retryAfter = transientError
                            ? android.os.SystemClock.uptimeMillis() + RETRY_AFTER_MS : Long.MAX_VALUE;
                }
            };
            loading = callback;
            CardImageLoader.load(url, callback);
            immediate[0] = false;
        }

        void release() {
            wanted = false;
            if (loading != null) {
                CardImageLoader.cancel(url, loading);
                loading = null;
            }
            if (image.getDrawable() == null) return;
            image.animate().cancel();
            image.setImageDrawable(null);
            if (listener != null) listener.onPicture(null);
        }
    }

    /**
     * A card's root view. It knows the card's pictures, so the grid can tell a card far from the
     * screen to let them go and a card coming near to fetch them.
     */
    private static final class CardRoot extends LinearLayout {
        private final List<RemotePicture> pictures = new ArrayList<>();
        private boolean shown;

        CardRoot(Context context) {
            super(context);
        }

        /** Gathers the pictures of every tile and profile picture under {@code view}. */
        void collectPictures(View view) {
            if (view.getTag() instanceof RemotePicture picture) pictures.add(picture);
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) collectPictures(group.getChildAt(i));
            }
        }

        /**
         * Called on every scroll step for cards near the screen, so showing must stay cheap: a
         * picture that is in place or on its way returns at once, and one that failed is only
         * tried again after a pause (see RemotePicture.show).
         */
        void showPictures(boolean show) {
            if (!show && !shown) return;
            shown = show;
            for (RemotePicture picture : pictures) {
                if (show) picture.show();
                else picture.release();
            }
        }
    }

    // ---------- Inline video (focus view) ----------

    private interface VideoSizeListener {
        void onSize(int width, int height);
    }

    /**
     * Plays a post's mp4 inside the enlarged card: muted autoplay like X, tap to pause or resume,
     * double-tap the left or right half to rewind or jump, a draggable scrubber with elapsed and
     * total time, a sound toggle, and a button for X's own full-screen player. GIFs loop silently
     * with no controls. The preview frame shows until the first video frame renders. Released when
     * the card closes.
     */
    private static final class VideoTile extends FrameLayout implements android.view.TextureView.SurfaceTextureListener {
        private static final int SEEK_STEP_MS = 10_000;

        private final MediaItem item;
        private final VideoSizeListener sizeListener;
        private final android.view.TextureView texture;
        private final ImageView poster;
        private final TextView playBadge;
        private final TextView seekFeedback;
        private final android.view.GestureDetector gestures;
        private TextView sound;
        private android.widget.SeekBar scrubber;
        private TextView elapsedLabel;
        private TextView totalLabel;
        private android.media.MediaPlayer player;
        private android.view.Surface surface;
        private boolean muted = true;
        private boolean prepared;
        private boolean tracking;
        // Paused because the app left the screen, to resume on return (not a pause by the reader).
        private boolean pausedByWindow;
        private android.media.AudioFocusRequest focusRequest;
        private int videoWidth;
        private int videoHeight;

        // Advances the scrubber and elapsed time ~twice a second while attached.
        private final Runnable progressTick = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                if (isAttachedToWindow()) postDelayed(this, 500);
            }
        };

        VideoTile(Context context, MediaItem item, View.OnClickListener openInPlayer, VideoSizeListener sizeListener) {
            super(context);
            this.item = item;
            this.sizeListener = sizeListener;
            setBackgroundColor(Color.BLACK);
            setContentDescription(item.gif ? "GIF" : "Video, tap to pause, double-tap the sides to seek");

            texture = new android.view.TextureView(context);
            texture.setSurfaceTextureListener(this);
            addView(texture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            poster = new ImageView(context);
            poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(poster, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            CardImageLoader.load(MediaRow.mediumUrl(item.url), new CardImageLoader.Callback() {
                @Override
                public void onLoaded(Bitmap bitmap) {
                    if (!bitmap.isRecycled()) poster.setImageBitmap(bitmap);
                }

                @Override
                public void onFailed(boolean transientError) {
                }
            });

            int inset = Theme.dpToPx(context, 8f);

            // The scrubber row, sound included, sits on a dark gradient at the bottom. GIFs are
            // short silent loops, so they get no controls.
            if (!item.gif) {
                LinearLayout bar = new LinearLayout(context);
                bar.setOrientation(LinearLayout.HORIZONTAL);
                bar.setGravity(Gravity.CENTER_VERTICAL);
                bar.setPadding(inset, inset / 2, inset / 2, inset / 2);
                bar.setBackground(new GradientDrawable(
                        GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));

                elapsedLabel = timeLabel(context, "0:00");
                totalLabel = timeLabel(context, "0:00");

                scrubber = new android.widget.SeekBar(context);
                int accent = Theme.primaryAccent(context);
                scrubber.setProgressTintList(ColorStateList.valueOf(accent));
                scrubber.setThumbTintList(ColorStateList.valueOf(accent));
                scrubber.setContentDescription("Video position");
                scrubber.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                        if (fromUser) elapsedLabel.setText(formatTime(progress));
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar sb) {
                        tracking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar sb) {
                        tracking = false;
                        android.media.MediaPlayer mediaPlayer = player;
                        if (mediaPlayer != null && prepared) {
                            try {
                                mediaPlayer.seekTo(sb.getProgress());
                            } catch (IllegalStateException ignored) {
                            }
                        }
                    }
                });

                int gap = Theme.dpToPx(context, 6f);
                LinearLayout.LayoutParams elapsedParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                LinearLayout.LayoutParams scrubberParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                scrubberParams.setMarginStart(gap);
                scrubberParams.setMarginEnd(gap);
                bar.addView(elapsedLabel, elapsedParams);
                bar.addView(scrubber, scrubberParams);
                bar.addView(totalLabel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                int soundSize = Theme.dpToPx(context, 32f);
                sound = overlayButton(context, "\uD83D\uDD07", 32f);
                sound.setContentDescription("Turn sound on");
                sound.setOnClickListener(view -> toggleSound());
                LinearLayout.LayoutParams soundParams = new LinearLayout.LayoutParams(soundSize, soundSize);
                soundParams.setMarginStart(gap);
                bar.addView(sound, soundParams);

                addView(bar, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            }

            // Brief "-10s" / "+10s" flash on a double-tap seek.
            seekFeedback = new TextView(context);
            seekFeedback.setTextColor(Color.WHITE);
            seekFeedback.setTextSize(15f);
            seekFeedback.setGravity(Gravity.CENTER);
            int feedbackPadH = Theme.dpToPx(context, 14f);
            int feedbackPadV = Theme.dpToPx(context, 8f);
            seekFeedback.setPadding(feedbackPadH, feedbackPadV, feedbackPadH, feedbackPadV);
            GradientDrawable feedbackBackground = new GradientDrawable();
            feedbackBackground.setColor(0x8C000000);
            feedbackBackground.setCornerRadius(Theme.dpToPx(context, 20f));
            seekFeedback.setBackground(feedbackBackground);
            seekFeedback.setVisibility(View.GONE);
            seekFeedback.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams feedbackParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            feedbackParams.setMargins(Theme.dpToPx(context, 32f), 0, Theme.dpToPx(context, 32f), 0);
            addView(seekFeedback, feedbackParams);

            playBadge = overlayButton(context, "\u25B6", 48f);
            playBadge.setVisibility(View.GONE);
            playBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(playBadge, new FrameLayout.LayoutParams(
                    Theme.dpToPx(context, 48f), Theme.dpToPx(context, 48f), Gravity.CENTER));

            int small = Theme.dpToPx(context, 36f);
            TextView expand = overlayButton(context, "\u2922", 36f);
            expand.setContentDescription("Open in X's player");
            expand.setOnClickListener(openInPlayer);
            FrameLayout.LayoutParams expandParams = new FrameLayout.LayoutParams(small, small, Gravity.TOP | Gravity.END);
            expandParams.setMargins(inset, inset, inset, inset);
            addView(expand, expandParams);

            gestures = new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent event) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent event) {
                    togglePlay();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent event) {
                    if (!item.gif && prepared) {
                        seekBy(event.getX() < getWidth() / 2f ? -SEEK_STEP_MS : SEEK_STEP_MS);
                    }
                    return true;
                }
            });
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            gestures.onTouchEvent(event);
            return true;
        }

        private static TextView overlayButton(Context context, String glyph, float sizeDp) {
            TextView button = new TextView(context);
            button.setText(glyph);
            button.setTextSize(sizeDp * 0.4f);
            button.setTextColor(Color.WHITE);
            button.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0x8C000000);
            button.setBackground(circle);
            return button;
        }

        private static TextView timeLabel(Context context, String text) {
            TextView label = new TextView(context);
            label.setText(text);
            label.setTextSize(11f);
            label.setTextColor(Color.WHITE);
            label.setIncludeFontPadding(false);
            return label;
        }

        private static String formatTime(int milliseconds) {
            int totalSeconds = Math.max(0, milliseconds) / 1000;
            int seconds = totalSeconds % 60;
            int minutes = (totalSeconds / 60) % 60;
            int hours = totalSeconds / 3600;
            if (hours > 0) {
                return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
            }
            return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
        }

        @Override
        public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture, int width, int height) {
            surface = new android.view.Surface(texture);
            start();
        }

        @Override
        public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture texture, int width, int height) {
            fitVideo();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture texture) {
            release();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture) {
        }

        private void start() {
            release();
            prepared = false;
            try {
                android.media.MediaPlayer mediaPlayer = new android.media.MediaPlayer();
                player = mediaPlayer;
                mediaPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                mediaPlayer.setDataSource(item.videoUrl);
                mediaPlayer.setSurface(surface);
                mediaPlayer.setLooping(item.gif);
                // Muted at first; a player started again keeps the reader's sound choice.
                mediaPlayer.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
                mediaPlayer.setOnPreparedListener(mp -> {
                    if (player != mp) return;
                    prepared = true;
                    int duration = Math.max(0, mp.getDuration());
                    if (scrubber != null) {
                        scrubber.setMax(duration);
                        scrubber.setEnabled(duration > 0);
                    }
                    if (totalLabel != null) totalLabel.setText(formatTime(duration));
                    if (getWindowVisibility() == View.VISIBLE) {
                        mp.start();
                        setPlaying(true);
                    } else {
                        // The app left the screen while the video was getting ready.
                        pausedByWindow = true;
                    }
                    removeCallbacks(progressTick);
                    post(progressTick);
                });
                mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) poster.setVisibility(View.GONE);
                    return false;
                });
                mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
                    if (width <= 0 || height <= 0) return;
                    videoWidth = width;
                    videoHeight = height;
                    sizeListener.onSize(width, height);
                    fitVideo();
                });
                mediaPlayer.setOnCompletionListener(mp -> setPlaying(false));
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    log("inline video failed what=" + what + " extra=" + extra);
                    poster.setVisibility(View.VISIBLE);
                    setPlaying(false);
                    return true;
                });
                mediaPlayer.prepareAsync();
            } catch (java.io.IOException | RuntimeException e) {
                NewXLogger.printException(() -> LOG_PREFIX + "inline video could not start", e);
                playBadge.setVisibility(View.VISIBLE);
            }
        }

        private void togglePlay() {
            android.media.MediaPlayer mediaPlayer = player;
            if (mediaPlayer == null) {
                if (surface != null) start();
                return;
            }
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    setPlaying(false);
                } else {
                    mediaPlayer.start();
                    setPlaying(true);
                }
            } catch (IllegalStateException e) {
                // Still preparing; it starts on its own when ready.
            }
        }

        /** The play badge shows while the video is stopped; the screen stays on while it plays. */
        private void setPlaying(boolean playing) {
            playBadge.setVisibility(playing ? View.GONE : View.VISIBLE);
            // A GIF loops for as long as the card is open; it must not keep the screen awake.
            setKeepScreenOn(playing && !item.gif);
        }

        /**
         * The app left the screen (Home, another app, the screen turned off) with the card still
         * open: the player would carry on behind it, sound included. Pause, and pick up where
         * it was on return.
         */
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            android.media.MediaPlayer mediaPlayer = player;
            if (mediaPlayer == null || !prepared) return;
            try {
                if (visibility != View.VISIBLE) {
                    if (!mediaPlayer.isPlaying()) return;
                    mediaPlayer.pause();
                    pausedByWindow = true;
                    setKeepScreenOn(false);
                    abandonAudioFocus();
                } else if (pausedByWindow) {
                    pausedByWindow = false;
                    mediaPlayer.start();
                    setPlaying(true);
                    if (!muted) requestAudioFocus();
                }
            } catch (IllegalStateException ignored) {
            }
        }

        /**
         * With its sound on, the video asks other apps to pause theirs, as any player does (it
         * used to play over music). If another app takes the sound back, the video mutes.
         */
        private void requestAudioFocus() {
            try {
                android.media.AudioManager audio =
                        (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                if (audio == null) return;
                if (focusRequest == null) {
                    focusRequest = new android.media.AudioFocusRequest.Builder(
                            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build())
                            .setOnAudioFocusChangeListener(change -> {
                                // Every loss is negative (AUDIOFOCUS_LOSS and its transient kinds).
                                if (change < 0 && !muted) toggleSound();
                            })
                            .build();
                }
                audio.requestAudioFocus(focusRequest);
            } catch (RuntimeException e) {
                NewXLogger.printException(() -> LOG_PREFIX + "audio focus request failed", e);
            }
        }

        private void abandonAudioFocus() {
            if (focusRequest == null) return;
            try {
                android.media.AudioManager audio =
                        (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                if (audio != null) audio.abandonAudioFocusRequest(focusRequest);
            } catch (RuntimeException ignored) {
            }
        }

        private void seekBy(int deltaMilliseconds) {
            android.media.MediaPlayer mediaPlayer = player;
            if (mediaPlayer == null || !prepared) return;
            try {
                int duration = mediaPlayer.getDuration();
                int limit = duration > 0 ? duration : Integer.MAX_VALUE;
                int target = Math.max(0, Math.min(limit, mediaPlayer.getCurrentPosition() + deltaMilliseconds));
                mediaPlayer.seekTo(target);
                if (scrubber != null) scrubber.setProgress(target);
                if (elapsedLabel != null) elapsedLabel.setText(formatTime(target));
                boolean forward = deltaMilliseconds > 0;
                showSeekFeedback((forward ? "+" : "\u2212") + (Math.abs(deltaMilliseconds) / 1000) + "s", forward);
            } catch (IllegalStateException ignored) {
            }
        }

        private void showSeekFeedback(String text, boolean forward) {
            seekFeedback.setText(text);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) seekFeedback.getLayoutParams();
            params.gravity = Gravity.CENTER_VERTICAL | (forward ? Gravity.END : Gravity.START);
            seekFeedback.setLayoutParams(params);
            seekFeedback.animate().cancel();
            seekFeedback.setAlpha(1f);
            seekFeedback.setVisibility(View.VISIBLE);
            seekFeedback.animate().alpha(0f).setStartDelay(450).setDuration(250)
                    .withEndAction(() -> seekFeedback.setVisibility(View.GONE)).start();
        }

        private void updateProgress() {
            android.media.MediaPlayer mediaPlayer = player;
            if (mediaPlayer == null || !prepared || tracking || scrubber == null) return;
            try {
                int position = mediaPlayer.getCurrentPosition();
                scrubber.setProgress(position);
                if (elapsedLabel != null) elapsedLabel.setText(formatTime(position));
            } catch (IllegalStateException ignored) {
            }
        }

        private void toggleSound() {
            muted = !muted;
            if (sound != null) {
                sound.setText(muted ? "\uD83D\uDD07" : "\uD83D\uDD0A");
                sound.setContentDescription(muted ? "Turn sound on" : "Mute");
            }
            if (player != null) {
                try {
                    player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
                } catch (IllegalStateException ignored) {
                }
            }
            if (muted) abandonAudioFocus();
            else requestAudioFocus();
        }

        /** Shows the whole frame (letterboxed) when the tile's shape was clamped. */
        private void fitVideo() {
            int viewWidth = texture.getWidth();
            int viewHeight = texture.getHeight();
            if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) return;
            float scale = Math.min(viewWidth / (float) videoWidth, viewHeight / (float) videoHeight);
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setScale(videoWidth * scale / viewWidth, videoHeight * scale / viewHeight,
                    viewWidth / 2f, viewHeight / 2f);
            texture.setTransform(matrix);
        }

        private void release() {
            removeCallbacks(progressTick);
            prepared = false;
            pausedByWindow = false;
            setKeepScreenOn(false);
            android.media.MediaPlayer mediaPlayer = player;
            player = null;
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.release();
                } catch (RuntimeException ignored) {
                }
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            release();
            abandonAudioFocus();
            if (surface != null) {
                surface.release();
                surface = null;
            }
            super.onDetachedFromWindow();
        }
    }

    // ---------- Sharp image loading ----------

    /**
     * Downloads card images at the requested size. The gallery's MediaThumbnailLoader is not used
     * here: it accepts any copy the app has cached under the same media key, which is often X's
     * low-resolution preview, so card media looked blurred until the post had been opened.
     */
    private static final class CardImageLoader {
        interface Callback {
            void onLoaded(Bitmap bitmap);

            void onFailed(boolean transientError);
        }

        private static final int MAX_BYTES = 12 * 1024 * 1024;
        // Sharp at card size (cards are at most ~1/2 of a tablet screen wide) while bounded: every
        // card keeps its image while the feed is open, so full-size originals would exhaust memory.
        private static final int MAX_DIMENSION = 1000;
        private static final java.util.concurrent.ExecutorService EXECUTOR =
                java.util.concurrent.Executors.newFixedThreadPool(3);
        private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
        private static final android.util.LruCache<String, Bitmap> CACHE =
                new android.util.LruCache<>((int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 12)) {
                    @Override
                    protected int sizeOf(String key, Bitmap value) {
                        return Math.max(1, value.getByteCount() / 1024);
                    }
                };

        private CardImageLoader() {
        }

        // A network error (not a missing picture) is tried again this many times, a little later
        // each time, before the views waiting on it are told it failed.
        private static final int MAX_RETRIES = 2;
        private static final long RETRY_DELAY_MS = 1_500L;

        /**
         * One fetch of one address. Every view that wants the same picture (an author's profile
         * picture on several cards) waits on the same fetch. Main thread only, except
         * {@code abandoned}, which the worker reads.
         */
        private static final class Request {
            final String url;
            final List<Callback> waiting = new ArrayList<>();
            volatile boolean abandoned;

            Request(String url) {
                this.url = url;
            }
        }

        private static final java.util.Map<String, Request> IN_FLIGHT = new java.util.HashMap<>();

        /**
         * Main thread. A picture in memory is handed over before this returns; otherwise the
         * callback is called later, on the main thread.
         */
        static void load(String url, Callback callback) {
            if (!NewXUtils.isHttpUrl(url)) {
                MAIN.post(() -> callback.onFailed(false));
                return;
            }
            Bitmap cached = CACHE.get(url);
            if (cached != null && !cached.isRecycled()) {
                callback.onLoaded(cached);
                return;
            }
            Request request = IN_FLIGHT.get(url);
            if (request == null) {
                request = new Request(url);
                IN_FLIGHT.put(url, request);
                request.waiting.add(callback);
                fetch(request, 0);
            } else {
                request.waiting.add(callback);
            }
        }

        /**
         * Main thread. Stops waiting for a picture (its card went far off screen). A fetch nobody
         * waits for any more is dropped if it has not started, so pictures the reader scrolled
         * past do not hold up the ones on screen.
         */
        static void cancel(String url, Callback callback) {
            Request request = IN_FLIGHT.get(url);
            if (request == null) return;
            request.waiting.remove(callback);
            if (request.waiting.isEmpty()) {
                request.abandoned = true;
                IN_FLIGHT.remove(url);
            }
        }

        private static void fetch(Request request, int attempt) {
            EXECUTOR.execute(() -> {
                if (request.abandoned) return;
                String url = request.url;
                // An earlier fetch of the same picture, given up on while it ran, may have
                // finished since this one was queued.
                Bitmap ready = CACHE.get(url);
                if (ready != null && !ready.isRecycled()) {
                    MAIN.post(() -> deliver(request, attempt, ready, false));
                    return;
                }
                Bitmap bitmap = null;
                boolean transientError = true;
                java.net.HttpURLConnection connection = null;
                try {
                    connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    connection.setConnectTimeout(15_000);
                    connection.setReadTimeout(20_000);
                    int status = connection.getResponseCode();
                    transientError = status >= 500 || status == 429;
                    if (status == 200) {
                        byte[] data = readAll(connection.getInputStream());
                        if (data != null) bitmap = decode(data);
                    }
                } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                    transientError = !(e instanceof OutOfMemoryError);
                } finally {
                    if (connection != null) connection.disconnect();
                }
                Bitmap result = bitmap;
                boolean retryable = transientError;
                if (result != null) CACHE.put(url, result);
                MAIN.post(() -> deliver(request, attempt, result, retryable));
            });
        }

        private static void deliver(Request request, int attempt, Bitmap result, boolean retryable) {
            if (request.abandoned) return;
            if (result == null && retryable && attempt < MAX_RETRIES) {
                MAIN.postDelayed(() -> {
                    if (!request.abandoned) fetch(request, attempt + 1);
                }, RETRY_DELAY_MS * (attempt + 1));
                return;
            }
            IN_FLIGHT.remove(request.url);
            // A copy: a callback may ask for another picture, which touches these lists.
            for (Callback callback : new ArrayList<>(request.waiting)) {
                if (result != null) callback.onLoaded(result);
                else callback.onFailed(retryable);
            }
        }

        private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
            try (java.io.InputStream in = input) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) return null;
                }
                return out.toByteArray();
            }
        }

        private static Bitmap decode(byte[] data) {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) return null;
            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest <= MAX_DIMENSION) return bitmap;
            float scale = MAX_DIMENSION / (float) longest;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (scaled != bitmap) bitmap.recycle();
            return scaled;
        }
    }

    /** X serves profile pictures in sizes; "_normal" (48px) looks soft at card size. */
    private static String largerAvatar(String url) {
        return url == null ? null : url.replace("_normal.", "_200x200.");
    }

    // ---------- Reading posts out of timeline items ----------

    private static String profileLink(String handle) {
        return handle == null ? null : "https://x.com/" + handle;
    }

    /**
     * Counts and the viewer's own state, from the post's inline action entries, the same list
     * X's action bar is built from:
     * {@code inlineActionEntry=[InlineActionEntry(actionType=Favorite, count=12, isEnabled=true), ...]}.
     * The entry types say the state: Unfavorite means already liked, UndoRetweet already reposted,
     * RemoveFromBookmarks already bookmarked.
     */
    private static final class Engagement {
        static final Engagement NONE = new Engagement(null, null, null, null, false, false, false);

        final Long replies;
        final Long reposts;
        final Long likes;
        final Long views;
        final boolean liked;
        final boolean reposted;
        final boolean bookmarked;

        private Engagement(Long replies, Long reposts, Long likes, Long views,
                           boolean liked, boolean reposted, boolean bookmarked) {
            this.replies = replies;
            this.reposts = reposts;
            this.likes = likes;
            this.views = views;
            this.liked = liked;
            this.reposted = reposted;
            this.bookmarked = bookmarked;
        }

        // Logged once per process, when Piko logging is on, so the real timeline shape is visible.
        private static volatile boolean loggedSample;

        static Engagement of(String post) {
            String entries = ToStringParser.fieldValue(post, "inlineActionEntry");
            Engagement result = parse(entries);
            if (!loggedSample && NewXLogger.isLoggingEnabled()) {
                loggedSample = true;
                String sample = entries == null ? "(no inlineActionEntry field)"
                        : entries.length() > 500 ? entries.substring(0, 500) + "\u2026" : entries;
                log("engagement sample inlineActionEntry=" + sample);
                log("engagement parsed replies=" + result.replies + " reposts=" + result.reposts
                        + " likes=" + result.likes + " views=" + result.views + " liked=" + result.liked
                        + " reposted=" + result.reposted + " bookmarked=" + result.bookmarked);
            }
            return result;
        }

        private static Engagement parse(String entries) {
            if (entries == null) return NONE;
            Long replies = null, reposts = null, likes = null, views = null;
            boolean liked = false, reposted = false, bookmarked = false;
            int offset = 0;
            while (true) {
                int start = entries.indexOf("InlineActionEntry(", offset);
                if (start < 0) break;
                // Balanced parens, so a field that itself prints "(...)" cannot cut the entry short.
                int close = balancedClose(entries, entries.indexOf('(', start));
                if (close < 0) break;
                String entry = entries.substring(start, close + 1);
                offset = close + 1;
                String type = ToStringParser.fieldValue(entry, "actionType");
                Long count = parseLong(ToStringParser.fieldValue(entry, "count"));
                if (type == null) continue;
                switch (type) {
                    case "Reply" -> replies = count;
                    case "Retweet" -> reposts = count;
                    case "UndoRetweet" -> { reposts = count; reposted = true; }
                    case "Favorite" -> likes = count;
                    case "Unfavorite" -> { likes = count; liked = true; }
                    case "RemoveFromBookmarks" -> bookmarked = true;
                    case "ViewCount" -> views = count;
                    default -> { }
                }
            }
            return new Engagement(replies, reposts, likes, views, liked, reposted, bookmarked);
        }

        private static int balancedClose(String text, int open) {
            if (open < 0) return -1;
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) return i;
            }
            return -1;
        }

        boolean sameAs(Engagement other) {
            return liked == other.liked && reposted == other.reposted && bookmarked == other.bookmarked
                    && Objects.equals(replies, other.replies) && Objects.equals(reposts, other.reposts)
                    && Objects.equals(likes, other.likes) && Objects.equals(views, other.views);
        }

        Engagement withLiked(boolean on, Long count) {
            return new Engagement(replies, reposts, count, views, on, reposted, bookmarked);
        }

        Engagement withReposted(boolean on, Long count) {
            return new Engagement(replies, count, likes, views, liked, on, bookmarked);
        }

        Engagement withBookmarked(boolean on) {
            return new Engagement(replies, reposts, likes, views, liked, reposted, on);
        }

        private static Long parseLong(String value) {
            if (value == null) return null;
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Anything with a post address that media tiles can link into. */
    private interface LinkSource {
        String mediaLink(String mediaPath);
    }

    private static final class MediaItem {
        final String url;          // still image: the photo, or a video's preview frame
        final boolean video;
        final boolean gif;
        final String videoUrl;     // playable mp4 for videos and GIFs, or null
        final float aspect;        // width / height from the model, or 0 when unknown

        MediaItem(String url, boolean video, boolean gif, String videoUrl, float aspect) {
            this.url = url;
            this.video = video;
            this.gif = gif;
            this.videoUrl = videoUrl;
            this.aspect = aspect;
        }
    }

    private static final class QuotedPost implements LinkSource {
        final String postId;
        final String name;
        final String handle;
        final String text;
        final List<MediaItem> media;

        QuotedPost(String postId, String name, String handle, String text, List<MediaItem> media) {
            this.postId = postId;
            this.name = name;
            this.handle = handle;
            this.text = text;
            this.media = media;
        }

        String link() {
            if (postId == null) return null;
            // x.com resolves /i/status/<id> without the handle.
            return "https://x.com/" + (handle == null ? "i" : handle) + "/status/" + postId;
        }

        @Override
        public String mediaLink(String mediaPath) {
            String base = link();
            return base == null ? null : base + "/" + mediaPath;
        }
    }

    /** What one card shows, read from the timeline item's model toString. */
    private static final class PostCard implements LinkSource {
        final String postId;
        final String name;
        final String handle;
        final String avatarUrl;
        final String text;
        // The tappable stretches of text: links, @mentions, #hashtags, $cashtags.
        final List<TextLink> links;
        final List<MediaItem> media;
        final String repostedBy;
        final String replyingTo;
        final QuotedPost quote;
        // When the post was made, in epoch milliseconds, or -1 when the model does not say.
        final long postedAt;
        final boolean sensitiveMedia;
        final String coverText;
        final Engagement engagement;
        // Everything a built card view shows apart from its action row and byline. Two copies of
        // a post with the same key can share one card view (see sameContentAs).
        private final String contentKey;
        // The live timeline post item this card was read from, set by extract(). One-tap actions
        // pass it to X's post-actions service through the patch bridges; null-safe (actions fall back).
        Object item;

        private PostCard(String postId, String name, String handle, String avatarUrl, String text,
                         List<TextLink> links,
                         List<MediaItem> media, String repostedBy, String replyingTo,
                         QuotedPost quote, long postedAt, boolean sensitiveMedia, String coverText,
                         Engagement engagement) {
            this.postId = postId;
            this.name = name;
            this.handle = handle;
            this.avatarUrl = avatarUrl;
            this.text = text;
            this.links = links;
            this.media = media;
            this.repostedBy = repostedBy;
            this.replyingTo = replyingTo;
            this.quote = quote;
            this.postedAt = postedAt;
            this.sensitiveMedia = sensitiveMedia;
            this.coverText = coverText;
            this.engagement = engagement;
            StringBuilder key = new StringBuilder();
            for (String part : new String[]{name, handle, avatarUrl, text, repostedBy, replyingTo, coverText}) {
                key.append(part).append('\u0001');
            }
            key.append(sensitiveMedia);
            appendMediaKey(key, media);
            if (quote != null) {
                key.append('\u0002').append(quote.postId).append('\u0001').append(quote.name)
                        .append('\u0001').append(quote.handle).append('\u0001').append(quote.text);
                appendMediaKey(key, quote.media);
            }
            this.contentKey = key.toString();
        }

        private static void appendMediaKey(StringBuilder key, List<MediaItem> media) {
            for (MediaItem item : media) {
                key.append('\u0001').append(item.url).append('\u0001').append(item.videoUrl);
            }
        }

        /**
         * True when a card view built for {@code other} shows this card too, once its action row
         * and byline are refreshed (CardViews.rebind): same post, same words, same pictures.
         */
        boolean sameContentAs(PostCard other) {
            return other == this || (other != null && Objects.equals(postId, other.postId)
                    && contentKey.equals(other.contentKey));
        }

        /** "now", "5m", "3h", "2d": how long ago the post was made, as of this call. */
        String age() {
            if (postedAt < 0) return null;
            long seconds = Math.max(0, (System.currentTimeMillis() - postedAt) / 1000);
            if (seconds < 60) return "now";
            if (seconds < 3600) return (seconds / 60) + "m";
            if (seconds < 86400) return (seconds / 3600) + "h";
            return (seconds / 86400) + "d";
        }

        String postLink() {
            if (postId == null) return null;
            return "https://x.com/" + (handle == null ? "i" : handle) + "/status/" + postId;
        }

        @Override
        public String mediaLink(String mediaPath) {
            String base = postLink();
            return base == null ? null : base + "/" + mediaPath;
        }

        /** X's web-intent link: /intent/{action}?{idParameter}={postId}. */
        String intentLink(String action, String idParameter) {
            if (postId == null) return null;
            return "https://x.com/intent/" + action + "?" + idParameter + "=" + Uri.encode(postId);
        }

        /** X's composer with this post's link attached, which X shows as a quoted post. */
        String quoteLink() {
            String link = postLink();
            return link == null ? null : "https://x.com/intent/post?url=" + Uri.encode(link);
        }

        String describe() {
            StringBuilder out = new StringBuilder("Post");
            if (name != null) out.append(" by ").append(name);
            if (text != null) out.append(": ").append(text.length() > 140 ? text.substring(0, 140) : text);
            return out.toString();
        }

        // Cards are equal when they show the same post; the item object changes every emission.
        @Override
        public boolean equals(Object other) {
            return other instanceof PostCard card && Objects.equals(postId, card.postId);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(postId);
        }

        /**
         * The cards already read from timeline items, by item instance. X's items are immutable
         * (every field of the timeline and post models is final in 12.40.1, so a changed post
         * is a new instance) and X hands the whole timeline over on every update, so only items
         * not seen in the previous update need reading. Reading one means printing and parsing
         * the whole model; doing that for every item on every update grew with the timeline.
         */
        static final class ReadItems {
            private java.util.IdentityHashMap<Object, List<PostCard>> byItem = new java.util.IdentityHashMap<>();
            private boolean showSensitive;
        }

        static List<PostCard> extract(List<?> items, ReadItems known) {
            // What a card shows of sensitive media is decided when it is read.
            boolean showSensitive = SettingsRegistry.getBooleanOrDefault(SHOW_SENSITIVE_SETTING, false);
            if (known.showSensitive != showSensitive) {
                known.showSensitive = showSensitive;
                known.byItem.clear();
            }
            java.util.IdentityHashMap<Object, List<PostCard>> current = new java.util.IdentityHashMap<>();
            List<PostCard> cards = new ArrayList<>();
            // X can hand the same post over more than once: as its own entry and inside a
            // conversation module (several modules can share the thread's parent), as a post and
            // a repost of it, or again after a refresh kept the old rows. The native list shows
            // threads as threads; the grid shows each post once, where X first placed it.
            java.util.Set<String> seen = new java.util.HashSet<>();
            int repeated = 0;
            int newlyRead = 0;
            for (Object item : items) {
                if (item == null) continue;
                List<PostCard> fromItem = known.byItem.get(item);
                if (fromItem == null) {
                    fromItem = readItem(item);
                    newlyRead++;
                }
                current.put(item, fromItem);
                for (PostCard card : fromItem) {
                    if (card.postId != null && !seen.add(card.postId)) {
                        repeated++;
                        continue;
                    }
                    cards.add(card);
                }
            }
            // Items X no longer sends are forgotten with the old map.
            known.byItem = current;
            if (newlyRead > 0) {
                log("read " + newlyRead + " new of " + items.size() + " items; " + cards.size()
                        + " cards, " + repeated + " repeated posts skipped");
            }
            return cards;
        }

        /**
         * The cards one timeline item becomes: one for a post, one per post for a module (a
         * conversation, a row of posts), none for anything else (cursors, prompts, "Who to
         * follow"). Each card keeps its own live post item, which is what X's post-actions
         * service is handed, so a post inside a module likes and reposts in place like any other.
         */
        private static List<PostCard> readItem(Object item) {
            try {
                List<Object> posts = null;
                try {
                    posts = app.morphe.extension.newx.timeline.NewXTimelineFilter.modulePosts(item);
                } catch (RuntimeException e) {
                    NewXLogger.printException(() -> LOG_PREFIX + "could not open a timeline module", e);
                }
                // Not a module, or a module whose posts could not be told apart: the item is
                // read whole, which for a module gives one card (its first post).
                if (posts == null || posts.isEmpty()) posts = Collections.singletonList(item);
                List<PostCard> cards = new ArrayList<>(posts.size());
                for (Object post : posts) {
                    if (post == null) continue;
                    String value = post.toString();
                    PostCard card = read(value);
                    if (card == null) continue;
                    // A module read as one card (the model bridges were left unpatched) is not
                    // a post item; its actions fall back to opening the post.
                    card.item = value.startsWith("UrtTimelineModule(") ? null : post;
                    cards.add(card);
                }
                return cards;
            } catch (RuntimeException e) {
                NewXLogger.printException(() -> LOG_PREFIX + "could not read a timeline item", e);
                return Collections.emptyList();
            }
        }

        /** X's models keep the API's escaping of &amp;, &lt; and &gt;; X undoes it as it draws text. */
        private static String unescape(String text) {
            if (text == null || text.indexOf('&') < 0) return text;
            return text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        }

        /*
         * Field order, from 12.40.1's model toString methods:
         *   ContextualPost(canonicalPost=, quotedPost=, rePostedPost=, ...)
         *   CanonicalPost(id=, text=, timestamp=, media=, entityList=, author=, legacyCard=, ...,
         *                 repliedToUserScreenName=, ...)
         *   MediaContentVideo(mediaId=, durationMillis=, previewImage=, variants=, viewCount=,
         *                     aspectRatio=, ...)
         */
        private static PostCard read(String value) {
            // Only posts become cards; cursors, prompts and "Who to follow" modules are skipped.
            int canonical = value.indexOf("CanonicalPost(");
            if (canonical < 0) return null;

            String reposted = ToStringParser.fieldValue(value, "rePostedPost");
            boolean isRepost = reposted != null && reposted.contains("text=");
            String shown = isRepost ? reposted : value.substring(canonical);
            String repostedBy = null;
            if (isRepost) {
                String outerAuthor = ToStringParser.fieldValue(value.substring(canonical), "author");
                repostedBy = outerAuthor == null ? null : unescape(ToStringParser.fieldValue(outerAuthor, "name"));
            }

            List<MediaItem> media = mediaOf(shown);
            String text = postText(shown);
            if (text != null && !media.isEmpty()) text = stripTrailingMediaLink(text);
            if (text != null) text = unescape(expandLinks(text, shown));

            // X's sensitive-content warnings live on the contextual post (Piko's "Show sensitive
            // media" clears them at the model) and on each video. A repost carries them on both
            // the wrapper and the original.
            boolean showSensitive = SettingsRegistry.getBooleanOrDefault(SHOW_SENSITIVE_SETTING, false);
            String coverText = null;
            boolean sensitiveMedia = false;
            if (!showSensitive) {
                coverText = coverText(ownFields(value));
                if (coverText == null && isRepost) coverText = coverText(ownFields(reposted));
                sensitiveMedia = hasBlur(ownFields(value))
                        || (isRepost && hasBlur(ownFields(reposted)))
                        || hasMediaWarning(shown);
            }

            String author = ToStringParser.fieldValue(shown, "author");
            String quotedSource = isRepost ? reposted : value;
            String quoted = ToStringParser.fieldValue(quotedSource, "quotedPost");

            return new PostCard(
                    NewXUtils.rawSourcePostId(value),
                    unescape(NewXUtils.rawSourceDisplayName(value)),
                    NewXUtils.rawSourceScreenName(value),
                    avatarOf(author),
                    text,
                    text == null ? Collections.emptyList() : linksIn(text, shown),
                    media,
                    repostedBy,
                    ToStringParser.fieldValue(shown, "repliedToUserScreenName"),
                    quoteOf(quoted, showSensitive),
                    epochMillis(ToStringParser.fieldValue(shown, "timestamp")),
                    sensitiveMedia,
                    coverText,
                    Engagement.of(shown)
            );
        }

        /** The post's own fields, without its nested quoted and reposted posts' fields. */
        private static String ownFields(String contextual) {
            String own = contextual;
            for (String nested : new String[]{"quotedPost", "rePostedPost"}) {
                String value = ToStringParser.fieldValue(own, nested);
                if (value != null) own = own.replace(value, "");
            }
            return own;
        }

        /** TweetInterstitial(revealText=, displayText=, displayType=): X's whole-post warning. */
        private static String coverText(String own) {
            String interstitial = ToStringParser.fieldValue(own, "tweetInterstitial");
            if (interstitial == null || !interstitial.startsWith("TweetInterstitial(")) return null;
            String text = between(interstitial, "displayText=", ", displayType=");
            return text != null ? text : "This post may contain sensitive content";
        }

        /** MediaVisibilityResults(blurImageInterstitial=BlurImageInterstitial(...)): blurred media. */
        private static boolean hasBlur(String own) {
            String visibility = ToStringParser.fieldValue(own, "mediaVisibilityResults");
            return visibility != null && visibility.contains("BlurImageInterstitial(");
        }

        /** MediaContentVideo(..., sensitiveMediaWarning=SensitiveMediaWarning(...)). */
        private static boolean hasMediaWarning(String post) {
            String list = ToStringParser.fieldValue(post, "media");
            return list != null && list.contains("sensitiveMediaWarning=SensitiveMediaWarning(");
        }

        private static String between(String text, String startMarker, String endMarker) {
            int start = text.indexOf(startMarker);
            if (start < 0) return null;
            start += startMarker.length();
            int end = text.indexOf(endMarker, start);
            if (end < 0) return null;
            String value = text.substring(start, end).trim();
            return value.isEmpty() || value.equals("null") ? null : value;
        }

        /**
         * Shows links the way X does: each t.co address in the text becomes its display form
         * (PostEntityList(urls=[UrlEntity(..., displayUrl=, url=https://t.co/...)])). Tapping the
         * card opens the post, where the link itself can be followed.
         */
        private static String expandLinks(String text, String post) {
            String entities = ToStringParser.fieldValue(post, "entityList");
            String urls = entities == null ? null : ToStringParser.fieldValue(entities, "urls");
            if (urls == null) return text;
            int offset = 0;
            while (true) {
                int start = urls.indexOf("UrlEntity(", offset);
                if (start < 0) break;
                int close = matchingClose(urls, urls.indexOf('(', start));
                if (close < 0) break;
                String entity = urls.substring(start, close + 1);
                offset = close + 1;
                String shortUrl = ToStringParser.fieldValue(entity, "url");
                String display = ToStringParser.fieldValue(entity, "displayUrl");
                if (shortUrl != null && display != null && shortUrl.startsWith("https://t.co/")) {
                    text = text.replace(shortUrl, display);
                }
            }
            return text;
        }

        // What X marks up in post text, simplified from its own rules: a name is letters, digits
        // and "_"; a hashtag needs a letter; an "@" or "#" inside a word or an address is not one.
        private static final java.util.regex.Pattern MENTION = java.util.regex.Pattern.compile(
                "(?<![A-Za-z0-9_@/])@([A-Za-z0-9_]{1,15})(?![A-Za-z0-9_])");
        private static final java.util.regex.Pattern HASHTAG = java.util.regex.Pattern.compile(
                "(?<![\\p{L}\\p{M}\\p{N}_&/#])#([\\p{L}\\p{M}\\p{N}_]*[\\p{L}\\p{M}][\\p{L}\\p{M}\\p{N}_]*)");
        private static final java.util.regex.Pattern CASHTAG = java.util.regex.Pattern.compile(
                "(?<![\\p{L}\\p{N}_$])\\$([A-Za-z]{1,6}(?:[._][A-Za-z]{1,2})?)(?![\\p{L}\\p{N}_])");

        /**
         * Where the card's text can be tapped, and what each stretch opens. Links come from the
         * post's own entities (UrlEntity(displayUrl=, expandedUrl=, url=, ...)): the text shows
         * the display form (see expandLinks) and the tap follows the full address. Mentions,
         * hashtags and cashtags are found in the text and open X's profile, hashtag and search
         * screens, which its deep-link router has routes for (/{handle}, /hashtag/{tag}, /search).
         */
        private static List<TextLink> linksIn(String text, String post) {
            List<TextLink> links = new ArrayList<>();
            String entities = ToStringParser.fieldValue(post, "entityList");
            String urls = entities == null ? null : ToStringParser.fieldValue(entities, "urls");
            int offset = 0;
            while (urls != null) {
                int start = urls.indexOf("UrlEntity(", offset);
                if (start < 0) break;
                int close = matchingClose(urls, urls.indexOf('(', start));
                if (close < 0) break;
                String entity = urls.substring(start, close + 1);
                offset = close + 1;
                String display = unescape(ToStringParser.fieldValue(entity, "displayUrl"));
                // The full address can hold commas, which end a plain field read early.
                String target = between(entity, "expandedUrl=", ", url=");
                if (!NewXUtils.isHttpUrl(target)) target = ToStringParser.fieldValue(entity, "expandedUrl");
                if (!NewXUtils.isHttpUrl(target)) target = ToStringParser.fieldValue(entity, "url");
                if (display == null || display.length() < 4 || !NewXUtils.isHttpUrl(target)) continue;
                for (int at = text.indexOf(display); at >= 0; at = text.indexOf(display, at + display.length())) {
                    addLink(links, at, at + display.length(), target);
                }
            }
            java.util.regex.Matcher mention = MENTION.matcher(text);
            while (mention.find()) addLink(links, mention.start(), mention.end(), profileLink(mention.group(1)));
            java.util.regex.Matcher hashtag = HASHTAG.matcher(text);
            while (hashtag.find()) {
                addLink(links, hashtag.start(), hashtag.end(), "https://x.com/hashtag/" + Uri.encode(hashtag.group(1)));
            }
            java.util.regex.Matcher cashtag = CASHTAG.matcher(text);
            while (cashtag.find()) {
                addLink(links, cashtag.start(), cashtag.end(),
                        "https://x.com/search?q=" + Uri.encode("$" + cashtag.group(1)) + "&src=cashtag_click");
            }
            return links.isEmpty() ? Collections.emptyList() : links;
        }

        /** Adds a link unless it overlaps one already there (a "#" inside a shown address, say). */
        private static void addLink(List<TextLink> links, int start, int end, String url) {
            if (url == null || start < 0 || end <= start) return;
            for (TextLink link : links) {
                if (start < link.end && end > link.start) return;
            }
            links.add(new TextLink(start, end, url));
        }

        private static QuotedPost quoteOf(String quoted, boolean showSensitive) {
            if (quoted == null || !quoted.contains("text=")) return null;
            String author = ToStringParser.fieldValue(quoted, "author");
            List<MediaItem> media = mediaOf(quoted);
            String text = postText(quoted);
            if (text != null && !media.isEmpty()) text = stripTrailingMediaLink(text);
            if (text != null) text = unescape(expandLinks(text, quoted));
            if (!showSensitive) {
                String own = ownFields(quoted);
                String cover = coverText(own);
                if (cover != null) text = "\u26A0 " + cover;
                // Warned quote media is left out; the quote box still opens the quoted post.
                if (cover != null || hasBlur(own) || hasMediaWarning(quoted)) media = Collections.emptyList();
            }
            return new QuotedPost(
                    ToStringParser.fieldValue(quoted, "id"),
                    author == null ? null : unescape(ToStringParser.fieldValue(author, "name")),
                    author == null ? null : ToStringParser.fieldValue(author, "screenName"),
                    text,
                    media);
        }

        private static String avatarOf(String author) {
            if (author == null) return null;
            for (String field : new String[]{"profileImageUrl", "avatarUrl", "imageUrl"}) {
                String url = ToStringParser.fieldValue(author, field);
                if (NewXUtils.isHttpUrl(url)) return url;
            }
            return null;
        }

        /**
         * Post text runs up to the next model field, not the next comma: ToStringParser stops
         * primitives at a comma, which would cut most sentences short.
         */
        private static String postText(String post) {
            int start = post.indexOf("text=");
            if (start < 0) return null;
            start += "text=".length();
            int end = -1;
            for (String next : new String[]{", timestamp=", ", media=", ", entityList=", ", author="}) {
                int found = post.indexOf(next, start);
                if (found >= 0 && (end < 0 || found < end)) end = found;
            }
            if (end < 0) return null;
            String text = post.substring(start, end).trim();
            return text.isEmpty() || text.equals("null") ? null : text;
        }

        private static List<MediaItem> mediaOf(String post) {
            String list = ToStringParser.fieldValue(post, "media");
            if (list == null) return Collections.emptyList();
            List<MediaItem> items = new ArrayList<>();
            int offset = 0;
            while (items.size() < MAX_MEDIA) {
                int start = nextMedia(list, offset);
                if (start < 0) break;
                int open = list.indexOf('(', start);
                int close = matchingClose(list, open);
                if (open < 0 || close < 0) break;
                String segment = list.substring(start, close + 1);
                offset = close + 1;
                boolean gif = segment.startsWith("MediaContentGif(");
                boolean video = gif || segment.startsWith("MediaContentVideo(");
                String url = ToStringParser.fieldValue(segment, gif ? "previewUrl" : "imageUrl");
                if (!NewXUtils.isHttpUrl(url)) url = ToStringParser.fieldValue(segment, "imageUrl");
                if (!NewXUtils.isHttpUrl(url)) continue;
                items.add(new MediaItem(url, video, gif, video ? playableMp4(segment) : null, aspectOf(segment)));
            }
            return items;
        }

        // A video variant's size as X writes it into the address: /1920x1080/.
        private static final java.util.regex.Pattern VARIANT_SIZE =
                java.util.regex.Pattern.compile("/(\\d{2,5})x(\\d{2,5})/");

        /**
         * The mp4 to play inline: MediaVariant(url=, bitRate=, contentType=video/mp4) entries,
         * read the way InlineDownloadButton reads them. The highest resolution X offers: the
         * largest size in the URL (X encodes it as /1920x1080/), else the highest bitrate.
         */
        private static String playableMp4(String segment) {
            String prefix = "MediaVariant(url=";
            String best = null;
            int bestWidth = -1;
            String fallback = null;
            long fallbackBitRate = -1;
            int offset = 0;
            while (true) {
                int start = segment.indexOf(prefix, offset);
                if (start < 0) break;
                start += prefix.length();
                int bitRateStart = segment.indexOf(", bitRate=", start);
                int contentTypeStart = bitRateStart < 0 ? -1 : segment.indexOf(", contentType=", bitRateStart);
                int end = contentTypeStart < 0 ? -1 : segment.indexOf(')', contentTypeStart);
                if (end < 0) break;
                offset = end + 1;
                String url = segment.substring(start, bitRateStart).trim();
                String contentType = segment.substring(contentTypeStart + ", contentType=".length(), end).trim();
                if (!InlineDownloadButton.isHttpMp4Variant(url, contentType)) continue;
                long bitRate = (long) number(segment.substring(bitRateStart + ", bitRate=".length(), contentTypeStart));
                java.util.regex.Matcher size = VARIANT_SIZE.matcher(url);
                if (size.find()) {
                    int width = Integer.parseInt(size.group(1));
                    if (width > bestWidth) {
                        best = url;
                        bestWidth = width;
                    }
                }
                if (bitRate > fallbackBitRate) {
                    fallback = url;
                    fallbackBitRate = bitRate;
                }
            }
            return best != null ? best : fallback;
        }

        private static int nextMedia(String list, int offset) {
            int best = -1;
            for (String marker : new String[]{"MediaContentImage(", "MediaContentVideo(", "MediaContentGif("}) {
                int found = list.indexOf(marker, offset);
                if (found >= 0 && (best < 0 || found < best)) best = found;
            }
            return best;
        }

        private static int matchingClose(String text, int open) {
            if (open < 0) return -1;
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) return i;
            }
            return -1;
        }

        /** Width / height from the model when it says; 0 lets the loaded image decide. */
        private static float aspectOf(String segment) {
            float width = number(ToStringParser.fieldValue(segment, "width"));
            float height = number(ToStringParser.fieldValue(segment, "height"));
            if (width > 0 && height > 0) return width / height;
            String ratio = ToStringParser.fieldValue(segment, "aspectRatio");
            if (ratio == null) return 0f;
            float direct = number(ratio);
            if (direct > 0) return direct;
            // e.g. AspectRatio(numerator=16, denominator=9) or (width=16, height=9)
            String[] parts = ratio.replaceAll("[^0-9.]+", " ").trim().split(" ");
            if (parts.length >= 2) {
                float a = number(parts[0]);
                float b = number(parts[1]);
                if (a > 0 && b > 0) return a / b;
            }
            return 0f;
        }

        private static float number(String value) {
            if (value == null) return 0f;
            try {
                return Float.parseFloat(value.trim());
            } catch (NumberFormatException e) {
                return 0f;
            }
        }

        // X appends the media's t.co link to the text; the card already shows the media.
        private static String stripTrailingMediaLink(String text) {
            String trimmed = text.replaceAll("\\s*https://t\\.co/\\S+\\s*$", "");
            return trimmed.isEmpty() ? null : trimmed;
        }

        /** The model's ISO-8601 timestamp in epoch milliseconds, or -1 when missing or unreadable. */
        private static long epochMillis(String timestamp) {
            if (timestamp == null) return -1L;
            try {
                return Instant.parse(timestamp.trim()).toEpochMilli();
            } catch (RuntimeException e) {
                return -1L;
            }
        }
    }
}
