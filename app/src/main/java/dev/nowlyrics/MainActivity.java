package dev.nowlyrics;

import dev.nowlyrics.LyricsData.Line;
import dev.nowlyrics.LyricsData.Track;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int ACCENT = 0xffe5c9ff;
    private static final int MUTED = 0xffaaa2b2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<TextView> rows = new ArrayList<>();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, 200);
        }
    };

    private TextView title, artist, clock, status, details;
    private Button access, follow, retry;
    private android.widget.ImageButton playback;
    private int playbackIcon;
    private ImageView artwork;
    private ProgressBar progress;
    private boolean following = true;
    private android.app.AlertDialog diagnostics;
    private MediaMetadata displayedMetadata;
    private LinearLayout lyrics;
    private ScrollView scroll;
    private MediaController controller;
    private long sessionCheckedAt = -1000;
    private String sessions = "", sessionError = "", lookup = "Idle", match = "";
    private Track track;
    private int generation, highlighted = -1;
    private List<Line> lines = java.util.Collections.emptyList();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xff141218);
        getWindow().setNavigationBarColor(0xff141218);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff141218);
        root.setPadding(dp(20), dp(6), dp(20), dp(10));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.addView(header);
        TextView heading = label(null, "Lyrics", 27, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        iconButton(header, R.drawable.ic_info, "Diagnostics", Color.TRANSPARENT, ACCENT, this::showDiagnostics);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(8));
        GradientDrawable playerSurface = surface(0xe626212e, 22);
        playerSurface.setStroke(dp(1), 0x36e5c9ff);
        card.setBackground(playerSurface);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(8);


        LinearLayout trackRow = new LinearLayout(this);
        trackRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(trackRow);
        artwork = new ImageView(this);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackground(surface(0xff3a3045, 13));
        artwork.setClipToOutline(true);
        showArtwork(null);
        trackRow.addView(artwork, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout metadata = new LinearLayout(this);
        metadata.setOrientation(LinearLayout.VERTICAL);
        metadata.setPadding(dp(10), 0, dp(6), 0);
        trackRow.addView(metadata, new LinearLayout.LayoutParams(0, -2, 1));
        title = label(metadata, "Waiting for music", 19, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = label(metadata, "", 14, MUTED);
        artist.setMaxLines(1);
        artist.setEllipsize(TextUtils.TruncateAt.END);

        playback = iconButton(trackRow, R.drawable.ic_play, "Play", ACCENT, 0xff291735, () -> {
            if (controller == null) return;
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                controller.getTransportControls().pause();
            } else {
                controller.getTransportControls().play();
            }
        });

        LinearLayout timeline = new LinearLayout(this);
        timeline.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(timeline);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff53465e));
        timeline.addView(progress, new LinearLayout.LayoutParams(0, dp(10), 1));
        clock = label(timeline, "", 11, MUTED);
        clock.setPadding(dp(10), 0, 0, 0);
        clock.setFontFeatureSettings("tnum");
        access = button(root, "Grant notification access",
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.addView(actions);
        actions.setGravity(android.view.Gravity.END);
        retry = button(actions, "Retry", () -> load(track));
        follow = button(actions, "Following", () -> {
            following = !following;
            updateFollow();
            if (following) scrollToHighlighted();
        });
        status = label(root, "", 13, MUTED);
        status.setPadding(0, 0, 0, dp(8));

        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        lyrics = new LinearLayout(this);
        lyrics.setOrientation(LinearLayout.VERTICAL);
        lyrics.setPadding(0, dp(24), 0, dp(40));
        scroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && following) {
                following = false;
                updateFollow();
            }
            return false;
        });
        scroll.addView(lyrics);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(card, cardParams);
    }

    @Override protected void onStart() {
        super.onStart();
        sessionCheckedAt = -1000;
        ticker.run();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override protected void onDestroy() {
        generation++;
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
        if (!java.util.Objects.equals(track, current)) load(current);

        setText(title, track == null ? "Waiting for music" : track.title());
        if (!java.util.Objects.equals(metadata, displayedMetadata)) {
            displayedMetadata = metadata;
            android.graphics.Bitmap cover = metadata == null ? null
                    : metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (cover == null && metadata != null) {
                cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            showArtwork(cover);
        }
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        if (playbackIcon != icon) {
            playbackIcon = icon;
            playback.setImageResource(icon);
        }
        String playbackAction = playing ? "Pause" : "Play";
        if (!TextUtils.equals(playback.getContentDescription(), playbackAction)) {
            playback.setContentDescription(playbackAction);
        }
        playback.setVisibility(state == null ? View.GONE : View.VISIBLE);
        setText(artist, track == null ? "" : track.artist() +
                (track.album().isEmpty() ? "" : "  ·  " + track.album()));
        long raw = state == null ? -1 : state.getPosition();
        long position = LyricsData.position(state, track, now);
        setText(clock, format(position) + " / " + format(track == null || track.duration() <= 0
                ? -1 : track.duration()));
        progress.setProgress(track == null || track.duration() <= 0 || position < 0 ? 0 : (int) (1000.0 * position / track.duration()));
        setText(status, !sessionError.isEmpty() ? sessionError : track == null ? "Play a song in a music app" : lookup);
        retry.setVisibility(track != null && lines.isEmpty() && !lookup.startsWith("Looking") ? View.VISIBLE : View.GONE);
        follow.setVisibility(lines.isEmpty() || following ? View.GONE : View.VISIBLE);
        status.setVisibility(lines.isEmpty() ? View.VISIBLE : View.GONE);
        highlight(position);
        if (diagnostics != null && diagnostics.isShowing()) {
            details.setText("Session access: " + (sessionError.isEmpty() ? "granted" : sessionError)
                    + "\nActive sessions: " + sessions
                    + "\nSelected: " + (controller == null ? "none" : controller.getPackageName())
                    + "\nMedia ID: " + (track == null ? "" : track.id())
                    + "\nPlaybackState: " + (state == null ? "unavailable" : state.getState())
                    + "\nRaw / estimated: " + raw + " / " + position + " ms"
                    + "\nReported duration: " + (track == null ? 0 : track.duration()) + " ms"
                    + "\nSpeed: " + (state == null ? "-" : state.getPlaybackSpeed())
                    + "\nState age: " + (state == null || state.getLastPositionUpdateTime() <= 0
                    ? "-" : (now - state.getLastPositionUpdateTime()) + " ms")
                    + "\nLyrics: " + lookup
                    + "\nQuery: " + (track == null ? "-" : track.title() + " / " + track.artist())
                    + "\nMatch: " + match
                    + "\nTimed lines: " + lines.size() + " · Active line: "
                    + (highlighted < 0 ? "-" : (highlighted + 1) + " @ " + lines.get(highlighted).time() + " ms"));
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
        access.setVisibility(sessionError.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void load(Track current) {
        if (!java.util.Objects.equals(track, current)) {
            following = true;
            updateFollow();
        }
        track = current;
        int request = ++generation;
        match = "";
        showLines(java.util.Collections.emptyList());
        if (current == null) {
            lookup = "Play a song in a music app";
            return;
        }
        lookup = "Looking up lyrics…";
        network.execute(() -> {
            LyricsData.Result result = LyricsData.lookup(current);
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
            TextView row = label(lyrics, line.text(), 24, MUTED);
            row.setPadding(dp(3), dp(8), dp(3), dp(8));
            row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            rows.add(row);
        }
    }

    private void highlight(long position) {
        int index = LyricsData.lineAt(lines, position);
        if (index == highlighted) return;
        if (highlighted >= 0) {
            TextView previous = rows.get(highlighted);
            previous.setTextColor(MUTED);


        }
        highlighted = index;
        if (index >= 0) {
            TextView row = rows.get(index);
            row.setTextColor(ACCENT);


            if (following) scrollToHighlighted();
        }
    }

    private void scrollToHighlighted() {
        int index = highlighted;
        if (index < 0) return;
        TextView row = rows.get(index);
        scroll.post(() -> {
            if (following && highlighted == index && rows.get(index) == row) {
                scroll.smoothScrollTo(0, Math.max(0, row.getTop() - scroll.getHeight() / 3));
            }
        });
    }

    private void updateFollow() {
        setText(follow, following ? "Following" : "Resume follow");
        follow.setSelected(following);
    }

    private void showDiagnostics() {
        ScrollView panel = new ScrollView(this);
        details = label(null, "", 12, MUTED);
        details.setTypeface(Typeface.MONOSPACE);
        details.setPadding(dp(20), dp(8), dp(20), dp(8));
        panel.addView(details);
        diagnostics = new android.app.AlertDialog.Builder(this)
                .setTitle("Diagnostics")
                .setView(panel)
                .setPositiveButton("Done", null)
                .create();
        diagnostics.show();
        update();
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private static void setText(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
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

    private void showArtwork(android.graphics.Bitmap cover) {
        artwork.setColorFilter(null);
        if (cover == null) {
            artwork.setScaleType(ImageView.ScaleType.CENTER);
            artwork.setImageResource(R.drawable.ic_music);
            artwork.setColorFilter(ACCENT);
        } else {
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setImageBitmap(cover);
        }
    }

    private android.widget.ImageButton iconButton(LinearLayout parent, int icon, String description,
            int background, int tint, Runnable action) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(tint));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x446d4b83), surface(background, 24), surface(Color.WHITE, 24)));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }
    private Button button(LinearLayout parent, String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(ACCENT);
        button.setMinHeight(dp(44));
        button.setMinimumWidth(dp(44));
        button.setMinWidth(dp(44));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33e5c9ff), surface(0xff302738, 14), null));
        button.setOnClickListener(view -> click.run());
        parent.addView(button);
        return button;
    }
}
