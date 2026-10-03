// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/** Request details shared by the Plex server and plex.tv clients. */
final class PlexHttp {
    static final String PRODUCT = "Kiosk Satellite Plex Photos";
    /** Reported to Plex only. Keep in step with the manifest version. */
    static final String VERSION = "1.2.0";

    private PlexHttp() { }

    /** Identifies this kiosk to Plex. Each kiosk has its own client ID, so Plex lists them separately. */
    static void identify(HttpURLConnection c, String clientId, String deviceName) {
        c.setRequestProperty("X-Plex-Product", PRODUCT);
        c.setRequestProperty("X-Plex-Version", VERSION);
        c.setRequestProperty("X-Plex-Platform", "Android");
        c.setRequestProperty("X-Plex-Device", "Kiosk Satellite");
        c.setRequestProperty("X-Plex-Client-Identifier", clientId.isEmpty() ? "ks-plex-photos" : clientId);
        String name = headerSafe(deviceName);
        if (!name.isEmpty()) c.setRequestProperty("X-Plex-Device-Name", name);
    }

    static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[32 * 1024];
        int total = 0;
        for (int r; (r = in.read(buf)) != -1; ) {
            total += r;
            if (total > limit) throw new IOException("Response too large from Plex");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    /** Header values must be printable ASCII. */
    static String headerSafe(String s) {
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 64));
        for (int i = 0; i < s.length() && sb.length() < 64; i++) {
            char ch = s.charAt(i);
            sb.append(ch >= 0x20 && ch < 0x7f ? ch : '?');
        }
        return sb.toString().trim();
    }
}
