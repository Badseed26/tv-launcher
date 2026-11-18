package com.ethan.tvlauncher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Shared look and helpers for the launcher screens. */
final class Ui {

    static final int TEXT_PRIMARY = 0xFFF2F4FA;
    static final int TEXT_SECONDARY = 0xFF8C92A8;
    static final int CARD = 0xFF1A1D2E;

    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int screenWidth(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels;
    }

    /** Deep near-black background with two soft colour glows. */
    static final class BackgroundView extends View {
        private final Paint base = new Paint();
        private final Paint glowBlue = new Paint();
        private final Paint glowPurple = new Paint();

        BackgroundView(Context c) {
            super(c);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w == 0 || h == 0) return;
            base.setShader(new LinearGradient(0, 0, w, h,
                    0xFF080A14, 0xFF11132A, Shader.TileMode.CLAMP));
            glowBlue.setShader(new RadialGradient(w * 0.12f, h * 0.05f, w * 0.65f,
                    0x553A5BFF, 0x003A5BFF, Shader.TileMode.CLAMP));
            glowPurple.setShader(new RadialGradient(w * 0.92f, h * 1.0f, w * 0.6f,
                    0x449B5CFF, 0x009B5CFF, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            canvas.drawRect(0, 0, w, h, base);
            canvas.drawRect(0, 0, w, h, glowBlue);
            canvas.drawRect(0, 0, w, h, glowPurple);
        }
    }

    static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Rounded pill button: soft glass when idle, solid white when selected. */
    static TextView pill(final Context c, String label, View.OnClickListener onClick) {
        final TextView t = text(c, label, 16, TEXT_PRIMARY, MEDIUM);
        int r = dp(c, 100);

        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(r);
        idle.setColor(0x1FFFFFFF);
        idle.setStroke(dp(c, 1), 0x22FFFFFF);

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(r);
        focused.setColor(0xFFFFFFFF);

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, focused);
        bg.addState(new int[] {}, idle);
        t.setBackground(bg);

        t.setTextColor(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_focused}, new int[] {}},
                new int[] {0xFF0B0D17, TEXT_PRIMARY}));

        t.setPadding(dp(c, 24), dp(c, 11), dp(c, 24), dp(c, 11));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        // Selected state is shown by the solid white fill, no growing, so nothing gets cut off.
        return t;
    }

    interface FocusListener {
        void onTileFocused(int index);
    }

    /**
     * Builds an app tile (rounded card with the app's TV banner and a label under it).
     * Returns null if the app isn't installed or can't be launched.
     */
    static View appTile(final Context c, final String pkg, String labelOverride,
                        int width, int height, final int index, final FocusListener focusListener) {
        final PackageManager pm = c.getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        if (launchIntent(pm, pkg) == null) return null;

        String label = labelOverride != null ? labelOverride : String.valueOf(pm.getApplicationLabel(ai));
        Drawable banner = loadBanner(pm, pkg, ai);

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        final FrameLayout card = new FrameLayout(c);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(c, 14));
        cardBg.setColor(CARD);
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setElevation(dp(c, 2));

        ImageView img = new ImageView(c);
        if (banner != null) {
            img.setImageDrawable(banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(ai);
            } catch (Exception ignored) {
            }
            img.setImageDrawable(icon != null ? icon : new ColorDrawable(0));
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(height * 0.5f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
            card.addView(img, lp);
        }

        // Thin white ring shown when the tile is selected.
        final View ring = new View(c);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setCornerRadius(dp(c, 14));
        ringBg.setColor(0x00000000);
        ringBg.setStroke(dp(c, 3), 0xF2FFFFFF);
        ring.setBackground(ringBg);
        ring.setVisibility(View.INVISIBLE);
        card.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(card, new LinearLayout.LayoutParams(width, height));

        final TextView name = text(c, label, 15, TEXT_PRIMARY, REGULAR);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setAlpha(0.55f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(c, 14);
        name.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        column.addView(name, nlp);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.09f : 1f;
                v.animate().scaleX(s).scaleY(s)
                        .translationZ(hasFocus ? dp(c, 18) : 0)
                        .setDuration(160).start();
                ring.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
                name.animate().alpha(hasFocus ? 1f : 0.55f)
                        .translationY(hasFocus ? dp(c, 6) : 0)
                        .setDuration(160).start();
                if (hasFocus && focusListener != null) focusListener.onTileFocused(index);
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(c, pkg);
            }
        });
        column.setTag(card);
        return column;
    }

    private static Drawable loadBanner(PackageManager pm, String pkg, ApplicationInfo ai) {
        try {
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li != null) {
                Drawable d = pm.getActivityBanner(li);
                if (d != null) return d;
            }
        } catch (Exception ignored) {
        }
        try {
            return pm.getApplicationBanner(ai);
        } catch (Exception ignored) {
        }
        return null;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = null;
        try {
            i = pm.getLeanbackLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {
        }
        if (i == null) {
            try {
                i = pm.getLaunchIntentForPackage(pkg);
            } catch (Exception ignored) {
            }
        }
        return i;
    }

    static void launch(Context c, String pkg) {
        Intent i = launchIntent(c.getPackageManager(), pkg);
        if (i == null) {
            Toast.makeText(c, "Can't open that app", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "Can't open that app", Toast.LENGTH_SHORT).show();
        }
    }

    static void openSettings(Context c) {
        Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            launch(c, "com.android.tv.settings");
        }
    }
}
