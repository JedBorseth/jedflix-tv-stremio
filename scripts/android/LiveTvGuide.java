package com.jedflix.tv;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

public final class LiveTvGuide {
    private static final String TAG = "JedflixLiveTv";
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final int UPCOMING_CARD_COUNT = 4; // 1 Next + 3 Following = 4 upcoming after Current (5 episode cards total)

    private static final int COLOR_BG = 0xFF0C0C0C;
    private static final int COLOR_SURFACE = 0xFF181818;
    private static final int COLOR_SURFACE_FOCUSED = 0xFF242424;
    private static final int COLOR_STROKE = 0x26FFFFFF;
    private static final int COLOR_STROKE_FOCUSED = 0xFFFFFFFF;
    private static final int COLOR_ACCENT_RED = 0xFFE53945;
    private static final int COLOR_BADGE_DARK = 0xCC181818;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF4F5F8;
    private static final int COLOR_TEXT_SECONDARY = 0xFFB3B3B3;
    private static final int COLOR_TEXT_MUTED = 0xFF8C8C8C;
    private static final int COLOR_PROGRESS_TRACK = 0x4DFFFFFF;

    private static final WeakHashMap<Object, Controller> CONTROLLERS = new WeakHashMap<Object, Controller>();
    private static String rememberedChannelId = "office";

    private static boolean sBindingMethodsResolved;
    private static Method sLoadImageMethod;
    private static Method sLoadImageFadeMethod;

    private LiveTvGuide() {
    }

    public static View create(Object fragment, Context suppliedContext) {
        Context context = resolveContext(fragment, suppliedContext);
        if (context == null) {
            return null;
        }
        Controller controller = new Controller(fragment, context);
        synchronized (CONTROLLERS) {
            CONTROLLERS.put(fragment, controller);
        }
        return controller.root;
    }

    public static void attach(Object fragment, View view) {
        Controller controller = getController(fragment);
        if (controller != null) {
            controller.onAttach();
        }
    }

    public static void resume(Object fragment) {
        Controller controller = getController(fragment);
        if (controller != null) {
            controller.onResume();
        }
    }

    public static void pause(Object fragment) {
        Controller controller = getController(fragment);
        if (controller != null) {
            controller.onPause();
        }
    }

    public static void destroy(Object fragment) {
        Controller controller;
        synchronized (CONTROLLERS) {
            controller = CONTROLLERS.remove(fragment);
        }
        if (controller != null) {
            controller.onDestroy();
        }
    }

    private static Controller getController(Object fragment) {
        synchronized (CONTROLLERS) {
            return CONTROLLERS.get(fragment);
        }
    }

    private static Context resolveContext(Object fragment, Context suppliedContext) {
        if (suppliedContext != null) {
            return suppliedContext;
        }
        if (fragment == null) {
            return null;
        }
        Object context = invokeNoArg(fragment, "requireContext");
        if (context instanceof Context) {
            return (Context) context;
        }
        context = invokeNoArg(fragment, "getContext");
        if (context instanceof Context) {
            return (Context) context;
        }
        Object activity = invokeNoArg(fragment, "getActivity");
        if (activity instanceof Context) {
            return (Context) activity;
        }
        return null;
    }

    static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Navigation graph handle for the guide fragment (reflection: no compile dep). */
    static Object findNavController(Object fragment) {
        if (fragment == null) {
            return null;
        }
        try {
            Class<?> fragmentType = Class.forName("androidx.fragment.app.Fragment");
            return Class.forName("androidx.navigation.fragment.NavHostFragment")
                .getMethod("findNavController", fragmentType)
                .invoke(null, fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void loadImageAsync(ImageView imageView, String url, boolean fade) {
        if (imageView == null) {
            return;
        }
        String cleanUrl = url != null ? url.trim() : "";
        if (cleanUrl.isEmpty()) {
            imageView.setImageDrawable(null);
            return;
        }
        resolveBindingMethods(imageView.getContext());
        try {
            if (fade && sLoadImageFadeMethod != null) {
                sLoadImageFadeMethod.invoke(null, imageView, cleanUrl, Boolean.TRUE);
                return;
            }
            if (sLoadImageMethod != null) {
                sLoadImageMethod.invoke(null, imageView, cleanUrl);
                return;
            }
        } catch (Throwable error) {
            Log.w(TAG, "BindingUtils image load failed for " + cleanUrl, error);
        }
    }

    private static synchronized void resolveBindingMethods(Context context) {
        if (sBindingMethodsResolved) {
            return;
        }
        sBindingMethodsResolved = true;
        try {
            ClassLoader loader = context != null ? context.getClassLoader() : LiveTvGuide.class.getClassLoader();
            Class<?> cls = Class.forName("com.stremio.tv.util.BindingUtils", false, loader);
            try {
                sLoadImageMethod = cls.getMethod("loadImage", ImageView.class, String.class);
                sLoadImageMethod.setAccessible(true);
            } catch (Throwable ignored) {
            }
            try {
                sLoadImageFadeMethod = cls.getMethod("loadImageWithFadeInOut", ImageView.class, String.class, boolean.class);
                sLoadImageFadeMethod.setAccessible(true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to resolve com.stremio.tv.util.BindingUtils", error);
        }
    }

    private static final class Controller implements Runnable, LiveTvPlayback.Listener {
        final Object fragment;
        final Context context;
        final Handler handler = new Handler(Looper.getMainLooper());
        final FrameLayout root;
        final List<ChannelRowHolder> rowHolders = new ArrayList<ChannelRowHolder>();

        // Hero views
        View heroContainer;
        ImageView heroBackground;
        TextView heroTitle;
        TextView heroBadge;
        TextView heroEpisodeMeta;
        TextView heroChannelName;
        TextView heroDescription;
        TextView heroStatusLine;
        String lastHeroImageUrl = "";

        // Focus & selection state
        int selectedChannelIndex = 0;
        int selectedColumnIndex = 0; // 0 = channel tile, 1 = current, 2 = next, 3..5 = upcoming
        int revealToken = 0;
        int revealedSeason = -1;
        boolean initialFocusRequested;
        boolean running;
        boolean tuning;
        String playbackStatusMessage = "";

        List<LiveTvSchedule.Channel> channels = Collections.emptyList();

        Controller(Object fragment, Context context) {
            this.fragment = fragment;
            this.context = context;
            this.root = buildUi();
            loadScheduleAndBuildRows();
            applyAppFont();
            refreshGuide(LiveTv.now(), true);
        }

        private void applyAppFont() {
            Resources resources = context.getResources();
            int id = resources.getIdentifier("plusjakartasans", "font", context.getPackageName());
            if (id == 0) return;
            try { applyFont(root, resources.getFont(id)); }
            catch (Resources.NotFoundException ignored) {}
        }

        private void applyFont(View view, android.graphics.Typeface font) {
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                int style = text.getTypeface() != null ? text.getTypeface().getStyle() : 0;
                text.setTypeface(font, style);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) applyFont(group.getChildAt(i), font);
            }
        }

        private FrameLayout buildUi() {
            FrameLayout container = new FrameLayout(context);
            container.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            container.setBackgroundColor(COLOR_BG);
            container.setClipChildren(false);
            container.setClipToPadding(false);

            int sideMenuMargin = getDimenPx("side_menu_content_margin", 92);

            LinearLayout mainColumn = new LinearLayout(context);
            mainColumn.setOrientation(LinearLayout.VERTICAL);
            mainColumn.setClipChildren(true);
            mainColumn.setClipToPadding(false);
            FrameLayout.LayoutParams mainParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            mainParams.leftMargin = sideMenuMargin;
            container.addView(mainColumn, mainParams);

            heroContainer = buildHeroSection();
            LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(248)
            );
            mainColumn.addView(heroContainer, heroParams);

            ScrollView verticalScroll = new ScrollView(context);
            verticalScroll.setFillViewport(true);
            verticalScroll.setVerticalScrollBarEnabled(false);
            verticalScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            verticalScroll.setClipChildren(true);
            verticalScroll.setClipToPadding(true);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            );
            mainColumn.addView(verticalScroll, scrollParams);

            int contentStart = getDimenPx("content_padding_start", 30);
            LinearLayout rowsContainer = new LinearLayout(context);
            rowsContainer.setId(android.R.id.list);
            rowsContainer.setOrientation(LinearLayout.VERTICAL);
            rowsContainer.setClipChildren(false);
            rowsContainer.setClipToPadding(false);
            rowsContainer.setPadding(contentStart, dp(14), dp(28), dp(38));
            verticalScroll.addView(rowsContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            return container;
        }

        private View buildHeroSection() {
            View inflated = tryInflateNativeHero();
            if (inflated != null) {
                return inflated;
            }
            return buildFallbackHeroSection();
        }

        private View tryInflateNativeHero() {
            try {
                Resources res = context.getResources();
                String pkg = context.getPackageName();
                int layoutId = res.getIdentifier("fragment_meta_preview", "layout", pkg);
                if (layoutId == 0) {
                    return null;
                }
                View view = LayoutInflater.from(context).inflate(layoutId, null, false);
                if (view instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) view;
                    vg.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                }
                view.setFocusable(false);

                int bgId = res.getIdentifier("meta_preview_background", "id", pkg);
                int nameId = res.getIdentifier("meta_preview_name", "id", pkg);
                int runtimeId = res.getIdentifier("meta_preview_runtime", "id", pkg);
                int yearId = res.getIdentifier("meta_preview_year", "id", pkg);
                int ratingId = res.getIdentifier("meta_preview_rating", "id", pkg);
                int ratingIconId = res.getIdentifier("meta_preview_rating_icon", "id", pkg);
                int genresId = res.getIdentifier("meta_preview_genres", "id", pkg);
                int descId = res.getIdentifier("meta_preview_description", "id", pkg);
                int castId = res.getIdentifier("meta_preview_cast", "id", pkg);

                heroBackground = bgId != 0 ? (ImageView) view.findViewById(bgId) : null;
                heroTitle = nameId != 0 ? (TextView) view.findViewById(nameId) : null;
                heroBadge = runtimeId != 0 ? (TextView) view.findViewById(runtimeId) : null;
                heroEpisodeMeta = yearId != 0 ? (TextView) view.findViewById(yearId) : null;
                heroChannelName = genresId != 0 ? (TextView) view.findViewById(genresId) : null;
                heroDescription = descId != 0 ? (TextView) view.findViewById(descId) : null;
                heroStatusLine = castId != 0 ? (TextView) view.findViewById(castId) : null;

                if (heroBackground == null || heroTitle == null || heroDescription == null) {
                    return null;
                }

                heroBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
                heroBackground.setBackgroundColor(COLOR_SURFACE);

                // Hide rating cluster completely
                View ratingText = ratingId != 0 ? view.findViewById(ratingId) : null;
                View ratingIcon = ratingIconId != 0 ? view.findViewById(ratingIconId) : null;
                if (ratingText != null) {
                    ratingText.setVisibility(View.GONE);
                    ViewParent parent = ratingText.getParent();
                    if (parent instanceof View && (heroBadge == null || parent != heroBadge.getParent())) {
                        ((View) parent).setVisibility(View.GONE);
                    }
                }
                if (ratingIcon != null) {
                    ratingIcon.setVisibility(View.GONE);
                }

                heroTitle.setMaxLines(2);
                heroTitle.setEllipsize(TextUtils.TruncateAt.END);

                if (heroBadge != null) {
                    heroBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                    heroBadge.setTextColor(Color.WHITE);
                    heroBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
                }

                if (heroEpisodeMeta != null) {
                    heroEpisodeMeta.setTextColor(COLOR_TEXT_PRIMARY);
                }

                if (heroChannelName != null) {
                    heroChannelName.setTextColor(COLOR_TEXT_SECONDARY);
                }

                heroDescription.setMaxLines(3);
                heroDescription.setEllipsize(TextUtils.TruncateAt.END);

                if (heroStatusLine != null) {
                    heroStatusLine.setVisibility(View.GONE);
                    heroStatusLine.setMaxLines(1);
                    heroStatusLine.setEllipsize(TextUtils.TruncateAt.END);
                }

                return view;
            } catch (Throwable error) {
                Log.w(TAG, "Failed to inflate fragment_meta_preview; using programmatic hero", error);
                return null;
            }
        }

        private View buildFallbackHeroSection() {
            FrameLayout hero = new FrameLayout(context);
            hero.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            hero.setFocusable(false);

            FrameLayout imageFrame = new FrameLayout(context);
            FrameLayout.LayoutParams imageFrameParams = new FrameLayout.LayoutParams(
                dp(620),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.TOP
            );
            hero.addView(imageFrame, imageFrameParams);

            heroBackground = new ImageView(context);
            heroBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
            heroBackground.setBackgroundColor(COLOR_SURFACE);
            imageFrame.addView(heroBackground, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            View leftGradient = new View(context);
            GradientDrawable leftFade = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { COLOR_BG, 0xD90C0C0C, 0x400C0C0C, 0x000C0C0C }
            );
            leftGradient.setBackground(leftFade);
            imageFrame.addView(leftGradient, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            View bottomGradient = new View(context);
            GradientDrawable bottomFade = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] { COLOR_BG, 0xB30C0C0C, 0x000C0C0C }
            );
            bottomGradient.setBackground(bottomFade);
            imageFrame.addView(bottomGradient, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            int contentStart = getDimenPx("content_padding_start", 30);
            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setPadding(contentStart, contentStart, dp(24), dp(14));
            FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                dp(460),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.TOP
            );
            hero.addView(textColumn, textParams);

            heroTitle = new TextView(context);
            applyStyleByName(heroTitle, "TextAppearance.Title.Large");
            heroTitle.setTextColor(COLOR_TEXT_PRIMARY);
            heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            heroTitle.setMaxLines(2);
            heroTitle.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(heroTitle);

            LinearLayout metaRow = new LinearLayout(context);
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            metaParams.topMargin = dp(12);
            textColumn.addView(metaRow, metaParams);

            heroBadge = new TextView(context);
            applyStyleByName(heroBadge, "TextAppearance.Label");
            heroBadge.setTextColor(Color.WHITE);
            heroBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            heroBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
            metaRow.addView(heroBadge);

            heroEpisodeMeta = new TextView(context);
            applyStyleByName(heroEpisodeMeta, "TextAppearance.Label");
            heroEpisodeMeta.setTextColor(COLOR_TEXT_PRIMARY);
            heroEpisodeMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams epParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            epParams.leftMargin = dp(16);
            metaRow.addView(heroEpisodeMeta, epParams);

            heroChannelName = new TextView(context);
            applyStyleByName(heroChannelName, "TextAppearance.Label");
            heroChannelName.setTextColor(COLOR_TEXT_SECONDARY);
            heroChannelName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams chParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            chParams.leftMargin = dp(16);
            metaRow.addView(heroChannelName, chParams);

            heroDescription = new TextView(context);
            heroDescription.setTextColor(COLOR_TEXT_SECONDARY);
            heroDescription.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            heroDescription.setMaxLines(3);
            heroDescription.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            descParams.topMargin = dp(12);
            textColumn.addView(heroDescription, descParams);

            heroStatusLine = new TextView(context);
            heroStatusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            heroStatusLine.setMaxLines(1);
            heroStatusLine.setEllipsize(TextUtils.TruncateAt.END);
            heroStatusLine.setVisibility(View.GONE);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            statusParams.topMargin = dp(8);
            textColumn.addView(heroStatusLine, statusParams);

            return hero;
        }

        private void loadScheduleAndBuildRows() {
            LinearLayout rowsContainer = (LinearLayout) root.findViewById(android.R.id.list);
            if (rowsContainer == null) {
                return;
            }
            rowsContainer.removeAllViews();
            rowHolders.clear();

            try {
                LiveTvSchedule schedule = LiveTv.schedule(context);
                channels = schedule != null ? schedule.channels() : Collections.<LiveTvSchedule.Channel>emptyList();
            } catch (Throwable error) {
                Log.e(TAG, "Failed to load Live TV schedule", error);
                channels = Collections.emptyList();
            }

            if (channels.isEmpty()) {
                TextView emptyView = new TextView(context);
                emptyView.setText("No Live TV channels available.");
                emptyView.setTextColor(COLOR_TEXT_SECONDARY);
                emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                rowsContainer.addView(emptyView);
                return;
            }

            for (int rowIndex = 0; rowIndex < channels.size(); rowIndex++) {
                LiveTvSchedule.Channel channel = channels.get(rowIndex);
                if (channel.id.equals(rememberedChannelId)) selectedChannelIndex = rowIndex;
                ChannelRowHolder holder = buildChannelRow(rowIndex, channel);
                rowHolders.add(holder);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                if (rowIndex > 0) {
                    rowParams.topMargin = dp(18);
                }
                rowsContainer.addView(holder.rowContainer, rowParams);
            }

            wireFocusGraph();
        }

        private ChannelRowHolder buildChannelRow(final int rowIndex, final LiveTvSchedule.Channel channel) {
            final ChannelRowHolder holder = new ChannelRowHolder(rowIndex, channel);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            holder.rowContainer = row;

            // 1. Tall narrow Channel Tile at the left (sole clickable tune target).
            // The channel poster fills the tile; LIVE/name/action sit on a dark
            // scrim so the text stays legible over any artwork.
            final FrameLayout channelTile = new FrameLayout(context);
            channelTile.setId(View.generateViewId());
            channelTile.setFocusable(true);
            channelTile.setFocusableInTouchMode(true);
            channelTile.setClickable(true);
            channelTile.setClipToOutline(true);
            holder.channelTile = channelTile;

            holder.artworkUrl = channel.thumbnail != null ? channel.thumbnail.trim() : "";
            ImageView artworkView = new ImageView(context);
            artworkView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            artworkView.setPadding(dp(4), dp(4), dp(4), dp(4));
            artworkView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            channelTile.addView(artworkView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            View scrim = new View(context);
            GradientDrawable scrimDrawable = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] { 0xFF0B0B0B, 0xD90B0B0B, 0x400B0B0B, 0x00000000 }
            );
            scrim.setBackground(scrimDrawable);
            channelTile.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            LinearLayout tileContent = new LinearLayout(context);
            tileContent.setOrientation(LinearLayout.VERTICAL);
            tileContent.setGravity(Gravity.CENTER_VERTICAL);
            tileContent.setPadding(dp(10), dp(12), dp(10), dp(12));
            channelTile.addView(tileContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));

            if (holder.artworkUrl.isEmpty()) {
                artworkView.setVisibility(View.GONE);
                scrim.setVisibility(View.GONE);
            } else {
                loadImageAsync(artworkView, holder.artworkUrl, true);
            }
            styleChannelTile(holder, false);

            TextView livePill = new TextView(context);
            applyStyleByName(livePill, "TextAppearance.Label");
            livePill.setText("LIVE");
            livePill.setTextColor(Color.WHITE);
            livePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            livePill.setPadding(dp(6), dp(2), dp(6), dp(2));
            livePill.setBackground(makeRoundedDrawable(COLOR_ACCENT_RED, dp(4), 0, 0));
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tileContent.addView(livePill, pillParams);

            View tileSpacer = new View(context);
            tileContent.addView(tileSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            ));

            TextView channelNameView = new TextView(context);
            applyStyleByName(channelNameView, "TextAppearance.Label");
            channelNameView.setText(channel.name);
            channelNameView.setTextColor(COLOR_TEXT_PRIMARY);
            channelNameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            channelNameView.setMaxLines(2);
            channelNameView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            channelNameView.setGravity(Gravity.BOTTOM);
            tileContent.addView(channelNameView, nameParams);

            TextView channelActionView = new TextView(context);
            channelActionView.setText("Press OK to tune");
            channelActionView.setTextColor(COLOR_TEXT_SECONDARY);
            channelActionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            channelActionView.setMaxLines(2);
            channelActionView.setEllipsize(TextUtils.TruncateAt.END);
            tileContent.addView(channelActionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            holder.channelNameView = channelNameView;
            holder.channelActionView = channelActionView;

            channelTile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    styleChannelTile(holder, hasFocus);
                    updateChannelActionLabel(holder, hasFocus);
                    if (hasFocus) {
                        selectedChannelIndex = rowIndex;
                        selectedColumnIndex = 0;
                        rememberedChannelId = channel.id;
                        holder.scrollView.smoothScrollTo(0, 0);
                        updateHeroForSelection(LiveTv.now());
                    }
                }
            });

            channelTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tuneChannel(channel);
                }
            });

            channelTile.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (isActivateKey(keyCode)) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !tuning) {
                            tuneChannel(channel);
                        }
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        View sideMenu = findSideMenuTarget(v);
                        if (sideMenu != null) {
                            sideMenu.requestFocus();
                            return true;
                        }
                    }
                    return false;
                }
            });

            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(122), dp(172));
            row.addView(channelTile, tileParams);

            // 2. HorizontalScrollView with landscape episode thumbnail cards
            HorizontalScrollView hScroll = new HorizontalScrollView(context);
            hScroll.setHorizontalScrollBarEnabled(false);
            hScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hScroll.setFocusable(false);
            hScroll.setFocusableInTouchMode(false);
            hScroll.setClipChildren(true);
            hScroll.setClipToPadding(true);
            holder.scrollView = hScroll;

            LinearLayout strip = new LinearLayout(context);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(dp(16), 0, dp(24), 0);
            hScroll.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            int totalEpisodeCards = 1 + UPCOMING_CARD_COUNT;
            for (int cardIdx = 0; cardIdx < totalEpisodeCards; cardIdx++) {
                final int columnIndex = cardIdx + 1;
                final EpisodeCardHolder cardHolder = buildEpisodeCard(holder, rowIndex, columnIndex);
                holder.episodeCards.add(cardHolder);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    dp(192),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                if (cardIdx > 0) {
                    cardParams.leftMargin = dp(16);
                }
                strip.addView(cardHolder.cardRoot, cardParams);
            }

            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            );
            row.addView(hScroll, scrollParams);

            return holder;
        }

        private EpisodeCardHolder buildEpisodeCard(
            final ChannelRowHolder rowHolder,
            final int rowIndex,
            final int columnIndex
        ) {
            final EpisodeCardHolder card = new EpisodeCardHolder(columnIndex);

            LinearLayout cardRoot = new LinearLayout(context);
            cardRoot.setId(View.generateViewId());
            cardRoot.setOrientation(LinearLayout.VERTICAL);
            cardRoot.setFocusable(true);
            cardRoot.setFocusableInTouchMode(true);
            cardRoot.setClickable(true);
            cardRoot.setClipChildren(false);
            cardRoot.setClipToPadding(false);
            card.cardRoot = cardRoot;

            final FrameLayout thumbContainer = new FrameLayout(context);
            final int cornerRadiusPx = getDimenPx("cardViewCornerRadius", 10);
            thumbContainer.setBackground(makeRoundedDrawable(COLOR_SURFACE, cornerRadiusPx, dp(2), COLOR_STROKE));
            thumbContainer.setClipToOutline(true);
            thumbContainer.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
                }
            });
            card.thumbContainer = thumbContainer;

            TextView fallbackLabel = new TextView(context);
            applyStyleByName(fallbackLabel, "TextAppearance.Label");
            fallbackLabel.setTextColor(0x55FFFFFF);
            fallbackLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            fallbackLabel.setGravity(Gravity.CENTER);
            fallbackLabel.setPadding(dp(8), dp(8), dp(8), dp(8));
            thumbContainer.addView(fallbackLabel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            card.fallbackLabel = fallbackLabel;

            ImageView imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbContainer.addView(imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            card.imageView = imageView;

            View thumbScrim = new View(context);
            thumbScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] { 0x80000000, 0x00000000 }
            ));
            thumbContainer.addView(thumbScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(32),
                Gravity.BOTTOM
            ));

            TextView badgeView = new TextView(context);
            applyStyleByName(badgeView, "TextAppearance.Label");
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.0f);
            badgeView.setTextColor(Color.WHITE);
            badgeView.setPadding(dp(6), dp(2), dp(6), dp(2));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
            );
            badgeParams.leftMargin = dp(8);
            badgeParams.topMargin = dp(8);
            thumbContainer.addView(badgeView, badgeParams);
            card.badgeView = badgeView;

            FrameLayout progressTrack = new FrameLayout(context);
            progressTrack.setBackgroundColor(COLOR_PROGRESS_TRACK);
            FrameLayout.LayoutParams trackParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4),
                Gravity.BOTTOM
            );
            View progressFill = new View(context);
            progressFill.setBackgroundColor(COLOR_ACCENT_RED);
            progressTrack.addView(progressFill, new FrameLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
            ));
            thumbContainer.addView(progressTrack, trackParams);
            card.progressTrack = progressTrack;
            card.progressFill = progressFill;

            View borderOverlay = new View(context);
            borderOverlay.setBackground(makeRoundedDrawable(Color.TRANSPARENT, cornerRadiusPx, dp(2), COLOR_STROKE));
            thumbContainer.addView(borderOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            card.borderOverlay = borderOverlay;

            cardRoot.addView(thumbContainer, new LinearLayout.LayoutParams(dp(192), dp(108)));

            TextView titleView = new TextView(context);
            applyStyleByName(titleView, "TextAppearance.Label");
            titleView.setTextColor(COLOR_TEXT_PRIMARY);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            titleParams.topMargin = dp(6);
            titleParams.leftMargin = dp(2);
            titleParams.rightMargin = dp(2);
            cardRoot.addView(titleView, titleParams);
            card.titleView = titleView;

            TextView subtitleView = new TextView(context);
            subtitleView.setTextColor(COLOR_TEXT_MUTED);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.0f);
            subtitleView.setMaxLines(1);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subParams.topMargin = dp(1);
            subParams.leftMargin = dp(2);
            subParams.rightMargin = dp(2);
            cardRoot.addView(subtitleView, subParams);
            card.subtitleView = subtitleView;

            cardRoot.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    styleEpisodeCard(card, hasFocus, cornerRadiusPx);
                    if (hasFocus) {
                        selectedChannelIndex = rowIndex;
                        selectedColumnIndex = columnIndex;
                        rememberedChannelId = rowHolder.channel.id;
                        ensureCardVisible(rowHolder.scrollView, v);
                        updateHeroForSelection(LiveTv.now());
                    }
                }
            });

            // Activation opens this program in the normal details screen. For a
            // series episode the video id is passed through so the details screen
            // selects that season and highlights the exact episode.
            cardRoot.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (isActivateKey(keyCode)) {
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            openDetails(card.boundSlot);
                        }
                        return true;
                    }
                    return false;
                }
            });
            cardRoot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openDetails(card.boundSlot);
                }
            });

            styleEpisodeCard(card, false, cornerRadiusPx);
            return card;
        }

        private void wireFocusGraph() {
            int rowCount = rowHolders.size();
            for (int r = 0; r < rowCount; r++) {
                ChannelRowHolder row = rowHolders.get(r);
                ChannelRowHolder prevRow = r > 0 ? rowHolders.get(r - 1) : null;
                ChannelRowHolder nextRow = r + 1 < rowCount ? rowHolders.get(r + 1) : null;

                View tile = row.channelTile;
                tile.setNextFocusUpId(prevRow != null ? prevRow.channelTile.getId() : tile.getId());
                tile.setNextFocusDownId(nextRow != null ? nextRow.channelTile.getId() : tile.getId());
                if (!row.episodeCards.isEmpty()) {
                    tile.setNextFocusRightId(row.episodeCards.get(0).cardRoot.getId());
                }

                int cardCount = row.episodeCards.size();
                for (int c = 0; c < cardCount; c++) {
                    View cardView = row.episodeCards.get(c).cardRoot;
                    View leftTarget = c == 0 ? tile : row.episodeCards.get(c - 1).cardRoot;
                    View rightTarget = c + 1 < cardCount ? row.episodeCards.get(c + 1).cardRoot : cardView;
                    View upTarget = prevRow != null && c < prevRow.episodeCards.size()
                        ? prevRow.episodeCards.get(c).cardRoot
                        : cardView;
                    View downTarget = nextRow != null && c < nextRow.episodeCards.size()
                        ? nextRow.episodeCards.get(c).cardRoot
                        : cardView;

                    cardView.setNextFocusLeftId(leftTarget.getId());
                    cardView.setNextFocusRightId(rightTarget.getId());
                    cardView.setNextFocusUpId(upTarget.getId());
                    cardView.setNextFocusDownId(downTarget.getId());
                }
            }
        }

        private void ensureCardVisible(HorizontalScrollView scrollView, View cardView) {
            if (scrollView == null || cardView == null) {
                return;
            }
            int cardLeft = cardView.getLeft();
            int cardRight = cardView.getRight();
            int scrollX = scrollView.getScrollX();
            int width = scrollView.getWidth();
            if (width <= 0) {
                return;
            }
            int pad = dp(24);
            if (cardLeft - pad < scrollX) {
                scrollView.smoothScrollTo(Math.max(0, cardLeft - pad), 0);
            } else if (cardRight + pad > scrollX + width) {
                scrollView.smoothScrollTo(cardRight + pad - width, 0);
            }
        }

        void onAttach() {
            requestInitialFocusIfNeeded();
        }

        void onResume() {
            running = true;
            // Any pending "focus the episode picker" task belongs to a details
            // screen we have already left.
            revealToken++;
            handler.removeCallbacks(this);
            refreshGuide(LiveTv.now(), false);
            requestInitialFocusIfNeeded();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }

        void onPause() {
            running = false;
            handler.removeCallbacks(this);
            LiveTvPlayback.cancelTune(fragment);
            tuning = false;
            playbackStatusMessage = "";
        }

        void onDestroy() {
            running = false;
            handler.removeCallbacks(this);
            LiveTvPlayback.cancelTune(fragment);
        }

        private void requestInitialFocusIfNeeded() {
            if (initialFocusRequested || rowHolders.isEmpty()) {
                return;
            }
            final View firstTile = rowHolders.get(Math.min(selectedChannelIndex, rowHolders.size() - 1)).channelTile;
            if (firstTile != null) {
                initialFocusRequested = true;
                firstTile.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!root.hasFocus()) {
                            firstTile.requestFocus();
                        }
                    }
                });
            }
        }

        @Override
        public void run() {
            if (!running) {
                return;
            }
            refreshGuide(LiveTv.now(), false);
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }

        void refreshGuide(long nowEpochMs, boolean forceImageReload) {
            for (int r = 0; r < rowHolders.size(); r++) {
                ChannelRowHolder holder = rowHolders.get(r);
                LiveTvSchedule.Slot currentSlot = holder.channel.at(nowEpochMs);
                List<LiveTvSchedule.Slot> upcomingSlots = new ArrayList<LiveTvSchedule.Slot>(UPCOMING_CARD_COUNT);
                LiveTvSchedule.Slot cursor = currentSlot;
                for (int i = 0; i < UPCOMING_CARD_COUNT && cursor != null; i++) {
                    cursor = cursor.next();
                    if (cursor != null) {
                        upcomingSlots.add(cursor);
                    }
                }

                holder.currentSlot = currentSlot;
                holder.upcomingSlots = upcomingSlots;

                updateChannelActionLabel(holder, holder.channelTile.hasFocus());

                for (int c = 0; c < holder.episodeCards.size(); c++) {
                    EpisodeCardHolder card = holder.episodeCards.get(c);
                    LiveTvSchedule.Slot slot = null;
                    if (c == 0) {
                        slot = currentSlot;
                    } else if (c - 1 < upcomingSlots.size()) {
                        slot = upcomingSlots.get(c - 1);
                    }
                    bindEpisodeCard(card, slot, c == 0, c == 1, forceImageReload);
                }
            }
            updateHeroForSelection(nowEpochMs);
        }

        private void bindEpisodeCard(
            EpisodeCardHolder card,
            LiveTvSchedule.Slot slot,
            boolean isCurrent,
            boolean isNext,
            boolean forceImageReload
        ) {
            card.boundSlot = slot;
            if (slot == null || slot.program == null) {
                card.titleView.setText("Unavailable");
                card.subtitleView.setText("");
                card.badgeView.setVisibility(View.GONE);
                card.progressTrack.setVisibility(View.GONE);
                return;
            }

            LiveTvSchedule.Program program = slot.program;
            boolean mixedShow = !TextUtils.isEmpty(program.seriesTitle);
            card.titleView.setText(mixedShow ? program.seriesTitle : program.title);
            String seCode = formatSeasonEpisode(program);
            card.subtitleView.setText(mixedShow ? seCode + " · " + program.title : seCode);
            card.fallbackLabel.setText(seCode + "\n" + program.title);

            if (isCurrent) {
                card.badgeView.setText("ON NOW");
                card.badgeView.setBackground(makeRoundedDrawable(COLOR_ACCENT_RED, dp(4), 0, 0));
                card.badgeView.setVisibility(View.VISIBLE);
                card.progressTrack.setVisibility(View.VISIBLE);
                updateCardProgressBar(card, slot);
            } else if (isNext) {
                card.badgeView.setText("UP NEXT");
                card.badgeView.setBackground(makeRoundedDrawable(COLOR_BADGE_DARK, dp(4), dp(1), 0x40FFFFFF));
                card.badgeView.setVisibility(View.VISIBLE);
                card.progressTrack.setVisibility(View.GONE);
            } else {
                card.badgeView.setVisibility(View.GONE);
                card.progressTrack.setVisibility(View.GONE);
            }

            String thumbUrl = program.thumbnail != null ? program.thumbnail.trim() : "";
            if (forceImageReload || !thumbUrl.equals(card.lastThumbnailUrl)) {
                card.lastThumbnailUrl = thumbUrl;
                loadImageAsync(card.imageView, thumbUrl, false);
            }
        }

        private void updateCardProgressBar(EpisodeCardHolder card, LiveTvSchedule.Slot slot) {
            int totalWidth = dp(192);
            if (card.thumbContainer.getWidth() > 0) {
                totalWidth = card.thumbContainer.getWidth();
            }
            double rawFraction = (double) slot.offsetMs / (double) Math.max(1L, slot.durationMs);
            float fraction = (float) Math.max(0.0, Math.min(1.0, rawFraction));
            int fillWidth = Math.max(dp(4), Math.round(totalWidth * fraction));
            ViewGroup.LayoutParams lp = card.progressFill.getLayoutParams();
            if (lp.width != fillWidth) {
                lp.width = fillWidth;
                card.progressFill.setLayoutParams(lp);
            }
        }

        void updateHeroForSelection(long nowEpochMs) {
            if (rowHolders.isEmpty()) {
                return;
            }
            int rowIdx = Math.max(0, Math.min(selectedChannelIndex, rowHolders.size() - 1));
            ChannelRowHolder row = rowHolders.get(rowIdx);

            LiveTvSchedule.Slot slot = row.currentSlot;
            String badgeLabel = "ON NOW";
            boolean isLiveSlot = true;

            if (selectedColumnIndex <= 1) {
                slot = row.currentSlot;
                badgeLabel = "ON NOW";
                isLiveSlot = true;
            } else {
                int upcomingIdx = selectedColumnIndex - 2;
                if (row.upcomingSlots != null && upcomingIdx >= 0 && upcomingIdx < row.upcomingSlots.size()) {
                    slot = row.upcomingSlots.get(upcomingIdx);
                }
                badgeLabel = selectedColumnIndex == 2 ? "UP NEXT" : "UPCOMING";
                isLiveSlot = false;
            }

            if (slot == null) {
                slot = row.channel.at(nowEpochMs);
            }
            if (slot == null || slot.program == null) {
                return;
            }

            LiveTvSchedule.Program program = slot.program;
            if (heroTitle != null) {
                heroTitle.setText(program.title);
            }
            if (heroBadge != null) {
                heroBadge.setText(badgeLabel);
                heroBadge.setBackground(makeRoundedDrawable(
                    isLiveSlot ? COLOR_ACCENT_RED : COLOR_BADGE_DARK,
                    dp(4),
                    isLiveSlot ? 0 : dp(1),
                    isLiveSlot ? 0 : 0x55FFFFFF
                ));
                heroBadge.setVisibility(View.VISIBLE);
            }
            if (heroEpisodeMeta != null) {
                heroEpisodeMeta.setText(formatSeasonEpisode(program));
                heroEpisodeMeta.setVisibility(View.VISIBLE);
            }
            if (heroChannelName != null) {
                heroChannelName.setText(TextUtils.isEmpty(program.seriesTitle)
                    ? row.channel.name : program.seriesTitle + " · " + row.channel.name);
                heroChannelName.setVisibility(View.VISIBLE);
            }
            if (heroDescription != null) {
                String desc = !TextUtils.isEmpty(program.description)
                    ? program.description
                    : row.channel.description;
                heroDescription.setText(desc);
                heroDescription.setVisibility(View.VISIBLE);
            }
            if (heroStatusLine != null) {
                if (!TextUtils.isEmpty(playbackStatusMessage)) {
                    heroStatusLine.setText(playbackStatusMessage);
                    heroStatusLine.setTextColor(tuning ? COLOR_TEXT_SECONDARY : 0xFFFF8A80);
                    heroStatusLine.setVisibility(View.VISIBLE);
                } else {
                    heroStatusLine.setVisibility(View.GONE);
                }
            }

            String heroUrl = !TextUtils.isEmpty(program.thumbnail) ? program.thumbnail.trim() : "";
            if (!heroUrl.equals(lastHeroImageUrl)) {
                lastHeroImageUrl = heroUrl;
                loadImageAsync(heroBackground, heroUrl, true);
            }
        }

        private void updateChannelActionLabel(ChannelRowHolder holder, boolean focused) {
            if (holder.channelActionView == null) {
                return;
            }
            if (tuning) {
                holder.channelActionView.setText(
                    !TextUtils.isEmpty(playbackStatusMessage) ? playbackStatusMessage : "Tuning..."
                );
                holder.channelActionView.setTextColor(COLOR_TEXT_PRIMARY);
            } else if (!TextUtils.isEmpty(playbackStatusMessage)) {
                holder.channelActionView.setText("Retry (OK)");
                holder.channelActionView.setTextColor(0xFFFF8A80);
            } else if (focused) {
                holder.channelActionView.setText("Press OK to tune");
                holder.channelActionView.setTextColor(COLOR_TEXT_PRIMARY);
            } else {
                holder.channelActionView.setText("Tune Live");
                holder.channelActionView.setTextColor(COLOR_TEXT_MUTED);
            }
        }

        void tuneChannel(LiveTvSchedule.Channel channel) {
            if (channel == null || tuning) {
                return;
            }
            tuning = true;
            playbackStatusMessage = "Tuning " + channel.name + "...";
            refreshGuide(LiveTv.now(), false);
            LiveTvPlayback.tune(fragment, channel, this);
        }

        /**
         * Opens a program in the normal details screen. Movies use their IMDb
         * id directly. A series opens at show level so the season/episode
         * picker is visible, then focus is moved to the scheduled season and
         * episode. Passing {@code videoId} would select the episode, but the
         * stock details screen hides the picker when a video is selected.
         */
        void openDetails(LiveTvSchedule.Slot slot) {
            if (slot == null || slot.program == null || fragment == null) {
                return;
            }
            String videoId = slot.program.videoId;
            if (TextUtils.isEmpty(videoId)) {
                return;
            }
            int destination = context.getResources().getIdentifier(
                "meta_details", "id", context.getPackageName()
            );
            if (destination == 0) {
                Log.w(TAG, "Meta details destination is unavailable");
                return;
            }
            Object nav = findNavController(fragment);
            if (nav == null) {
                return;
            }
            int separator = videoId.indexOf(':');
            boolean series = separator > 0;
            String type = series ? "series" : "movie";
            String id = series ? videoId.substring(0, separator) : videoId;
            Bundle args = new Bundle();
            args.putString("type", type);
            args.putString("id", id);
            args.putBoolean("autoPlay", false);
            try {
                LiveTvPlayback.cancelTune(fragment);
            } catch (Throwable ignored) {
            }
            revealToken++;
            revealedSeason = -1;
            try {
                nav.getClass().getMethod("navigate", int.class, Bundle.class)
                    .invoke(nav, destination, args);
            } catch (Throwable error) {
                Log.w(TAG, "Unable to open details for " + videoId, error);
                return;
            }
            if (series) {
                final int token = revealToken;
                final int season = slot.program.season;
                final int episode = slot.program.episode;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        revealEpisodePicker(season, episode, token, 8);
                    }
                }, 800L);
            }
        }

        private void revealEpisodePicker(final int season, final int episode,
                final int token, final int attemptsLeft) {
            if (token != revealToken) {
                return;
            }
            if (tryRevealEpisodePicker(season, episode)) {
                return;
            }
            if (attemptsLeft > 0) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        revealEpisodePicker(season, episode, token, attemptsLeft - 1);
                    }
                }, 400L);
            }
        }

        private boolean tryRevealEpisodePicker(int season, int episode) {
            try {
                if (!(context instanceof Activity)) {
                    return false;
                }
                View decor = ((Activity) context).getWindow().getDecorView();
                int videosId = context.getResources().getIdentifier(
                    "meta_details_videos", "id", context.getPackageName()
                );
                if (videosId == 0) {
                    return false;
                }
                View videos = decor.findViewById(videosId);
                if (videos == null || videos.getVisibility() != View.VISIBLE) {
                    return false;
                }
                String episodeCode = String.format(Locale.US, "E%02d", episode);
                View direct = findEpisodeCard(videos, episodeCode);
                if (direct != null) {
                    direct.requestFocus();
                    return true;
                }
                int seasonsId = context.getResources().getIdentifier(
                    "meta_details_seasons", "id", context.getPackageName()
                );
                View seasons = seasonsId == 0 ? null : decor.findViewById(seasonsId);
                if (seasons != null && seasons.getVisibility() == View.VISIBLE
                        && revealedSeason != season) {
                    View seasonLabel = findText(seasons, "Season " + season);
                    if (seasonLabel != null) {
                        View target = nearestFocusable(seasonLabel);
                        if (target != null && !target.isSelected() && !target.isActivated()) {
                            revealedSeason = season;
                            target.requestFocus();
                            target.performClick();
                            return false;
                        }
                    }
                }
                return focusAcrossEpisodes(videos, episodeCode);
            } catch (Throwable error) {
                Log.w(TAG, "Unable to reveal episode picker", error);
                return false;
            }
        }

        private boolean focusAcrossEpisodes(View videos, String episodeCode) {
            View current = firstFocusable(videos);
            if (current == null) {
                return false;
            }
            current.requestFocus();
            for (int step = 0; step < 60; step++) {
                if (containsText(current, episodeCode)) {
                    current.requestFocus();
                    return true;
                }
                View next = current.focusSearch(View.FOCUS_RIGHT);
                if (next == null || next == current) {
                    return false;
                }
                next.requestFocus();
                current = next;
            }
            return false;
        }

        private View findText(View view, String text) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return null;
            }
            if (view instanceof TextView) {
                CharSequence value = ((TextView) view).getText();
                if (value != null && text.contentEquals(value.toString().trim())) {
                    return view;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = findText(group.getChildAt(i), text);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private View findEpisodeCard(View view, String episodeCode) {
            View label = findText(view, episodeCode);
            return label == null ? null : nearestFocusable(label);
        }

        private View nearestFocusable(View view) {
            View candidate = view;
            while (candidate != null) {
                if (candidate.isFocusable() && candidate.getVisibility() == View.VISIBLE) {
                    return candidate;
                }
                ViewParent parent = candidate.getParent();
                candidate = parent instanceof View ? (View) parent : null;
            }
            return null;
        }

        private View firstFocusable(View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) {
                return null;
            }
            if (view.isFocusable()) {
                return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = firstFocusable(group.getChildAt(i));
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private boolean containsText(View view, String text) {
            return findText(view, text) != null;
        }

        @Override
        public void onStatus(String message, boolean loading) {
            tuning = loading;
            playbackStatusMessage = message != null ? message.trim() : "";
            refreshGuide(LiveTv.now(), false);
        }

        private void styleChannelTile(ChannelRowHolder holder, boolean focused) {
            View view = holder.channelTile;
            boolean hasArtwork = holder.artworkUrl != null && !holder.artworkUrl.isEmpty();
            int bg = hasArtwork
                ? 0x00000000
                : (focused ? COLOR_SURFACE_FOCUSED : COLOR_SURFACE);
            int stroke = focused ? COLOR_STROKE_FOCUSED : COLOR_STROKE;
            int strokeWidth = focused ? dp(2) : dp(1);
            int radius = getDimenPx("cardViewCornerRadius", 10);
            view.setBackground(makeRoundedDrawable(bg, radius, strokeWidth, stroke));
            // Never scale the focused tile. Scaling pushes the focus ring past
            // the row and scroll-viewport edges, which clips its top and bottom
            // (most visibly on the first and last channels). The white stroke
            // already marks focus.
            view.setScaleX(1.0f);
            view.setScaleY(1.0f);
        }

        private void styleEpisodeCard(EpisodeCardHolder card, boolean focused, int radiusPx) {
            int strokeColor = focused ? COLOR_STROKE_FOCUSED : COLOR_STROKE;
            int strokeWidth = focused ? dp(2) : dp(1);
            card.borderOverlay.setBackground(makeRoundedDrawable(Color.TRANSPARENT, radiusPx, strokeWidth, strokeColor));
            card.titleView.setTextColor(focused ? Color.WHITE : COLOR_TEXT_PRIMARY);
            card.subtitleView.setTextColor(focused ? COLOR_TEXT_SECONDARY : COLOR_TEXT_MUTED);
            float scale = focused ? 1.04f : 1.0f;
            card.thumbContainer.setScaleX(scale);
            card.thumbContainer.setScaleY(scale);
        }

        private void applyStyleByName(TextView textView, String styleName) {
            if (textView == null || TextUtils.isEmpty(styleName)) {
                return;
            }
            try {
                Resources res = context.getResources();
                int styleId = res.getIdentifier(styleName, "style", context.getPackageName());
                if (styleId == 0) {
                    styleId = res.getIdentifier("TextAppearance.StreamCard.Description", "style", context.getPackageName());
                }
                if (styleId != 0) {
                    textView.setTextAppearance(styleId);
                }
            } catch (Throwable ignored) {
            }
        }

        private GradientDrawable makeRoundedDrawable(int fillColor, int radiusPx, int strokeWidthPx, int strokeColor) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(radiusPx);
            drawable.setColor(fillColor);
            if (strokeWidthPx > 0) {
                drawable.setStroke(strokeWidthPx, strokeColor);
            }
            return drawable;
        }

        private String formatSeasonEpisode(LiveTvSchedule.Program program) {
            if (program.season <= 0) {
                String runtime = Math.max(1L, program.durationMs / 60000L) + " min";
                return program.year > 0 ? program.year + " · " + runtime : runtime;
            }
            return String.format(Locale.US, "S%d \u00B7 E%d", program.season, program.episode);
        }

        private int getDimenPx(String name, int fallbackDp) {
            try {
                Resources res = context.getResources();
                int id = res.getIdentifier(name, "dimen", context.getPackageName());
                if (id != 0) {
                    return res.getDimensionPixelSize(id);
                }
            } catch (Throwable ignored) {
            }
            return dp(fallbackDp);
        }

        private int dp(float value) {
            return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()
            ));
        }

        static boolean isActivateKey(int keyCode) {
            return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_SPACE;
        }

        static View findSideMenuTarget(View currentView) {
            if (currentView == null) {
                return null;
            }
            try {
                View sideTarget = currentView.focusSearch(View.FOCUS_LEFT);
                if (sideTarget != null && sideTarget != currentView) {
                    return sideTarget;
                }
            } catch (Throwable ignored) {
            }
            Context context = currentView.getContext();
            if (!(context instanceof Activity)) {
                return null;
            }
            View decor = ((Activity) context).getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) {
                return null;
            }
            return findCheckedOrFirstMenuButton((ViewGroup) decor, currentView);
        }

        private static View findCheckedOrFirstMenuButton(ViewGroup root, View excludeSubtree) {
            View fallback = null;
            int count = root.getChildCount();
            for (int index = 0; index < count; index++) {
                View child = root.getChildAt(index);
                if (child == excludeSubtree || isDescendantOf(child, excludeSubtree)) {
                    continue;
                }
                String className = child.getClass().getName();
                if (className.contains("MenuButton") && child.getVisibility() == View.VISIBLE && child.isFocusable()) {
                    if (child.isSelected() || child.isActivated()) {
                        return child;
                    }
                    if (fallback == null) {
                        fallback = child;
                    }
                }
                if (child instanceof ViewGroup) {
                    View nested = findCheckedOrFirstMenuButton((ViewGroup) child, excludeSubtree);
                    if (nested != null) {
                        if (nested.isSelected() || nested.isActivated()) {
                            return nested;
                        }
                        if (fallback == null) {
                            fallback = nested;
                        }
                    }
                }
            }
            return fallback;
        }

        private static boolean isDescendantOf(View view, View potentialAncestor) {
            if (view == null || potentialAncestor == null) {
                return false;
            }
            ViewParent parent = view.getParent();
            while (parent instanceof View) {
                if (parent == potentialAncestor) {
                    return true;
                }
                parent = parent.getParent();
            }
            return false;
        }
    }

    private static final class ChannelRowHolder {
        final int rowIndex;
        final LiveTvSchedule.Channel channel;
        final List<EpisodeCardHolder> episodeCards = new ArrayList<EpisodeCardHolder>();
        LinearLayout rowContainer;
        View channelTile;
        String artworkUrl = "";
        TextView channelNameView;
        TextView channelActionView;
        HorizontalScrollView scrollView;
        LiveTvSchedule.Slot currentSlot;
        List<LiveTvSchedule.Slot> upcomingSlots = Collections.emptyList();

        ChannelRowHolder(int rowIndex, LiveTvSchedule.Channel channel) {
            this.rowIndex = rowIndex;
            this.channel = channel;
        }
    }

    private static final class EpisodeCardHolder {
        final int columnIndex;
        LinearLayout cardRoot;
        FrameLayout thumbContainer;
        TextView fallbackLabel;
        ImageView imageView;
        TextView badgeView;
        FrameLayout progressTrack;
        View progressFill;
        View borderOverlay;
        TextView titleView;
        TextView subtitleView;
        LiveTvSchedule.Slot boundSlot;
        String lastThumbnailUrl = "";

        EpisodeCardHolder(int columnIndex) {
            this.columnIndex = columnIndex;
        }
    }
}
