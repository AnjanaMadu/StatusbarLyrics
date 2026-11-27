package dev.nowlyrics;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.nowlyrics.LyricsData.Line;
import dev.nowlyrics.LyricsData.Track;

public final class SessionAccess extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::observeSessions;
    private final MediaController.Callback playbackChanged = new MediaController.Callback() {
        @Override public void onMetadataChanged(android.media.MediaMetadata metadata) {
            update();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override public void onSessionDestroyed() {
            if (manager != null) {
                try {
                    observeSessions(manager.getActiveSessions(new ComponentName(SessionAccess.this, SessionAccess.class)));
                } catch (SecurityException denied) {
                    disconnect();
                }
            }
        }
    };

    private final Runnable tick = this::update;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged =
            (prefs, key) -> handler.post(this::update);
    private MediaSessionManager manager;
    private final Map<MediaSession.Token, MediaController> observed = new LinkedHashMap<>();
    private Track track;
    private List<Line> lines = Collections.emptyList();
    private int generation;

    @Override public void onListenerConnected() {
        if (manager != null) return;
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsChanged);
        MediaSessionManager sessions = getSystemService(MediaSessionManager.class);
        ComponentName listener = new ComponentName(this, SessionAccess.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
            manager = sessions;
            observeSessions(sessions.getActiveSessions(listener));
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
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsChanged);
        if (manager != null) manager.removeOnActiveSessionsChangedListener(sessionsChanged);
        manager = null;
        observeSessions(Collections.emptyList());
    }

    private void observeSessions(List<MediaController> sessions) {
        Map<MediaSession.Token, MediaController> next = new LinkedHashMap<>();
        if (manager != null && sessions != null) {
            for (MediaController candidate : sessions) {
                MediaSession.Token token = candidate.getSessionToken();
                if (next.containsKey(token)) continue;
                MediaController existing = observed.remove(token);
                if (existing == null) {
                    candidate.registerCallback(playbackChanged, handler);
                    existing = candidate;
                }
                next.put(token, existing);
            }
        }
        for (MediaController removed : observed.values()) removed.unregisterCallback(playbackChanged);
        observed.clear();
        observed.putAll(next);
        update();
    }

    static MediaController preferredSession(Iterable<MediaController> sessions) {
        MediaController paused = null;
        for (MediaController candidate : sessions) {
            PlaybackState state = candidate.getPlaybackState();
            if (state == null || LyricsData.track(candidate.getMetadata()) == null) continue;
            if (state.getState() == PlaybackState.STATE_PLAYING) return candidate;
            if (paused == null && (state.getState() == PlaybackState.STATE_PAUSED
                    || state.getState() == PlaybackState.STATE_BUFFERING
                    || state.getState() == PlaybackState.STATE_CONNECTING)) {
                paused = candidate;
            }
        }
        return paused;
    }

    private void update() {
        handler.removeCallbacks(tick);
        if (!Prefs.isStatusBarActive(this)) {
            track = null;
            generation++;
            lines = Collections.emptyList();
            lookupRequested = false;
            StatusLyricProvider.publish(this, "");
            return;
        }
        MediaController controller = preferredSession(observed.values());
        Track current = controller == null ? null : LyricsData.track(controller.getMetadata());
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        if (!Objects.equals(track, current)) {
            track = current;
            generation++;
            lines = Collections.emptyList();
            lookupRequested = false;
        }
        if (playing && current != null && !lookupRequested) {
            lookupRequested = true;
            int request = generation;
            network.execute(() -> {
                List<Line> result = LyricsData.lookup(this, current, false).lines();
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
