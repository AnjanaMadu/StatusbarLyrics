package dev.nowlyrics;

import dev.nowlyrics.ArtColorExtractor.Palette;
import dev.nowlyrics.LyricsData.Line;
import dev.nowlyrics.LyricsData.Track;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xA6FFFFFF;
    private static final int TEXT_PAST = 0x5EFFFFFF;
    private static final int TEXT_UPCOMING = 0x99FFFFFF;
    private static final int ON_ACCENT = 0xFF14101B;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<TextView> rows = new ArrayList<>();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, 200);
        }
    };

    private Palette palette = ArtColorExtractor.DEFAULT;
    private GradientDrawable ambientBg;
    private ValueAnimator paletteAnimator;

    // Header
    private Switch statusBarSwitch;
    private ImageButton infoButton;

    // Lyrics viewport & overlays
    private ScrollView scroll;
    private LinearLayout lyrics;
    private LinearLayout followPill;
    private ImageView followIcon;
    private TextView followLabel;

    // Empty / onboarding state card
    private LinearLayout stateCard;
    private FrameLayout stateIconWrap;
    private ImageView stateIcon;
    private ProgressBar stateSpinner;
    private TextView stateTitle;
    private TextView stateBody;
    private LinearLayout stateAction;
    private ImageView stateActionIcon;
    private TextView stateActionLabel;

    // Player dock
    private LinearLayout playerCard;
    private GradientDrawable playerSurface;
    private ImageView artwork;
    private TextView title;
    private TextView artist;
    private LinearLayout transportCluster;
    private ImageButton prevBtn;
    private ImageButton playBtn;
    private ImageButton nextBtn;
    private SeekBar seekBar;
    private TextView clockElapsed;
    private TextView clockDuration;

    // Diagnostics modal
    private AlertDialog diagnostics;
    private TextView diagSession, diagPlayback, diagLyrics;
    private String lastDiagnosticsText = "";

    // State
    private int playbackIcon;
    private boolean following = true;
    private boolean seeking = false;
    private boolean hasArtwork = false;
    private MediaMetadata displayedMetadata;
    private MediaController controller;
    private long sessionCheckedAt = -1000;
    private String sessions = "", sessionError = "", lookup = "Play a song in a music app", match = "";
    private Track track;
    private int generation, highlighted = -1;
    private List<Line> lines = Collections.emptyList();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();

        ambientBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{palette.ambientTop(), palette.ambientBottom()});

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ambientBg);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    insets.getSystemWindowInsetLeft() + dp(18),
                    insets.getSystemWindowInsetTop() + dp(10),
                    insets.getSystemWindowInsetRight() + dp(18),
                    insets.getSystemWindowInsetBottom() + dp(12)
            );
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);

        buildHeader(root);
        buildLyricsStage(root);
        buildPlayerDock(root);
        applyPalette(palette, false);
    }

    @SuppressWarnings("deprecation")
    private void configureEdgeToEdge() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    private void buildHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, 0, dp(8));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.HORIZONTAL);
        titleWrap.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleWrap, new LinearLayout.LayoutParams(0, -2, 1));

        TextView heading = label(titleWrap, "Now Lyrics", 22, TEXT_PRIMARY);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heading.setLetterSpacing(-0.02f);

        LinearLayout barToggle = new LinearLayout(this);
        barToggle.setOrientation(LinearLayout.HORIZONTAL);
        barToggle.setGravity(Gravity.CENTER_VERTICAL);
        barToggle.setPadding(dp(10), dp(4), dp(6), dp(4));
        barToggle.setBackground(surface(0x26FFFFFF, 16));
        TextView barLabel = label(barToggle, "Bar", 12, TEXT_SECONDARY);
        barLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusBarSwitch = new Switch(this);
        statusBarSwitch.setChecked(Prefs.isStatusBarActive(this));
        statusBarSwitch.setOnCheckedChangeListener((v, isChecked) -> Prefs.setStatusBarActive(this, isChecked));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(-2, -2);
        swLp.leftMargin = dp(4);
        barToggle.addView(statusBarSwitch, swLp);
        barToggle.setOnClickListener(v -> statusBarSwitch.toggle());
        header.addView(barToggle, new LinearLayout.LayoutParams(-2, -2));

        infoButton = iconButton(header, R.drawable.ic_info, "Diagnostics",
                42, Color.TRANSPARENT, TEXT_SECONDARY, this::showDiagnostics);
        ((LinearLayout.LayoutParams) infoButton.getLayoutParams()).leftMargin = dp(4);
    }

    private void buildLyricsStage(LinearLayout root) {
        FrameLayout stage = new FrameLayout(this);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, 0, 1);
        stageLp.topMargin = dp(2);
        stageLp.bottomMargin = dp(10);
        root.addView(stage, stageLp);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(44));
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && following && !lines.isEmpty()) {
                setFollowing(false);
            }
            return false;
        });

        lyrics = new LinearLayout(this);
        lyrics.setOrientation(LinearLayout.VERTICAL);
        lyrics.setPadding(0, dp(28), 0, dp(72));
        scroll.addView(lyrics, new FrameLayout.LayoutParams(-1, -2));
        stage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // Centered empty / onboarding / loading card
        stateCard = new LinearLayout(this);
        stateCard.setOrientation(LinearLayout.VERTICAL);
        stateCard.setGravity(Gravity.CENTER_HORIZONTAL);
        stateCard.setPadding(dp(24), dp(26), dp(24), dp(24));
        GradientDrawable cardBg = surface(0x8A171520, 24);
        cardBg.setStroke(dp(1), 0x1FFFFFFF);
        stateCard.setBackground(cardBg);

        stateIconWrap = new FrameLayout(this);
        stateIcon = new ImageView(this);
        stateIcon.setImageResource(R.drawable.ic_music);
        stateIcon.setScaleType(ImageView.ScaleType.CENTER);
        stateIconWrap.addView(stateIcon, new FrameLayout.LayoutParams(-1, -1));

        stateSpinner = new ProgressBar(this);
        stateSpinner.setIndeterminate(true);
        stateSpinner.setVisibility(View.GONE);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        stateIconWrap.addView(stateSpinner, spinLp);
        stateCard.addView(stateIconWrap, new LinearLayout.LayoutParams(dp(52), dp(52)));

        stateTitle = label(stateCard, "Waiting for music", 18, TEXT_PRIMARY);
        stateTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        stateTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stTitleLp = new LinearLayout.LayoutParams(-2, -2);
        stTitleLp.topMargin = dp(14);
        stateTitle.setLayoutParams(stTitleLp);

        stateBody = label(stateCard, "Play a song in YouTube Music, Spotify, or any media app.", 13, TEXT_SECONDARY);
        stateBody.setGravity(Gravity.CENTER);
        stateBody.setLineSpacing(dp(2), 1.15f);
        LinearLayout.LayoutParams stBodyLp = new LinearLayout.LayoutParams(-2, -2);
        stBodyLp.topMargin = dp(6);
        stateBody.setLayoutParams(stBodyLp);

        stateAction = new LinearLayout(this);
        stateAction.setOrientation(LinearLayout.HORIZONTAL);
        stateAction.setGravity(Gravity.CENTER);
        stateAction.setPadding(dp(18), dp(10), dp(18), dp(10));
        stateActionIcon = new ImageView(this);
        stateActionIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        stateAction.addView(stateActionIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        stateActionLabel = label(stateAction, "", 14, ON_ACCENT);
        stateActionLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams actLblLp = new LinearLayout.LayoutParams(-2, -2);
        actLblLp.leftMargin = dp(8);
        stateActionLabel.setLayoutParams(actLblLp);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-2, -2);
        actLp.topMargin = dp(18);
        stateCard.addView(stateAction, actLp);

        FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        stateLp.leftMargin = dp(8);
        stateLp.rightMargin = dp(8);
        stage.addView(stateCard, stateLp);

        // Floating Sync pill at bottom center of lyrics stage
        followPill = new LinearLayout(this);
        followPill.setOrientation(LinearLayout.HORIZONTAL);
        followPill.setGravity(Gravity.CENTER);
        followPill.setPadding(dp(14), dp(8), dp(16), dp(8));
        followPill.setElevation(dp(6));
        followPill.setVisibility(View.GONE);
        followPill.setAlpha(0f);

        followIcon = new ImageView(this);
        followIcon.setImageResource(R.drawable.ic_sync);
        followIcon.setImageTintList(ColorStateList.valueOf(ON_ACCENT));
        followPill.addView(followIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));

        followLabel = label(followPill, "Sync to line", 13, ON_ACCENT);
        followLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(-2, -2);
        flLp.leftMargin = dp(6);
        followLabel.setLayoutParams(flLp);

        followPill.setOnClickListener(v -> {
            setFollowing(true);
            scrollToHighlighted();
        });

        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pillLp.bottomMargin = dp(12);
        stage.addView(followPill, pillLp);
    }

    private void buildPlayerDock(LinearLayout root) {
        playerCard = new LinearLayout(this);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setPadding(dp(14), dp(12), dp(14), dp(10));
        playerSurface = surface(palette.cardSurface(), 24);
        playerSurface.setStroke(dp(1), 0x24FFFFFF);
        playerCard.setBackground(playerSurface);
        playerCard.setElevation(dp(8));
        root.addView(playerCard, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout trackRow = new LinearLayout(this);
        trackRow.setGravity(Gravity.CENTER_VERTICAL);
        playerCard.addView(trackRow, new LinearLayout.LayoutParams(-1, -2));

        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackground(surface(0xFF2D2736, 12));
        artwork.setClipToOutline(true);
        showArtwork(null);
        trackRow.addView(artwork, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.VERTICAL);
        metadata.setPadding(dp(12), 0, dp(8), 0);
        trackRow.addView(metadata, new LinearLayout.LayoutParams(0, -2, 1));

        title = label(metadata, "Waiting for music", 16, TEXT_PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);

        artist = label(metadata, "", 13, TEXT_SECONDARY);
        artist.setMaxLines(1);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(-1, -2);
        artistLp.topMargin = dp(2);
        artist.setLayoutParams(artistLp);

        transportCluster = new LinearLayout(this);
        transportCluster.setOrientation(LinearLayout.HORIZONTAL);
        transportCluster.setGravity(Gravity.CENTER_VERTICAL);
        trackRow.addView(transportCluster, new LinearLayout.LayoutParams(-2, -2));

        prevBtn = iconButton(transportCluster, R.drawable.ic_skip_previous, "Previous",
                36, Color.TRANSPARENT, TEXT_PRIMARY, () -> sendTransport(TransportAction.PREV));
        playBtn = iconButton(transportCluster, R.drawable.ic_play, "Play",
                42, palette.accent(), ON_ACCENT, () -> sendTransport(TransportAction.PLAY_PAUSE));
        LinearLayout.LayoutParams playLp = (LinearLayout.LayoutParams) playBtn.getLayoutParams();
        playLp.leftMargin = dp(4);
        playLp.rightMargin = dp(4);
        nextBtn = iconButton(transportCluster, R.drawable.ic_skip_next, "Next",
                36, Color.TRANSPARENT, TEXT_PRIMARY, () -> sendTransport(TransportAction.NEXT));

        // Scrubber
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setPadding(dp(4), dp(8), dp(4), dp(2));
        seekBar.setSplitTrack(false);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && track != null && track.duration() > 0) {
                    long previewMs = (long) (progress / 1000.0 * track.duration());
                    setText(clockElapsed, format(previewMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (controller != null && track != null && track.duration() > 0) {
                    long targetMs = (long) (bar.getProgress() / 1000.0 * track.duration());
                    controller.getTransportControls().seekTo(targetMs);
                }
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(-1, -2);
        seekLp.topMargin = dp(6);
        playerCard.addView(seekBar, seekLp);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setPadding(dp(4), 0, dp(4), 0);
        playerCard.addView(clockRow, new LinearLayout.LayoutParams(-1, -2));

        clockElapsed = label(clockRow, "--:--", 11, TEXT_SECONDARY);
        clockElapsed.setFontFeatureSettings("tnum");
        clockElapsed.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        clockDuration = label(clockRow, "--:--", 11, TEXT_SECONDARY);
        clockDuration.setFontFeatureSettings("tnum");
        clockDuration.setGravity(Gravity.END);
    }

    private enum TransportAction { PREV, PLAY_PAUSE, NEXT }

    private void sendTransport(TransportAction action) {
        if (controller == null) return;
        MediaController.TransportControls controls = controller.getTransportControls();
        switch (action) {
            case PREV -> controls.skipToPrevious();
            case NEXT -> controls.skipToNext();
            case PLAY_PAUSE -> {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    controls.pause();
                } else {
                    controls.play();
                }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        sessionCheckedAt = -1000;
        ticker.run();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        generation++;
        if (paletteAnimator != null) paletteAnimator.cancel();
        network.shutdownNow();
        super.onDestroy();
    }

    private void update() {
        long now = SystemClock.elapsedRealtime();
        if (now - sessionCheckedAt >= 1000) {
            sessionCheckedAt = now;
            refreshSession();
        }
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        Track current = LyricsData.track(metadata);
        if (!Objects.equals(track, current)) load(current, false);

        setText(title, track == null ? "Waiting for music" : track.title());
        setText(artist, track == null ? "No active session" : track.artist()
                + (track.album().isEmpty() ? "" : "  ·  " + track.album()));

        if (!Objects.equals(metadata, displayedMetadata)) {
            displayedMetadata = metadata;
            Bitmap cover = metadata == null ? null : metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (cover == null && metadata != null) {
                cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            showArtwork(cover);
            applyPalette(ArtColorExtractor.extract(cover), true);
        }

        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        if (playbackIcon != icon) {
            playbackIcon = icon;
            playBtn.setImageResource(icon);
        }
        String playbackAction = playing ? "Pause" : "Play";
        if (!TextUtils.equals(playBtn.getContentDescription(), playbackAction)) {
            playBtn.setContentDescription(playbackAction);
        }
        transportCluster.setVisibility(state == null ? View.GONE : View.VISIBLE);

        long raw = state == null ? -1 : state.getPosition();
        long position = LyricsData.position(state, track, now);
        long duration = track == null || track.duration() <= 0 ? -1 : track.duration();

        if (!seeking) {
            setText(clockElapsed, format(position));
            seekBar.setEnabled(duration > 0 && controller != null);
            seekBar.setProgress(duration <= 0 || position < 0 ? 0 : (int) (1000.0 * position / duration));
        }
        setText(clockDuration, format(duration));

        updateStateCard();
        updateFollowPill();
        highlight(position);

        if (diagnostics != null && diagnostics.isShowing()) {
            updateDiagnosticsContent(state, raw, position, now);
        }
    }

    private void updateStateCard() {
        boolean showCard = !sessionError.isEmpty() || lines.isEmpty();
        stateCard.setVisibility(showCard ? View.VISIBLE : View.GONE);
        scroll.setVisibility(showCard ? View.GONE : View.VISIBLE);
        if (!showCard) return;

        if (!sessionError.isEmpty()) {
            stateSpinner.setVisibility(View.GONE);
            stateIcon.setVisibility(View.VISIBLE);
            stateIcon.setImageResource(R.drawable.ic_notifications);
            setText(stateTitle, "Notification access needed");
            setText(stateBody, "Allow Now Lyrics to read active media sessions so it can fetch and sync lyrics.");
            stateAction.setVisibility(View.VISIBLE);
            stateActionIcon.setImageResource(R.drawable.ic_notifications);
            setText(stateActionLabel, "Open Settings");
            stateAction.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        } else if (track == null) {
            stateSpinner.setVisibility(View.GONE);
            stateIcon.setVisibility(View.VISIBLE);
            stateIcon.setImageResource(R.drawable.ic_music);
            setText(stateTitle, "Waiting for music");
            setText(stateBody, "Start playing a track in YouTube Music, Spotify, or any supported player.");
            stateAction.setVisibility(View.GONE);
        } else if (lookup.startsWith("Looking")) {
            stateSpinner.setVisibility(View.VISIBLE);
            stateIcon.setVisibility(View.GONE);
            setText(stateTitle, "Finding synced lyrics…");
            setText(stateBody, track.title() + " · " + track.artist());
            stateAction.setVisibility(View.GONE);
        } else {
            stateSpinner.setVisibility(View.GONE);
            stateIcon.setVisibility(View.VISIBLE);
            stateIcon.setImageResource(R.drawable.ic_music);
            setText(stateTitle, "No synced lyrics");
            setText(stateBody, lookup);
            stateAction.setVisibility(View.VISIBLE);
            stateActionIcon.setImageResource(R.drawable.ic_refresh);
            setText(stateActionLabel, "Retry");
            stateAction.setOnClickListener(v -> load(track, true));
        }
    }

    private void refreshSession() {
        MediaSessionManager manager = getSystemService(MediaSessionManager.class);
        ArrayList<String> packages = new ArrayList<>();
        controller = null;
        try {
            List<MediaController> active = manager.getActiveSessions(new ComponentName(this, SessionAccess.class));
            controller = SessionAccess.preferredSession(active);
            for (MediaController candidate : active) {
                packages.add(candidate.getPackageName());
            }
            sessionError = "";
        } catch (SecurityException denied) {
            sessionError = "Notification access required";
        }
        sessions = packages.isEmpty() ? "none" : String.join(", ", packages);
    }

    private void load(Track current, boolean forceRefresh) {
        if (!Objects.equals(track, current)) {
            setFollowing(true);
        }
        track = current;
        int request = ++generation;
        match = "";
        showLines(Collections.emptyList());
        if (current == null) {
            lookup = "Play a song in a music app";
            return;
        }
        lookup = "Looking up lyrics…";
        network.execute(() -> {
            LyricsData.Result result = LyricsData.lookup(this, current, forceRefresh);
            handler.post(() -> {
                if (request != generation) return;
                match = result.match();
                lookup = result.message();
                showLines(result.lines());
                update();
            });
        });
    }

    private void showLines(List<Line> replacement) {
        lines = replacement;
        highlighted = -1;
        rows.clear();
        lyrics.removeAllViews();
        scroll.scrollTo(0, 0);

        for (Line line : lines) {
            TextView row = label(lyrics, line.text(), 23, TEXT_UPCOMING);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setLineSpacing(dp(2), 1.2f);
            row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.setPivotX(0f);
            row.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x26FFFFFF), null, surface(Color.WHITE, 10)));
            row.setOnClickListener(v -> {
                if (controller != null) {
                    controller.getTransportControls().seekTo(line.time());
                    setFollowing(true);
                    highlight(line.time());
                    scrollToHighlighted();
                }
            });
            rows.add(row);
        }
    }

    private void highlight(long position) {
        int index = LyricsData.lineAt(lines, position);
        if (index == highlighted) return;

        highlighted = index;
        for (int i = 0; i < rows.size(); i++) {
            TextView row = rows.get(i);
            int targetColor = i < index ? TEXT_PAST : (i == index ? TEXT_PRIMARY : TEXT_UPCOMING);
            float targetScale = i == index ? 1.04f : 1.0f;

            if (row.getCurrentTextColor() != targetColor) {
                animateTextColor(row, targetColor);
            }
            if (Math.abs(row.getScaleX() - targetScale) > 0.005f) {
                row.animate().scaleX(targetScale).scaleY(targetScale).setDuration(180).start();
            }
        }
        if (index >= 0 && following) {
            scrollToHighlighted();
        }
    }

    private void animateTextColor(TextView view, int targetColor) {
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), view.getCurrentTextColor(), targetColor);
        anim.setDuration(180);
        anim.addUpdateListener(a -> view.setTextColor((int) a.getAnimatedValue()));
        anim.start();
    }

    private void scrollToHighlighted() {
        int index = highlighted;
        if (index < 0 || index >= rows.size()) return;
        TextView row = rows.get(index);
        scroll.post(() -> {
            if (following && highlighted == index && index < rows.size() && rows.get(index) == row) {
                int targetY = Math.max(0, row.getTop() - (int) (scroll.getHeight() * 0.34f));
                scroll.smoothScrollTo(0, targetY);
            }
        });
    }

    private void setFollowing(boolean value) {
        if (following == value) return;
        following = value;
        updateFollowPill();
    }

    private void updateFollowPill() {
        boolean shouldShow = !lines.isEmpty() && !following && sessionError.isEmpty();
        if (shouldShow && followPill.getVisibility() != View.VISIBLE) {
            followPill.setVisibility(View.VISIBLE);
            followPill.setTranslationY(dp(12));
            followPill.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else if (!shouldShow && followPill.getVisibility() == View.VISIBLE) {
            followPill.animate().alpha(0f).translationY(dp(12)).setDuration(150)
                    .withEndAction(() -> followPill.setVisibility(View.GONE)).start();
        }
    }

    private void applyPalette(Palette target, boolean animate) {
        Palette from = this.palette;
        this.palette = target;
        if (!animate) {
            renderPalette(target);
            return;
        }
        if (paletteAnimator != null) paletteAnimator.cancel();
        ArgbEvaluator eval = new ArgbEvaluator();
        paletteAnimator = ValueAnimator.ofFloat(0f, 1f);
        paletteAnimator.setDuration(420);
        paletteAnimator.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            Palette blended = new Palette(
                    (int) eval.evaluate(f, from.accent(), target.accent()),
                    (int) eval.evaluate(f, from.ambientTop(), target.ambientTop()),
                    (int) eval.evaluate(f, from.ambientBottom(), target.ambientBottom()),
                    (int) eval.evaluate(f, from.cardSurface(), target.cardSurface())
            );
            renderPalette(blended);
        });
        paletteAnimator.start();
    }

    private void renderPalette(Palette p) {
        ambientBg.setColors(new int[]{p.ambientTop(), p.ambientBottom()});
        playerSurface.setColor(p.cardSurface());

        int accentSoft = (0x26 << 24) | (p.accent() & 0x00FFFFFF);

        stateIconWrap.setBackground(surface(accentSoft, 26));
        stateIcon.setColorFilter(p.accent());
        stateSpinner.setIndeterminateTintList(ColorStateList.valueOf(p.accent()));

        stateAction.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), surface(p.accent(), 20), surface(Color.WHITE, 20)));
        stateActionIcon.setColorFilter(ON_ACCENT);

        followPill.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), surface(p.accent(), 20), surface(Color.WHITE, 20)));

        playBtn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), surface(p.accent(), 21), surface(Color.WHITE, 21)));

        seekBar.setProgressTintList(ColorStateList.valueOf(p.accent()));
        seekBar.setThumbTintList(ColorStateList.valueOf(p.accent()));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));

        if (statusBarSwitch != null) {
            statusBarSwitch.setThumbTintList(ColorStateList.valueOf(p.accent()));
            statusBarSwitch.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{(0x66 << 24) | (p.accent() & 0x00FFFFFF), 0x33FFFFFF}
            ));
        }

        if (!hasArtwork) {
            artwork.setColorFilter(p.accent());
        }
    }

    private void showDiagnostics() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable dialogBg = surface(0xFF171520, 22);
        dialogBg.setStroke(dp(1), 0x22FFFFFF);
        container.setBackground(dialogBg);
        container.setPadding(dp(20), dp(18), dp(20), dp(14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        TextView diagTitle = label(topRow, "Diagnostics", 19, TEXT_PRIMARY);
        diagTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        diagTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout copyBtn = new LinearLayout(this);
        copyBtn.setOrientation(LinearLayout.HORIZONTAL);
        copyBtn.setGravity(Gravity.CENTER_VERTICAL);
        copyBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        copyBtn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), surface(0xFF282334, 14), surface(Color.WHITE, 14)));
        ImageView copyIcon = new ImageView(this);
        copyIcon.setImageResource(R.drawable.ic_copy);
        copyIcon.setColorFilter(palette.accent());
        copyBtn.addView(copyIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView copyLabel = label(copyBtn, "Copy", 12, palette.accent());
        copyLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(-2, -2);
        cpLp.leftMargin = dp(6);
        copyLabel.setLayoutParams(cpLp);
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Now Lyrics Diagnostics", lastDiagnosticsText));
                Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show();
            }
        });
        topRow.addView(copyBtn, new LinearLayout.LayoutParams(-2, -2));

        ScrollView panel = new ScrollView(this);
        LinearLayout sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setPadding(0, dp(10), 0, dp(6));
        panel.addView(sections);

        diagSession = addDiagSection(sections, "SESSION");
        diagPlayback = addDiagSection(sections, "PLAYBACK");
        diagLyrics = addDiagSection(sections, "LYRICS");

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.topMargin = dp(4);
        container.addView(panel, scrollLp);

        TextView doneBtn = label(container, "Done", 14, palette.accent());
        doneBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        doneBtn.setPadding(dp(16), dp(10), dp(16), dp(10));
        doneBtn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), surface(Color.TRANSPARENT, 14), surface(Color.WHITE, 14)));
        doneBtn.setOnClickListener(v -> {
            if (diagnostics != null) diagnostics.dismiss();
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(-2, -2);
        doneLp.gravity = Gravity.END;
        doneLp.topMargin = dp(8);
        doneBtn.setLayoutParams(doneLp);

        diagnostics = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(container)
                .create();
        if (diagnostics.getWindow() != null) {
            diagnostics.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        diagnostics.show();
        update();
    }

    private TextView addDiagSection(LinearLayout parent, String heading) {
        TextView header = label(parent, heading, 11, palette.accent());
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        header.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-2, -2);
        hLp.topMargin = dp(10);
        hLp.bottomMargin = dp(4);
        header.setLayoutParams(hLp);

        TextView body = label(parent, "", 12, TEXT_SECONDARY);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackground(surface(0xFF211E2C, 12));
        body.setLineSpacing(dp(2), 1.15f);
        return body;
    }

    private void updateDiagnosticsContent(PlaybackState state, long raw, long position, long now) {
        String sessionStr = "Access: " + (sessionError.isEmpty() ? "granted" : sessionError)
                + "\nStatus bar: " + (Prefs.isStatusBarActive(this) ? "enabled" : "disabled")
                + "\nActive: " + sessions
                + "\nSelected: " + (controller == null ? "none" : controller.getPackageName())
                + "\nMedia ID: " + (track == null || track.id().isEmpty() ? "-" : track.id());

        String playbackStr = "State: " + (state == null ? "unavailable" : state.getState())
                + "\nPos (raw/est): " + raw + " / " + position + " ms"
                + "\nDuration: " + (track == null ? 0 : track.duration()) + " ms"
                + "\nSpeed: " + (state == null ? "-" : state.getPlaybackSpeed())
                + "\nState age: " + (state == null || state.getLastPositionUpdateTime() <= 0
                ? "-" : (now - state.getLastPositionUpdateTime()) + " ms");

        String lyricsStr = "Status: " + lookup
                + "\nQuery: " + (track == null ? "-" : track.title() + " / " + track.artist())
                + "\nMatch: " + (match.isEmpty() ? "-" : match)
                + "\nTimed lines: " + lines.size()
                + "\nActive line: " + (highlighted < 0 ? "-" : (highlighted + 1) + " @ " + lines.get(highlighted).time() + " ms");

        setText(diagSession, sessionStr);
        setText(diagPlayback, playbackStr);
        setText(diagLyrics, lyricsStr);
        lastDiagnosticsText = "[SESSION]\n" + sessionStr + "\n\n[PLAYBACK]\n" + playbackStr + "\n\n[LYRICS]\n" + lyricsStr;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private static void setText(TextView view, String text) {
        if (view != null && !TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static String format(long millis) {
        return millis < 0 ? "--:--" : String.format(Locale.ROOT, "%d:%02d", millis / 60000, millis / 1000 % 60);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(LinearLayout parent, String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (parent != null) parent.addView(view);
        return view;
    }

    private void showArtwork(Bitmap cover) {
        artwork.setColorFilter(null);
        hasArtwork = cover != null;
        if (cover == null) {
            artwork.setScaleType(ImageView.ScaleType.CENTER);
            artwork.setImageResource(R.drawable.ic_music);
            artwork.setColorFilter(palette.accent());
        } else {
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setImageBitmap(cover);
        }
    }

    private ImageButton iconButton(LinearLayout parent, int icon, String description,
            int sizeDp, int background, int tint, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        int radius = sizeDp / 2;
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), surface(background, radius), surface(Color.WHITE, radius)));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return button;
    }
}
