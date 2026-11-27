package dev.nowlyrics;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import dev.nowlyrics.LyricsData.Line;
import dev.nowlyrics.LyricsData.Result;
import dev.nowlyrics.LyricsData.Track;

/**
 * Persistent disk-based lyrics cache with in-flight request deduplication.
 */
final class LyricsCache {
    private static final long POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    private static final long NEGATIVE_TTL_MS = 15L * 60 * 1000;           // 15 minutes
    private static final int MAX_DISK_ENTRIES = 200;

    private record Entry(Result result, long cachedAt, boolean positive) {
        boolean isExpired(long now) {
            long ttl = positive ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
            return now - cachedAt > ttl;
        }
    }

    private static final ConcurrentHashMap<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    private LyricsCache() {}

    static Result getOrFetch(Context context, Track track, boolean forceRefresh, Supplier<Result> fetcher) {
        String key = cacheKey(track);
        long now = System.currentTimeMillis();

        if (!forceRefresh && context != null) {
            Entry disk = readDisk(context, key, now);
            if (disk != null) {
                return markCached(disk.result());
            }
        }

        // Deduplicate concurrent requests for the same track (e.g. MainActivity + SessionAccess).
        boolean[] isLeader = {false};
        CompletableFuture<Result> future = inFlight.computeIfAbsent(key, k -> {
            isLeader[0] = true;
            return new CompletableFuture<>();
        });

        if (!isLeader[0]) {
            try {
                return future.get();
            } catch (Exception ignored) {
                return fetcher.get();
            }
        }

        try {
            Result result = fetcher.get();
            if (shouldCache(result) && context != null) {
                Entry entry = new Entry(result, System.currentTimeMillis(), !result.lines().isEmpty());
                writeDisk(context, key, entry);
            }
            future.complete(result);
            return result;
        } catch (Throwable error) {
            future.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(key, future);
        }
    }

    private static boolean shouldCache(Result result) {
        if (result == null) return false;
        if (!result.lines().isEmpty()) return true;
        // Cache confirmed "no lyrics" responses briefly, but never cache transient network errors.
        String msg = result.message();
        return msg != null && !msg.contains("Lookup failed:") && !msg.contains("timed out")
                && !msg.contains("Unable to resolve host");
    }

    private static Result markCached(Result result) {
        String msg = result.message();
        if (msg.endsWith("· Cached")) return result;
        return new Result(result.lines(), result.match(), msg + " · Cached");
    }

    private static String cacheKey(Track track) {
        long durationBucket = track.duration() > 0 ? track.duration() / 2000 : 0;
        return normalize(track.title()) + "|" + normalize(track.artist()) + "|" + durationBucket;
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N}]", "");
    }

    private static File cacheDir(Context context) {
        File dir = new File(context.getApplicationContext().getCacheDir(), "lyrics_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File entryFile(Context context, String key) {
        return new File(cacheDir(context), sha256(key) + ".json");
    }

    private static Entry readDisk(Context context, String key, long now) {
        File file = entryFile(context, key);
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = in.readAllBytes();
            JSONObject obj = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            long cachedAt = obj.optLong("cachedAt", 0);
            JSONArray arr = obj.optJSONArray("lines");
            List<Line> lines = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray item = arr.optJSONArray(i);
                    if (item != null && item.length() >= 2) {
                        lines.add(new Line(item.optLong(0), item.optString(1)));
                    }
                }
            }
            Entry entry = new Entry(
                    new Result(Collections.unmodifiableList(lines),
                            obj.optString("match", ""),
                            obj.optString("message", "")),
                    cachedAt,
                    !lines.isEmpty()
            );
            if (entry.isExpired(now)) {
                file.delete();
                return null;
            }
            file.setLastModified(now);
            return entry;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeDisk(Context context, String key, Entry entry) {
        try {
            File dir = cacheDir(context);
            File file = entryFile(context, key);
            JSONObject obj = new JSONObject();
            obj.put("cachedAt", entry.cachedAt());
            obj.put("match", entry.result().match());
            obj.put("message", entry.result().message());
            JSONArray arr = new JSONArray();
            for (Line line : entry.result().lines()) {
                JSONArray item = new JSONArray();
                item.put(line.time());
                item.put(line.text());
                arr.put(item);
            }
            obj.put("lines", arr);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
            pruneIfNeeded(dir);
        } catch (Exception ignored) {}
    }

    private static void pruneIfNeeded(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_DISK_ENTRIES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int toDelete = files.length - MAX_DISK_ENTRIES;
        for (int i = 0; i < toDelete; i++) {
            files[i].delete();
        }
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
