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
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModule;

public final class StatusBarHook extends XposedModule {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CLOCK = SYSTEM_UI + ".statusbar.policy.Clock";
    private static final String SAMSUNG_CLOCK = SYSTEM_UI + ".statusbar.policy.QSClockIndicatorView";
    private static final String SAMSUNG_CLOCK_SOUND = SYSTEM_UI + ".statusbar.policy.QSClockBellSound";
    private static final String STATUS_BAR = SYSTEM_UI + ".statusbar.phone.PhoneStatusBarView";
    private static final Uri LYRIC = Uri.parse(StatusLyricProvider.URI);
    private static final long SCROLL_DELAY_MS = 180;
    private static final long LINE_TRANSITION_MS = 150;
    private static final float SCROLL_DP_PER_SECOND = 36f;
    private WeakHashMap<TextView, String> lyrics;
    private WeakHashMap<TextView, CharSequence> nativeSamsungText;
    private ExecutorService reader;
    private boolean systemUiProcess;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        systemUiProcess = SYSTEM_UI.equals(param.getProcessName());
        if (systemUiProcess) {
            lyrics = new WeakHashMap<>();
            nativeSamsungText = new WeakHashMap<>();
            reader = Executors.newSingleThreadExecutor();
        }
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!systemUiProcess || !SYSTEM_UI.equals(param.getPackageName())) return;
        try {
            ClassLoader loader = param.getClassLoader();
            if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
                hookSamsungStatusBar(loader);
                return;
            }
            Class<?> clockClass = Class.forName(CLOCK, false, loader);
            Method updateClock = findClockUpdate(clockClass);
            updateClock.setAccessible(true);
            Method inflate = Class.forName(STATUS_BAR, false, loader)
                    .getDeclaredMethod("onFinishInflate");
            hook(updateClock).intercept(chain -> {
                Object result = chain.proceed();
                TextView clock = (TextView) chain.getThisObject();
                String line = lyrics.get(clock);
                if (line != null && !isLocked(clock)) clock.setText(line);
                return result;
            });
            hook(inflate).intercept(chain -> {
                Object result = chain.proceed();
                View bar = (View) chain.getThisObject();
                if (findView(bar, "clock") instanceof TextView clock) {
                    bar.addOnAttachStateChangeListener(new LyricBinding(bar, clock, updateClock));
                    Log.i("NowLyrics", "Native status bar clock found");
                }
                return result;
            });
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            Log.w("NowLyrics", "Unable to initialize status bar lyrics", error);
        }
    }

    private void hookSamsungStatusBar(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> clockClass = Class.forName(SAMSUNG_CLOCK, false, loader);
        Class<?> soundClass = Class.forName(SAMSUNG_CLOCK_SOUND, false, loader);
        Class<?> barClass = Class.forName(STATUS_BAR, false, loader);
        Method update = clockClass.getDeclaredMethod("notifyTimeChanged", soundClass);
        Method inflate = barClass.getDeclaredMethod("onFinishInflate");
        hook(update).intercept(chain -> {
            Object result = chain.proceed();
            TextView clock = (TextView) chain.getThisObject();
            String line = lyrics.get(clock);
            if (line != null) {
                if (!TextUtils.equals(clock.getText(), line)) {
                    nativeSamsungText.put(clock, TextUtils.stringOrSpannedString(clock.getText()));
                }
                CharSequence text = isLocked(clock) ? nativeSamsungText.get(clock) : line;
                if (!TextUtils.equals(clock.getText(), text)) clock.setText(text);
            }
            return result;
        });
        hook(inflate).intercept(chain -> {
            Object result = chain.proceed();
            View bar = (View) chain.getThisObject();
            View candidate = findView(bar, "clock");
            if (!clockClass.isInstance(candidate)) {
                ArrayList<TextView> clocks = new ArrayList<>();
                findSamsungClocks(bar, clockClass, clocks);
                candidate = clocks.size() == 1 ? clocks.get(0) : null;
            }
            if (candidate instanceof TextView clock) {
                bar.addOnAttachStateChangeListener(new LyricBinding(bar, clock, null));
                Log.i("NowLyrics", "One UI status bar clock found");
            } else {
                Log.w("NowLyrics", "No unambiguous One UI clock in PhoneStatusBarView");
            }
            return result;
        });
    }

    private static void findSamsungClocks(View root, Class<?> clockClass, ArrayList<TextView> clocks) {
        if (root instanceof TextView clock && clockClass.isInstance(clock)) {
            clocks.add(clock);
        } else if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                findSamsungClocks(group.getChildAt(i), clockClass, clocks);
            }
        }
    }

    private static boolean isLocked(TextView clock) {
        KeyguardManager keyguard = clock.getContext().getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isKeyguardLocked();
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
        private String requestedLine = "";

        LyricBinding(View bar, TextView clock, Method updateClock) {
            this.bar = bar;
            this.clock = clock;
            startScroll = clock::invalidate;
            View start = findView(bar, "status_bar_start_side_container");
            View end = findView(bar, "status_bar_end_side_container");
            if (updateClock == null) {
                if (start == null) start = findView(bar, "status_bar_left_side");
                if (end == null) end = findView(bar, "status_bar_right_side");
            }
            startSide = start;
            endSide = end;
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
            requestedLine = "";
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
                    if (listening && reading == request) {
                        requestedLine = line == null ? "" : line;
                        show(updateClock == null && isLocked(clock) ? "" : requestedLine);
                    }
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
            if (updateClock == null && !requestedLine.isEmpty()) {
                if (isLocked(clock)) {
                    if (lyrics.containsKey(clock)) {
                        show("");
                        return false;
                    }
                    return true;
                }
                if (!lyrics.containsKey(clock)) {
                    show(requestedLine);
                    return false;
                }
            }
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
                if (updateClock == null && previous == null) {
                    nativeSamsungText.put(clock, TextUtils.stringOrSpannedString(clock.getText()));
                }
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
                if (updateClock == null) {
                    clock.setText(line.isEmpty() ? nativeSamsungText.remove(clock) : line);
                } else if (updateClock.getParameterCount() == 0) updateClock.invoke(clock);
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
