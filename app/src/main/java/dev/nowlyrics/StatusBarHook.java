package dev.nowlyrics;

import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public final class StatusBarHook implements IXposedHookLoadPackage {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CLOCK = SYSTEM_UI + ".statusbar.policy.Clock";
    private static final String STATUS_BAR = SYSTEM_UI + ".statusbar.phone.PhoneStatusBarView";
    private static final Uri LYRIC = Uri.parse(StatusLyricProvider.URI);
    private static final long SCROLL_DELAY_MS = 180;
    private static final long LINE_TRANSITION_MS = 150;
    private static final float SCROLL_DP_PER_SECOND = 36f;
    private final WeakHashMap<TextView, String> lyrics = new WeakHashMap<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();

    @Override public void handleLoadPackage(LoadPackageParam param) {
        if (!SYSTEM_UI.equals(param.packageName)) return;
        try {
            Class<?> clockClass = Class.forName(CLOCK, false, param.classLoader);
            Method updateClock = findClockUpdate(clockClass);
            updateClock.setAccessible(true);
            XC_MethodHook keepLyric = new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            TextView clock = (TextView) hook.thisObject;
                            String line = lyrics.get(clock);
                            if (line != null && !clock.getContext().getSystemService(
                                    KeyguardManager.class).isKeyguardLocked()) {
                                clock.setText(line);
                            }
                        }
                    };
            if (updateClock.getParameterCount() == 0) {
                XposedHelpers.findAndHookMethod(CLOCK, param.classLoader, "updateClock", keepLyric);
            } else {
                XposedHelpers.findAndHookMethod(CLOCK, param.classLoader, "updateClock",
                        boolean.class, keepLyric);
            }
            XposedHelpers.findAndHookMethod(STATUS_BAR, param.classLoader, "onFinishInflate",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            View bar = (View) hook.thisObject;
                            if (findView(bar, "clock") instanceof TextView clock) {
                                bar.addOnAttachStateChangeListener(
                                        new LyricBinding(bar, clock, updateClock));
                                Log.i("NowLyrics", "Native status bar clock found");
                            }
                        }
                    });
        } catch (ClassNotFoundException otherLoader) {
            // SystemUI can load additional packages through unrelated class loaders.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            Log.w("NowLyrics", "Unable to initialize status bar lyrics", error);
        }
    }

    private static View findView(View bar, String name) {
        int id = bar.getResources().getIdentifier(name, "id", SYSTEM_UI);
        return id == 0 ? null : bar.findViewById(id);
    }

    private static Method findClockUpdate(Class<?> clockClass) throws NoSuchMethodException {
        try {
            return clockClass.getDeclaredMethod("updateClock", boolean.class);
        } catch (NoSuchMethodException olderClock) {
            return clockClass.getDeclaredMethod("updateClock");
        }
    }

    private final class LyricBinding implements View.OnAttachStateChangeListener {
        private final View bar;
        private final View startSide;
        private final View endSide;
        private final View cutoutSpace;
        private final View centerClock;
        private final TextView clock;
        private final ViewTreeObserver.OnGlobalLayoutListener layoutChanged = this::fitLyric;
        private final ViewTreeObserver.OnPreDrawListener drawScroll = this::drawScroll;
        private final Runnable startScroll;
        private LinearLayout.LayoutParams expandedEndParams;
        private int originalEndWidth;
        private float originalEndWeight;
        private boolean originalHorizontalScrolling;
        private long scrollStartsAt;
        private AnimationSet transition;
        private final Method updateClock;
        private final int originalMaxWidth;
        private final boolean widthInEms;
        private final TextUtils.TruncateAt originalEllipsize;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ContentObserver observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                refresh();
            }
        };
        private boolean listening;
        private int request;

        LyricBinding(View bar, TextView clock, Method updateClock) {
            this.bar = bar;
            this.clock = clock;
            startScroll = clock::invalidate;
            startSide = findView(bar, "status_bar_start_side_container");
            endSide = findView(bar, "status_bar_end_side_container");
            cutoutSpace = findView(bar, "cutout_space_view");
            centerClock = findView(bar, "clock_center");
            this.updateClock = updateClock;
            widthInEms = clock.getMaxEms() >= 0;
            originalMaxWidth = widthInEms ? clock.getMaxEms() : clock.getMaxWidth();
            originalEllipsize = clock.getEllipsize();
        }

        @Override public void onViewAttachedToWindow(View view) {
            try {
                clock.getContext().getContentResolver().registerContentObserver(LYRIC, false, observer);
                listening = true;
                bar.getViewTreeObserver().addOnGlobalLayoutListener(layoutChanged);
                bar.getViewTreeObserver().addOnPreDrawListener(drawScroll);
                refresh();
            } catch (RuntimeException error) {
                Log.w("NowLyrics", "Unable to observe status bar lyrics", error);
            }
        }

        @Override public void onViewDetachedFromWindow(View view) {
            if (!listening) return;
            listening = false;
            request++;
            bar.getViewTreeObserver().removeOnGlobalLayoutListener(layoutChanged);
            bar.getViewTreeObserver().removeOnPreDrawListener(drawScroll);
            clock.getContext().getContentResolver().unregisterContentObserver(observer);
            show("");
        }

        private void refresh() {
            if (!listening) return;
            int reading = ++request;
            ContentResolver resolver = clock.getContext().getContentResolver();
            reader.execute(() -> {
                String text = "";
                try (Cursor row = resolver.query(LYRIC, null, null, null, null)) {
                    if (row != null && row.moveToFirst()) text = row.getString(0);
                } catch (RuntimeException error) {
                    Log.w("NowLyrics", "Unable to read status bar lyric", error);
                }
                String line = text;
                main.post(() -> {
                    if (listening && reading == request) show(line);
                });
            });
        }

        private void fitLyric() {
            if (!lyrics.containsKey(clock)) return;

            // With a corner cutout, the end side only needs enough width for its icons.
            boolean canExpand = startSide != null && endSide != null
                    && startSide.getParent() == endSide.getParent()
                    && cutoutSpace != null && cutoutSpace.getVisibility() == View.GONE
                    && (centerClock == null || !centerClock.isShown())
                    && clock.isShown();
            if (canExpand && expandedEndParams == null
                    && endSide.getLayoutParams() instanceof LinearLayout.LayoutParams params
                    && params.weight > 0) {
                expandedEndParams = params;
                originalEndWidth = params.width;
                originalEndWeight = params.weight;
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.weight = 0;
                endSide.setLayoutParams(params);
            } else if (!canExpand) {
                restoreEndSide();
            }

            View boundary = startSide == null ? bar : startSide;
            int width = boundary.getWidth();
            View child = clock;
            while (child != boundary && child.getParent() instanceof ViewGroup parent) {
                width -= parent.getPaddingLeft() + parent.getPaddingRight();
                if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
                    width -= margins.leftMargin + margins.rightMargin;
                }
                if (parent instanceof LinearLayout row && row.getOrientation() == LinearLayout.HORIZONTAL) {
                    for (int i = 0; i < row.getChildCount(); i++) {
                        View sibling = row.getChildAt(i);
                        if (sibling == child || sibling.getVisibility() == View.GONE) continue;
                        width -= sibling.getMeasuredWidth();
                        if (sibling.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
                            width -= margins.leftMargin + margins.rightMargin;
                        }
                    }
                }
                child = parent;
            }
            if (child == boundary && boundary.getWidth() > 0) {
                int available = Math.max(0, width);
                if (clock.getMaxWidth() != available) clock.setMaxWidth(available);
            }
        }

        private void restoreEndSide() {
            if (expandedEndParams == null) return;
            expandedEndParams.width = originalEndWidth;
            expandedEndParams.weight = originalEndWeight;
            if (endSide.getLayoutParams() == expandedEndParams) {
                endSide.setLayoutParams(expandedEndParams);
            }
            expandedEndParams = null;
        }

        private boolean drawScroll() {
            String line = lyrics.get(clock);
            Layout layout = clock.getLayout();
            int width = clock.getWidth() - clock.getCompoundPaddingLeft()
                    - clock.getCompoundPaddingRight();
            if (line == null || !TextUtils.equals(clock.getText(), line)
                    || !clock.isShown() || layout == null || width <= 0) return true;

            int left = (int) Math.floor(layout.getLineLeft(0));
            int right = (int) Math.ceil(layout.getLineRight(0));
            int overflow = Math.max(0, right - left - width);
            long elapsed = SystemClock.uptimeMillis() - scrollStartsAt;
            float speed = SCROLL_DP_PER_SECOND * clock.getResources().getDisplayMetrics().density;
            int distance = Math.min(overflow, Math.round(Math.max(0, elapsed) * speed / 1000));
            boolean rtl = layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT;
            clock.setScrollX(rtl ? Math.max(left, right - width) - distance : left + distance);
            if (elapsed >= 0 && distance < overflow) clock.postInvalidateOnAnimation();
            return true;
        }

        private void show(String line) {
            String previous = lyrics.get(clock);
            if (TextUtils.equals(previous, line) || (previous == null && line.isEmpty())) return;
            main.removeCallbacks(startScroll);
            if (line.isEmpty()) {
                lyrics.remove(clock);
                restoreEndSide();
                if (widthInEms) clock.setMaxEms(originalMaxWidth);
                else clock.setMaxWidth(originalMaxWidth);
                clock.setEllipsize(originalEllipsize);
                if (Build.VERSION.SDK_INT >= 29) {
                    clock.setHorizontallyScrolling(originalHorizontalScrolling);
                }
                clock.setScrollX(0);
                if (transition != null && clock.getAnimation() == transition) clock.clearAnimation();
            } else {
                lyrics.put(clock, line);
                scrollStartsAt = SystemClock.uptimeMillis() + SCROLL_DELAY_MS;
                main.postAtTime(startScroll, scrollStartsAt);
                clock.setScrollX(0);
                if (previous == null) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        originalHorizontalScrolling = clock.isHorizontallyScrollable();
                        clock.setHorizontallyScrolling(true);
                    }
                    clock.setEllipsize(null);
                }
                fitLyric();
            }
            try {
                if (updateClock.getParameterCount() == 0) updateClock.invoke(clock);
                else updateClock.invoke(clock, true);
            } catch (ReflectiveOperationException error) {
                Log.w("NowLyrics", "Unable to refresh status bar clock", error);
            }
            if (!line.isEmpty() && TextUtils.equals(clock.getText(), line)) {
                transition = new AnimationSet(true);
                transition.addAnimation(new TranslateAnimation(
                        Animation.ABSOLUTE, 0f, Animation.ABSOLUTE, 0f,
                        Animation.RELATIVE_TO_SELF, 0.55f, Animation.RELATIVE_TO_SELF, 0f));
                transition.addAnimation(new AlphaAnimation(0f, 1f));
                transition.setDuration(LINE_TRANSITION_MS);
                transition.setInterpolator(new DecelerateInterpolator());
                clock.startAnimation(transition);
            }
        }
    }
}
