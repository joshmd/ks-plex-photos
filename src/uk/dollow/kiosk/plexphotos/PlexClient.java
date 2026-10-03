// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Minimal Plex Media Server client for photo libraries. Not thread-safe: use from one worker. */
final class PlexClient {
    private static final int PAGE = 500;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 40 * 1024 * 1024;
    private static final int MAX_ALBUM_DEPTH = 8;

    private final String base;
    private final URL baseUrl;
    private final String token;
    private final PinningTrustManager pinning;
    private final SSLSocketFactory pinnedFactory;
    private volatile HttpURLConnection active;
    private volatile boolean aborted;

    PlexClient(Config config) throws IOException {
        base = config.serverUrl;
        baseUrl = new URL(base);
        token = config.token;
        if (config.allowInsecureTls) {
            if (!config.tlsFingerprintValid) {
                throw new IOException("Server certificate fingerprint is not a SHA-256 value. Clear it to learn it again.");
            }
            pinning = new PinningTrustManager(config.tlsFingerprint);
            pinnedFactory = factory(pinning);
        } else {
            pinning = null;
            pinnedFactory = null;
        }
    }

    /** Unblocks any request in flight. Safe to call from another thread. */
    void abort() {
        aborted = true;
        HttpURLConnection c = active;
        if (c != null) c.disconnect();
    }

    /**
     * Fingerprint of the certificate trusted on first use, when self-signed HTTPS is allowed and
     * no fingerprint was configured. Null otherwise.
     */
    String learnedFingerprint() {
        return pinning == null ? null : pinning.learned;
    }

    // ---- Library listing -------------------------------------------------------------------

    static final class Section {
        final String key;
        final String title;
        Section(String key, String title) { this.key = key; this.title = title; }
    }

    List<Section> photoSections(Set<String> wanted) throws IOException {
        JSONObject mc = container(getJson("/library/sections"));
        List<Section> out = new ArrayList<>();
        JSONArray dirs = mc.optJSONArray("Directory");
        if (dirs == null) return out;
        for (int i = 0; i < dirs.length(); i++) {
            JSONObject d = dirs.optJSONObject(i);
            if (d == null || !"photo".equals(d.optString("type"))) continue;
            String title = d.optString("title");
            if (!wanted.isEmpty() && !wanted.contains(title.toLowerCase(Locale.ROOT))) continue;
            out.add(new Section(d.optString("key"), title));
        }
        return out;
    }

    /** Every photo in a section, flattened across albums. */
    void allPhotos(Section section, PhotoCollector into) throws IOException {
        boolean any = false;
        String path = "/library/sections/" + enc(section.key) + "/all?type=13";
        for (int start = 0; !into.done(); start += PAGE) {
            JSONObject mc = container(getJson(path + "&X-Plex-Container-Start=" + start + "&X-Plex-Container-Size=" + PAGE));
            JSONArray items = mc.optJSONArray("Metadata");
            int n = items == null ? 0 : items.length();
            for (int i = 0; i < n; i++) {
                Photo p = photo(items.optJSONObject(i), "");
                if (p != null) { into.add(p); any = true; }
            }
            int total = mc.optInt("totalSize", -1);
            if (n < PAGE || (total >= 0 && start + n >= total)) break;
        }
        // Some servers ignore the type filter on photo sections. Fall back to walking the tree.
        if (!any) walk("/library/sections/" + enc(section.key) + "/all", "", 0, into);
    }

    /** Photos inside albums whose names match, recursing into sub-albums. */
    void albumPhotos(Section section, Set<String> albumNames, PhotoCollector into) throws IOException {
        JSONObject mc = container(getJson("/library/sections/" + enc(section.key) + "/all?type=14"));
        Set<String> missing = new HashSet<>(albumNames);
        for (JSONObject a : items(mc)) {
            String title = a.optString("title");
            String name = title.toLowerCase(Locale.ROOT);
            if (!albumNames.contains(name)) continue;
            missing.remove(name);
            walk(childrenPath(a), title, 0, into);
            if (into.done()) return;
        }
        // Older servers only list top-level albums for type=14. Walk the tree for any name not found.
        if (!missing.isEmpty()) findAlbums("/library/sections/" + enc(section.key) + "/all", missing, 0, into);
    }

    private void findAlbums(String path, Set<String> albumNames, int depth, PhotoCollector into) throws IOException {
        if (depth > MAX_ALBUM_DEPTH || into.done()) return;
        for (JSONObject item : items(container(getJson(path)))) {
            if (into.done()) return;
            if (!isAlbum(item)) continue;
            String title = item.optString("title");
            if (albumNames.contains(title.toLowerCase(Locale.ROOT))) walk(childrenPath(item), title, 0, into);
            else findAlbums(childrenPath(item), albumNames, depth + 1, into);
        }
    }

    private void walk(String path, String albumTitle, int depth, PhotoCollector into) throws IOException {
        if (depth > MAX_ALBUM_DEPTH || into.done()) return;
        for (JSONObject item : items(container(getJson(path)))) {
            if (into.done()) return;
            Photo p = photo(item, albumTitle);
            if (p != null) into.add(p);
            else if (isAlbum(item)) walk(childrenPath(item), item.optString("title", albumTitle), depth + 1, into);
        }
    }

    private static Photo photo(JSONObject m, String albumTitle) {
        if (m == null) return null;
        String type = m.optString("type");
        if (!type.isEmpty() && !"photo".equals(type)) return null;
        JSONArray media = m.optJSONArray("Media");
        if (media == null || media.length() == 0) return null;
        JSONObject first = media.optJSONObject(0);
        JSONArray parts = first == null ? null : first.optJSONArray("Part");
        JSONObject part = parts == null || parts.length() == 0 ? null : parts.optJSONObject(0);
        String partKey = part == null ? "" : part.optString("key");
        if (partKey.isEmpty()) return null;
        String date = m.optString("originallyAvailableAt");
        if (date.length() >= 10) date = date.substring(0, 10);
        else date = "";
        Photo p = new Photo(m.optString("ratingKey"), partKey, m.optString("title"), m.optString("parentTitle"), date);
        return albumTitle.isEmpty() ? p : p.withAlbum(albumTitle);
    }

    private static boolean isAlbum(JSONObject m) {
        if (m.optJSONArray("Media") != null) return false;
        String type = m.optString("type");
        String key = m.optString("key");
        return "photoalbum".equals(type) || key.endsWith("/children") || ("photo".equals(type) && !key.isEmpty());
    }

    private static String childrenPath(JSONObject album) {
        String key = album.optString("key");
        if (key.startsWith("/")) return key;
        return "/library/metadata/" + enc(album.optString("ratingKey")) + "/children";
    }

    private static List<JSONObject> items(JSONObject mc) {
        List<JSONObject> out = new ArrayList<>();
        for (String name : new String[] {"Metadata", "Directory"}) {
            JSONArray a = mc.optJSONArray(name);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(o);
            }
        }
        return out;
    }

    private static JSONObject container(JSONObject root) throws IOException {
        JSONObject mc = root.optJSONObject("MediaContainer");
        if (mc == null) throw new IOException("Unexpected response from Plex");
        return mc;
    }

    // ---- Images ----------------------------------------------------------------------------

    /**
     * Asks the Plex photo transcoder for a JPEG sized to the box. Falls back to the original
     * file when the transcoder refuses, which the fitter then decodes and shrinks locally.
     */
    byte[] image(Photo p, int boxW, int boxH, boolean cover) throws IOException {
        String transcode = "/photo/:/transcode?url=" + enc(p.partKey)
            + "&width=" + boxW + "&height=" + boxH
            + "&minSize=" + (cover ? 1 : 0) + "&upscale=0";
        try {
            return getBytes(transcode, MAX_IMAGE_BYTES, "image/jpeg");
        } catch (IOException transcodeError) {
            if (aborted) throw transcodeError;
            return getBytes(p.partKey, MAX_IMAGE_BYTES, "*/*");
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------

    private JSONObject getJson(String path) throws IOException {
        byte[] body = getBytes(path, MAX_JSON_BYTES, "application/json");
        try {
            return new JSONObject(new String(body, "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Plex did not return JSON. Check the server address.");
        }
    }

    private byte[] getBytes(String path, int limit, String accept) throws IOException {
        if (aborted) throw new IOException("Stopped");
        HttpURLConnection c = (HttpURLConnection) serverUrl(path).openConnection();
        if (pinnedFactory != null && c instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) c;
            https.setSSLSocketFactory(pinnedFactory);
            // The exact certificate is pinned, so its name does not need to match the address.
            https.setHostnameVerifier((host, session) -> true);
        }
        c.setConnectTimeout(6000);
        c.setReadTimeout(20000);
        // A redirect would carry the token header to wherever it points.
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-Plex-Token", token);
        c.setRequestProperty("X-Plex-Product", "Kiosk Satellite Plex Photos");
        c.setRequestProperty("X-Plex-Client-Identifier", "ks-plex-photos");
        active = c;
        try {
            if (aborted) throw new IOException("Stopped");
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Plex rejected the token (401)");
            if (code < 200 || code >= 300) throw new IOException("Plex returned HTTP " + code + " for " + redact(path));
            try (InputStream in = c.getInputStream()) {
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
        } finally {
            active = null;
            c.disconnect();
        }
    }

    /** Paths come partly from Plex responses. Refuse any that would leave the configured server. */
    private URL serverUrl(String path) throws IOException {
        if (!path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0) {
            throw new IOException("Refused unexpected path from Plex");
        }
        URL url = new URL(base + path);
        if (!url.getProtocol().equals(baseUrl.getProtocol())
            || !url.getHost().equalsIgnoreCase(baseUrl.getHost())
            || url.getPort() != baseUrl.getPort()
            || url.getUserInfo() != null) {
            throw new IOException("Refused unexpected path from Plex");
        }
        return url;
    }

    private static String redact(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SSLSocketFactory factory(TrustManager tm) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("TLS unavailable", e);
        }
    }

    static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02X", b & 0xff));
            return Config.normalizeFingerprint(sb.toString());
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new CertificateException(e);
        }
    }

    /**
     * Trusts exactly one server certificate, identified by its SHA-256 fingerprint. With no
     * fingerprint configured, trusts the first certificate seen and remembers it.
     */
    private static final class PinningTrustManager implements X509TrustManager {
        private final String expected;
        volatile String learned;

        PinningTrustManager(String expected) {
            this.expected = expected;
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not supported");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("No server certificate");
            String actual = fingerprint(chain[0]);
            String want = !expected.isEmpty() ? expected : learned;
            if (want == null) {
                learned = actual;
            } else if (!want.equals(actual)) {
                throw new CertificateException("Plex server certificate changed. Clear the certificate fingerprint to trust the new one.");
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
