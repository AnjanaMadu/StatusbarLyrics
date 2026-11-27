package dev.nowlyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LyricsData {
    private static final Pattern TIME = Pattern.compile("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\[offset:([+-]?\\d+)\\]");
    record Track(String title, String artist, String album, long duration, String id) {}
    record Line(long time, String text) {}

    record Result(List<Line> lines, String match, String message) {}

    static Track track(MediaMetadata metadata) {
        Track current = metadata == null ? null : new Track(
                value(metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                value(metadata, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                value(metadata, MediaMetadata.METADATA_KEY_ALBUM),
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                value(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID));
        if (current != null && current.title().isEmpty()) current = null;
        return current;
    }

    static long position(PlaybackState state, Track track, long now) {
        long raw = state == null ? -1 : state.getPosition();
        long position = raw;
        if (raw >= 0 && state.getLastPositionUpdateTime() > 0
                && state.getState() == PlaybackState.STATE_PLAYING) {
            position = Math.max(0, raw + (long) ((now - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed()));
        }
        if (track != null && track.duration() > 0) position = Math.min(position, track.duration());
        return position;
    }

    static int lineAt(List<Line> lines, long position) {
        int low = 0, high = lines.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).time() <= position) low = middle + 1;
            else high = middle;
        }
        return low - 1;
    }

    static Result lookup(Track current) {
        List<Line> result = java.util.Collections.emptyList();
        String found = "", message;
        try {
            String query = "https://lrclib.net/api/get?track_name=" + encode(current.title())
                    + "&artist_name=" + encode(current.artist())
                    + (current.album().isEmpty() ? "" : "&album_name=" + encode(current.album()))
                    + (current.duration() < 500 || current.duration() > 3600000 ? "" : "&duration=" + Math.round(current.duration() / 1000.0));
            JSONObject song = fetch(query);
            if (song == null && !current.album().isEmpty()) {
                song = fetch(query.replace("&album_name=" + encode(current.album()), ""));
            }
            if (song == null) {
                message = "No LRCLIB match (HTTP 404)";
            } else {
                found = song.optString("trackName") + " / " + song.optString("artistName")
                        + " · " + song.optDouble("duration") + " s · ID " + song.optLong("id");
                result = parse(song.optString("syncedLyrics", ""));
                message = result.isEmpty() ? "Match has no synced lyrics" : "Synced lyrics";
            }
        } catch (Exception error) {
            message = "Lookup failed: " + error.getClass().getSimpleName() + " · " + error.getMessage();
        }
        return new Result(result, found, message);
    }

    private static JSONObject fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("User-Agent", "Now Lyrics/1.1 (Android)");
            int code = connection.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream stream = connection.getInputStream()) {
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) body.write(buffer, 0, read);
                return new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<Line> parse(String lrc) {
        ArrayList<Line> result = new ArrayList<>();
        Matcher offsetTag = OFFSET.matcher(lrc);
        long offset = offsetTag.find() ? Long.parseLong(offsetTag.group(1)) : 0;
        for (String row : lrc.split("\\R")) {
            Matcher tags = TIME.matcher(row);
            String words = TIME.matcher(row).replaceAll("").trim();
            while (tags.find()) {
                String fraction = tags.group(3);
                long millis = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
                long stamp = Long.parseLong(tags.group(1)) * 60000 + Long.parseLong(tags.group(2)) * 1000;
                result.add(new Line(Math.max(0, stamp + millis - offset), words));
            }
        }
        result.sort(Comparator.comparingLong(Line::time));
        return result;
    }

    private static String value(MediaMetadata data, String... keys) {
        for (String key : keys) {
            String value = data.getString(key);
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String encode(String text) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(text, "UTF-8");
    }

}
