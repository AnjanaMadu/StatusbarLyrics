package dev.nowlyrics;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Extracts a harmonious dark-mode palette (accent + ambient background) from album artwork.
 */
final class ArtColorExtractor {
    static final int BASE_BG = 0xFF0E0D12;
    static final int DEFAULT_ACCENT = 0xFFE5C9FF;

    record Palette(int accent, int ambientTop, int ambientBottom, int cardSurface) {}

    static final Palette DEFAULT = new Palette(
            DEFAULT_ACCENT,
            0xFF221930,
            BASE_BG,
            0xE81C1824
    );

    private ArtColorExtractor() {}

    static Palette extract(Bitmap source) {
        if (source == null || source.isRecycled()) return DEFAULT;

        Bitmap sample = Bitmap.createScaledBitmap(source, 24, 24, true);
        int width = sample.getWidth();
        int height = sample.getHeight();
        int[] pixels = new int[width * height];
        sample.getPixels(pixels, 0, width, 0, 0, width, height);
        if (sample != source) sample.recycle();

        // 12 hue buckets (30 degrees each) to find the dominant vibrant hue
        float[] bucketScore = new float[12];
        float[] bucketHueSum = new float[12];
        float[] bucketSatSum = new float[12];
        float[] bucketWeight = new float[12];
        float[] hsv = new float[3];

        for (int pixel : pixels) {
            if (Color.alpha(pixel) < 128) continue;
            Color.colorToHSV(pixel, hsv);
            float s = hsv[1];
            float v = hsv[2];
            if (v < 0.14f || v > 0.96f || s < 0.15f) continue;

            int bucket = ((int) (hsv[0] / 30f)) % 12;
            float weight = s * (1f - Math.abs(v - 0.55f));
            bucketScore[bucket] += weight;
            bucketHueSum[bucket] += hsv[0] * weight;
            bucketSatSum[bucket] += s * weight;
            bucketWeight[bucket] += weight;
        }

        int bestBucket = -1;
        float bestScore = 0f;
        for (int i = 0; i < 12; i++) {
            if (bucketScore[i] > bestScore) {
                bestScore = bucketScore[i];
                bestBucket = i;
            }
        }
        if (bestBucket < 0 || bucketWeight[bestBucket] <= 0.01f) return DEFAULT;

        float hue = bucketHueSum[bestBucket] / bucketWeight[bestBucket];
        float sat = bucketSatSum[bestBucket] / bucketWeight[bestBucket];

        // Pastel, high-contrast accent for dark backgrounds
        int accent = Color.HSVToColor(new float[]{
                hue,
                clamp(sat * 0.55f, 0.25f, 0.52f),
                0.96f
        });

        // Deep atmospheric top glow
        int ambientTop = Color.HSVToColor(new float[]{
                hue,
                clamp(sat * 0.72f, 0.28f, 0.65f),
                0.24f
        });

        // Subtle tinted surface for the floating player dock
        int cardRaw = Color.HSVToColor(new float[]{
                hue,
                clamp(sat * 0.42f, 0.14f, 0.36f),
                0.15f
        });
        int cardSurface = (0xE8 << 24) | (cardRaw & 0x00FFFFFF);

        return new Palette(accent, ambientTop, BASE_BG, cardSurface);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
