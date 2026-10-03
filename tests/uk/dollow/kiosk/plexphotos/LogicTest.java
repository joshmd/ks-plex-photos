// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Checks the classes that need neither Android nor a Plex server. Run with java -ea. */
public final class LogicTest {
    private static final Calendar TODAY = new GregorianCalendar(2026, Calendar.OCTOBER, 3);

    public static void main(String[] args) {
        dates();
        fingerprints();
        collectorFiltersBeforeCap();
        collectorSamplesBeyondCap();
        collectorKeepsOldestInDateOrder();
        collectorSkipsDuplicates();
        slideFitsInlineLimit();
        System.out.println("LogicTest passed");
    }

    private static void dates() {
        check(Dates.matches("2020-10-06", "On this day", TODAY), "on this day, +3 days");
        check(!Dates.matches("2020-10-07", "On this day", TODAY), "on this day, +4 days");
        check(!Dates.matches("2026-10-03", "On this day", TODAY), "on this day excludes this year");
        check(Dates.matches("2020-12-30", "On this day", new GregorianCalendar(2027, Calendar.JANUARY, 1)), "on this day wraps the year end");
        check(Dates.matches("2026-09-03", "Past month", TODAY), "past month boundary");
        check(!Dates.matches("2026-09-02", "Past month", TODAY), "past month excludes older");
        check(!Dates.matches("", "Past year", TODAY), "date filter excludes undated");
        check(Dates.matches("", "Any time", TODAY), "any time includes undated");
    }

    private static void fingerprints() {
        String hex = "11cbb5396445a05a90f56e7d5a6f233b50c14ee10c3bd266a91047dddbd285fd";
        String want = "11:CB:B5:39:64:45:A0:5A:90:F5:6E:7D:5A:6F:23:3B:50:C1:4E:E1:0C:3B:D2:66:A9:10:47:DD:DB:D2:85:FD";
        check(want.equals(Config.normalizeFingerprint(hex)), "fingerprint without colons");
        check(want.equals(Config.normalizeFingerprint(" " + want.toLowerCase() + " ")), "fingerprint with colons");
        check(Config.normalizeFingerprint("abc").isEmpty(), "short fingerprint rejected");

        Map<String, Object> s = new HashMap<>();
        s.put("tlsFingerprint", "not a fingerprint");
        check(!Config.from(s).tlsFingerprintValid, "invalid fingerprint flagged");
        s.put("tlsFingerprint", "");
        check(Config.from(s).tlsFingerprintValid, "blank fingerprint means trust on first use");
    }

    private static void collectorFiltersBeforeCap() {
        PhotoCollector c = new PhotoCollector("Past year", TODAY, true, new Random(1));
        int total = PhotoCollector.MAX_PHOTOS + 5000;
        for (int i = 0; i < total; i++) c.add(photo(i, i >= total - 100 ? "2026-09-01" : "2010-01-01"));
        check(c.matched() == 100 && c.result().size() == 100, "recent photos listed after the cap are kept");
    }

    private static void collectorSamplesBeyondCap() {
        PhotoCollector c = new PhotoCollector("Any time", TODAY, true, new Random(1));
        int total = PhotoCollector.MAX_PHOTOS + 5000;
        for (int i = 0; i < total; i++) c.add(photo(i, "2020-01-01"));
        List<Photo> out = c.result();
        int late = 0;
        for (Photo p : out) if (Integer.parseInt(p.ratingKey) >= PhotoCollector.MAX_PHOTOS) late++;
        check(out.size() == PhotoCollector.MAX_PHOTOS && c.matched() == total, "shuffle caps the list");
        check(late > 0, "shuffle samples photos listed after the cap");
    }

    private static void collectorKeepsOldestInDateOrder() {
        PhotoCollector c = new PhotoCollector("Any time", TODAY, false, new Random(1));
        int total = PhotoCollector.MAX_PHOTOS + 10;
        // Listed newest first, as a server might.
        // Zero-padded so string order is date order; "Any time" does not parse them.
        for (int i = 0; i < total; i++) c.add(photo(i, String.format("%06d", total - i)));
        List<Photo> out = c.result();
        check(out.size() == PhotoCollector.MAX_PHOTOS, "date order caps the list");
        check(out.get(0).date.equals("000001"), "date order starts at the oldest");
        check(out.get(out.size() - 1).date.equals(String.format("%06d", PhotoCollector.MAX_PHOTOS)), "date order drops the newest");
        for (int i = 1; i < out.size(); i++) {
            check(PhotoCollector.DATE_ORDER.compare(out.get(i - 1), out.get(i)) < 0, "date order is sorted");
        }
    }

    private static void collectorSkipsDuplicates() {
        PhotoCollector c = new PhotoCollector("Any time", TODAY, true, new Random(1));
        for (Photo p : Arrays.asList(photo(1, "2020-01-01"), photo(1, "2020-01-01"), photo(2, "2020-01-01"))) c.add(p);
        check(c.matched() == 2, "duplicates from overlapping albums are dropped");
    }

    /** ImageFitter's budgets. Copied because ImageFitter needs Android to compile. */
    private static final int MAIN_BUDGET = 340 * 1024;
    private static final int THUMB_BUDGET = 28 * 1024;

    private static void slideFitsInlineLimit() {
        Map<String, Object> s = new HashMap<>();
        Config cfg = Config.from(s);
        Photo p = new Photo("1", "/p", "IMG_1", "Holiday <b>", "2020-01-01");
        Slide prev = new Slide(p, "x", 1920, 1080, base64(THUMB_BUDGET));
        Slide next = new Slide(p, base64(MAIN_BUDGET), 1920, 1080, base64(THUMB_BUDGET));
        String doc = Html.slide(next, prev, cfg, 16 / 9.0);
        check(doc != null && Html.bytes(doc) <= Html.MAX_BYTES, "largest slide with crossfade fits");
        check(doc.contains("Holiday &lt;b&gt;"), "caption is escaped");
    }

    private static String base64(int rawBytes) {
        char[] c = new char[(rawBytes + 2) / 3 * 4];
        Arrays.fill(c, 'A');
        return new String(c);
    }

    private static Photo photo(int id, String date) {
        return new Photo(String.valueOf(id), "/library/parts/" + id + "/f.jpg", "Photo " + id, "", date);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
