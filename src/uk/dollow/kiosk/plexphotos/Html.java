// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.regex.Pattern;

/** Builds the self-contained inline documents KS renders inside its screensaver surface. */
final class Html {
    /** KS limit for publishScreensaver's inline HTML argument. */
    static final int MAX_BYTES = 512 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"};
    private static final Pattern FILENAME = Pattern.compile(
        "(?i)^(?:[a-z]{2,6}[_-]?\\d{3,}.*|\\d{8}[_-]\\d{4,}.*|.*\\.(?:jpe?g|heic|heif|png|webp|dng|tiff?))$");

    private static final String BASE_CSS =
        "html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}"
        + ".s{position:absolute;top:0;right:0;bottom:0;left:0}"
        + ".b{position:absolute;top:-6%;right:-6%;bottom:-6%;left:-6%;background:center/cover no-repeat;"
        + "filter:blur(36px) brightness(.45)}"
        + ".f{position:absolute;top:0;right:0;bottom:0;left:0;background:center/contain no-repeat}"
        + ".f.c{background-size:cover}"
        + ".in{opacity:0;animation:in 1.2s ease-out forwards}"
        + "@keyframes in{to{opacity:1}}"
        + ".v{position:absolute;width:70vmin;height:40vmin;pointer-events:none}"
        + ".i{position:absolute;max-width:60vw;color:#fff;font-family:system-ui,Roboto,'Segoe UI',sans-serif;"
        + "text-shadow:0 1px 3px rgba(0,0,0,.85);line-height:1.3}"
        + ".d{font-size:3vmin;font-weight:600}.a{font-size:2.3vmin;opacity:.85;margin-top:.3vmin}"
        + ".m{position:absolute;top:50%;left:0;right:0;transform:translateY(-50%);text-align:center;"
        + "color:#8a8f98;font:500 3vmin system-ui,Roboto,sans-serif;padding:0 8vmin}";

    private Html() { }

    static String message(String text) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>" + BASE_CSS + "</style></head><body><div class=\"m\">" + esc(text) + "</div></body></html>";
    }

    /**
     * @param prev outgoing slide for a crossfade, or null to fade from black
     * @param screenAspect width / height of the kiosk screen
     */
    static String slide(Slide next, Slide prev, Config c, double screenAspect) {
        next.cover = cover(next, c.fill, screenAspect);
        boolean crossfade = "Crossfade".equals(c.transition) && prev != null && prev.thumbBase64 != null;
        boolean animate = !"None".equals(c.transition);
        String doc = build(next, crossfade ? prev : null, animate, c);
        if (bytes(doc) > MAX_BYTES && crossfade) doc = build(next, null, animate, c);
        return bytes(doc) > MAX_BYTES ? null : doc;
    }

    private static String build(Slide next, Slide prev, boolean animate, Config c) {
        StringBuilder sb = new StringBuilder(next.jpegBase64.length() + 48 * 1024);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
          .append(BASE_CSS)
          .append(":root{--n:url(\"data:image/jpeg;base64,").append(next.jpegBase64).append("\")");
        if (prev != null) sb.append(";--p:url(\"data:image/jpeg;base64,").append(prev.thumbBase64).append("\")");
        sb.append("}#n .b,#n .f{background-image:var(--n)}");
        if (prev != null) sb.append("#p .b,#p .f{background-image:var(--p)}");
        sb.append("</style></head><body>");
        if (prev != null) layer(sb, "p", prev.cover, "");
        layer(sb, "n", next.cover, animate ? "in" : "");
        if (c.showInfo) caption(sb, next.photo, c);
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void layer(StringBuilder sb, String id, boolean cover, String anim) {
        sb.append("<div class=\"s ").append(anim).append("\" id=\"").append(id).append("\">");
        if (!cover) sb.append("<div class=\"b\"></div>");
        sb.append("<div class=\"f").append(cover ? " c" : "").append("\"></div></div>");
    }

    private static void caption(StringBuilder sb, Photo p, Config c) {
        String date = prettyDate(p.date, "On this day".equals(c.takenWithin));
        String second = !p.album.isEmpty() ? p.album : (looksLikeFilename(p.title) ? "" : p.title);
        if (date.isEmpty() && second.isEmpty()) return;
        boolean top = c.infoCorner.startsWith("Top");
        boolean right = c.infoCorner.endsWith("right");
        String v = (top ? "top:0;" : "bottom:0;") + (right ? "right:0;" : "left:0;");
        String grad = "radial-gradient(ellipse at " + (right ? "100%" : "0%") + " " + (top ? "0%" : "100%")
            + ",rgba(0,0,0,.55),rgba(0,0,0,0) 70%)";
        sb.append("<div class=\"v\" style=\"").append(v).append("background:").append(grad).append("\"></div>");
        String pos = (top ? "top:4vmin;" : "bottom:4vmin;") + (right ? "right:4vmin;text-align:right;" : "left:4vmin;");
        sb.append("<div class=\"i\" style=\"").append(pos).append("\">");
        if (!date.isEmpty()) sb.append("<div class=\"d\">").append(esc(date)).append("</div>");
        if (!second.isEmpty()) sb.append("<div class=\"a\">").append(esc(second)).append("</div>");
        sb.append("</div>");
    }

    static boolean cover(Slide s, String fill, double screenAspect) {
        if ("Always".equals(fill)) return true;
        if (!"Smart".equals(fill) || s.width <= 0 || s.height <= 0 || screenAspect <= 0) return false;
        double ratio = ((double) s.width / s.height) / screenAspect;
        // Crop at most about 12% of either edge. A 4:3 photo on a 16:10 panel stays uncropped.
        return ratio > 0.88 && ratio < 1.14;
    }

    static String prettyDate(String iso, boolean yearsAgo) {
        if (iso.length() < 10) return "";
        try {
            int y = Integer.parseInt(iso.substring(0, 4));
            int m = Integer.parseInt(iso.substring(5, 7));
            int d = Integer.parseInt(iso.substring(8, 10));
            if (m < 1 || m > 12) return "";
            String out = d + " " + MONTHS[m - 1] + " " + y;
            if (yearsAgo) {
                int ago = Calendar.getInstance().get(Calendar.YEAR) - y;
                if (ago == 1) out += " \u00b7 1 year ago";
                else if (ago > 1) out += " \u00b7 " + ago + " years ago";
            }
            return out;
        } catch (NumberFormatException e) {
            return "";
        }
    }

    static boolean looksLikeFilename(String title) {
        return title.isEmpty() || FILENAME.matcher(title).matches();
    }

    static int bytes(String s) {
        return s.getBytes(UTF8).length;
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }
}
