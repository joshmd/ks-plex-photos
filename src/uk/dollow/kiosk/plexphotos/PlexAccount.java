// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * plex.tv sign-in with a short code entered at plex.tv/link, the flow Plex uses for TVs, and
 * discovery of the servers that account can reach. Uses normal certificate checks throughout.
 */
final class PlexAccount {
    static final String PLEX_TV = "https://plex.tv";
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private final String base;
    private final String clientId;
    private final String deviceName;

    PlexAccount(String clientId, String deviceName) {
        this(PLEX_TV, clientId, deviceName);
    }

    /** Package-private so tests can point it at a local server. */
    PlexAccount(String base, String clientId, String deviceName) {
        this.base = base;
        this.clientId = clientId;
        this.deviceName = deviceName;
    }

    static final class Pin {
        final long id;
        final String code;
        /** Seconds until the code expires, as reported by Plex. */
        final int expiresIn;

        Pin(long id, String code, int expiresIn) {
            this.id = id;
            this.code = code;
            this.expiresIn = expiresIn;
        }
    }

    /** Thrown when Plex no longer knows the code, for example after it expired. */
    static final class PinGoneException extends IOException {
        PinGoneException() { super("The sign-in code expired"); }
    }

    private static final class StatusException extends IOException {
        final int code;
        StatusException(int code, String message) { super(message); this.code = code; }
    }

    Pin createPin() throws IOException {
        JSONObject o = object(request("POST", base + "/api/v2/pins?strong=false", null, 5000));
        String code = o.optString("code");
        long id = o.optLong("id", -1);
        if (id < 0 || code.isEmpty()) throw new IOException("plex.tv did not return a sign-in code");
        return new Pin(id, code, o.optInt("expiresIn", 900));
    }

    /** The account token once the code has been entered at plex.tv/link, otherwise null. */
    String pinToken(long id) throws IOException {
        JSONObject o;
        try {
            o = object(request("GET", base + "/api/v2/pins/" + id, null, 5000));
        } catch (StatusException e) {
            if (e.code == 404) throw new PinGoneException();
            throw e;
        }
        String token = o.optString("authToken", "");
        return token.isEmpty() || "null".equals(token) ? null : token;
    }

    static final class Connection {
        final String uri;
        final String address;
        final int port;
        final boolean local;
        final boolean relay;

        Connection(String uri, String address, int port, boolean local, boolean relay) {
            this.uri = uri;
            this.address = address;
            this.port = port;
            this.local = local;
            this.relay = relay;
        }
    }

    static final class Server {
        final String name;
        final String machineId;
        /** Token for this server. For a server shared with you it differs from the account token. */
        final String accessToken;
        final boolean owned;
        final List<Connection> connections;

        Server(String name, String machineId, String accessToken, boolean owned, List<Connection> connections) {
            this.name = name;
            this.machineId = machineId;
            this.accessToken = accessToken;
            this.owned = owned;
            this.connections = connections;
        }
    }

    List<Server> servers(String accountToken) throws IOException {
        byte[] body = request("GET", base + "/api/v2/resources?includeHttps=1&includeRelay=0", accountToken, 5000);
        JSONArray arr;
        try {
            arr = new JSONArray(new String(body, "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Unexpected response from plex.tv");
        }
        List<Server> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null || !r.optString("provides").contains("server")) continue;
            List<Connection> conns = new ArrayList<>();
            JSONArray ca = r.optJSONArray("connections");
            for (int j = 0; ca != null && j < ca.length(); j++) {
                JSONObject c = ca.optJSONObject(j);
                if (c == null) continue;
                conns.add(new Connection(c.optString("uri"), c.optString("address"), c.optInt("port", 32400),
                    c.optBoolean("local"), c.optBoolean("relay")));
            }
            out.add(new Server(r.optString("name"), r.optString("clientIdentifier"), r.optString("accessToken"),
                r.optBoolean("owned"), conns));
        }
        return out;
    }

    /** The machine identifier a server reports at /identity, or null if it does not answer. */
    String identity(String serverUrl) {
        try {
            JSONObject mc = object(request("GET", serverUrl + "/identity", null, 3000)).optJSONObject("MediaContainer");
            return mc == null ? null : mc.optString("machineIdentifier", null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The server address and token chosen after sign-in. */
    static final class Choice {
        final String serverUrl;
        final String token;
        /** Empty when the configured address did not match any server on the account. */
        final String serverName;

        Choice(String serverUrl, String token, String serverName) {
            this.serverUrl = serverUrl;
            this.token = token;
            this.serverName = serverName;
        }
    }

    interface Probe {
        String machineId(String serverUrl);
    }

    /**
     * With an address already configured, keeps it and uses the matching server's token. Without
     * one, tries each server's addresses, owned servers and LAN addresses first, and returns the
     * first that answers as that server. Null when none answers.
     */
    static Choice choose(List<Server> servers, String configuredUrl, String accountToken, Probe probe) {
        if (!configuredUrl.isEmpty()) {
            for (Server s : servers) {
                for (Connection c : s.connections) {
                    if (sameServer(configuredUrl, c)) return new Choice(configuredUrl, tokenFor(s, accountToken), s.name);
                }
            }
            return new Choice(configuredUrl, accountToken, "");
        }
        List<Server> ordered = new ArrayList<>();
        for (Server s : servers) if (s.owned) ordered.add(s);
        for (Server s : servers) if (!s.owned) ordered.add(s);
        for (Server s : ordered) {
            if (s.machineId.isEmpty()) continue;
            for (String url : candidates(s)) {
                if (s.machineId.equals(probe.machineId(url))) return new Choice(url, tokenFor(s, accountToken), s.name);
            }
        }
        return null;
    }

    private static String tokenFor(Server s, String accountToken) {
        return s.accessToken.isEmpty() ? accountToken : s.accessToken;
    }

    /** LAN addresses first: the plex.direct HTTPS name, then plain HTTP to the IP. Then remote, never relays. */
    static List<String> candidates(Server s) {
        Set<String> out = new LinkedHashSet<>();
        for (Connection c : s.connections) {
            if (!c.local || c.relay) continue;
            if (c.uri.startsWith("https://")) out.add(trimSlash(c.uri));
        }
        for (Connection c : s.connections) {
            if (!c.local || c.relay || c.address.isEmpty()) continue;
            String host = c.address.indexOf(':') >= 0 ? "[" + c.address + "]" : c.address;
            out.add("http://" + host + ":" + c.port);
        }
        for (Connection c : s.connections) {
            if (c.local || c.relay) continue;
            if (c.uri.startsWith("https://")) out.add(trimSlash(c.uri));
        }
        return new ArrayList<>(out);
    }

    private static boolean sameServer(String configuredUrl, Connection c) {
        try {
            URL want = new URL(configuredUrl);
            int wantPort = want.getPort() < 0 ? want.getDefaultPort() : want.getPort();
            if (!c.uri.isEmpty()) {
                URL u = new URL(c.uri);
                int port = u.getPort() < 0 ? u.getDefaultPort() : u.getPort();
                if (u.getHost().equalsIgnoreCase(want.getHost()) && port == wantPort) return true;
            }
            String host = want.getHost();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            return host.equalsIgnoreCase(c.address) && wantPort == c.port;
        } catch (IOException e) {
            return false;
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private byte[] request(String method, String url, String token, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs * 2);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json");
        PlexHttp.identify(c, clientId, deviceName);
        if (token != null) c.setRequestProperty("X-Plex-Token", token);
        try {
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                OutputStream out = c.getOutputStream();
                out.close();
            }
            int code = c.getResponseCode();
            if (code == 401) throw new StatusException(code, "plex.tv rejected the sign-in (401)");
            if (code < 200 || code >= 300) throw new StatusException(code, "plex.tv returned HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return PlexHttp.read(in, MAX_JSON_BYTES);
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject object(byte[] body) throws IOException {
        try {
            return new JSONObject(new String(body, "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("Unexpected response from plex.tv");
        }
    }
}
