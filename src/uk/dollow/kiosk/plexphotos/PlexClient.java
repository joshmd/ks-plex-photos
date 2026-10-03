// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
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
    private static final int MAX_PHOTOS = 20000;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 40 * 1024 * 1024;
    private static final int MAX_ALBUM_DEPTH = 8;

    private final String base;
    private final String token;
    private final SSLSocketFactory insecureFactory;
    private volatile HttpURLConnection active;
    private volatile boolean aborted;

    PlexClient(Config config) {
        base = config.serverUrl;
        token = config.token;
        insecureFactory = config.allowInsecureTls ? trustAllFactory() : null;
    }

    /** Unblocks any request in flight. Safe to call from another thread. */
    void abort() {
        aborted = true;
        HttpURLConnection c = active;
        if (c != null) c.disconnect();
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
    void allPhotos(Section section, List<Photo> into) throws IOException {
        int before = into.size();
        String path = "/library/sections/" + enc(section.key) + "/all?type=13";
        for (int start = 0; into.size() < MAX_PHOTOS; start += PAGE) {
            JSONObject mc = container(getJson(path + "&X-Plex-Container-Start=" + start + "&X-Plex-Container-Size=" + PAGE));
            JSONArray items = mc.optJSONArray("Metadata");
            int n = items == null ? 0 : items.length();
            for (int i = 0; i < n; i++) {
                Photo p = photo(items.optJSONObject(i), "");
                if (p != null) into.add(p);
            }
            int total = mc.optInt("totalSize", -1);
            if (n < PAGE || (total >= 0 && start + n >= total)) break;
        }
        // Some servers ignore the type filter on photo sections. Fall back to walking the tree.
        if (into.size() == before) walk("/library/sections/" + enc(section.key) + "/all", "", 0, into);
    }

    /** Photos inside albums whose names match, recursing into sub-albums. */
    void albumPhotos(Section section, Set<String> albumNames, List<Photo> into) throws IOException {
        JSONObject mc = container(getJson("/library/sections/" + enc(section.key) + "/all?type=14"));
        List<JSONObject> albums = items(mc);
        boolean matchedAny = false;
        for (JSONObject a : albums) {
            String title = a.optString("title");
            if (!albumNames.contains(title.toLowerCase(Locale.ROOT))) continue;
            matchedAny = true;
            walk(childrenPath(a), title, 0, into);
            if (into.size() >= MAX_PHOTOS) return;
        }
        // Older servers only list top-level albums for type=14. Walk the tree to find nested ones.
        if (!matchedAny) findAlbums("/library/sections/" + enc(section.key) + "/all", albumNames, 0, into);
    }

    private void findAlbums(String path, Set<String> albumNames, int depth, List<Photo> into) throws IOException {
        if (depth > MAX_ALBUM_DEPTH || into.size() >= MAX_PHOTOS) return;
        for (JSONObject item : items(container(getJson(path)))) {
            if (!isAlbum(item)) continue;
            String title = item.optString("title");
            if (albumNames.contains(title.toLowerCase(Locale.ROOT))) walk(childrenPath(item), title, 0, into);
            else findAlbums(childrenPath(item), albumNames, depth + 1, into);
        }
    }

    private void walk(String path, String albumTitle, int depth, List<Photo> into) throws IOException {
        if (depth > MAX_ALBUM_DEPTH || into.size() >= MAX_PHOTOS) return;
        for (JSONObject item : items(container(getJson(path)))) {
            if (into.size() >= MAX_PHOTOS) return;
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
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        if (insecureFactory != null && c instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) c;
            https.setSSLSocketFactory(insecureFactory);
            https.setHostnameVerifier((host, session) -> true);
        }
        c.setConnectTimeout(6000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-Plex-Token", token);
        c.setRequestProperty("X-Plex-Product", "Kiosk Satellite Plex Photos");
        c.setRequestProperty("X-Plex-Client-Identifier", "ks-plex-photos");
        active = c;
        try {
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

    private static SSLSocketFactory trustAllFactory() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("TLS unavailable", e);
        }
    }
}
