package dev.nowlyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LyricsData {
    private static final String NETEASE = "https://music.163.com/api/";
    private static final long NETEASE_MAX_DURATION_DIFF_MS = 8000;
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
        Result primary;
        try {
            primary = lookupNetEase(current);
            if (!primary.lines().isEmpty()) return primary;
        } catch (Exception error) {
            primary = new Result(Collections.emptyList(), "", "NetEase · " + error.getMessage());
        }
        Result fallback = lookupLrclib(current);
        return new Result(fallback.lines(), fallback.match(),
                fallback.message() + " · " + primary.message());
    }

    private static Result lookupNetEase(Track track) throws Exception {
        JSONObject search = fetch(NETEASE + "search/pc?type=1&offset=0&limit=10&s="
                + encode(track.title() + " " + track.artist()), 2500);
        if (search == null) return new Result(Collections.emptyList(), "", "NetEase · No search results");
        if (search.optInt("code") != 200) {
            return new Result(Collections.emptyList(), "", "NetEase · Search code " + search.optInt("code"));
        }
        JSONObject results = search.optJSONObject("result");
        JSONArray songs = results == null ? null : results.optJSONArray("songs");
        if (songs == null) return new Result(Collections.emptyList(), "", "NetEase · No search results");

        String title = normalize(track.title());
        String artist = normalize(track.artist());
        String album = normalize(track.album());
        JSONObject best = null;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null || song.optLong("id") <= 0
                    || !title.equals(normalize(song.optString("name")))) continue;
            JSONArray artists = song.optJSONArray("artists");
            boolean artistMatches = artist.isEmpty();
            StringBuilder names = new StringBuilder();
            for (int j = 0; artists != null && j < artists.length(); j++) {
                JSONObject entry = artists.optJSONObject(j);
                String name = entry == null ? "" : normalize(entry.optString("name"));
                if (name.equals(artist)) artistMatches = true;
                names.append(name);
            }
            if (!artistMatches && !artist.equals(names.toString())) continue;

            long length = song.optLong("duration");
            long difference = track.duration() > 0 && length > 0
                    ? Math.abs(track.duration() - length) : 0;
            if (difference > NETEASE_MAX_DURATION_DIFF_MS) continue;
            JSONObject release = song.optJSONObject("album");
            boolean albumMatches = release != null && album.equals(normalize(release.optString("name")));
            long score = difference + (albumMatches ? 0 : 1000);
            if (score < bestScore) {
                best = song;
                bestScore = score;
            }
        }
        if (best == null) return new Result(Collections.emptyList(), "", "NetEase · No matching track");

        long id = best.optLong("id");
        JSONObject response = fetch(NETEASE + "song/lyric?os=pc&lv=-1&kv=-1&tv=-1&id=" + id, 2500);
        JSONObject lrc = response == null ? null : response.optJSONObject("lrc");
        List<Line> lines = lrc == null ? Collections.emptyList() : parse(lrc.optString("lyric"));
        if (lines.stream().noneMatch(line -> !line.text().isEmpty())) {
            return new Result(Collections.emptyList(), "", "NetEase · Match has no synced lyrics");
        }
        return new Result(lines, best.optString("name") + " · ID " + id, "NetEase · Synced lyrics");
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]", "");
    }

    private static Result lookupLrclib(Track current) {
        List<Line> result = Collections.emptyList();
        String found = "", message;
        try {
            String query = "https://lrclib.net/api/get?track_name=" + encode(current.title())
                    + "&artist_name=" + encode(current.artist())
                    + (current.album().isEmpty() ? "" : "&album_name=" + encode(current.album()))
                    + (current.duration() < 500 || current.duration() > 3600000 ? "" : "&duration=" + Math.round(current.duration() / 1000.0));
            JSONObject song = fetch(query, 7000);
            if (song == null && !current.album().isEmpty()) {
                song = fetch(query.replace("&album_name=" + encode(current.album()), ""), 7000);
            }
            if (song == null) {
                message = "No match (HTTP 404)";
            } else {
                found = song.optString("trackName") + " / " + song.optString("artistName")
                        + " · " + song.optDouble("duration") + " s · ID " + song.optLong("id");
                result = parse(song.optString("syncedLyrics", ""));
                message = result.isEmpty() ? "Match has no synced lyrics" : "Synced lyrics";
            }
        } catch (Exception error) {
            message = "Lookup failed: " + error.getClass().getSimpleName() + " · " + error.getMessage();
        }
        return new Result(result, found, "LRCLIB · " + message);
    }

    private static JSONObject fetch(String url, int timeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
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
