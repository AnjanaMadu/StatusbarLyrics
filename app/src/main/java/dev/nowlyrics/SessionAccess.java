package dev.nowlyrics;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.nowlyrics.LyricsData.Line;
import dev.nowlyrics.LyricsData.Track;

public final class SessionAccess extends NotificationListenerService {
    static final String YTM = "com.google.android.apps.youtube.music";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::selectSession;
    private final MediaController.Callback playbackChanged = new MediaController.Callback() {
        @Override public void onMetadataChanged(android.media.MediaMetadata metadata) {
            update();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override public void onSessionDestroyed() {
            selectSession(List.of());
        }
    };

    private final Runnable tick = this::update;
    private MediaSessionManager manager;
    private MediaController controller;
    private Track track;
    private List<Line> lines = List.of();
    private int generation;

    @Override public void onListenerConnected() {
        if (manager != null) return;
        MediaSessionManager sessions = getSystemService(MediaSessionManager.class);
        ComponentName listener = new ComponentName(this, SessionAccess.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
            manager = sessions;
            selectSession(sessions.getActiveSessions(listener));
        } catch (SecurityException denied) {
            disconnect();
        }
    }

    @Override public void onListenerDisconnected() {
        disconnect();
    }

    @Override public void onDestroy() {
        disconnect();
        network.shutdownNow();
        super.onDestroy();
    }

    private void disconnect() {
        if (manager != null) manager.removeOnActiveSessionsChangedListener(sessionsChanged);
        manager = null;
        selectSession(List.of());
    }

    private void selectSession(List<MediaController> sessions) {
        MediaController selected = null;
        if (manager != null && sessions != null) {
            for (MediaController candidate : sessions) {
                if (YTM.equals(candidate.getPackageName())) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (controller == null || selected == null
                || !controller.getSessionToken().equals(selected.getSessionToken())) {
            if (controller != null) controller.unregisterCallback(playbackChanged);
            controller = selected;
            if (controller != null) controller.registerCallback(playbackChanged, handler);
        }
        update();
    }

    private void update() {
        handler.removeCallbacks(tick);
        Track current = controller == null ? null : LyricsData.track(controller.getMetadata());
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        if (!Objects.equals(track, current)) {
            track = current;
            generation++;
            lines = List.of();
            lookupRequested = false;
        }
        if (playing && current != null && !lookupRequested) {
            lookupRequested = true;
            int request = generation;
            network.execute(() -> {
                List<Line> result = LyricsData.lookup(current).lines();
                handler.post(() -> {
                    if (request != generation) return;
                    lines = result;
                    update();
                });
            });
        }
        long position = LyricsData.position(state, current, SystemClock.elapsedRealtime());
        int index = playing ? LyricsData.lineAt(lines, position) : -1;
        StatusLyricProvider.publish(this, index < 0 ? "" : lines.get(index).text());
        int next = index + 1;
        if (playing && next < lines.size() && position >= 0 && state.getPlaybackSpeed() > 0) {
            long delay = (long) Math.ceil((lines.get(next).time() - position) / state.getPlaybackSpeed());
            handler.postDelayed(tick, Math.max(25, delay));
        }
    }

    private boolean lookupRequested;
}
