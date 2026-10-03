// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Shrinks a photo until its base64 form fits inside KS's 512 KiB inline screensaver document,
 * applying EXIF orientation so the browser and the bitmap agree on which way is up.
 */
final class ImageFitter {
    /** Raw JPEG budget for the main image. Base64 adds a third, leaving room for HTML and the thumb. */
    static final int MAIN_BUDGET = 340 * 1024;
    static final int THUMB_BUDGET = 28 * 1024;
    private static final int THUMB_EDGE = 400;

    private ImageFitter() { }

    static Slide fit(Photo photo, byte[] src, int boxW, int boxH, boolean cover) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(src, 0, src.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Unsupported image format");

        int orientation = orientation(src);
        boolean swap = orientation >= 5 && orientation <= 8;
        int w = swap ? bounds.outHeight : bounds.outWidth;
        int h = swap ? bounds.outWidth : bounds.outHeight;

        double scale = cover
            ? Math.max((double) boxW / w, (double) boxH / h)
            : Math.min((double) boxW / w, (double) boxH / h);
        scale = Math.min(1.0, scale);
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));

        boolean upright = orientation <= 1;
        boolean jpeg = "image/jpeg".equals(bounds.outMimeType);
        if (jpeg && upright && scale >= 0.999 && src.length <= MAIN_BUDGET) {
            // Plex already sized it. Pass the bytes through untouched and only build the thumb.
            Bitmap small = decode(src, bounds.outWidth, bounds.outHeight, THUMB_EDGE, THUMB_EDGE);
            String thumb = small == null ? null : thumb(small);
            if (small != null) small.recycle();
            return new Slide(photo, b64(src), w, h, thumb);
        }

        int decodeW = swap ? th : tw;
        int decodeH = swap ? tw : th;
        Bitmap bmp = decode(src, bounds.outWidth, bounds.outHeight, decodeW, decodeH);
        if (bmp == null) throw new IOException("Could not decode image");
        bmp = rotate(bmp, orientation);
        bmp = resize(bmp, tw, th);

        byte[] out = null;
        for (int pass = 0; pass < 5; pass++) {
            for (int q = 86; q >= 58; q -= 7) {
                out = jpeg(bmp, q);
                if (out.length <= MAIN_BUDGET) break;
            }
            if (out.length <= MAIN_BUDGET) break;
            bmp = resize(bmp, Math.max(1, bmp.getWidth() * 4 / 5), Math.max(1, bmp.getHeight() * 4 / 5));
        }
        if (out == null || out.length > MAIN_BUDGET) {
            bmp.recycle();
            throw new IOException("Image could not be made small enough");
        }
        String thumb = thumb(bmp);
        int fw = bmp.getWidth();
        int fh = bmp.getHeight();
        bmp.recycle();
        return new Slide(photo, b64(out), fw, fh, thumb);
    }

    private static Bitmap decode(byte[] src, int srcW, int srcH, int wantW, int wantH) {
        int sample = 1;
        while (srcW / (sample * 2) >= wantW && srcH / (sample * 2) >= wantH) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(src, 0, src.length, o);
    }

    private static Bitmap resize(Bitmap bmp, int w, int h) {
        if (bmp.getWidth() == w && bmp.getHeight() == h) return bmp;
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
        if (scaled != bmp) bmp.recycle();
        return scaled;
    }

    private static Bitmap rotate(Bitmap bmp, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
            default: return bmp;
        }
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (out != bmp) bmp.recycle();
        return out;
    }

    private static int orientation(byte[] src) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(src));
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static String thumb(Bitmap bmp) {
        double s = Math.min(1.0, (double) THUMB_EDGE / Math.max(bmp.getWidth(), bmp.getHeight()));
        Bitmap small = Bitmap.createScaledBitmap(bmp,
            Math.max(1, (int) Math.round(bmp.getWidth() * s)),
            Math.max(1, (int) Math.round(bmp.getHeight() * s)), true);
        byte[] out = jpeg(small, 55);
        if (small != bmp) small.recycle();
        return out.length <= THUMB_BUDGET ? b64(out) : null;
    }

    private static byte[] jpeg(Bitmap bmp, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
