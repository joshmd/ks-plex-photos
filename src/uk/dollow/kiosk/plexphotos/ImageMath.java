// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

/**
 * Sizing decisions for {@link ImageFitter}, kept free of Android classes so they can be tested.
 * Decoding happens inside the Kiosk Satellite process, so every decode is kept small.
 */
final class ImageMath {
    /** Largest bitmap ever decoded: about 32 MB at 4 bytes per pixel. */
    static final long MAX_DECODE_PIXELS = 8_000_000L;

    private ImageMath() { }

    /**
     * Power-of-two inSampleSize for decoding a srcW x srcH image that will be shown at wantW x
     * wantH: as small as possible without dropping below the wanted size, then doubled until the
     * decoded bitmap is at most {@link #MAX_DECODE_PIXELS}.
     */
    static int sampleSize(int srcW, int srcH, int wantW, int wantH) {
        int sample = 1;
        while (srcW / (sample * 2) >= wantW && srcH / (sample * 2) >= wantH) sample *= 2;
        while ((long) (srcW / sample) * (srcH / sample) > MAX_DECODE_PIXELS) sample *= 2;
        return sample;
    }

    /** The centred region of a w x h image with the box's shape, as {x, y, width, height}. */
    static int[] coverCrop(int w, int h, int boxW, int boxH) {
        double box = (double) boxW / boxH;
        if ((double) w / h > box) {
            int cw = Math.max(1, Math.min(w, (int) Math.round(h * box)));
            return new int[] {(w - cw) / 2, 0, cw, h};
        }
        int ch = Math.max(1, Math.min(h, (int) Math.round(w / box)));
        return new int[] {0, (h - ch) / 2, w, ch};
    }

    /** Scale that fits a w x h image inside the box without enlarging it. */
    static double fitScale(int w, int h, int boxW, int boxH) {
        return Math.min(1.0, Math.min((double) boxW / w, (double) boxH / h));
    }
}
