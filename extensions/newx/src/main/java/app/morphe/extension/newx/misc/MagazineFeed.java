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

import java.time.Duration;
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
 * <p>Every action goes through X itself, by in-app x.com links that X's deep-link router handles
 * ({@code /intent/{action}} with {@code tweet_id}/{@code in_reply_to}, verified in 12.40.1's
 * {@code com.x.android.deeplink} handler), so replying, reposting and liking run X's own flows.
 * Bookmarking has no such link and opens the post, where X's own bookmark button is. Counts and
 * liked/reposted state are not part of the timeline item, so the buttons show neither.
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

    private static Object repostedOf(Object contextualPost) {
        throw new IllegalStateException("Piko 2.0 repostedOf bridge was not patched");
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
            Object post = postResultOf(card.item);
            if (post == null) return false;
            if (card.repostedBy != null) {
                // A repost card shows the original post; act on that one.
                try {
                    Object original = repostedOf(post);
                    if (original != null) post = original;
                } catch (RuntimeException ignored) {
                }
            }
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
    // If X never confirms (the action failed, say), its own state wins again after this long.
    private static final long PENDING_TTL_MS = 60_000L;

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
        if (pending == null) return card.engagement;
        if (android.os.SystemClock.uptimeMillis() - pending.at > PENDING_TTL_MS) {
            pendingEngagement.remove(card.postId);
            return card.engagement;
        }
        return pending.engagement;
    }

    private static void rememberAction(PostCard card, Engagement next) {
        if (card.postId != null) pendingEngagement.put(card.postId, new PendingEngagement(next));
    }

    /** Drops a pending action once X's own state for the post agrees with it (X's counts win). */
    private static void reconcile(PostCard card) {
        PendingEngagement pending = card.postId == null ? null : pendingEngagement.get(card.postId);
        if (pending == null) return;
        Engagement x = card.engagement;
        Engagement mine = pending.engagement;
        if (x.liked == mine.liked && x.reposted == mine.reposted && x.bookmarked == mine.bookmarked) {
            pendingEngagement.remove(card.postId);
        }
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

    /** After the focus view closes, shows on the grid card whatever the reader did in it. */
    private static void refreshGridCard(String postId) {
        if (postId == null) return;
        for (MagazineView view : new ArrayList<>(liveViews)) view.refreshCard(postId);
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
    private static void showFocus(Context context, PostCard card) {
        Activity activity = NewXUtils.findActivity(context);
        if (activity == null || activity.isFinishing()) {
            openInX(context, card.postLink());
            return;
        }
        dismissFocus();
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        FrameLayout scrim = new FrameLayout(activity);
        scrim.setOnClickListener(view -> dialog.dismiss());
        scrim.setContentDescription("Close");

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
        close.setContentDescription("Close");
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

        TextView openFull = CardViews.text(activity, "Open full post \u00B7 replies", 15f, Theme.onPrimaryAccent(activity));
        CardViews.bold(openFull);
        openFull.setGravity(Gravity.CENTER);
        int buttonPadding = Theme.dpToPx(activity, 12f);
        openFull.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
        openFull.setBackground(CardViews.rounded(Theme.primaryAccent(activity), Theme.dpToPx(activity, 24f)));
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
            refreshGridCard(card.postId);
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
        String link = shareLink(card);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (link == null || clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Post link", link));
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show();
    }

    // ---------- Scroll container ----------

    private static final class MagazineView extends NestedScrollView {
        // One id per process so recreated AndroidView instances share saved scroll state.
        private static final int VIEW_STATE_ID = View.generateViewId();

        // Where the reader was, kept across view recreation (leaving for a post and coming back
        // can rebuild the AndroidView) and across X re-sending the timeline: the post at the top
        // of the screen and how far it was scrolled past.
        private static String anchorPostId;
        private static int anchorOffset;

        private final MasonryGrid grid;
        private final LoadingIndicatorView loadingIndicator;
        private boolean restorePending;
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
            setOnScrollChangeListener((View.OnScrollChangeListener) (view, x, y, oldX, oldY) -> {
                if (!restorePending) rememberAnchor();
                if (y <= oldY || loadMoreInFlight) return;
                paginationArmed = true;
                checkLoadMore();
            });
            addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                if (!loadMoreInFlight) post(this::checkLoadMore);
            });

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int gap = Theme.dpToPx(context, GAP_DP);
            content.setPadding(gap, gap, gap, gap);

            grid = new MasonryGrid(context);
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
            restorePending = anchorPostId != null;
            setItems(items);
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
                if (top == 0) anchorPostId = null;
                return;
            }
            anchorPostId = grid.postIdAt(bestIndex);
            anchorOffset = top - bestTop;
        }

        /** After the cards are laid out, puts the remembered post back at the top of the screen. */
        private void restoreAnchorAfterLayout() {
            if (anchorPostId == null) {
                restorePending = false;
                return;
            }
            restorePending = true;
            getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    int index = grid.indexOfPost(anchorPostId);
                    if (index >= 0) {
                        int target = grid.getTop() + grid.getChildAt(index).getTop() + anchorOffset;
                        scrollTo(0, Math.max(0, target));
                        log("restored position at post " + anchorPostId);
                    }
                    restorePending = false;
                }
            });
        }

        // Public: NestedScrollView widens View's protected onAttachedToWindow.
        @Override
        public void onAttachedToWindow() {
            super.onAttachedToWindow();
            liveViews.add(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            liveViews.remove(this);
            super.onDetachedFromWindow();
        }

        /**
         * Home re-selected (or the top bar tapped) while this feed is on screen, as on the native
         * list: scrolled down, glide back to the newest post; already at the top, ask X for newer
         * posts. The refresh that follows lands at the top, since the anchor is cleared here.
         */
        void onReselected() {
            anchorPostId = null;
            restorePending = false;
            if (getScrollY() > 0) {
                smoothScrollTo(0, 0);
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

        void refreshCard(String postId) {
            grid.refreshCard(postId);
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
            List<PostCard> cards = PostCard.extract(next);
            boolean rebuilt = grid.setCards(cards);
            // A new view, or X replacing the timeline (a refresh): keep the reader on their post.
            // Going to the top is an explicit request only (Home re-select, see onReselected), so
            // coming back from a post never throws the reader to the top.
            if (restorePending || rebuilt) restoreAnchorAfterLayout();
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
     */
    private static final class MasonryGrid extends ViewGroup {
        private final int gap;
        private List<PostCard> cards = Collections.emptyList();
        private final List<Integer> columnOf = new ArrayList<>();
        private int assignedColumns = -1;

        MasonryGrid(Context context) {
            super(context);
            gap = Theme.dpToPx(context, GAP_DP);
            setWillNotDraw(true);
        }

        String postIdAt(int index) {
            return index >= 0 && index < cards.size() ? cards.get(index).postId : null;
        }

        int indexOfPost(String postId) {
            if (postId == null) return -1;
            for (int i = 0; i < cards.size(); i++) if (postId.equals(cards.get(i).postId)) return i;
            return -1;
        }

        /** Redraws one card's action row with what it should show now (see shownEngagement). */
        void refreshCard(String postId) {
            int index = indexOfPost(postId);
            if (index >= 0 && index < getChildCount()) {
                CardViews.rebindEngagement(getChildAt(index), cards.get(index));
            }
        }

        /** Returns true when the cards were rebuilt rather than appended to. */
        boolean setCards(List<PostCard> next) {
            boolean appendOnly = next.size() >= cards.size()
                    && next.subList(0, cards.size()).equals(cards);
            if (!appendOnly) {
                // Refresh or reorder: rebuild, so positions follow the new order.
                for (PostCard card : next) reconcile(card);
                removeAllViews();
                columnOf.clear();
                for (PostCard card : next) addView(CardViews.build(getContext(), card));
            } else {
                // Same posts in the same places: refresh only cards whose shown counts or state
                // changed, e.g. once X re-sends a post the reader just liked. Update the action
                // row in place instead of rebuilding the card, or the media reloads, the grid
                // reflows and every post blinks. A stale re-send (X has not applied the action
                // yet) leaves the reader's pending state on screen rather than flipping it back.
                for (int i = 0; i < cards.size(); i++) {
                    Engagement before = shownEngagement(cards.get(i));
                    reconcile(next.get(i));
                    if (!before.sameAs(shownEngagement(next.get(i)))) {
                        CardViews.rebindEngagement(getChildAt(i), next.get(i));
                    }
                }
                for (int i = cards.size(); i < next.size(); i++) {
                    reconcile(next.get(i));
                    addView(CardViews.build(getContext(), next.get(i)));
                }
            }
            requestLayout();
            boolean rebuilt = !appendOnly && !cards.isEmpty();
            cards = next;
            return rebuilt;
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

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int columns = columnCount(width);
            if (columns != assignedColumns) {
                // Rotation or resize: columns are re-dealt once for the new count.
                columnOf.clear();
                assignedColumns = columns;
            }
            int childWidthSpec = MeasureSpec.makeMeasureSpec(columnWidth(width, columns), MeasureSpec.EXACTLY);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int[] heights = new int[columns];
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(childWidthSpec, childHeightSpec);
                if (i >= columnOf.size()) columnOf.add(shortest(heights));
                int column = columnOf.get(i);
                heights[column] += (heights[column] == 0 ? 0 : gap) + child.getMeasuredHeight();
            }
            int tallest = 0;
            for (int h : heights) tallest = Math.max(tallest, h);
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(tallest, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int columns = columnCount(width);
            int columnWidth = columnWidth(width, columns);
            int[] tops = new int[columns];
            for (int i = 0; i < getChildCount() && i < columnOf.size(); i++) {
                View child = getChildAt(i);
                int column = Math.min(columnOf.get(i), columns - 1);
                int left = column * (columnWidth + gap);
                child.layout(left, tops[column], left + columnWidth, tops[column] + child.getMeasuredHeight());
                tops[column] += child.getMeasuredHeight() + gap;
            }
        }

        private static int shortest(int[] heights) {
            int best = 0;
            for (int i = 1; i < heights.length; i++) if (heights[i] < heights[best]) best = i;
            return best;
        }
    }

    // ---------- Card views ----------

    private static final class CardViews {
        private CardViews() {
        }

        // Marks a card's action row so it can be found and refreshed in place (see rebindEngagement).
        private static final Object ACTIONS_TAG = new Object();

        /**
         * Refreshes a built card's action row for new engagement without rebuilding the card, so the
         * media is not reloaded and the masonry grid does not reflow. Used when X re-sends a post
         * after a like/repost/bookmark; rebuilding the whole card made every post blink.
         */
        static void rebindEngagement(View cardRoot, PostCard card) {
            if (cardRoot == null) return;
            View actionRow = cardRoot.findViewWithTag(ACTIONS_TAG);
            if (actionRow == null || !(actionRow.getParent() instanceof ViewGroup body)) return;
            Context context = cardRoot.getContext();
            int index = body.indexOfChild(actionRow);
            body.removeViewAt(index);
            body.addView(actions(context, card, Theme.secondaryText(context)), index);
            cardRoot.setContentDescription(card.describe());
            // Keep the card tap (focus view) pointed at the fresh card.
            cardRoot.setOnClickListener(view -> showFocus(context, card));
        }

        static View build(Context context, PostCard card) {
            return build(context, card, false);
        }

        /** {@code focal}: the enlarged card of the focus view (full text, larger type). */
        static View build(Context context, PostCard card, boolean focal) {
            int pad = Theme.dpToPx(context, 14f);
            int ink = Theme.primaryText(context);
            int muted = Theme.secondaryText(context);

            LinearLayout root = new LinearLayout(context);
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
            root.setContentDescription(card.describe());

            if (card.repostedBy != null) {
                TextView contextLine = text(context, "\u21BB " + card.repostedBy + " reposted", 13f, muted);
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
                TextView replying = text(context, "\u21A9 Replying to @" + card.replyingTo, 13f, muted);
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
            return root;
        }

        private static View header(Context context, PostCard card, int ink, int muted) {
            LinearLayout head = new LinearLayout(context);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            int avatarSize = Theme.dpToPx(context, 32f);
            View avatar = avatar(context, card, avatarSize);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(avatarSize, avatarSize);
            avatarParams.rightMargin = Theme.dpToPx(context, 10f);
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
            String views = formatCount(card.engagement.views);
            String sub = joinNonNull(joinNonNull(card.handle == null ? null : "@" + card.handle, card.age),
                    views == null ? null : views + " views");
            if (sub != null) {
                TextView handle = text(context, sub, 13f, muted);
                handle.setSingleLine(true);
                handle.setEllipsize(TextUtils.TruncateAt.END);
                who.addView(handle);
            }
            head.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View more = iconButton(context, "More", muted, null, "\u22EF", "ic_vector_overflow", "ic_vector_more");
            more.setOnClickListener(view -> showMoreMenu(view, card));
            head.addView(more);
            return head;
        }

        private static void showMoreMenu(View anchor, PostCard card) {
            Context context = anchor.getContext();
            PopupMenu menu = new PopupMenu(context, anchor);
            menu.getMenu().add(0, 1, 0, "Open post");
            menu.getMenu().add(0, 2, 1, "Copy link");
            menu.getMenu().add(0, 3, 2, "Share via\u2026");
            if (card.handle != null) menu.getMenu().add(0, 4, 3, "View @" + card.handle);
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1 -> openInX(context, card.postLink());
                    case 2 -> copyLink(context, card);
                    case 3 -> sharePost(context, card);
                    case 4 -> openInX(context, profileLink(card.handle));
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
            menu.getMenu().add(0, 1, 0, state.active ? "Undo repost" : "Repost");
            menu.getMenu().add(0, 2, 1, "Quote");
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1 -> {
                        boolean reposting = !state.active;
                        if (runAction(card, reposting ? PostAction.REPOST : PostAction.UNREPOST)) {
                            state.toggle();
                            paint.run();
                            rememberAction(card, shownEngagement(card).withReposted(state.active, state.count));
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

            View reply = iconButton(context, "Reply", muted, e.replies, "\u21A9",
                    "ic_vector_reply_stroke", "ic_vector_reply");
            reply.setOnClickListener(view -> openInX(context, card.intentLink("post", "in_reply_to")));

            // Repost offers X's own choices (repost or quote; undo once reposted) instead of
            // reposting publicly on a single tap. Repost and undo run in place, like like/bookmark.
            ToggleState repostState = new ToggleState(e.reposted, e.reposts);
            LinearLayout repost = (LinearLayout) iconButton(context, "Repost", muted, null, "\u21BB", "ic_vector_retweet_stroke");
            Runnable paintRepost = () -> fillButton(repost, repostState.active ? "Undo repost" : "Repost",
                    repostState.active ? REPOST_COLOR : muted, repostState.count, "\u21BB",
                    repostState.active ? "ic_vector_retweet" : "ic_vector_retweet_stroke", "ic_vector_retweet");
            paintRepost.run();
            repost.setOnClickListener(view -> showRepostMenu(view, card, repostState, paintRepost));

            // Like, repost and bookmark run through X's own service in one tap; when X re-emits the
            // post with the new state, the card redraws with it.
            ToggleState likeState = new ToggleState(e.liked, e.likes);
            LinearLayout like = (LinearLayout) iconButton(context, "Like", muted, null, "\u2661", "ic_vector_heart_stroke");
            Runnable paintLike = () -> fillButton(like, likeState.active ? "Unlike" : "Like",
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
                    rememberAction(card, shownEngagement(card).withLiked(likeState.active, likeState.count));
                } else {
                    openInX(context, card.postLink());
                }
            });

            ToggleState bookmarkState = new ToggleState(e.bookmarked, null);
            LinearLayout bookmark = (LinearLayout) iconButton(context, "Bookmark", muted, null, "\u2606", "ic_vector_bookmark_stroke");
            Runnable paintBookmark = () -> fillButton(bookmark, bookmarkState.active ? "Remove bookmark" : "Bookmark",
                    bookmarkState.active ? accent : muted, null,
                    bookmarkState.active ? "\u2605" : "\u2606",
                    bookmarkState.active ? "ic_vector_bookmark" : "ic_vector_bookmark_stroke", "ic_vector_bookmark");
            paintBookmark.run();
            bookmark.setOnClickListener(view -> {
                boolean adding = !bookmarkState.active;
                if (runAction(card, adding ? PostAction.BOOKMARK : PostAction.UNBOOKMARK)) {
                    bookmarkState.toggle();
                    paintBookmark.run();
                    rememberAction(card, shownEngagement(card).withBookmarked(bookmarkState.active));
                    Toast.makeText(context, adding ? "Bookmarked" : "Removed from bookmarks", Toast.LENGTH_SHORT).show();
                } else {
                    // X has no bookmark link; its own bookmark button is on the post screen.
                    Toast.makeText(context, "Bookmark it from the post", Toast.LENGTH_SHORT).show();
                    openInX(context, card.postLink());
                }
            });
            View share = iconButton(context, "Share", muted, null, "\u2197",
                    "ic_vector_share_android", "ic_vector_share");
            share.setOnClickListener(view -> sharePost(context, card));

            // Reply, repost and like spread out; bookmark and share sit at the end, as on X.
            for (View button : new View[]{reply, repost, like}) {
                row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            row.addView(bookmark);
            row.addView(share);
            row.setTag(ACTIONS_TAG);
            return row;
        }

        /**
         * A 40dp touch target with X's own icon when the app ships it (else a text glyph), and the
         * count beside it when there is one. The label and count form the spoken description.
         */
        private static View iconButton(Context context, String label, int color, Long count,
                                       String glyph, String... iconNames) {
            int size = Theme.dpToPx(context, 40f);
            LinearLayout button = new LinearLayout(context);
            button.setOrientation(LinearLayout.HORIZONTAL);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setMinimumWidth(size);
            button.setMinimumHeight(size);
            button.setClickable(true);
            button.setFocusable(true);
            TypedValue ripple = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                button.setBackgroundResource(ripple.resourceId);
            }
            int horizontal = Theme.dpToPx(context, 6f);
            button.setPadding(horizontal, 0, horizontal, 0);
            fillButton(button, label, color, count, glyph, iconNames);
            return button;
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
                params.leftMargin = Theme.dpToPx(context, 5f);
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
            String initial = card.name == null || card.name.isEmpty() ? "?" : card.name.substring(0, 1);
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
                String url = card.avatarUrl;
                CardImageLoader.load(largerAvatar(url), new CardImageLoader.Callback() {
                    @Override
                    public void onLoaded(Bitmap bitmap) {
                        if (!bitmap.isRecycled()) {
                            image.setImageBitmap(bitmap);
                            letter.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onFailed(boolean transientError) {
                    }
                });
            }
            return frame;
        }

        /** Stands in for media X marks sensitive; tapping opens the post with X's own warning. */
        private static View sensitiveTile(Context context, PostCard card) {
            TextView tile = text(context, "Sensitive media \u00B7 Tap to view", 14f, Theme.secondaryText(context));
            tile.setGravity(Gravity.CENTER);
            int vertical = Theme.dpToPx(context, 40f);
            tile.setPadding(Theme.dpToPx(context, 12f), vertical, Theme.dpToPx(context, 12f), vertical);
            tile.setBackgroundColor(Theme.dividerColor(context));
            tile.setClickable(true);
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

        void addTile(Context context, MediaItem item, View.OnClickListener click) {
            int index = getChildCount();
            aspects.add(shownAspect(item.aspect > 0 ? item.aspect : UNKNOWN_ASPECT));

            FrameLayout tile = new FrameLayout(context);
            tile.setBackgroundColor(Theme.dividerColor(context));
            tile.setClickable(true);
            tile.setOnClickListener(click);
            tile.setContentDescription(item.video ? "Video" : "Photo " + (index + 1));
            ImageView image = new ImageView(context);
            image.setScaleType(scaleFor(item.aspect));
            image.setTag(item.url);
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

            String url = item.url;
            CardImageLoader.load(mediumUrl(url), new CardImageLoader.Callback() {
                @Override
                public void onLoaded(Bitmap bitmap) {
                    if (!url.equals(image.getTag()) || bitmap.isRecycled()) return;
                    image.setImageBitmap(bitmap);
                    // The real shape arrives with the image; the model's size is only a hint.
                    if (bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                        float real = bitmap.getWidth() / (float) bitmap.getHeight();
                        image.setScaleType(scaleFor(real));
                        float shown = shownAspect(real);
                        if (Math.abs(shown - aspects.get(index)) > 0.01f) {
                            aspects.set(index, shown);
                            requestLayout();
                        }
                    }
                }

                @Override
                public void onFailed(boolean transientError) {
                    // The grey tile stays; tapping still opens X's own viewer.
                }
            });
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
                mediaPlayer.setVolume(0f, 0f);
                mediaPlayer.setOnPreparedListener(mp -> {
                    if (player != mp) return;
                    prepared = true;
                    int duration = Math.max(0, mp.getDuration());
                    if (scrubber != null) {
                        scrubber.setMax(duration);
                        scrubber.setEnabled(duration > 0);
                    }
                    if (totalLabel != null) totalLabel.setText(formatTime(duration));
                    mp.start();
                    playBadge.setVisibility(View.GONE);
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
                mediaPlayer.setOnCompletionListener(mp -> playBadge.setVisibility(View.VISIBLE));
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    log("inline video failed what=" + what + " extra=" + extra);
                    poster.setVisibility(View.VISIBLE);
                    playBadge.setVisibility(View.VISIBLE);
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
                    playBadge.setVisibility(View.VISIBLE);
                } else {
                    mediaPlayer.start();
                    playBadge.setVisibility(View.GONE);
                }
            } catch (IllegalStateException e) {
                // Still preparing; it starts on its own when ready.
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
            EXECUTOR.execute(() -> {
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
                } catch (java.io.IOException | RuntimeException e) {
                    transientError = true;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                Bitmap result = bitmap;
                boolean retryable = transientError;
                if (result != null) CACHE.put(url, result);
                MAIN.post(() -> {
                    if (result != null) callback.onLoaded(result);
                    else callback.onFailed(retryable);
                });
            });
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
        final List<MediaItem> media;
        final String repostedBy;
        final String replyingTo;
        final QuotedPost quote;
        final String age;
        final boolean sensitiveMedia;
        final String coverText;
        final Engagement engagement;
        // The live timeline item this card was read from, set by extract(). One-tap actions pass it
        // to X's post-actions service through the patch bridges; null-safe (actions fall back).
        Object item;

        private PostCard(String postId, String name, String handle, String avatarUrl, String text,
                         List<MediaItem> media, String repostedBy, String replyingTo,
                         QuotedPost quote, String age, boolean sensitiveMedia, String coverText,
                         Engagement engagement) {
            this.postId = postId;
            this.name = name;
            this.handle = handle;
            this.avatarUrl = avatarUrl;
            this.text = text;
            this.media = media;
            this.repostedBy = repostedBy;
            this.replyingTo = replyingTo;
            this.quote = quote;
            this.age = age;
            this.sensitiveMedia = sensitiveMedia;
            this.coverText = coverText;
            this.engagement = engagement;
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

        static List<PostCard> extract(List<?> items) {
            List<PostCard> cards = new ArrayList<>();
            for (Object item : items) {
                if (item == null) continue;
                try {
                    PostCard card = read(item.toString());
                    if (card != null) {
                        card.item = item;
                        cards.add(card);
                    }
                } catch (RuntimeException e) {
                    NewXLogger.printException(() -> LOG_PREFIX + "could not read a timeline item", e);
                }
            }
            return cards;
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
                repostedBy = outerAuthor == null ? null : ToStringParser.fieldValue(outerAuthor, "name");
            }

            List<MediaItem> media = mediaOf(shown);
            String text = postText(shown);
            if (text != null && !media.isEmpty()) text = stripTrailingMediaLink(text);
            if (text != null) text = expandLinks(text, shown);

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
                    NewXUtils.rawSourceDisplayName(value),
                    NewXUtils.rawSourceScreenName(value),
                    avatarOf(author),
                    text,
                    media,
                    repostedBy,
                    ToStringParser.fieldValue(shown, "repliedToUserScreenName"),
                    quoteOf(quoted, showSensitive),
                    age(ToStringParser.fieldValue(shown, "timestamp")),
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

        private static QuotedPost quoteOf(String quoted, boolean showSensitive) {
            if (quoted == null || !quoted.contains("text=")) return null;
            String author = ToStringParser.fieldValue(quoted, "author");
            List<MediaItem> media = mediaOf(quoted);
            String text = postText(quoted);
            if (text != null && !media.isEmpty()) text = stripTrailingMediaLink(text);
            if (text != null) text = expandLinks(text, quoted);
            if (!showSensitive) {
                String own = ownFields(quoted);
                String cover = coverText(own);
                if (cover != null) text = "\u26A0 " + cover;
                // Warned quote media is left out; the quote box still opens the quoted post.
                if (cover != null || hasBlur(own) || hasMediaWarning(quoted)) media = Collections.emptyList();
            }
            return new QuotedPost(
                    ToStringParser.fieldValue(quoted, "id"),
                    author == null ? null : ToStringParser.fieldValue(author, "name"),
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
                java.util.regex.Matcher size = java.util.regex.Pattern.compile("/(\\d{2,5})x(\\d{2,5})/").matcher(url);
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

        private static String age(String timestamp) {
            if (timestamp == null) return null;
            try {
                long seconds = Duration.between(Instant.parse(timestamp), Instant.now()).getSeconds();
                if (seconds < 60) return "now";
                if (seconds < 3600) return (seconds / 60) + "m";
                if (seconds < 86400) return (seconds / 3600) + "h";
                return (seconds / 86400) + "d";
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
