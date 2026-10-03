// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable snapshot of the validated plugin settings. */
final class Config {
    final String serverUrl;
    final String token;
    final boolean allowInsecureTls;
    final Set<String> libraries;
    final Set<String> albums;
    final String takenWithin;
    final boolean shuffle;
    final int refreshHours;
    final int seconds;
    final String fill;
    final String transition;
    final boolean showInfo;
    final String infoCorner;
    final int maxEdge;

    private Config(Map<String, Object> s) {
        serverUrl = trimSlash(str(s, "serverUrl"));
        token = str(s, "token").trim();
        allowInsecureTls = Boolean.TRUE.equals(s.get("allowInsecureTls"));
        libraries = names(str(s, "libraries"));
        albums = names(str(s, "albums"));
        takenWithin = orDefault(str(s, "takenWithin"), "Any time");
        shuffle = !Boolean.FALSE.equals(s.get("shuffle"));
        refreshHours = clamp(num(s, "refreshHours", 6), 1, 48);
        seconds = clamp(num(s, "seconds", 20), 5, 600);
        fill = orDefault(str(s, "fill"), "Smart");
        transition = orDefault(str(s, "transition"), "Crossfade");
        showInfo = !Boolean.FALSE.equals(s.get("showInfo"));
        infoCorner = orDefault(str(s, "infoCorner"), "Bottom left");
        maxEdge = clamp(num(s, "maxEdge", 1920), 640, 2560);
    }

    static Config from(Map<String, Object> settings) {
        return new Config(settings == null ? Collections.<String, Object>emptyMap() : settings);
    }

    boolean configured() {
        return (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) && !token.isEmpty();
    }

    /** Settings that change which photos are in the list. */
    String sourceKey() {
        return serverUrl + "|" + token + "|" + allowInsecureTls + "|" + libraries + "|" + albums + "|" + takenWithin + "|" + shuffle;
    }

    /** Settings that change how a downloaded photo is requested or drawn. */
    String renderKey() {
        return fill + "|" + transition + "|" + showInfo + "|" + infoCorner + "|" + maxEdge;
    }

    private static String str(Map<String, Object> s, String key) {
        Object v = s.get(key);
        return v instanceof String ? (String) v : "";
    }

    private static int num(Map<String, Object> s, String key, int fallback) {
        Object v = s.get(key);
        if (!(v instanceof Number)) return fallback;
        double d = ((Number) v).doubleValue();
        return Double.isFinite(d) ? (int) Math.round(d) : fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String orDefault(String v, String fallback) {
        return v.isEmpty() ? fallback : v;
    }

    private static String trimSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static Set<String> names(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (!p.isEmpty()) out.add(p);
        }
        return Collections.unmodifiableSet(out);
    }
}
