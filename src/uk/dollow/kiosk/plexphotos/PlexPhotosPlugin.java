// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 *
 * <p>The stored settings hold the Plex token encrypted (see {@link TokenVault}). {@link #config}
 * is built from them with the token decrypted, and is never saved.
 */
public final class PlexPhotosPlugin implements KioskPlugin {
    static final String KEY = "plex";
    static final String TITLE = "Plex Photos";
    private static final String TILE = "plex";
    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_MS = 60_000L;
    private static final long REFRESH_RETRY_MS = 5 * 60_000L;

    private static final long SIGN_IN_POLL_MS = 2_000L;

    private final TokenVault vault;
    private final Object lock = new Object();
    private volatile Config config = Config.from(null);
    /** Why the stored token could not be decrypted, or empty. */
    private volatile String tokenProblem = "";
    private Map<String, Object> settings = new LinkedHashMap<>(); // as stored, guarded by lock
    private Session session; // guarded by lock
    /** plex.tv, or a local stand-in in tests. */
    String plexTv = PlexAccount.PLEX_TV;

    public PlexPhotosPlugin() {
        this(new TokenVault(new AndroidKeys()));
    }

    /** Package-private so tests can supply a key that is not in the Android Keystore. */
    PlexPhotosPlugin(TokenVault vault) {
        this.vault = vault;
    }

    private interface Step { void run(Session s, int gen) throws Exception; }

    // ---- KioskPlugin -----------------------------------------------------------------------

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        Session s = new Session(host);
        Map<String, Object> stored = copy(settings);
        boolean changed = secure(host, stored);
        synchronized (lock) {
            this.settings = stored;
            config = resolve(stored);
            session = s;
        }
        if (changed) save(host, stored);
        s.publishMessage(config.configured() ? "Loading photos from Plex\u2026" : s.setupMessage(config));
        host.subscribe("screensaver.state");
        host.executeCommand("getDeviceInfo", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (ok && data instanceof Map) s.onDeviceInfo((Map<?, ?>) data);
        });
        host.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (ok) s.setActive(Boolean.TRUE.equals(data));
        });
        s.restart(null);
        if (config.signIn) s.startSignIn();
        host.log("Plex Photos started");
    }

    @Override
    public void configure(Map<String, Object> settings) {
        Session s = current();
        Map<String, Object> stored = copy(settings);
        boolean changed = s != null && secure(s.host, stored);
        Config prev;
        Config next;
        synchronized (lock) {
            this.settings = stored;
            prev = config;
            next = resolve(stored);
            config = next;
        }
        if (s == null) return;
        if (changed) save(s.host, stored);
        if (next.signIn && !prev.signIn) s.startSignIn();
        else if (!next.signIn && prev.signIn) s.cancelSignIn();
        final boolean source = !next.sourceKey().equals(prev.sourceKey());
        final boolean render = !next.renderKey().equals(prev.renderKey());
        final boolean pin = !next.tlsFingerprint.equals(prev.tlsFingerprint)
            || next.tlsFingerprintValid != prev.tlsFingerprintValid;
        if (!source && !render && !pin && next.seconds == prev.seconds) return;
        s.restart((ss, gen) -> {
            if (source || render) { ss.dropQueued(); ss.preloaded = null; }
            // Reconnect so the new fingerprint is what gets checked.
            if (pin) ss.forceReload = true;
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
        } else if ("signin".equals(command)) {
            s.startSignIn();
        } else {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        Session s = current();
        if (s == null) return;
        if ("ks.screensaver.state".equals(event)) s.setActive(Boolean.TRUE.equals(payload.get("active")));
        // The window's only button is Cancel. Closing the window just hides the code; sign-in carries on.
        else if ("window.action".equals(event)) s.cancelSignIn();
    }

    @Override
    public void stop() {
        Session s;
        synchronized (lock) { s = session; session = null; }
        if (s != null) s.close();
    }

    private static Map<String, Object> copy(Map<String, Object> settings) {
        return settings == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(settings);
    }

    /**
     * Encrypts a token pasted into the settings and gives the kiosk a client ID on first run.
     * Returns true when the stored map changed and needs saving.
     */
    private boolean secure(PluginHost host, Map<String, Object> stored) {
        boolean changed = false;
        Object id = stored.get("clientId");
        if (!(id instanceof String) || ((String) id).trim().isEmpty()) {
            stored.put("clientId", UUID.randomUUID().toString());
            changed = true;
        }
        Object raw = stored.get("token");
        if (raw instanceof String) {
            String token = ((String) raw).trim();
            if (!token.isEmpty() && !TokenVault.isSealed(token)) {
                String sealed = seal(host, token);
                if (!sealed.equals(token)) {
                    stored.put("token", sealed);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** The encrypted token, or the plain one if this kiosk cannot encrypt. */
    private String seal(PluginHost host, String token) {
        try {
            return vault.seal(token);
        } catch (GeneralSecurityException | RuntimeException e) {
            try {
                host.log("Could not encrypt the Plex token: " + e.getMessage());
                host.status("The Plex token is stored unencrypted because this kiosk could not encrypt it: " + e.getMessage(), true);
            } catch (RuntimeException ignored) { }
            return token;
        }
    }

    /** The live config: the stored settings with the token decrypted. Call with the lock held. */
    private Config resolve(Map<String, Object> stored) {
        Object raw = stored.get("token");
        if (!(raw instanceof String) || !TokenVault.isSealed(((String) raw).trim())) {
            tokenProblem = "";
            return Config.from(stored);
        }
        Map<String, Object> plain = copy(stored);
        try {
            plain.put("token", vault.open(((String) raw).trim()));
            tokenProblem = "";
        } catch (GeneralSecurityException | RuntimeException e) {
            // Usually the Keystore key is gone because KS's data was cleared or it was reinstalled.
            plain.put("token", "");
            tokenProblem = "The saved Plex sign-in cannot be read on this kiosk. Turn on Sign in with Plex to sign in again.";
        }
        return Config.from(plain);
    }

    /** Saves changed settings and updates the live config. saveSettings does not call configure. */
    private void persist(PluginHost host, Map<String, Object> changes) {
        Map<String, Object> next;
        synchronized (lock) {
            next = copy(settings);
            next.putAll(changes);
            settings = next;
            config = resolve(next);
        }
        save(host, next);
    }

    private static void save(PluginHost host, Map<String, Object> stored) {
        try {
            host.saveSettings(stored);
        } catch (RuntimeException e) {
            try { host.log("Could not save settings: " + e.getMessage()); } catch (RuntimeException ignored) { }
        }
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
        private int matchedTotal;
        private volatile String deviceName = "";
        // Sign-in, worker thread only
        private PlexAccount account;
        private PlexAccount.Pin pin;
        private long pinDeadline;
        private ScheduledFuture<?> pinPoll;

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
        private synchronized boolean isClosed() { return closed; }

        void close() {
            synchronized (this) {
                closed = true;
                generation++;
                if (pending != null) pending.cancel(true);
            }
            worker.shutdownNow();
            PlexClient c = client;
            if (c != null) c.abort();
            // KS expects plugin threads to have stopped when stop() returns. Its deadline is 3 s.
            try {
                worker.awaitTermination(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
            Object name = info.get("name");
            if (name instanceof String) deviceName = (String) name;
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
            if (!cfg.configured()) { publishMessage(setupMessage(cfg)); return; }
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
            if (!cfg.configured()) { publishMessage(setupMessage(cfg)); return; }
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
                PlexClient c = new PlexClient(cfg, deviceName);
                client = c;
                if (isClosed()) c.abort();
                List<Photo> list = load(c, cfg);
                playlist = list;
                loadedSource = cfg.sourceKey();
                loadedAt = now;
                forceReload = false;
                if (!sameSource) queued = null;
                cursor = sameSource && !cfg.shuffle ? resumeAfter(list) : 0;
                lastError = "";
                String learned = c.learnedFingerprint();
                if (learned != null && cfg.tlsFingerprint.isEmpty()) rememberFingerprint(learned);
                String n = String.format(Locale.UK, "%,d", list.size());
                String loaded = matchedTotal > list.size()
                    ? n + " of " + String.format(Locale.UK, "%,d", matchedTotal) + (cfg.shuffle ? " photos, random sample" : " photos, oldest first")
                    : n + " photos";
                tile(list.isEmpty() ? "warn" : "on", list.isEmpty() ? "No matching photos" : loaded);
                status(list.isEmpty() ? emptyMessage(cfg) : loaded + " loaded from Plex", list.isEmpty());
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
            PhotoCollector found = new PhotoCollector(cfg.takenWithin, Calendar.getInstance(), cfg.shuffle, random);
            for (PlexClient.Section s : sections) {
                if (found.done()) break;
                if (cfg.albums.isEmpty()) c.allPhotos(s, found);
                else c.albumPhotos(s, cfg.albums, found);
            }
            matchedTotal = found.matched();
            return found.result();
        }

        /**
         * In date order, carries on after the last photo taken from the old list instead of
         * going back to the oldest photo on every refresh.
         */
        private int resumeAfter(List<Photo> list) {
            Photo anchor = queued != null ? queued.photo
                : shown != null ? shown.photo
                : preloaded != null ? preloaded.photo : null;
            if (anchor == null) return 0;
            for (int i = 0; i < list.size(); i++) {
                if (PhotoCollector.DATE_ORDER.compare(list.get(i), anchor) > 0) return i;
            }
            return list.size();
        }

        /** Saves the certificate trusted on first use so later connections are pinned to it. */
        private void rememberFingerprint(String fingerprint) {
            persist(host, Collections.<String, Object>singletonMap("tlsFingerprint", fingerprint));
            log("Pinned Plex server certificate " + fingerprint);
        }

        String setupMessage(Config cfg) {
            if (!tokenProblem.isEmpty()) return tokenProblem;
            PlexAccount.Pin p = pin;
            if (p != null) return "Sign in to Plex: go to plex.tv/link and enter " + p.code;
            if (cfg.token.isEmpty()) return "Turn on Sign in with Plex in Plugin Manager \u203a Plex Photos.";
            return "Add the Plex server address in Plugin Manager \u203a Plex Photos.";
        }

        // -- sign-in (worker thread) --

        void startSignIn() {
            submit(this::beginSignIn);
        }

        void cancelSignIn() {
            submit(() -> { if (pin != null) endSignIn("Sign-in cancelled.", false); });
        }

        private void submit(Runnable r) {
            try {
                worker.execute(() -> {
                    if (isClosed()) return;
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        log("Sign-in error: " + e.getMessage());
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // Session already closed.
            }
        }

        private void beginSignIn() {
            if (pin != null) { showCode(); return; }
            account = new PlexAccount(plexTv, config.clientId, deviceName);
            try {
                pin = account.createPin();
            } catch (IOException e) {
                endSignIn("Could not reach plex.tv to sign in: " + e.getMessage(), true);
                return;
            }
            pinDeadline = System.currentTimeMillis() + Math.max(60, Math.min(pin.expiresIn, 1800)) * 1000L;
            log("Waiting for Plex sign-in");
            showCode();
            schedulePoll();
        }

        private void showCode() {
            try {
                host.showWindow("Sign in to Plex",
                    "On your phone or computer, go to plex.tv/link and enter this code:\n\n" + pin.code
                        + "\n\nSign in as the Plex user whose photos this kiosk should show.",
                    "Cancel");
            } catch (RuntimeException e) {
                log("Could not show the sign-in window: " + e.getMessage());
            }
            status("Sign in to Plex: go to plex.tv/link and enter " + pin.code, false);
            tile("warn", "Sign in: enter " + pin.code + " at plex.tv/link");
            Config cfg = config;
            if (!cfg.configured()) publishMessage(setupMessage(cfg));
        }

        private void schedulePoll() {
            try {
                pinPoll = worker.schedule(() -> {
                    if (isClosed()) return;
                    try {
                        pollSignIn();
                    } catch (RuntimeException e) {
                        endSignIn("Sign-in failed: " + e.getMessage(), true);
                    }
                }, SIGN_IN_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Session already closed.
            }
        }

        private void pollSignIn() {
            if (pin == null) return;
            if (System.currentTimeMillis() > pinDeadline) {
                endSignIn("The sign-in code expired. Turn on Sign in with Plex to get a new one.", true);
                return;
            }
            String accountToken;
            try {
                accountToken = account.pinToken(pin.id);
            } catch (PlexAccount.PinGoneException e) {
                endSignIn("The sign-in code expired. Turn on Sign in with Plex to get a new one.", true);
                return;
            } catch (IOException e) {
                // A network blip. Keep trying until the code expires.
                schedulePoll();
                return;
            }
            if (accountToken == null) schedulePoll();
            else finishSignIn(accountToken);
        }

        private void finishSignIn(String accountToken) {
            Config cfg = config;
            PlexAccount.Choice choice = null;
            String listError = "";
            try {
                choice = PlexAccount.choose(account.servers(accountToken), cfg.serverUrl, accountToken, account::identity);
            } catch (IOException e) {
                listError = "Could not list your Plex servers: " + e.getMessage() + ". ";
            }
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("token", seal(host, choice != null ? choice.token : accountToken));
            if (choice != null && cfg.serverUrl.isEmpty()) changes.put("serverUrl", choice.serverUrl);
            changes.put("signIn", false);
            persist(host, changes);
            clearSignIn();

            String message;
            boolean error = false;
            if (choice != null && !choice.serverName.isEmpty()) {
                message = "Signed in to Plex. Using " + choice.serverName + " at " + choice.serverUrl + ".";
            } else if (!cfg.serverUrl.isEmpty()) {
                message = listError + "Signed in to Plex.";
            } else {
                message = listError + "Signed in to Plex, but no server answered on this network. Enter the Server address.";
                error = true;
            }
            log("Signed in to Plex");
            status(message, error);
            restart((ss, gen) -> { ss.forceReload = true; ss.dropQueued(); ss.preloaded = null; });
        }

        private void endSignIn(String message, boolean error) {
            clearSignIn();
            if (config.signIn) persist(host, Collections.<String, Object>singletonMap("signIn", false));
            status(message, error);
            Config cfg = config;
            tile(cfg.configured() ? "" : "warn", cfg.configured() ? "Sign-in stopped" : "Not signed in to Plex");
            if (!cfg.configured()) publishMessage(setupMessage(cfg));
        }

        private void clearSignIn() {
            if (pinPoll != null) pinPoll.cancel(false);
            pinPoll = null;
            pin = null;
            account = null;
            try { host.hideWindow(); } catch (RuntimeException ignored) { }
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
