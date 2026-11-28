package com.jungleclash.game.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Online play through the Firebase Realtime Database REST API (no Firebase SDK needed).
 *
 * <pre>
 * rooms/{code}/meta          {host, t}               written by the host
 * rooms/{code}/hostAlive     server time             host heartbeat
 * rooms/{code}/toHost/{push} {from, msg}             player → host messages
 * rooms/{code}/views/{id}    "json string"           host → player (latest view only)
 * rooms/{code}/alive/{id}    server time             player heartbeat
 * </pre>
 *
 * The host phone stays authoritative (it runs HostController), exactly like the Bluetooth mode.
 * Callbacks go to the same {@link NearbyLink.Listener}; endpoint ids are player ids on the host
 * and the room code on player phones.
 */
public class OnlineLink implements Link {

    public static final String PROJECT_ID = "jungleclash-appcom";

    // Messages for onError (Arabic source text, translated by the app).
    public static final String ERR_NETWORK = "لا يوجد اتصال بالإنترنت أو تعذّر الوصول إلى الخادم.";
    public static final String ERR_NO_DB = "قاعدة البيانات غير جاهزة. أنشئ Realtime Database في Firebase.";
    public static final String ERR_RULES = "قاعدة البيانات ترفض الدخول. حدّث قواعد Realtime Database في Firebase.";
    public static final String ERR_NO_ROOM = "لا توجد غرفة بهذا الرمز.";

    /** For tests: use this database URL instead of finding the Firebase one. */
    public static volatile String baseOverride;
    private static volatile String base;

    private static final int TICK_MS = 4000;
    private static final int STALE_TICKS = 4;            // ~16 s without a heartbeat = disconnected

    public interface RoomListener { void onRoomReady(String code); }

    private final NearbyLink.Listener listener;
    private final String myId;
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    private final Map<String, String> pendingView = new ConcurrentHashMap<>();
    private final Map<String, String> lastSent = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile String code;
    private volatile String hostName;
    private NearbyLink.Gate gate;
    private RoomListener roomListener;
    private Stream stream;
    private Thread ticker;

    // host side
    private volatile boolean hosting;
    private final Set<String> seenMessages = ConcurrentHashMap.newKeySet();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Map<String, String> langs = new ConcurrentHashMap<>();
    private final Set<String> connected = ConcurrentHashMap.newKeySet();
    private final Set<String> lost = ConcurrentHashMap.newKeySet();
    private final Map<String, String> beat = new ConcurrentHashMap<>();
    private final Map<String, Integer> staleTicks = new ConcurrentHashMap<>();

    // player side
    private volatile boolean joined;

    public OnlineLink(NearbyLink.Listener listener, String myId) {
        this.listener = listener;
        this.myId = myId;
    }

    public void setRoomListener(RoomListener l) { this.roomListener = l; }

    public String roomCode() { return code; }

    public static String newClientId() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder("p");
        for (int i = 0; i < 12; i++) sb.append("abcdefghijkmnpqrstuvwxyz23456789".charAt(r.nextInt(32)));
        return sb.toString();
    }

    @Override public void setGate(NearbyLink.Gate gate) { this.gate = gate; }
    @Override public void stopAdvertising() { }
    @Override public void startSearching() { }
    @Override public void stopSearching() { }

    // ================================================================ host

    @Override
    public void startHosting(String name) {
        if (hosting || closed) return;
        hosting = true;
        hostName = name;
        background(() -> {
            try {
                resolveBase();
                SecureRandom r = new SecureRandom();
                String c = null;
                for (int i = 0; i < 30 && c == null; i++) {
                    String tryCode = String.valueOf(1000 + r.nextInt(9000));
                    if ("null".equals(http("GET", room(tryCode) + "/meta.json", null))) c = tryCode;
                }
                if (c == null) throw new IOException("no free code");
                http("PUT", room(c) + ".json",
                     "{\"meta\":{\"host\":" + MiniJson.quote(name) + ",\"t\":{\".sv\":\"timestamp\"}},"
                   + "\"hostAlive\":{\".sv\":\"timestamp\"}}");
                code = c;
                if (closed) { http("DELETE", room(c) + ".json", null); return; }
                stream = new Stream(room(c) + "/toHost.json", this::onHostEvent);
                stream.start();
                startTicker(true);
                if (roomListener != null) roomListener.onRoomReady(c);
            } catch (Failure f) {
                hosting = false;
                listener.onError(f.getMessage());
            } catch (IOException e) {
                hosting = false;
                listener.onError(ERR_NETWORK);
            }
        });
    }

    private void onHostEvent(String path, Object data) {
        Map<String, Object> msgs = new LinkedHashMap<>();
        if (path.equals("/")) {
            if (data instanceof Map) for (Map.Entry<String, Object> e : asMap(data).entrySet()) msgs.put(e.getKey(), e.getValue());
        } else {
            String[] seg = path.substring(1).split("/");
            if (seg.length == 1 && data instanceof Map) msgs.put(seg[0], data);
        }
        for (Map.Entry<String, Object> e : msgs.entrySet()) {
            if (!seenMessages.add(e.getKey())) continue;
            Map<String, Object> m = asMap(e.getValue());
            String from = str(m.get("from")), msg = str(m.get("msg"));
            final String key = e.getKey();
            out.submit(() -> { try { http("DELETE", room(code) + "/toHost/" + key + ".json", null); } catch (Exception ignored) { } });
            if (from == null || msg == null) continue;
            handleFromPlayer(from, msg);
        }
    }

    private void handleFromPlayer(String from, String msg) {
        Object parsed;
        try { parsed = MiniJson.parse(msg); } catch (RuntimeException e) { return; }
        Map<String, Object> m = asMap(parsed);
        String t = str(m.get("t"));
        if ("join".equals(t)) {
            String name = str(m.get("name"));
            names.put(from, name == null ? "" : name);
            if (connected.remove(from)) {                 // came back before we noticed it left
                if (!lost.remove(from)) listener.onDisconnected(from);
            }
            lost.remove(from);
            lastSent.remove(from);
            NearbyLink.Gate g = gate;
            if (g == null) accept(from); else g.onRequest(from, name == null ? "" : name);
            return;
        }
        if ("hello".equals(t)) {
            String l = str(m.get("lang"));
            if (l != null) langs.put(from, l);
        }
        if (connected.contains(from)) listener.onMessage(from, msg);
    }

    @Override
    public void accept(String id) {
        connected.add(id);
        lost.remove(id);
        staleTicks.put(id, 0);
        lastSent.remove(id);
        pendingView.remove(id);
        final String c = code;
        out.submit(() -> {
            try {
                http("PUT", room(c) + "/views/" + id + ".json",
                     MiniJson.quote("{\"t\":\"accepted\",\"host\":" + MiniJson.quote(hostName) + "}"));
            } catch (Exception ignored) { }
        });
    }

    @Override
    public void reject(String id) {
        final String c = code;
        out.submit(() -> {
            try { http("PUT", room(c) + "/views/" + id + ".json", MiniJson.quote("{\"t\":\"rejected\"}")); }
            catch (Exception ignored) { }
        });
    }

    // ================================================================ player

    @Override
    public void join(String myName, String roomCode) {
        code = roomCode;
        background(() -> {
            try {
                resolveBase();
                Object meta = MiniJson.parse(http("GET", room(roomCode) + "/meta.json", null));
                if (!(meta instanceof Map)) { listener.onError(ERR_NO_ROOM); return; }
                hostName = str(asMap(meta).get("host"));
                http("DELETE", room(roomCode) + "/views/" + myId + ".json", null);
                http("PUT", room(roomCode) + "/alive/" + myId + ".json", "{\".sv\":\"timestamp\"}");
                if (closed) return;
                stream = new Stream(room(roomCode) + "/views/" + myId + ".json", this::onPlayerEvent);
                stream.start();
                post("{\"t\":\"join\",\"name\":" + MiniJson.quote(myName) + "}");
                startTicker(false);
            } catch (Failure f) {
                listener.onError(f.getMessage());
            } catch (IOException | RuntimeException e) {
                listener.onError(ERR_NETWORK);
            }
        });
    }

    private void onPlayerEvent(String path, Object data) {
        if (!path.equals("/")) return;
        if (data == null) {
            if (joined) dropped();
            return;
        }
        String msg = str(data);
        if (msg == null) return;
        String t;
        try { t = str(asMap(MiniJson.parse(msg)).get("t")); } catch (RuntimeException e) { return; }
        if ("accepted".equals(t)) {
            if (!joined) { joined = true; listener.onConnected(code, hostName); }
        } else if ("rejected".equals(t)) {
            listener.onConnectFailed(code, true);
            shutdown();
        } else {
            if (!joined) { joined = true; listener.onConnected(code, hostName); }
            listener.onMessage(code, msg);
        }
    }

    private void dropped() {
        if (closed) return;
        boolean was = joined;
        joined = false;
        shutdown();
        if (was) listener.onDisconnected(code);
    }

    private void post(String msg) {
        final String c = code;
        out.submit(() -> {
            try {
                http("POST", room(c) + "/toHost.json",
                     "{\"from\":" + MiniJson.quote(myId) + ",\"msg\":" + MiniJson.quote(msg) + "}");
            } catch (Exception ignored) { }
        });
    }

    // ================================================================ both

    @Override
    public void send(String endpointId, String message) {
        if (closed || code == null) return;
        if (!hosting) { post(message); return; }
        if (!connected.contains(endpointId)) return;
        if (message.equals(lastSent.get(endpointId))) return;
        lastSent.put(endpointId, message);
        // Only the newest view matters: queued older views for the same player are dropped.
        pendingView.put(endpointId, message);
        final String c = code;
        out.submit(() -> {
            String m = pendingView.remove(endpointId);
            if (m == null) return;
            try { http("PUT", room(c) + "/views/" + endpointId + ".json", MiniJson.quote(m)); }
            catch (Exception e) { lastSent.remove(endpointId); }
        });
    }

    @Override
    public void shutdown() {
        if (closed) return;
        closed = true;
        if (stream != null) stream.close();
        if (ticker != null) ticker.interrupt();
        final String c = code;
        final boolean wasHost = hosting;
        if (c != null) {
            out.submit(() -> {
                try {
                    if (wasHost) http("DELETE", room(c) + ".json", null);
                    else http("DELETE", room(c) + "/alive/" + myId + ".json", null);
                } catch (Exception ignored) { }
            });
        }
        out.shutdown();
    }

    private void startTicker(boolean isHost) {
        ticker = new Thread(() -> {
            String lastHostBeat = null;
            int sameHostBeat = 0, failures = 0;
            while (!closed) {
                try { Thread.sleep(TICK_MS); } catch (InterruptedException e) { return; }
                if (closed) return;
                try {
                    if (isHost) {
                        http("PUT", room(code) + "/hostAlive.json", "{\".sv\":\"timestamp\"}");
                        checkPlayers(asMap(MiniJson.parse(http("GET", room(code) + "/alive.json", null))));
                    } else {
                        http("PUT", room(code) + "/alive/" + myId + ".json", "{\".sv\":\"timestamp\"}");
                        String hb = http("GET", room(code) + "/hostAlive.json", null);
                        if (hb.equals(lastHostBeat)) sameHostBeat++; else { sameHostBeat = 0; lastHostBeat = hb; }
                        if ("null".equals(hb) || sameHostBeat >= STALE_TICKS + 1) { dropped(); return; }
                    }
                    failures = 0;
                } catch (IOException | RuntimeException e) {
                    if (!isHost && ++failures >= STALE_TICKS + 1) { dropped(); return; }
                }
            }
        });
        ticker.setDaemon(true);
        ticker.start();
    }

    /** Host: notice players whose heartbeat stopped, and players whose heartbeat came back. */
    private void checkPlayers(Map<String, Object> alive) {
        for (String id : new ArrayList<>(connected)) {
            String now = String.valueOf(alive.get(id));
            boolean changed = !now.equals(beat.get(id));
            beat.put(id, now);
            if (changed) {
                staleTicks.put(id, 0);
                if (lost.remove(id)) {
                    // It is back (for example the host's own internet dropped for a while): rejoin quietly.
                    String l = langs.get(id);
                    listener.onMessage(id, "{\"t\":\"hello\",\"name\":" + MiniJson.quote(names.getOrDefault(id, ""))
                        + ",\"lang\":" + MiniJson.quote(l == null ? "ar" : l) + "}");
                    lastSent.remove(id);
                }
            } else {
                int n = staleTicks.getOrDefault(id, 0) + 1;
                staleTicks.put(id, n);
                if (n >= STALE_TICKS && lost.add(id)) listener.onDisconnected(id);
            }
        }
    }

    // ================================================================ HTTP

    private static final class Failure extends IOException {
        Failure(String m) { super(m); }
    }

    private static String room(String c) { return base + "/rooms/" + c; }

    private void background(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    private static final Pattern REGION_URL = Pattern.compile("https://[A-Za-z0-9.-]+\\.firebasedatabase\\.app");

    /** Find the database URL: the default one, or the regional one the server points to. */
    static synchronized void resolveBase() throws IOException {
        if (baseOverride != null) { base = baseOverride; return; }
        if (base != null) return;
        String[] candidates = {
            "https://" + PROJECT_ID + "-default-rtdb.firebaseio.com",
            "https://" + PROJECT_ID + "-default-rtdb.europe-west1.firebasedatabase.app",
            "https://" + PROJECT_ID + "-default-rtdb.asia-southeast1.firebasedatabase.app",
        };
        boolean reached = false;
        for (String cand : candidates) {
            for (int hop = 0; hop < 2 && cand != null; hop++) {
                int[] status = new int[1];
                String body;
                try { body = raw("GET", cand + "/rooms/0000/meta.json", null, status); }
                catch (IOException e) { break; }
                reached = true;
                if (status[0] == 200) { base = cand; return; }
                if (status[0] == 401 || status[0] == 403) throw new Failure(ERR_RULES);
                Matcher m = REGION_URL.matcher(body);
                cand = m.find() && !m.group().equals(cand) ? m.group() : null;
            }
        }
        throw new Failure(reached ? ERR_NO_DB : ERR_NETWORK);
    }

    static String http(String method, String url, String body) throws IOException {
        int[] status = new int[1];
        String r = raw(method, url, body, status);
        if (status[0] == 401 || status[0] == 403) throw new Failure(ERR_RULES);
        if (status[0] / 100 != 2) throw new IOException("HTTP " + status[0] + ": " + r);
        return r;
    }

    private static String raw(String method, String url, String body, int[] status) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod(method);
        if (method.equals("PUT") || method.equals("POST")) {
            c.setDoOutput(true);
            byte[] b = (body == null ? "null" : body).getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
        }
        try {
            status[0] = c.getResponseCode();
            InputStream in = status[0] >= 400 ? c.getErrorStream() : c.getInputStream();
            return in == null ? "" : readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString().trim();
    }

    // ================================================================ live updates (Server-Sent Events)

    interface Handler { void on(String path, Object data); }

    /** Listens to one database path; reconnects by itself when the connection drops. */
    private final class Stream extends Thread {
        private final String url;
        private final Handler handler;
        private volatile HttpURLConnection conn;
        private volatile boolean stop;

        Stream(String url, Handler handler) {
            this.url = url;
            this.handler = handler;
            setDaemon(true);
        }

        void close() {
            stop = true;
            interrupt();
            HttpURLConnection c = conn;
            if (c != null) c.disconnect();
        }

        @Override
        public void run() {
            while (!stop && !closed) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    conn = c;
                    c.setRequestProperty("Accept", "text/event-stream");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(70000);       // the server sends keep-alive every 30 s
                    c.setInstanceFollowRedirects(true);
                    if (c.getResponseCode() == 401) { listener.onError(ERR_RULES); return; }
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    String event = null, line;
                    while (!stop && (line = r.readLine()) != null) {
                        if (line.startsWith("event:")) event = line.substring(6).trim();
                        else if (line.startsWith("data:") && event != null) {
                            String d = line.substring(5).trim();
                            if (event.equals("put") || event.equals("patch")) {
                                Map<String, Object> m = asMap(MiniJson.parse(d));
                                String path = str(m.get("path"));
                                Object data = m.get("data");
                                if (event.equals("patch") && data instanceof Map && path != null) {
                                    for (Map.Entry<String, Object> e : asMap(data).entrySet())
                                        handler.on(path.equals("/") ? "/" + e.getKey() : path + "/" + e.getKey(), e.getValue());
                                } else if (path != null) {
                                    handler.on(path, data);
                                }
                            } else if (event.equals("cancel") || event.equals("auth_revoked")) {
                                listener.onError(ERR_RULES);
                                return;
                            }
                            event = null;
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                } finally {
                    HttpURLConnection c = conn;
                    if (c != null) c.disconnect();
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }
    }

    // ================================================================ tiny JSON

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
    }

    static String str(Object o) { return o instanceof String ? (String) o : null; }

    /** Minimal JSON reader/writer so the link has no dependencies (works the same on phone and in tests). */
    static final class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) { this.s = s; }

        static Object parse(String s) {
            MiniJson p = new MiniJson(s);
            p.ws();
            Object v = p.value();
            return v;
        }

        static String quote(String v) {
            if (v == null) return "null";
            StringBuilder sb = new StringBuilder("\"");
            for (int k = 0; k < v.length(); k++) {
                char ch = v.charAt(k);
                switch (ch) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                        else sb.append(ch);
                }
            }
            return sb.append('"').toString();
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("bad json at " + i);
            String n = s.substring(st, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws(); i++; ws();           // ':'
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }
    }
}
