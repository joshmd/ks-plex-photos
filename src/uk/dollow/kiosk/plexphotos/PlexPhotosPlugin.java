// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Plex photo-frame screensaver for Kiosk Satellite.
 *
 * <p>KS screensaver documents cannot reach the network, so this class downloads each photo,
 * shrinks it to fit the inline document limit and publishes a fresh self-contained document per
 * slide. KS keeps ownership of idle timing, wake, brightness, widgets and dismissal.
 *
 * <p>Lifecycle callbacks only flip flags and queue work. All network and image work runs on one
 * plugin-owned thread per session, so session state needs no further locking.
 */
public final class PlexPhotosPlugin implements KioskPlugin {
    static final String KEY = "plex";
    static final String TITLE = "Plex Photos";
    private static final String TILE = "plex";
    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_MS = 60_000L;
    private static final long REFRESH_RETRY_MS = 5 * 60_000L;

    private final Object lock = new Object();
    private volatile Config config = Config.from(null);
    private Session session; // guarded by lock

    private interface Step { void run(Session s, int gen) throws Exception; }

    // ---- KioskPlugin -----------------------------------------------------------------------

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        config = Config.from(settings);
        Session s = new Session(host);
        synchronized (lock) { session = s; }
        s.publishMessage(config.configured()
            ? "Loading photos from Plex\u2026"
            : "Add the Plex server address and token in Plugin Manager \u203a Plex Photos.");
        host.subscribe("screensaver.state");
        host.executeCommand("getDeviceInfo", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (ok && data instanceof Map) s.onDeviceInfo((Map<?, ?>) data);
        });
        host.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (ok) s.setActive(Boolean.TRUE.equals(data));
        });
        s.restart(null);
        host.log("Plex Photos started");
    }

    @Override
    public void configure(Map<String, Object> settings) {
        Config prev = config;
        Config next = Config.from(settings);
        config = next;
        Session s = current();
        if (s == null) return;
        final boolean source = !next.sourceKey().equals(prev.sourceKey());
        final boolean render = !next.renderKey().equals(prev.renderKey());
        if (!source && !render && next.seconds == prev.seconds) return;
        s.restart((ss, gen) -> {
            if (source || render) { ss.dropQueued(); ss.preloaded = null; }
        });
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        Session s = current();
        if (s == null) return;
        if ("next".equals(command)) {
            s.restart((ss, gen) -> ss.preloaded = null);
        } else if ("refresh".equals(command)) {
            s.restart((ss, gen) -> { ss.forceReload = true; ss.dropQueued(); ss.preloaded = null; });
        } else {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if (!"ks.screensaver.state".equals(event)) return;
        Session s = current();
        if (s != null) s.setActive(Boolean.TRUE.equals(payload.get("active")));
    }

    @Override
    public void stop() {
        Session s;
        synchronized (lock) { s = session; session = null; }
        if (s != null) s.close();
    }

    /** String.join needs API 26. */
    private static String join(Set<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) { if (sb.length() > 0) sb.append(", "); sb.append(n); }
        return sb.toString();
    }

    private Session current() {
        synchronized (lock) { return session; }
    }

    // ---- Session ---------------------------------------------------------------------------

    private final class Session {
        private final PluginHost host;
        private final ScheduledExecutorService worker;
        private final Random random = new Random();
        // Guarded by Session.this
        private int generation;
        private boolean active;
        private boolean closed;
        private ScheduledFuture<?> pending;
        // Worker thread only
        private volatile PlexClient client;
        private List<Photo> playlist = new ArrayList<>();
        private String loadedSource;
        private long loadedAt;
        private long retryAt;
        private boolean forceReload;
        private int cursor;
        private Slide shown;
        private Slide queued;
        private String queuedKey;
        private Slide preloaded;
        private int screenW = 1920;
        private int screenH = 1080;
        private String lastError = "";

        Session(PluginHost host) {
            this.host = host;
            this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "plex-photos");
                t.setDaemon(true);
                return t;
            });
        }

        // -- scheduling (any thread) --

        void setActive(boolean value) {
            synchronized (this) {
                if (closed || value == active) return;
                active = value;
            }
            restart(null);
        }

        /** Cancels whatever is queued and runs prelude, then the step for the current state, now. */
        void restart(Step prelude) {
            synchronized (this) {
                if (closed) return;
                generation++;
                if (pending != null) pending.cancel(false);
                final int gen = generation;
                pending = worker.schedule(() -> run(gen, (s, g) -> {
                    if (prelude != null) prelude.run(s, g);
                    if (isActive()) showNext(g); else prepareIdle(g);
                }), 0, TimeUnit.MILLISECONDS);
            }
        }

        private void later(int gen, long delayMs) {
            synchronized (this) {
                if (closed || gen != generation) return;
                pending = worker.schedule(() -> run(gen, (s, g) -> showNext(g)), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
            }
        }

        private synchronized boolean current(int gen) { return !closed && gen == generation; }
        private synchronized boolean isActive() { return active; }

        void close() {
            synchronized (this) {
                closed = true;
                generation++;
                if (pending != null) pending.cancel(true);
            }
            worker.shutdownNow();
            PlexClient c = client;
            if (c != null) c.abort();
        }

        private void run(int gen, Step step) {
            if (!current(gen)) return;
            try {
                step.run(this, gen);
            } catch (Throwable t) {
                if (!current(gen)) return;
                lastError = String.valueOf(t.getMessage());
                log("Error: " + lastError);
                status("Error: " + lastError, true);
                if (isActive()) later(gen, RETRY_MS);
            }
        }

        void onDeviceInfo(Map<?, ?> info) {
            Object w = info.get("screenWidth");
            Object h = info.get("screenHeight");
            if (!(w instanceof Number) || !(h instanceof Number)) return;
            final int sw = ((Number) w).intValue();
            final int sh = ((Number) h).intValue();
            if (sw <= 0 || sh <= 0) return;
            try {
                worker.execute(() -> {
                    if (sw != screenW || sh != screenH) { screenW = sw; screenH = sh; dropQueued(); }
                });
            } catch (RuntimeException ignored) {
                // Session already closed.
            }
        }

        // -- steps (worker thread) --

        private void prepareIdle(int gen) throws IOException {
            Config cfg = config;
            if (!cfg.configured()) { publishMessage("Add the Plex server address and token in Plugin Manager \u203a Plex Photos."); return; }
            ensurePlaylist(cfg);
            if (playlist.isEmpty()) { publishMessage(emptyMessage(cfg)); return; }
            Slide s = take(cfg);
            if (s == null || !current(gen)) return;
            String doc = Html.slide(s, null, cfg, aspect());
            if (doc == null) return;
            // Published while idle, so the screensaver opens on a photo with no network wait.
            publish(doc);
            preloaded = s;
            shown = null;
            queue(cfg);
        }

        private void showNext(int gen) throws IOException {
            Config cfg = config;
            if (!cfg.configured()) { publishMessage("Add the Plex server address and token in Plugin Manager \u203a Plex Photos."); return; }
            ensurePlaylist(cfg);
            if (playlist.isEmpty()) { publishMessage(emptyMessage(cfg)); later(gen, RETRY_MS); return; }

            Slide s;
            if (preloaded != null) {
                // KS is already showing the document published while idle.
                s = preloaded;
                preloaded = null;
            } else {
                s = take(cfg);
                if (s == null) { later(gen, 15_000L); return; }
                String doc = Html.slide(s, shown, cfg, aspect());
                if (doc == null) { later(gen, 1_000L); return; }
                if (!current(gen)) return;
                publish(doc);
            }
            long shownAt = System.currentTimeMillis();
            shown = s;
            report(cfg, s);
            queue(cfg);
            later(gen, cfg.seconds * 1000L - (System.currentTimeMillis() - shownAt));
        }

        // -- playlist --

        private void ensurePlaylist(Config cfg) {
            long now = System.currentTimeMillis();
            boolean sameSource = cfg.sourceKey().equals(loadedSource);
            boolean stale = now - loadedAt > cfg.refreshHours * 3_600_000L;
            if (sameSource && !forceReload && (!stale || now < retryAt)) return;
            try {
                PlexClient c = new PlexClient(cfg);
                client = c;
                List<Photo> list = load(c, cfg);
                playlist = list;
                loadedSource = cfg.sourceKey();
                loadedAt = now;
                forceReload = false;
                cursor = 0;
                if (!sameSource) queued = null;
                lastError = "";
                String n = String.format(Locale.UK, "%,d", list.size());
                tile(list.isEmpty() ? "warn" : "on", list.isEmpty() ? "No matching photos" : n + " photos");
                status(list.isEmpty() ? emptyMessage(cfg) : n + " photos loaded from Plex", list.isEmpty());
            } catch (IOException e) {
                lastError = String.valueOf(e.getMessage());
                retryAt = now + REFRESH_RETRY_MS;
                forceReload = false;
                if (!sameSource) { playlist = new ArrayList<>(); loadedSource = null; }
                tile("off", "Plex unreachable");
                status("Could not load photos: " + lastError, true);
                log("Load failed: " + lastError);
            }
        }

        private List<Photo> load(PlexClient c, Config cfg) throws IOException {
            List<PlexClient.Section> sections = c.photoSections(cfg.libraries);
            if (sections.isEmpty()) {
                throw new IOException(cfg.libraries.isEmpty()
                    ? "No photo libraries found on this server"
                    : "No photo library called " + join(cfg.libraries));
            }
            List<Photo> raw = new ArrayList<>();
            for (PlexClient.Section s : sections) {
                if (cfg.albums.isEmpty()) c.allPhotos(s, raw);
                else c.albumPhotos(s, cfg.albums, raw);
            }
            Calendar today = Calendar.getInstance();
            Set<String> seen = new HashSet<>();
            List<Photo> out = new ArrayList<>(raw.size());
            for (Photo p : raw) {
                if (!seen.add(p.ratingKey.isEmpty() ? p.partKey : p.ratingKey)) continue;
                if (Dates.matches(p.date, cfg.takenWithin, today)) out.add(p);
            }
            if (cfg.shuffle) {
                Collections.shuffle(out, random);
            } else {
                Collections.sort(out, new Comparator<Photo>() {
                    @Override public int compare(Photo a, Photo b) {
                        int d = a.date.compareTo(b.date);
                        return d != 0 ? d : a.title.compareTo(b.title);
                    }
                });
            }
            return out;
        }

        private Photo pick(Config cfg) {
            if (playlist.isEmpty()) return null;
            if (cursor >= playlist.size()) {
                cursor = 0;
                if (cfg.shuffle && playlist.size() > 1) {
                    Collections.shuffle(playlist, random);
                    // Avoid the same photo twice in a row across the reshuffle.
                    if (shown != null && playlist.get(0).ratingKey.equals(shown.photo.ratingKey)) {
                        Collections.swap(playlist, 0, playlist.size() - 1);
                    }
                }
            }
            return playlist.get(cursor++);
        }

        // -- images --

        private Slide take(Config cfg) {
            String key = sizeKey(cfg);
            if (queued != null && key.equals(queuedKey)) {
                Slide s = queued;
                queued = null;
                return s;
            }
            dropQueued();
            return fetch(cfg);
        }

        /** Discards the prefetched slide and puts its photo back at the front of the queue. */
        void dropQueued() {
            if (queued == null) return;
            queued = null;
            if (cursor > 0) cursor--;
        }

        private void queue(Config cfg) {
            if (queued != null && sizeKey(cfg).equals(queuedKey)) return;
            queued = fetch(cfg);
            queuedKey = sizeKey(cfg);
        }

        private Slide fetch(Config cfg) {
            PlexClient c = client;
            if (c == null) return null;
            int longEdge = Math.min(cfg.maxEdge, Math.max(screenW, screenH));
            int shortEdge = (int) Math.round(longEdge * (double) Math.min(screenW, screenH) / Math.max(screenW, screenH));
            int boxW = screenW >= screenH ? longEdge : shortEdge;
            int boxH = screenW >= screenH ? shortEdge : longEdge;
            boolean cover = "Always".equals(cfg.fill);
            for (int attempt = 0; attempt < MAX_ATTEMPTS && !Thread.currentThread().isInterrupted(); attempt++) {
                Photo p = pick(cfg);
                if (p == null) return null;
                try {
                    byte[] bytes = c.image(p, boxW, boxH, cover);
                    return ImageFitter.fit(p, bytes, boxW, boxH, cover);
                } catch (IOException | RuntimeException e) {
                    lastError = String.valueOf(e.getMessage());
                    log("Skipped " + p.ratingKey + ": " + lastError);
                }
            }
            status("Could not download photos: " + lastError, true);
            return null;
        }

        private String sizeKey(Config cfg) {
            return cfg.fill + "|" + cfg.maxEdge + "|" + screenW + "x" + screenH + "|" + loadedSource;
        }

        private double aspect() {
            return (double) screenW / screenH;
        }

        // -- host output --

        void publishMessage(String text) {
            preloaded = null;
            publish(Html.message(text));
        }

        private void publish(String doc) {
            try {
                host.publishScreensaver(KEY, TITLE, doc);
            } catch (RuntimeException e) {
                // Revoked after stop, or a publish burst over the rate limit. The next slide retries.
                log("Publish failed: " + e.getMessage());
            }
        }

        private void report(Config cfg, Slide s) {
            String date = Html.prettyDate(s.photo.date, false);
            String what = date.isEmpty() ? s.photo.title : date;
            if (!s.photo.album.isEmpty()) what += " \u00b7 " + s.photo.album;
            status(String.format(Locale.UK, "%,d photos. Showing %s", playlist.size(), what), false);
        }

        private String emptyMessage(Config cfg) {
            if (!lastError.isEmpty() && playlist.isEmpty()) return "Could not reach Plex: " + lastError;
            if (!cfg.albums.isEmpty()) return "No photos found in " + join(cfg.albums) + " for this date filter.";
            return "No photos match the current filters.";
        }

        private void status(String text, boolean error) {
            try { host.status(text.length() > 1000 ? text.substring(0, 1000) : text, error); } catch (RuntimeException ignored) { }
        }

        private void tile(String level, String text) {
            try { host.publishStatusTile(TILE, TITLE, level, text); } catch (RuntimeException ignored) { }
        }

        private void log(String text) {
            try { host.log(text); } catch (RuntimeException ignored) { }
        }
    }
}
