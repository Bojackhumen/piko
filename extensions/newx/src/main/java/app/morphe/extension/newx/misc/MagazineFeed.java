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

    // ---------- Opening things in X ----------

    /** Opens an x.com link inside X itself; X's deep-link router picks the screen. */
    private static void openInX(Context context, String url) {
        if (url == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage(context.getPackageName());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            NewXLogger.printException(() -> LOG_PREFIX + "could not open " + url, e);
        }
    }

    private static void sharePost(Context context, PostCard card) {
        String link = card.postLink();
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
        String link = card.postLink();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (link == null || clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Post link", link));
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show();
    }

    // ---------- Scroll container ----------

    private static final class MagazineView extends NestedScrollView {
        // One id per process so recreated AndroidView instances share saved scroll state.
        private static final int VIEW_STATE_ID = View.generateViewId();

        private final MasonryGrid grid;
        private final LoadingIndicatorView loadingIndicator;
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
            setItems(items);
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
            grid.setCards(cards);
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

        void setCards(List<PostCard> next) {
            boolean appendOnly = next.size() >= cards.size()
                    && next.subList(0, cards.size()).equals(cards);
            if (!appendOnly) {
                // Refresh or reorder: rebuild, so positions follow the new order.
                removeAllViews();
                columnOf.clear();
                for (PostCard card : next) addView(CardViews.build(getContext(), card));
            } else {
                for (int i = cards.size(); i < next.size(); i++) {
                    addView(CardViews.build(getContext(), next.get(i)));
                }
            }
            if (!appendOnly || next.size() != cards.size()) requestLayout();
            cards = next;
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

        static View build(Context context, PostCard card) {
            int pad = Theme.dpToPx(context, 14f);
            int ink = Theme.primaryText(context);
            int muted = Theme.secondaryText(context);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(rounded(Theme.surfaceContainer(context), Theme.dpToPx(context, CARD_RADIUS_DP)));
            root.setElevation(Theme.dpToPx(context, 2f));
            clipToRoundRect(root, Theme.dpToPx(context, CARD_RADIUS_DP));
            root.setClickable(true);
            root.setOnClickListener(view -> openInX(context, card.postLink()));
            root.setContentDescription(card.describe());

            if (card.repostedBy != null) {
                TextView contextLine = text(context, "\u21BB " + card.repostedBy + " reposted", 13f, muted);
                contextLine.setTypeface(Typeface.DEFAULT_BOLD);
                contextLine.setPadding(pad, pad - Theme.dpToPx(context, 2f), pad, Theme.dpToPx(context, 8f));
                root.addView(contextLine);
            }

            if (!card.media.isEmpty()) {
                root.addView(mediaBlock(context, card.media, card), matchWidth());
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

            if (card.text != null) {
                TextView text = text(context, card.text, 16f, ink);
                text.setLineSpacing(0f, 1.2f);
                text.setMaxLines(card.media.isEmpty() ? MAX_TEXT_LINES : MAX_TEXT_LINES_WITH_MEDIA);
                text.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams params = matchWidth();
                params.topMargin = Theme.dpToPx(context, 8f);
                body.addView(text, params);
            }

            if (card.quote != null) {
                LinearLayout.LayoutParams params = matchWidth();
                params.topMargin = Theme.dpToPx(context, 12f);
                body.addView(quoteBox(context, card.quote, ink, muted), params);
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
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                who.addView(name);
            }
            String sub = joinNonNull(card.handle == null ? null : "@" + card.handle, card.age);
            if (sub != null) {
                TextView handle = text(context, sub, 13f, muted);
                handle.setSingleLine(true);
                handle.setEllipsize(TextUtils.TruncateAt.END);
                who.addView(handle);
            }
            head.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View more = iconButton(context, "More", muted, "\u22EF", "ic_vector_overflow", "ic_vector_more");
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

        private static View actions(Context context, PostCard card, int muted) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = matchWidth();
            rowParams.topMargin = Theme.dpToPx(context, 6f);
            row.setLayoutParams(rowParams);

            View reply = iconButton(context, "Reply", muted, "\u21A9", "ic_vector_reply_stroke", "ic_vector_reply");
            reply.setOnClickListener(view -> openInX(context, card.intentLink("post", "in_reply_to")));
            View repost = iconButton(context, "Repost", muted, "\u21BB", "ic_vector_retweet_stroke", "ic_vector_retweet");
            repost.setOnClickListener(view -> openInX(context, card.intentLink("retweet", "tweet_id")));
            View like = iconButton(context, "Like", muted, "\u2661", "ic_vector_heart_stroke", "ic_vector_heart");
            like.setOnClickListener(view -> openInX(context, card.intentLink("like", "tweet_id")));
            View bookmark = iconButton(context, "Bookmark", muted, "\u2606", "ic_vector_bookmark_stroke", "ic_vector_bookmark");
            bookmark.setOnClickListener(view -> {
                // X has no bookmark link; its own bookmark button is on the post screen.
                Toast.makeText(context, "Bookmark it from the post", Toast.LENGTH_SHORT).show();
                openInX(context, card.postLink());
            });
            View share = iconButton(context, "Share", muted, "\u2197", "ic_vector_share_android", "ic_vector_share");
            share.setOnClickListener(view -> sharePost(context, card));

            // Reply, repost and like spread out; bookmark and share sit at the end, as on X.
            for (View button : new View[]{reply, repost, like}) {
                row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            row.addView(bookmark);
            row.addView(share);
            return row;
        }

        /**
         * A 40dp touch target with X's own icon when the app still ships it, else a text glyph.
         * The label is the accessibility description.
         */
        private static View iconButton(Context context, String label, int color, String glyph, String... iconNames) {
            int size = Theme.dpToPx(context, 40f);
            FrameLayout button = new FrameLayout(context);
            button.setMinimumWidth(size);
            button.setMinimumHeight(size);
            button.setContentDescription(label);
            button.setClickable(true);
            button.setFocusable(true);
            TypedValue ripple = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                button.setBackgroundResource(ripple.resourceId);
            }
            Drawable icon = findIcon(context, iconNames);
            View inner;
            if (icon != null) {
                ImageView image = new ImageView(context);
                image.setImageDrawable(icon);
                image.setImageTintList(ColorStateList.valueOf(color));
                int iconSize = Theme.dpToPx(context, 18f);
                button.addView(image, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER_VERTICAL | Gravity.START));
                inner = image;
            } else {
                TextView text = text(context, glyph, 17f, color);
                button.addView(text, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL | Gravity.START));
                inner = text;
            }
            inner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int horizontal = Theme.dpToPx(context, 6f);
            button.setPadding(horizontal, 0, horizontal, 0);
            return button;
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
                MediaThumbnailLoader.load(context, null, url, new MediaThumbnailLoader.Callback() {
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

        private static View quoteBox(Context context, QuotedPost quote, int ink, int muted) {
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable outline = new GradientDrawable();
            float radius = Theme.dpToPx(context, 12f);
            outline.setCornerRadius(radius);
            outline.setStroke(Math.max(1, Theme.dpToPx(context, 1f)), Theme.dividerColor(context));
            box.setBackground(outline);
            clipToRoundRect(box, radius);
            box.setClickable(true);
            box.setOnClickListener(view -> openInX(context, quote.link()));
            box.setContentDescription("Quoted post" + (quote.name == null ? "" : " by " + quote.name));

            if (!quote.media.isEmpty()) {
                box.addView(mediaBlock(context, quote.media.subList(0, 1), quote), matchWidth());
            }
            LinearLayout inner = new LinearLayout(context);
            inner.setOrientation(LinearLayout.VERTICAL);
            int pad = Theme.dpToPx(context, 10f);
            inner.setPadding(pad + Theme.dpToPx(context, 2f), pad, pad + Theme.dpToPx(context, 2f), pad);
            String who = joinNonNull(quote.name, quote.handle == null ? null : "@" + quote.handle);
            TextView author = text(context, who == null ? "Quoted post" : who, 13.5f, quote.name == null ? muted : ink);
            author.setTypeface(Typeface.DEFAULT_BOLD);
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

        /**
         * Up to four media tiles, as in the userscript: one row, or two rows of two for four
         * items. Each tile keeps its real shape; tiles in a row share one height.
         */
        private static View mediaBlock(Context context, List<MediaItem> items, LinkSource source) {
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
            for (int r = 0; r < rows.size(); r++) {
                MediaRow row = new MediaRow(context, gap);
                for (int index : rows.get(r)) {
                    MediaItem item = shown.get(index);
                    // X's photo viewer counts media positions from 1; videos open the player.
                    String path = item.video ? "video/1" : "photo/" + (index + 1);
                    row.addTile(context, item, view -> openInX(context, source.mediaLink(path)));
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
            return view;
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

        MediaRow(Context context, int gap) {
            super(context);
            this.gap = gap;
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
            MediaThumbnailLoader.load(context, mediumUrl(url), url, new MediaThumbnailLoader.Callback() {
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
            int used = 0;
            for (int i = 0; i < count; i++) {
                int tileWidth = i == count - 1 ? available - used : Math.round(aspects.get(i) * height);
                used += tileWidth;
                getChildAt(i).measure(
                        MeasureSpec.makeMeasureSpec(Math.max(1, tileWidth), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int left = 0;
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

    // ---------- Reading posts out of timeline items ----------

    private static String profileLink(String handle) {
        return handle == null ? null : "https://x.com/" + handle;
    }

    /** Anything with a post address that media tiles can link into. */
    private interface LinkSource {
        String mediaLink(String mediaPath);
    }

    private static final class MediaItem {
        final String url;
        final boolean video;
        final float aspect; // width / height from the model, or 0 when unknown

        MediaItem(String url, boolean video, float aspect) {
            this.url = url;
            this.video = video;
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

        private PostCard(String postId, String name, String handle, String avatarUrl, String text,
                         List<MediaItem> media, String repostedBy, String replyingTo,
                         QuotedPost quote, String age) {
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
                    if (card != null) cards.add(card);
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
                    quoteOf(quoted),
                    age(ToStringParser.fieldValue(shown, "timestamp"))
            );
        }

        private static QuotedPost quoteOf(String quoted) {
            if (quoted == null || !quoted.contains("text=")) return null;
            String author = ToStringParser.fieldValue(quoted, "author");
            List<MediaItem> media = mediaOf(quoted);
            String text = postText(quoted);
            if (text != null && !media.isEmpty()) text = stripTrailingMediaLink(text);
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
                items.add(new MediaItem(url, video, aspectOf(segment)));
            }
            return items;
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
