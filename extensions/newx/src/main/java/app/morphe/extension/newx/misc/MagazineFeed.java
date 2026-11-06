package app.morphe.extension.newx.misc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * setting is on. Paging reuses the gallery's bottom-paginator bridge, and a card tap invokes the
 * native item-click callback, so the post opens in X's own post-detail screen where replying,
 * liking and reposting work as usual.
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
        return context -> new MagazineView((Context) context, items, callback, itemClickCallback, topPaddingDp);
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
                magazineView.update(items, callback, itemClickCallback, topPaddingDp);
            }
            // AndroidView ignores the updater result; avoid the host's obfuscated Unit singleton.
            return null;
        };
    }

    private static void log(String message) {
        NewXLogger.printInfo(() -> LOG_PREFIX + message);
    }

    // ---------- Scroll container ----------

    private static final class MagazineView extends NestedScrollView {
        // One id per process so recreated AndroidView instances share saved scroll state.
        private static final int VIEW_STATE_ID = View.generateViewId();

        private final MasonryGrid grid;
        private final LoadingIndicatorView loadingIndicator;
        private Object callback;
        private Object itemClickCallback;
        private int currentItemCount = -1;
        private boolean loadMoreInFlight;
        private boolean paginationArmed;
        private int autoLoadItemCount = -1;
        private float downScreenTop = Float.NaN;

        MagazineView(
                Context context,
                List<?> items,
                Object callback,
                Object itemClickCallback,
                float topPaddingDp
        ) {
            // Same constructor shape the Photos gallery relies on in the target APK.
            super(context, null);
            setId(VIEW_STATE_ID);
            this.callback = callback;
            this.itemClickCallback = itemClickCallback;
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

        void update(List<?> items, Object callback, Object itemClickCallback, float topPaddingDp) {
            this.callback = callback;
            this.itemClickCallback = itemClickCallback;
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
            grid.setCards(cards, this::openPost);
            if (currentItemCount != next.size()) {
                int previous = currentItemCount;
                log("items=" + next.size() + " cards=" + cards.size() + " previous=" + previous);
                setLoadingMore(false);
            }
            currentItemCount = next.size();
        }

        /**
         * Opens the post, or one of its photos/videos, through X's own deep-link handling.
         *
         * <p>The timeline's item-click callback does nothing for home-timeline posts (tested on
         * 12.40.1: taps were swallowed), so cards use the same in-app deep link the Photos gallery
         * falls back to. The intent is pinned to this package, so X opens it in-app.
         */
        private void openPost(PostCard card, String mediaPath) {
            String target = card.link(mediaPath);
            if (target == null) {
                log("tap without a post id or author: " + card.postId);
                return;
            }
            Context context = getContext();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.setPackage(context.getPackageName());
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (RuntimeException e) {
                NewXLogger.printException(() -> LOG_PREFIX + "could not open " + target, e);
            }
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

    private interface CardClick {
        /** {@code mediaPath} is null for the post itself, else e.g. "photo/1" or "video/1". */
        void open(PostCard card, String mediaPath);
    }

    /** Each card goes into the currently shortest column, in timeline order. */
    private static final class MasonryGrid extends ViewGroup {
        private final int gap;
        private List<PostCard> cards = Collections.emptyList();
        private int[] cardColumn = new int[0];

        MasonryGrid(Context context) {
            super(context);
            gap = Theme.dpToPx(context, GAP_DP);
            setWillNotDraw(true);
        }

        void setCards(List<PostCard> next, CardClick click) {
            boolean appendOnly = next.size() >= cards.size()
                    && next.subList(0, cards.size()).equals(cards);
            if (!appendOnly) {
                // Refresh or reorder: rebuild from scratch. Cards are cheap compared to the
                // native post rows, and positions must follow the new order.
                removeAllViews();
                for (PostCard card : next) addView(CardViews.build(getContext(), card, click));
            } else {
                for (int i = 0; i < cards.size(); i++) {
                    // Same post, newer item object: keep the view, refresh the tap target.
                    PostCard card = next.get(i);
                    getChildAt(i).setOnClickListener(view -> click.open(card, null));
                }
                for (int i = cards.size(); i < next.size(); i++) {
                    addView(CardViews.build(getContext(), next.get(i), click));
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

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int columns = columnCount(width);
            int columnWidth = Math.max(1, (width - gap * (columns - 1)) / columns);
            int[] heights = new int[columns];
            cardColumn = new int[getChildCount()];
            int childWidthSpec = MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(childWidthSpec, childHeightSpec);
                int column = shortest(heights);
                cardColumn[i] = column;
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
            int columnWidth = Math.max(1, (width - gap * (columns - 1)) / columns);
            int[] tops = new int[columns];
            for (int i = 0; i < getChildCount() && i < cardColumn.length; i++) {
                View child = getChildAt(i);
                int column = cardColumn[i];
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

        static View build(Context context, PostCard card, CardClick click) {
            int pad = Theme.dpToPx(context, 14f);
            int ink = Theme.primaryText(context);
            int muted = Theme.secondaryText(context);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Theme.surfaceContainer(context));
            float radius = Theme.dpToPx(context, CARD_RADIUS_DP);
            background.setCornerRadius(radius);
            root.setBackground(background);
            root.setElevation(Theme.dpToPx(context, 2f));
            root.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            root.setClipToOutline(true);
            root.setClickable(true);
            root.setOnClickListener(view -> click.open(card, null));

            if (card.repostedBy != null) {
                TextView contextLine = text(context, "\u21BB " + card.repostedBy + " reposted", 13f, muted);
                contextLine.setTypeface(Typeface.DEFAULT_BOLD);
                contextLine.setPadding(pad, pad - Theme.dpToPx(context, 2f), pad, 0);
                root.addView(contextLine);
            }

            if (card.imageUrl != null) {
                View media = media(context, card);
                // Tapping the media opens X's own photo viewer or video player for it.
                media.setOnClickListener(view -> click.open(card, card.video ? "video/1" : "photo/1"));
                root.addView(media, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(pad, pad, pad, pad);

            if (card.name != null) {
                TextView name = text(context, card.name, 14.5f, ink);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                body.addView(name);
            }
            String sub = joinNonNull(card.handle == null ? null : "@" + card.handle, card.age);
            if (sub != null) {
                TextView handle = text(context, sub, 13f, muted);
                handle.setSingleLine(true);
                handle.setEllipsize(TextUtils.TruncateAt.END);
                body.addView(handle);
            }
            if (card.text != null) {
                TextView text = text(context, card.text, 16f, ink);
                text.setLineSpacing(0f, 1.2f);
                text.setMaxLines(card.imageUrl == null ? MAX_TEXT_LINES : MAX_TEXT_LINES_WITH_MEDIA);
                text.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = Theme.dpToPx(context, 8f);
                body.addView(text, params);
            }
            root.addView(body);
            return root;
        }

        private static View media(Context context, PostCard card) {
            FrameLayout frame = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    // Fixed 4:3 tile until media dimensions are read from the model.
                    int width = MeasureSpec.getSize(widthSpec);
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(width * 3 / 4, MeasureSpec.EXACTLY));
                }
            };
            frame.setBackgroundColor(Theme.dividerColor(context));
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag(card.imageUrl);
            frame.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (card.video) {
                TextView play = text(context, "\u25B6", 18f, Color.WHITE);
                play.setGravity(Gravity.CENTER);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(0x8C000000);
                play.setBackground(circle);
                int size = Theme.dpToPx(context, 48f);
                frame.addView(play, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            }
            String url = card.imageUrl;
            MediaThumbnailLoader.load(context, mediumUrl(url), url, new MediaThumbnailLoader.Callback() {
                @Override
                public void onLoaded(Bitmap bitmap) {
                    if (url.equals(image.getTag()) && !bitmap.isRecycled()) image.setImageBitmap(bitmap);
                }

                @Override
                public void onFailed(boolean transientError) {
                    // The grey tile stays; tapping still opens the post with its native media.
                }
            });
            return frame;
        }

        private static TextView text(Context context, String value, float sp, int color) {
            TextView view = new TextView(context);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(color);
            view.setIncludeFontPadding(false);
            return view;
        }

        private static String joinNonNull(String a, String b) {
            if (a == null) return b;
            if (b == null) return a;
            return a + " \u00B7 " + b;
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

    /** What one card shows, read from the timeline item's model toString. */
    private static final class PostCard {
        final Object timelineItem;
        final String postId;
        final String name;
        final String handle;
        final String text;
        final String imageUrl;
        final boolean video;
        final String repostedBy;
        final String age;

        private PostCard(Object timelineItem, String postId, String name, String handle, String text,
                         String imageUrl, boolean video, String repostedBy, String age) {
            this.timelineItem = timelineItem;
            this.postId = postId;
            this.name = name;
            this.handle = handle;
            this.text = text;
            this.imageUrl = imageUrl;
            this.video = video;
            this.repostedBy = repostedBy;
            this.age = age;
        }

        /** In-app link to this post, or to one of its media when {@code mediaPath} is given. */
        String link(String mediaPath) {
            if (postId == null || handle == null) return null;
            String base = "https://x.com/" + handle + "/status/" + postId;
            return mediaPath == null ? base : base + "/" + mediaPath;
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
                    PostCard card = read(item);
                    if (card != null) cards.add(card);
                } catch (RuntimeException e) {
                    NewXLogger.printException(() -> LOG_PREFIX + "could not read a timeline item", e);
                }
            }
            return cards;
        }

        private static PostCard read(Object item) {
            String value = item.toString();
            // Only posts become cards; cursors, prompts and "Who to follow" modules are skipped.
            int canonical = value.indexOf("CanonicalPost(");
            if (canonical < 0) return null;

            String reposted = ToStringParser.fieldValue(value, "rePostedPost");
            String shown = reposted != null && reposted.contains("text=") ? reposted : value.substring(canonical);
            String repostedBy = null;
            if (reposted != null) {
                String outerAuthor = ToStringParser.fieldValue(value.substring(canonical), "author");
                repostedBy = outerAuthor == null ? null : ToStringParser.fieldValue(outerAuthor, "name");
            }

            String media = firstMedia(shown);
            boolean video = media != null && (media.startsWith("MediaContentVideo(") || media.startsWith("MediaContentGif("));
            String imageUrl = media == null ? null
                    : ToStringParser.fieldValue(media, media.startsWith("MediaContentGif(") ? "previewUrl" : "imageUrl");
            if (imageUrl != null && !NewXUtils.isHttpUrl(imageUrl)) imageUrl = null;

            String text = postText(shown);
            if (text != null && imageUrl != null) text = stripTrailingMediaLink(text);

            return new PostCard(
                    item,
                    NewXUtils.rawSourcePostId(value),
                    NewXUtils.rawSourceDisplayName(value),
                    NewXUtils.rawSourceScreenName(value),
                    text,
                    imageUrl,
                    video,
                    repostedBy,
                    age(ToStringParser.fieldValue(shown, "timestamp"))
            );
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

        private static String firstMedia(String post) {
            String list = ToStringParser.fieldValue(post, "media");
            if (list == null) return null;
            int best = -1;
            for (String marker : new String[]{"MediaContentImage(", "MediaContentVideo(", "MediaContentGif("}) {
                int found = list.indexOf(marker);
                if (found >= 0 && (best < 0 || found < best)) best = found;
            }
            return best < 0 ? null : list.substring(best);
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
