// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Filters photos as Plex lists them, so the playlist cap counts only photos that match.
 * Shuffle keeps a uniform random sample of the matches; date order keeps the oldest ones.
 */
final class PhotoCollector {
    static final int MAX_PHOTOS = 20000;
    /** Listing stops after this many items, matching or not, to bound a refresh on huge servers. */
    static final int MAX_SCANNED = 200000;

    /** Date order, oldest first. Photos with no date sort first. */
    static final Comparator<Photo> DATE_ORDER = new Comparator<Photo>() {
        @Override public int compare(Photo a, Photo b) {
            int d = a.date.compareTo(b.date);
            if (d != 0) return d;
            d = a.title.compareTo(b.title);
            return d != 0 ? d : a.ratingKey.compareTo(b.ratingKey);
        }
    };

    private final String within;
    private final Calendar today;
    private final boolean shuffle;
    private final Random random;
    private final Set<String> seen = new HashSet<>();
    private final List<Photo> sample = new ArrayList<>();
    private final PriorityQueue<Photo> oldest = new PriorityQueue<>(64, Collections.reverseOrder(DATE_ORDER));
    private int scanned;
    private int matched;

    PhotoCollector(String within, Calendar today, boolean shuffle, Random random) {
        this.within = within;
        this.today = today;
        this.shuffle = shuffle;
        this.random = random;
    }

    void add(Photo p) {
        scanned++;
        if (!seen.add(p.ratingKey.isEmpty() ? p.partKey : p.ratingKey)) return;
        if (!Dates.matches(p.date, within, today)) return;
        matched++;
        if (shuffle) {
            // Reservoir sampling: every match has the same chance of being kept.
            if (sample.size() < MAX_PHOTOS) sample.add(p);
            else {
                int i = random.nextInt(matched);
                if (i < MAX_PHOTOS) sample.set(i, p);
            }
        } else {
            oldest.add(p);
            if (oldest.size() > MAX_PHOTOS) oldest.poll();
        }
    }

    boolean done() {
        return scanned >= MAX_SCANNED;
    }

    /** Matching photos seen, including any beyond the cap. */
    int matched() {
        return matched;
    }

    List<Photo> result() {
        if (shuffle) {
            List<Photo> out = new ArrayList<>(sample);
            Collections.shuffle(out, random);
            return out;
        }
        List<Photo> out = new ArrayList<>(oldest);
        Collections.sort(out, DATE_ORDER);
        return out;
    }
}
