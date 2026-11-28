package com.jungleclash.game;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jungleclash.game.engine.Cards;
import com.jungleclash.game.engine.Lang;
import com.jungleclash.game.net.Link;
import com.jungleclash.game.net.NearbyLink;
import com.jungleclash.game.net.OnlineLink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single-screen app. All screens are built in code, right-to-left, in Arabic. */
public class MainActivity extends Activity implements NearbyLink.Listener, HostController.Sender {

    // Palette (matches the printed cards)
    static final int BG = Color.parseColor("#10261A");
    static final int PANEL = Color.parseColor("#F8F1DF");
    static final int GOLD = Color.parseColor("#D9A93A");
    static final int GOLD_LIGHT = Color.parseColor("#F2C14E");
    static final int INK = Color.parseColor("#2A241C");
    static final int PRED = Color.parseColor("#9E2A2B");
    static final int PRED_DARK = Color.parseColor("#3A1416");
    static final int HERB = Color.parseColor("#2E6B3A");
    static final int HERB_DARK = Color.parseColor("#13301E");
    static final int EVENT = Color.parseColor("#4B2E83");
    static final int ATTACK = Color.parseColor("#B83A0E");
    static final int HIDE = Color.parseColor("#0B5F6B");
    static final int MUTED = Color.parseColor("#E9E2CF");

    private static final int REQ_PERMS = 7;

    private LinearLayout root;
    private float ui = 1f;          // layout scale for this screen width
    private float fontUi = 1f;      // text scale (screen width × the phone's font setting, capped)
    private boolean narrow;         // small phones: stack side-by-side boxes
    private Link link;              // the link in use: nearby or online
    private NearbyLink nearby;
    private boolean onlineMode;     // this game goes over the internet
    private String roomCode;        // online room code
    private HostController host;          // only on the host phone
    private String hostEndpoint;          // only on player phones
    private String myName = "";
    /** App language: Lang.AR or Lang.EN. */
    private String lang = Lang.AR;
    /** Redraws the current non-game screen (home, search, info), or null while a game view is shown. */
    private Runnable current;
    private Runnable afterPermissions;
    private JSONObject lastView;
    private final Map<String, String> found = new LinkedHashMap<>();
    private boolean searching;
    private String hostName;        // name of the room we joined (for reconnecting)
    private String autoJoin;        // when set, join this room as soon as it is found
    private String lastFlipKey = "";       // which reveal was already animated
    private int shownDuels = 0, shownDuelRound = 0;  // duels already revealed on this phone
    private boolean countingDown;
    private final android.os.Handler ui_handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<String> answered = new ArrayList<>(); // prompt keys already answered locally

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lang = getSharedPreferences("jc", MODE_PRIVATE).getString("lang", Lang.AR);
        computeScale();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setLayoutDirection(dir());
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(dir());
        root.setPadding(dp(10), dp(8), dp(10), dp(14));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        // Keep everything clear of the status bar, notch and navigation bar (edge-to-edge phones).
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                         insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        nearby = new NearbyLink(this, this);
        link = nearby;
        SharedPreferences prefs = getSharedPreferences("jc", MODE_PRIVATE);
        myName = prefs.getString("name", "");
        if (!prefs.getBoolean("tutorialSeen", false)) {
            prefs.edit().putBoolean("tutorialSeen", true).apply();
            showTutorial(0);
        } else {
            showHome();
        }
    }

    @Override
    protected void onDestroy() {
        if (link != null) link.shutdown();
        stopSound();
        stopMusic();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (host == null && hostEndpoint == null && !searching) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
            .setMessage(T("هل تريد مغادرة اللعبة؟"))
            .setPositiveButton(T("مغادرة"), (d, w) -> leave())
            .setNegativeButton(T("إلغاء"), null)
            .show();
    }

    private void leave() {
        autoJoin = null;
        lastJson = "";
        hostName = null;
        link.shutdown();
        link = nearby;
        onlineMode = false;
        roomCode = null;
        host = null;
        hostEndpoint = null;
        searching = false;
        lastView = null;
        found.clear();
        showHome();
    }

    // ================================================================ permissions

    private String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        List<String> missing = new ArrayList<>();
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) missing.add(s);
        return missing.toArray(new String[0]);
    }

    private void withPermissions(Runnable r) {
        String[] missing = neededPermissions();
        if (missing.length == 0) { r.run(); return; }
        afterPermissions = r;
        requestPermissions(missing, REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS) return;
        if (neededPermissions().length == 0 && afterPermissions != null) {
            Runnable r = afterPermissions;
            afterPermissions = null;
            r.run();
        } else {
            toastDialog(T("اللعب بدون إنترنت يحتاج إذن البلوتوث والأجهزة القريبة والموقع. اسمح بها من إعدادات التطبيق ثم حاول مرة أخرى."));
        }
    }

    // ================================================================ home / lobby screens

    private void showHome() {
        current = this::showHome;
        music("menu");
        root.removeAllViews();
        LinearLayout top = row();
        top.setGravity(Gravity.END);
        Button lb = button(Lang.en(lang) ? "العربية" : "English", Color.parseColor("#6B4E1E"), x -> toggleLang());
        lb.setMinHeight(dp(40));
        lb.setPadding(dp(14), dp(6), dp(14), dp(6));
        top.addView(lb, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(120));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(4);
        root.addView(logo, lp);
        TextView title = text(T("صراع الغابة"), 40, GOLD_LIGHT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = text(T("لعبة القوة والدهاء · من 2 إلى 6 لاعبين · بدون إنترنت"), 14, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
        space(10);
        root.addView(animalCarousel());
        space(14);

        EditText name = new EditText(this);
        name.setHint(T("اكتب اسمك"));
        name.setText(myName);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setTextColor(INK);
        name.setHintTextColor(Color.GRAY);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18 * fontUi);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(12), dp(12), dp(12), dp(12));
        name.setBackground(rounded(PANEL, GOLD, 12));
        root.addView(name, new LinearLayout.LayoutParams(-1, -2));
        space(16);

        root.addView(withIcon(R.drawable.ic_bt, button(T("إنشاء غرفة (أنا المضيف)"), HERB, v -> {
            if (!saveName(name)) return;
            withPermissions(this::startHosting);
        })));
        space(10);
        root.addView(withIcon(R.drawable.ic_bt, button(T("الانضمام إلى غرفة قريبة"), ATTACK, v -> {
            if (!saveName(name)) return;
            withPermissions(this::startSearching);
        })));
        space(10);
        root.addView(button(T("العب وحدك ضد الكمبيوتر"), EVENT, v -> {
            if (!saveName(name)) return;
            showSolo();
        }));
        space(14);
        root.addView(text(T("اللعب عبر الإنترنت"), 17, GOLD_LIGHT, true));
        space(6);
        root.addView(pair(withIcon(R.drawable.ic_wifi, button(T("إنشاء غرفة على الإنترنت"), HERB, v -> {
                              if (!saveName(name)) return;
                              startOnlineHosting();
                          })),
                          withIcon(R.drawable.ic_wifi, button(T("الانضمام برمز الغرفة"), ATTACK, v -> {
                              if (!saveName(name)) return;
                              showJoinCode();
                          }))));
        space(18);
        LinearLayout extras = pair(button(T("موسوعة الغابة"), EVENT, v -> showEncyclopedia()),
                                   button(T("القواعد"), HIDE, v -> showRules()));
        root.addView(extras);
        space(8);
        root.addView(button(T("جولة تعليمية"), Color.parseColor("#6B4E1E"), v -> showTutorial(0)));
        space(18);
        root.addView(infoBox(T("كيف تلعب"), 13,
            T("• شغّلوا البلوتوث والموقع (GPS) على كل الهواتف.\n")
          + T("• لاعب واحد ينشئ الغرفة، والباقون ينضمون إليها.\n")
          + T("• كل لاعب يرى حيوانه وقراره سراً على هاتفه.\n")
          + T("• 3 جولات، والأعلى نقاطاً في النهائي يُتوَّج ملك الغابة.")));
    }

    private boolean saveName(EditText e) {
        String n = e.getText().toString().trim();
        if (n.isEmpty()) { e.setError(T("اكتب اسمك أولاً")); return false; }
        myName = n;
        getSharedPreferences("jc", MODE_PRIVATE).edit().putString("name", n).apply();
        return true;
    }

    private void startHosting() {
        host = new HostController(this);
        link.setGate((id, name) -> runOnUiThread(() -> {
            if (host == null || !host.addRequest(id, name)) link.reject(id);
        }));
        link.startHosting(myName);
        host.onHello(HostController.LOCAL_ID, myName, lang);
    }

    private void startSearching() {
        searching = true;
        found.clear();
        link.setGate(null);
        link.startSearching();
        showSearch(T("ابحث عن غرفة قريبة…"));
    }

    // ================================================================ solo play

    private int soloCount = 2;
    private boolean soloHard;

    private void showSolo() {
        current = this::showSolo;
        root.removeAllViews();
        header(T("العب وحدك"));
        root.addView(text(T("خصوم الكمبيوتر يلعبون فوراً، وأنت المضيف: تتحكم في بدء الجولات وكشف المبارزات."), 14, MUTED, false));
        space(12);
        root.addView(text(T("عدد الخصوم"), 17, GOLD_LIGHT, true));
        space(6);
        LinearLayout counts = row();
        for (int i = 1; i <= 5; i++) {
            final int n = i;
            Button b = button(String.valueOf(i), i == soloCount ? ATTACK : HIDE, x -> { soloCount = n; showSolo(); });
            b.setPadding(0, dp(10), 0, dp(10));
            counts.addView(b, weight());
        }
        root.addView(counts);
        space(14);
        root.addView(text(T("المستوى"), 17, GOLD_LIGHT, true));
        space(6);
        root.addView(pair(button(T("سهل"), soloHard ? HIDE : ATTACK, x -> { soloHard = false; showSolo(); }),
                          button(T("صعب"), soloHard ? ATTACK : HIDE, x -> { soloHard = true; showSolo(); })));
        space(18);
        if (soloCount == 1) {
            root.addView(button(T("ابدأ: وضع المبارزة (مقترح)"), HERB, x -> startSolo("duel")));
            space(8);
            root.addView(button(T("ابدأ: الوضع العادي"), HERB, x -> startSolo("normal")));
        } else {
            root.addView(button(T("ابدأ اللعب"), HERB, x -> startSolo("normal")));
        }
        space(10);
        root.addView(button(T("رجوع"), Color.DKGRAY, x -> showHome()));
    }

    private void startSolo(String mode) {
        current = null;
        host = new HostController(this);
        host.onHello(HostController.LOCAL_ID, myName, lang);
        for (int i = 0; i < soloCount; i++) host.addBot(soloHard ? HostController.HARD : HostController.EASY);
        host.startGame(mode);
    }

    // ================================================================ online play

    private String clientId() {
        SharedPreferences p = getSharedPreferences("jc", MODE_PRIVATE);
        String id = p.getString("cid", "");
        if (id.isEmpty()) {
            id = OnlineLink.newClientId();
            p.edit().putString("cid", id).apply();
        }
        return id;
    }

    private OnlineLink useOnline() {
        if (link != null && link != nearby) link.shutdown();
        OnlineLink o = new OnlineLink(this, clientId());
        link = o;
        onlineMode = true;
        return o;
    }

    private void startOnlineHosting() {
        roomCode = null;
        OnlineLink o = useOnline();
        o.setRoomListener(c -> runOnUiThread(() -> {
            roomCode = c;
            if (lastView != null && current == null) { lastJson = null; render(lastView); }
        }));
        startHosting();
    }

    private void showJoinCode() {
        current = this::showJoinCode;
        root.removeAllViews();
        header(T("الانضمام برمز الغرفة"));
        root.addView(text(T("اكتب رمز الغرفة المكوّن من 4 أرقام"), 16, MUTED, false));
        space(10);
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setTextColor(INK);
        code.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30 * fontUi);
        code.setGravity(Gravity.CENTER);
        code.setPadding(dp(12), dp(12), dp(12), dp(12));
        code.setBackground(rounded(PANEL, GOLD, 12));
        if (roomCode != null) code.setText(roomCode);
        root.addView(code, new LinearLayout.LayoutParams(-1, -2));
        space(14);
        root.addView(button(T("انضم"), HERB, v -> {
            String c = code.getText().toString().trim();
            if (c.length() != 4) { code.setError(T("اكتب رمز الغرفة المكوّن من 4 أرقام")); return; }
            joinOnline(c);
        }));
        space(10);
        root.addView(button(T("رجوع"), Color.DKGRAY, v -> leave()));
    }

    private void joinOnline(String code) {
        useOnline();
        roomCode = code;
        hostName = null;
        searching = true;
        found.clear();
        link.setGate(null);
        showSearch(T("أُرسل طلبك إلى غرفة ") + code + T(". بانتظار موافقة المضيف…"));
        link.join(myName, code);
    }

    private void showReconnect() {
        current = this::showReconnect;
        root.removeAllViews();
        header(T("انقطع الاتصال"));
        root.addView(infoBox(T("لا تقلق"), T("مكانك في اللعبة محفوظ عند المضيف. ستُلعب اختياراتك بأمان (اختباء) حتى تعود.")));
        space(14);
        root.addView(button(T("إعادة الاتصال بغرفة ") + (hostName == null ? T("المضيف") : hostName), HERB, x -> {
            if (onlineMode && roomCode != null) { joinOnline(roomCode); return; }
            withPermissions(() -> {
                searching = true;
                found.clear();
                autoJoin = hostName;
                link.setGate(null);
                link.startSearching();
                showSearch(T("أبحث عن غرفة ") + (hostName == null ? T("المضيف") : hostName) + "…");
            });
        }));
        space(10);
        root.addView(button(T("مغادرة اللعبة"), Color.DKGRAY, x -> leave()));
    }

    private void showSearch(String status) {
        current = () -> showSearch(status);
        root.removeAllViews();
        header(T("الانضمام إلى غرفة"));
        root.addView(text(status, 16, MUTED, false));
        space(12);
        if (found.isEmpty() && !onlineMode) {
            root.addView(text(T("لم تظهر غرف بعد. تأكد أن المضيف أنشأ الغرفة وأن البلوتوث يعمل."), 14, MUTED, false));
        }
        for (Map.Entry<String, String> e : found.entrySet()) {
            String id = e.getKey();
            root.addView(button(T("غرفة ") + e.getValue(), HERB, v -> {
                hostName = e.getValue();
                showSearch(T("أُرسل طلبك إلى غرفة ") + e.getValue() + T(". بانتظار موافقة المضيف…"));
                link.join(myName, id);
            }));
            space(8);
        }
        space(16);
        root.addView(button(T("رجوع"), Color.DKGRAY, v -> leave()));
    }

    // ================================================================ network callbacks

    @Override
    public void onPeerFound(String endpointId, String name) {
        runOnUiThread(() -> {
            found.put(endpointId, name);
            if (autoJoin != null && autoJoin.equals(name) && hostEndpoint == null) {
                showSearch(T("وجدت غرفة ") + name + T(". جارٍ العودة إلى اللعبة…"));
                link.join(myName, endpointId);
                autoJoin = null;
                return;
            }
            if (searching && hostEndpoint == null) showSearch(T("اختر الغرفة التي تريد الانضمام إليها:"));
        });
    }

    @Override
    public void onPeerLost(String endpointId) {
        runOnUiThread(() -> {
            found.remove(endpointId);
            if (searching && hostEndpoint == null) showSearch(T("ابحث عن غرفة قريبة…"));
        });
    }

    @Override
    public void onConnected(String endpointId, String name) {
        runOnUiThread(() -> {
            if (host != null) return; // host waits for the player's "hello"
            hostEndpoint = endpointId;
            searching = false;
            link.stopSearching();
            JSONObject hello = new JSONObject();
            try {
                hello.put("t", "hello");
                hello.put("name", myName);
                hello.put("lang", lang);
            } catch (JSONException ignored) { }
            link.send(endpointId, hello.toString());
            showSearch(T("تم الاتصال! بانتظار المضيف…"));
        });
    }

    @Override
    public void onDisconnected(String endpointId) {
        runOnUiThread(() -> {
            if (host != null) {
                host.removeRequest(endpointId);
                host.onDisconnected(endpointId);
                // Show the room again so the player can find it and ask to come back.
                if (host.hasOffline()) link.startHosting(myName);
            } else if (endpointId.equals(hostEndpoint)) {
                hostEndpoint = null;
                showReconnect();
            }
        });
    }

    @Override
    public void onMessage(String endpointId, String message) {
        runOnUiThread(() -> {
            try {
                JSONObject m = new JSONObject(message);
                String t = m.optString("t");
                if (host != null) {
                    if (t.equals("hello")) {
                        host.onHello(endpointId, m.optString("name"), m.optString("lang", Lang.AR));
                        if (!host.phase().equals("lobby") && !host.hasOffline()) link.stopAdvertising();
                    }
                    else if (t.equals("lang")) host.setLang(host.playerFor(endpointId), m.optString("lang"));
                    else if (t.equals("choice")) host.onChoice(host.playerFor(endpointId), m.optString("kind"), m.optString("value"));
                } else if (t.equals("view")) {
                    renderIfChanged(message);
                }
            } catch (JSONException ignored) { }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            if (onlineMode && host != null && roomCode == null) leave();               // the room couldn't be created
            else if (onlineMode && host == null && hostEndpoint == null) showJoinCode();  // couldn't join
            toastDialog(T(message));
        });
    }

    @Override
    public void onConnectFailed(String endpointId, boolean rejected) {
        runOnUiThread(() -> {
            if (host != null) { host.removeRequest(endpointId); return; }
            toastDialog(rejected ? T("رفض المضيف طلب الانضمام.") : T("تعذّر الاتصال بالغرفة. حاول مرة أخرى."));
            if (onlineMode) { String c = roomCode; leave(); roomCode = c; showJoinCode(); return; }
            if (searching) showSearch(T("اختر الغرفة التي تريد الانضمام إليها:"));
        });
    }

    /** HostController.Sender: deliver a view to a player (or to this phone). */
    private String lastJson = "";

    /** Re-drawing an identical view would restart animations, so skip exact repeats. */
    private void renderIfChanged(String json) {
        if (json.equals(lastJson)) return;
        lastJson = json;
        try { render(new JSONObject(json)); } catch (JSONException ignored) { }
    }

    @Override
    public void send(String playerId, String json) {
        if (HostController.LOCAL_ID.equals(playerId)) {
            runOnUiThread(() -> renderIfChanged(json));
        } else {
            link.send(playerId, json);
        }
    }

    private void choose(String kind, String value) {
        answered.add(kind + "#" + (lastView == null ? 0 : lastView.optInt("round")));
        sendChoice(kind, value);
    }

    private void sendChoice(String kind, String value) {
        if (host != null) {
            host.onChoice(HostController.LOCAL_ID, kind, value);
        } else if (hostEndpoint != null) {
            JSONObject c = new JSONObject();
            try {
                c.put("t", "choice");
                c.put("kind", kind);
                c.put("value", value);
            } catch (JSONException ignored) { }
            link.send(hostEndpoint, c.toString());
            if (lastView != null) render(lastView); // hide the buttons right away
        }
    }

    // ================================================================ game screen

    private void render(JSONObject v) {
        lastView = v;
        current = null;
        music(musicFor(v));
        root.removeAllViews();
        String phase = v.optString("phase");
        boolean isHost = v.optBoolean("isHost");

        if (phase.equals("lobby")) { renderLobby(v, isHost); return; }
        if (v.optString("mode").equals("duel")) { renderDuel(v, isHost); return; }

        // ---- header
        int round = v.optInt("round");
        String rTitle = v.optBoolean("twoPlayer") ? T("الجولة ") + round
                      : (v.optBoolean("finalRound") ? T("الجولة ") + round + T(" · النهائي") : T("الجولة ") + round);
        header(rTitle);
        requestsBox(v);
        root.addView(pair(chip(T("البيئة: ") + v.optString("envName"), v.optString("envText"), HERB),
                          chip(T("الحدث: ") + v.optString("eventName"), v.optString("eventText"), EVENT)));
        space(12);

        // ---- revealed animals / results come first so everyone sees them
        renderArena(v);

        // ---- my animal or spirit
        JSONObject animal = v.optJSONObject("animal");
        if (v.optBoolean("alive") && animal == null) {
            // choosing phase: the hand is shown with the prompt below
        } else if (v.optBoolean("alive")) {
            root.addView(animalCard(animal, true));
            if (!phase.equals("pre") && !phase.equals("decide")) {
                space(6);
                TextView d = text(T("قرارك: ") + v.optString("decisionText"), 15, MUTED, true);
                d.setGravity(Gravity.CENTER);
                root.addView(d);
            }
        } else {
            root.addView(infoBox(T("أنت روح الغابة"), T("خرجت من المنافسة، لكنك تمنح +2 لأحد اللاعبين في كل جولة.")));
        }
        space(10);

        // ---- private notes
        JSONArray notes = v.optJSONArray("notes");
        if (notes != null && notes.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < notes.length(); i++) sb.append(i > 0 ? "\n" : "").append(notes.optString(i));
            root.addView(infoBox(T("سرّي لك"), sb.toString()));
            space(10);
        }

        // ---- prompt
        JSONObject prompt = v.optJSONObject("prompt");
        if (prompt == null) answered.clear(); // the host has processed our last answer
        if (prompt != null && !answered.contains(prompt.optString("kind") + "#" + round)) {
            if (prompt.optString("kind").equals("pick")) renderHand(v.optJSONArray("hand"));
            else renderPrompt(prompt);
        } else if (phase.equals("pre") || phase.equals("decide") || phase.equals("post")) {
            root.addView(waitingBox(v));
        }

        boolean resultsShown = (v.optInt("round") + ":true").equals(countedKey);
        if (phase.equals("over") && resultsShown) {
            space(8);
            root.addView(winnerBox(v.optString("winner")));
        }

        if (isHost && (phase.equals("result") || phase.equals("over"))) {
            space(14);
            if (phase.equals("result")) root.addView(button(T("الجولة التالية"), HERB, x -> { answered.clear(); host.nextRound(); }));
            else newGameButtons(v);
        } else if (phase.equals("result") || phase.equals("over")) {
            space(10);
            if (phase.equals("over")) { root.addView(roomBoard(v)); space(8); }
            TextView t = text(T("بانتظار المضيف ليبدأ ") + (phase.equals("over") ? T("لعبة جديدة…") : T("الجولة التالية…")), 14, MUTED, false);
            t.setGravity(Gravity.CENTER);
            root.addView(t);
        }

        space(18);
        root.addView(playersStrip(v));
    }

    private void newGameButtons(JSONObject v) {
        root.addView(roomBoard(v));
        space(10);
        if (v.optBoolean("canDuel")) {
            root.addView(button(T("لعبة جديدة: وضع المبارزة"), ATTACK, x -> { answered.clear(); host.newGame("duel"); }));
            space(8);
            root.addView(button(T("لعبة جديدة: الوضع العادي"), HERB, x -> { answered.clear(); host.newGame("normal"); }));
        } else {
            root.addView(button(T("لعبة جديدة بنفس اللاعبين"), HERB, x -> { answered.clear(); host.newGame("normal"); }));
        }
    }

    /** Crowns won in this room, across all games since the room was created. */
    private View roomBoard(JSONObject v) {
        JSONArray ps = v.optJSONArray("players");
        StringBuilder sb = new StringBuilder();
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; ps != null && i < ps.length(); i++) list.add(ps.optJSONObject(i));
        list.sort((a, b) -> Integer.compare(b.optInt("roomCrowns"), a.optInt("roomCrowns")));
        for (JSONObject p : list)
            sb.append(sb.length() > 0 ? "\n" : "").append("• ").append(p.optString("name")).append(": ")
              .append(p.optInt("roomCrowns")).append(p.optInt("roomCrowns") == 1 ? T(" تاج") : T(" تيجان"));
        return infoBox(T("تيجان الغرفة"), sb.toString());
    }

    // ================================================================ duel mode (2 players)

    private void renderDuel(JSONObject v, boolean isHost) {
        String phase = v.optString("phase");
        header(T("المبارزة · الجولة ") + v.optInt("duelRound"));
        requestsBox(v);
        String score = T("أنت: ") + v.optInt("myCrowns") + T(" تاج   ·   ") + v.optString("opponent") + ": " + v.optInt("opCrowns") + T(" تاج");
        TextView sc = text(score, 16, GOLD_LIGHT, true);
        sc.setGravity(Gravity.CENTER);
        root.addView(sc);
        if (v.optInt("revealed") > 0) {
            TextView dw = text(T("المبارزات في هذه الجولة: أنت ") + v.optInt("myDuels") + " – " + v.optInt("opDuels"), 14, MUTED, false);
            dw.setGravity(Gravity.CENTER);
            root.addView(dw);
        }
        space(10);
        JSONObject prompt = v.optJSONObject("prompt");
        if (prompt == null) answered.clear();
        boolean canAnswer = prompt != null && !answered.contains(prompt.optString("kind") + "#" + v.optInt("duelRound") + "#" + v.optJSONArray("order").length() + "#" + v.optJSONArray("hand").length());

        if (phase.equals("draft")) {
            root.addView(infoBox(T("اختيار سري في نفس الوقت"), T("يختار كل لاعب 3 حيوانات من الستة سراً، ويمكن أن تختارا نفس الحيوان. لا أفضلية لأحد.")
                + "\n" + v.optString("opponent") + T(" اختار ") + v.optInt("opPicked") + T(" من 3.")));
            space(8);
            root.addView(text(T("في الساحة"), 18, GOLD_LIGHT, true));
            JSONArray market = v.optJSONArray("market");
            JSONArray mineH = v.optJSONArray("hand");
            for (int i = 0; market != null && i < market.length(); i++) {
                JSONObject a = market.optJSONObject(i);
                String key = a.optString("key");
                boolean taken = false;
                for (int k = 0; mineH != null && k < mineH.length(); k++) if (mineH.optJSONObject(k).optString("key").equals(key)) taken = true;
                space(6);
                boolean can = canAnswer && !taken;
                root.addView(compactAnimal(a, taken ? "✓" : (can ? T("اختر") : null), can ? x -> duelChoose(v, "draft", key) : null));
            }
            space(10);
            root.addView(text(T("حيواناتك"), 16, GOLD_LIGHT, true));
            addCompactList(v.optJSONArray("hand"));

        } else if (phase.equals("arrange")) {
            root.addView(infoBox(T("رتّب حيواناتك سراً"), T("اختر من يدخل كل مبارزة، ثم ضع الضربة القوية (+4) على مبارزة واحدة. البيئة والحدث يُسحبان عشوائياً عند كشف كل مبارزة.")));
            space(8);
            JSONArray order = v.optJSONArray("order");
            for (int i = 0; order != null && i < order.length(); i++)
                root.addView(text(T("المبارزة ") + (i + 1) + ": " + order.optJSONObject(i).optString("name"), 16, Color.WHITE, true));
            space(8);
            if (canAnswer) {
                JSONObject pr = prompt;
                String kind = pr.optString("kind");
                if (kind.equals("place")) {
                    root.addView(text(pr.optString("title"), 18, GOLD_LIGHT, true));
                    JSONArray hand = v.optJSONArray("hand");
                    for (int i = 0; hand != null && i < hand.length(); i++) {
                        JSONObject a = hand.optJSONObject(i);
                        boolean placed = false;
                        for (int k = 0; order != null && k < order.length(); k++)
                            if (order.optJSONObject(k).optString("key").equals(a.optString("key"))) placed = true;
                        if (placed) continue;
                        String key = a.optString("key");
                        space(6);
                        root.addView(compactAnimal(a, T("للمبارزة ") + (order.length() + 1), x -> duelChoose(v, "place", key)));
                    }
                } else {
                    renderDuelPrompt(v, pr);
                }
            } else {
                root.addView(waitingBox(v));
            }
        } else {
            JSONArray order = v.optJSONArray("order");
            StringBuilder plan = new StringBuilder();
            for (int i = 0; order != null && i < order.length(); i++)
                plan.append(i > 0 ? "   ·   " : "").append(i + 1).append(": ").append(order.optJSONObject(i).optString("name"))
                    .append(v.optInt("strike") == i ? " (+4)" : "");
            TextView pl = text(T("خطتك:  ") + plan, 14, MUTED, false);
            pl.setGravity(Gravity.CENTER);
            root.addView(pl);
            JSONArray ds = v.optJSONArray("duels");
            int round = v.optInt("duelRound");
            if (round != shownDuelRound) { shownDuelRound = round; shownDuels = 0; }
            int revealed = ds == null ? 0 : ds.length();
            boolean fresh = revealed > shownDuels;
            for (int i = 0; ds != null && i < ds.length(); i++) {
                space(12);
                if (fresh && i == revealed - 1) {
                    if (countingDown) { root.addView(countdownView("…")); continue; }
                    startCountdown(revealed);
                    root.addView(countdownView("3"));
                    continue;
                }
                View block = duelBlock(ds.optJSONObject(i));
                root.addView(block);
                if (i == revealed - 1 && justRevealed == revealed) {
                    flipIn(block, 0);
                    justRevealed = 0;
                    JSONArray rows = ds.optJSONObject(i).optJSONArray("rows");
                    for (int k = 0; rows != null && k < rows.length(); k++)
                        if (rows.optJSONObject(k).optBoolean("won")) playAnimal(rows.optJSONObject(k).optJSONObject("animal"), 250);
                }
            }
            space(14);
            if (countingDown || revealed > shownDuels) {
                TextView t = text(T("استعدّوا…"), 16, MUTED, true);
                t.setGravity(Gravity.CENTER);
                root.addView(t);
            } else if (phase.equals("duel")) {
                int next = v.optInt("revealed") + 1;
                if (isHost) root.addView(button(T("اكشف المبارزة ") + next, ATTACK, x -> host.revealNextDuel()));
                else {
                    TextView t = text(T("بانتظار المضيف ليكشف المبارزة ") + next + "…", 15, MUTED, false);
                    t.setGravity(Gravity.CENTER);
                    root.addView(t);
                }
            } else if (phase.equals("result")) {
                root.addView(infoBox(T("انتهت الجولة"), v.optBoolean("roundDraw") ? T("تعادل في هذه الجولة، لا تاج لأحد.") : v.optString("roundWinner") + T(" فاز بالجولة وأخذ تاجاً.")));
                space(10);
                if (isHost) root.addView(button(T("الجولة التالية"), HERB, x -> { answered.clear(); host.nextRound(); }));
            } else if (phase.equals("over")) {
                if (countingDown || justRevealed != 0) { space(18); root.addView(playersStrip(v)); return; }  // let the last duel finish its countdown
                root.addView(winnerBox(v.optString("winner")));
                space(12);
                if (isHost) newGameButtons(v);
                else root.addView(roomBoard(v));
            }
        }
        space(18);
        root.addView(playersStrip(v));
    }

    private int justRevealed = 0;
    private TextView countdownLabel;

    private View countdownView(String n) {
        TextView t = text(n, 64, GOLD_LIGHT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, dp(18));
        t.setBackground(rounded(Color.parseColor("#0B1C12"), GOLD, 14));
        countdownLabel = t;
        return t;
    }

    /** 3, 2, 1 … then the duel card flips in. */
    private void startCountdown(int revealed) {
        startCountdownThen(() -> {
            shownDuels = revealed;
            justRevealed = revealed;
            if (lastView != null) render(lastView);
        });
    }

    /** Card-flip entrance: the view turns from its edge to face the player. */
    private void flipIn(View v, long delay) {
        v.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        v.setRotationY(90f);
        v.setAlpha(0f);
        v.animate().rotationY(0f).alpha(1f).setStartDelay(delay).setDuration(450).start();
    }

    private String countedKey = "", flippedKey = "";

    /** Revealed animals and round results: 3-2-1 countdown, then the cards flip in one by one. */
    private void renderArena(JSONObject v) {
        JSONArray arena = v.optJSONArray("arena");
        if (arena == null) return;
        boolean scored = v.optBoolean("scored");
        String key = v.optInt("round") + ":" + scored;
        root.addView(text(scored ? T("نتيجة الجولة") : T("الحيوانات في الساحة"), 20, GOLD_LIGHT, true));
        space(6);
        if (!key.equals(countedKey)) {
            if (!countingDown) startCountdownThen(() -> { countedKey = key; if (lastView != null) render(lastView); });
            root.addView(countdownView(countdownLabel != null && countingDown ? String.valueOf(countdownLabel.getText()) : "3"));
            space(12);
            return;
        }
        boolean animate = !key.equals(flippedKey);
        flippedKey = key;
        for (int i = 0; i < arena.length(); i++) {
            View r = resultRow(arena.optJSONObject(i), scored);
            root.addView(r);
            if (animate) flipIn(r, 220L * i);
            if (animate && scored && arena.optJSONObject(i).optBoolean("won"))
                playAnimal(arena.optJSONObject(i).optJSONObject("animal"), 220L * i + 250);
            space(8);
        }
        space(6);
    }

    private void startCountdownThen(Runnable done) {
        countingDown = true;
        ui_handler.postDelayed(() -> { if (countdownLabel != null) countdownLabel.setText("2"); }, 800);
        ui_handler.postDelayed(() -> { if (countdownLabel != null) countdownLabel.setText("1"); }, 1600);
        ui_handler.postDelayed(() -> { countingDown = false; done.run(); }, 2400);
    }

    private void renderDuelPrompt(JSONObject v, JSONObject prompt) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(rounded(PANEL, GOLD, 14));
        box.addView(text(prompt.optString("title"), 18, INK, true));
        JSONArray opts = prompt.optJSONArray("options");
        String kind = prompt.optString("kind");
        for (int i = 0; opts != null && i < opts.length(); i++) {
            JSONObject o = opts.optJSONObject(i);
            String id = o.optString("id");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(5);
            box.addView(button(o.optString("label"), ATTACK, x -> duelChoose(v, kind, id)), lp);
        }
        root.addView(box);
    }

    private void duelChoose(JSONObject v, String kind, String value) {
        answered.add(kind + "#" + v.optInt("duelRound") + "#" + v.optJSONArray("order").length() + "#" + v.optJSONArray("hand").length());
        sendChoice(kind, value);
    }

    private void addCompactList(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            root.addView(text("—", 14, MUTED, false));
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            space(4);
            root.addView(compactAnimal(arr.optJSONObject(i), null, null));
        }
    }

    private View compactAnimal(JSONObject a, String action, View.OnClickListener onClick) {
        boolean pred = a.optBoolean("pred");
        LinearLayout box = row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.setBackground(rounded(pred ? PRED_DARK : HERB_DARK, GOLD, 12));
        box.addView(haloWrap(animalPic(a, 64), dp(64)), new LinearLayout.LayoutParams(dp(78), dp(78)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, dp(10), 0);
        mid.addView(text(a.optString("name") + "  (" + a.optInt("power") + ")", 17, Color.WHITE, true));
        mid.addView(text((pred ? T("مفترس") : T("غير مفترس")) + " · " + a.optString("abilityName"), 13, MUTED, false));
        box.addView(mid, weight());
        if (action != null) {
            TextView tag = text(action, 15, Color.WHITE, true);
            tag.setPadding(dp(10), dp(6), dp(10), dp(6));
            tag.setBackground(rounded(pred ? PRED : HERB, GOLD, 12));
            box.addView(tag, new LinearLayout.LayoutParams(-2, -2));
        }
        if (onClick != null) { picking = false; tapToChoose(box, () -> onClick.onClick(box)); }
        return box;
    }

    private View duelBlock(JSONObject d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(rounded(Color.parseColor("#0B1C12"), GOLD, 14));
        box.addView(text(T("المبارزة ") + d.optInt("n"), 19, GOLD_LIGHT, true));
        LinearLayout chips = pair(chip(T("البيئة: ") + d.optString("envName"), d.optString("envText"), HERB),
                                  chip(T("الحدث: ") + d.optString("eventName"), d.optString("eventText"), EVENT));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(6);
        box.addView(chips, cp);
        JSONArray rows = d.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            try {
                r.put("decisionText", r.optBoolean("strike") ? T("الضربة القوية") : T("بدون ضربة"));
                r.put("out", false);
            } catch (JSONException ignored) { }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(5);
            box.addView(resultRow(r, true), rp);
        }
        return box;
    }

    /** Host only: join and re-join requests with accept / reject buttons. */
    private void requestsBox(JSONObject v) {
        if (host == null) return;
        JSONArray rq = v.optJSONArray("requests");
        if (rq != null && rq.length() > 0) {
            space(12);
            root.addView(text(T("طلبات الانضمام"), 18, GOLD_LIGHT, true));
            for (int i = 0; i < rq.length(); i++) {
                JSONObject r = rq.optJSONObject(i);
                String id = r.optString("id");
                LinearLayout line = row();
                if (narrow) line.setOrientation(LinearLayout.VERTICAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(dp(10), dp(8), dp(10), dp(8));
                line.setBackground(rounded(PANEL, GOLD, 12));
                line.addView(text(r.optString("label") + (r.optBoolean("rejoin") ? T(" يريد العودة إلى اللعبة") : T(" يريد الانضمام")), 16, INK, true),
                             narrow ? new LinearLayout.LayoutParams(-1, -2) : weight());
                Button ok = button(T("قبول"), HERB, x -> { link.accept(id); host.removeRequest(id); });
                ok.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
                ok.setMinHeight(dp(44));
                line.addView(ok);
                Button no = button(T("رفض"), PRED, x -> { link.reject(id); host.removeRequest(id); });
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
                np.setMarginStart(dp(6));
                no.setLayoutParams(np);
                no.setMinHeight(dp(44));
                line.addView(no);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                root.addView(line, lp);
            }
        }
    }

    private void renderLobby(JSONObject v, boolean isHost) {
        header(isHost ? T("غرفتك جاهزة") : T("في الغرفة"));
        if (onlineMode) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(12), dp(10), dp(12), dp(10));
            box.setBackground(rounded(PANEL, GOLD, 14));
            TextView cl = text(T("رمز الغرفة"), 15, INK, true);
            cl.setGravity(Gravity.CENTER);
            box.addView(cl);
            TextView cv = text(roomCode == null ? T("جارٍ إنشاء الغرفة…") : roomCode, roomCode == null ? 18 : 44, HERB, true);
            cv.setGravity(Gravity.CENTER);
            box.addView(cv);
            root.addView(box);
            space(8);
            if (isHost) root.addView(text(T("أعطِ هذا الرمز لأصدقائك ليضغطوا «الانضمام برمز الغرفة»."), 15, MUTED, false));
        }
        if (!(onlineMode && isHost)) root.addView(text(isHost ? T("اطلب من اللاعبين الضغط على «الانضمام إلى غرفة قريبة».") : T("بانتظار أن يبدأ المضيف اللعبة…"), 15, MUTED, false));
        space(12);
        JSONArray ps = v.optJSONArray("players");
        int n = ps == null ? 0 : ps.length();
        root.addView(text(T("اللاعبون (") + n + T(" من 6)"), 18, GOLD_LIGHT, true));
        for (int i = 0; i < n; i++) {
            JSONObject p = ps.optJSONObject(i);
            TextView t = text("• " + p.optString("name") + (p.optBoolean("you") ? T(" (أنت)") : ""), 18, Color.WHITE, false);
            t.setPadding(0, dp(4), 0, dp(4));
            root.addView(t);
        }
        requestsBox(v);
        space(16);
        if (isHost) {
            if (n == 2) {
                root.addView(infoBox(T("أسلوب اللعب"), T("مع لاعبَين ننصح بوضع «المبارزة»: يختار كل منكما 3 حيوانات سراً من 6 معروضة، ثم 3 مبارزات تُسحب لكل منها بيئة وحدث عشوائيان.")));
                space(10);
                root.addView(button(T("ابدأ: وضع المبارزة (مقترح)"), ATTACK, x -> {
                    link.stopAdvertising();
                    answered.clear();
                    host.startGame("duel");
                }));
                space(8);
            }
            Button start = button(n >= 2 ? (n == 2 ? T("ابدأ: الوضع العادي") : T("ابدأ اللعب")) : T("بانتظار لاعب واحد على الأقل…"), HERB, x -> {
                link.stopAdvertising();
                answered.clear();
                host.startGame("normal");
            });
            start.setEnabled(n >= 2);
            start.setAlpha(n >= 2 ? 1f : 0.5f);
            root.addView(start);
            if (n >= 2) { space(10); root.addView(roomBoard(v)); }
        }
        space(10);
        root.addView(button(T("مغادرة"), Color.DKGRAY, x -> leave()));
    }

    private void renderPrompt(JSONObject prompt) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(rounded(PANEL, GOLD, 14));
        TextView t = text(prompt.optString("title"), 18, INK, true);
        box.addView(t);
        JSONArray opts = prompt.optJSONArray("options");
        String kind = prompt.optString("kind");
        for (int i = 0; opts != null && i < opts.length(); i++) {
            JSONObject o = opts.optJSONObject(i);
            String id = o.optString("id");
            int color = id.equals("attack") ? ATTACK : id.equals("hide") ? HIDE : HERB_DARK;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(5);
            if (id.startsWith("attack:") || id.equals("hide")) {
                box.addView(decisionButton(id, o.optString("label"), x -> choose(kind, id)), lp);
                continue;
            }
            box.addView(button(o.optString("label"), color, x -> choose(kind, id)), lp);
        }
        root.addView(box);
    }

    private void renderHand(JSONArray hand) {
        TextView t = text(T("اختر حيوانك لهذه الجولة سراً"), 20, GOLD_LIGHT, true);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        root.addView(text(T("اضغط على صورة الحيوان لاختياره. الحيوان الآخر يعود إلى الكومة."), 14, MUTED, false));
        picking = false;
        for (int i = 0; hand != null && i < hand.length(); i++) {
            JSONObject a = hand.optJSONObject(i);
            space(10);
            View card = animalCard(a, true);
            String key = a.optString("key");
            tapToChoose(card, () -> choose("pick", key));
            root.addView(card);
        }
    }

    private View waitingBox(JSONObject v) {
        JSONArray ps = v.optJSONArray("players");
        StringBuilder names = new StringBuilder();
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p.optBoolean("waiting")) names.append(names.length() > 0 ? T("، ") : "").append(p.optString("name"));
        }
        return infoBox(T("انتظر قليلاً"), names.length() > 0 ? T("بانتظار: ") + names : T("جارٍ التحضير…"));
    }

    // ================================================================ widgets

    /** The animal's round badge, or a gold-ringed name badge if no picture is bundled yet. */
    private View animalPic(JSONObject a, int sizeDp) {
        int res = getResources().getIdentifier("a_" + a.optString("key"), "drawable", getPackageName());
        if (res != 0) {
            ImageView img = new ImageView(this);
            img.setImageResource(res);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return img;
        }
        TextView t = text(a.optString("name"), Math.max(12, sizeDp / 7), GOLD_LIGHT, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(a.optBoolean("pred") ? PRED_DARK : HERB_DARK);
        g.setStroke(dp(Math.max(3, sizeDp / 20)), GOLD);
        t.setBackground(g);
        return t;
    }

    /** Horizontal card: picture on one side, name, strength and ability on the other. Fits small screens. */
    private View animalCard(JSONObject a, boolean big) {
        boolean pred = a.optBoolean("pred");
        LinearLayout card = row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(rounded(pred ? PRED_DARK : HERB_DARK, GOLD, 18));

        int size = Math.min(dp(big ? 110 : 80), (int) (screenWidthPx() * (big ? 0.30f : 0.22f)));
        int outer = Math.round(size * 1.22f);
        card.addView(haloWrap(animalPic(a, big ? 130 : 90), size), new LinearLayout.LayoutParams(outer, outer));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(4), 0);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(a.optString("name"), big ? 22 : 17, Color.WHITE, true), weight());
        TextView power = text(String.valueOf(a.optInt("power")), big ? 18 : 15, Color.parseColor("#2B1D00"), true);
        power.setGravity(Gravity.CENTER);
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(GOLD_LIGHT);
        badge.setStroke(dp(2), Color.WHITE);
        power.setBackground(badge);
        int s = dp(big ? 40 : 32);
        top.addView(power, new LinearLayout.LayoutParams(s, s));
        col.addView(top);
        TextView type = text(pred ? T("مفترس") : T("غير مفترس"), 12, Color.WHITE, true);
        type.setPadding(dp(10), dp(1), dp(10), dp(1));
        type.setBackground(rounded(pred ? PRED : HERB, GOLD, 30));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(4);
        col.addView(type, tp);
        TextView ab = text(a.optString("abilityName") + ": " + a.optString("abilityText"), big ? 13 : 12, MUTED, false);
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-1, -2);
        abp.topMargin = dp(4);
        col.addView(ab, abp);
        card.addView(col, weight());
        return card;
    }

    private View resultRow(JSONObject r, boolean scored) {
        JSONObject a = r.optJSONObject("animal");
        boolean pred = a.optBoolean("pred");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        int stroke = r.optBoolean("won") ? GOLD_LIGHT : (r.optBoolean("out") ? Color.parseColor("#C0392B") : Color.parseColor("#55705F"));
        box.setBackground(rounded(pred ? PRED_DARK : HERB_DARK, stroke, 12));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout pic = haloWrap(animalPic(a, 58), dp(58));
        top.addView(pic, new LinearLayout.LayoutParams(dp(72), dp(72)));
        if (scored && r.optBoolean("won")) shine(pic);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, dp(10), 0);
        String status = r.optBoolean("out") ? T(" · خرج") : (r.optBoolean("won") ? T(" · الأعلى") : "");
        mid.addView(text(r.optString("name") + status, 17, Color.WHITE, true));
        String dec = scored ? " · " + r.optString("decisionText") : "";
        mid.addView(text(a.optString("name") + " (" + a.optInt("power") + ")" + dec, 14, MUTED, false));
        if (r.optInt("crowns") > 0) mid.addView(text(T("التيجان: ") + r.optInt("crowns"), 13, GOLD_LIGHT, true));
        top.addView(mid, weight());

        if (scored) {
            TextView total = text(String.valueOf(r.optInt("total")), 26, GOLD_LIGHT, true);
            top.addView(total);
        }
        box.addView(top);

        JSONArray parts = r.optJSONArray("parts");
        if (scored && parts != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                int val = p.optInt("value");
                // Isolate the number so Arabic text does not flip "-3" into "3-".
                String sign = "\u2066" + (val > 0 ? "+" + val : val < 0 ? "−" + (-val) : "0") + "\u2069";
                sb.append(i > 0 ? "  ·  " : "").append(p.optString("label")).append(" ").append(sign);
                if (p.optBoolean("ignored")) sb.append(T(" (تجاهله الاختباء)"));
            }
            TextView br = text(sb.toString(), 12, MUTED, false);
            br.setPadding(0, dp(6), 0, 0);
            box.addView(br);
        }
        return box;
    }

    private View playersStrip(JSONObject v) {
        JSONArray ps = v.optJSONArray("players");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            sb.append(i > 0 ? "   " : "").append(p.optBoolean("alive") ? "● " : "○ ").append(p.optString("name"));
            if (p.optBoolean("offline")) sb.append(T(" (انقطع)"));
            if (p.optInt("roomCrowns") > 0) sb.append(T(" · تيجان الغرفة ")).append(p.optInt("roomCrowns"));
        }
        TextView t = text(sb.toString(), 13, MUTED, false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void header(String title) {
        LinearLayout h = row();
        h.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        h.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView t = text(title, 22, GOLD_LIGHT, true);
        t.setPadding(dp(10), 0, dp(10), 0);
        h.addView(t, weight());
        Button help = button(T("؟"), HIDE, x -> showRules());
        help.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20 * fontUi);
        help.setMinHeight(dp(44));
        help.setMinWidth(dp(44));
        help.setPadding(0, 0, 0, 0);
        h.addView(help, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp2.setMarginStart(dp(6));
        h.addView(langButton(), lp2);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp3.setMarginStart(dp(6));
        h.addView(soundButton(), lp3);
        root.addView(h);
        space(10);
    }

    private View chip(String title, String body, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(8), dp(10), dp(8));
        c.setBackground(rounded(color, GOLD, 12));
        c.addView(text(title, 15, Color.WHITE, true));
        c.addView(text(body, 12, MUTED, false));
        return c;
    }

    private View infoBox(String title, String body) { return infoBox(title, 15, body); }

    private View infoBox(String title, int bodySp, String body) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(8), dp(10), dp(8));
        c.setBackground(rounded(PANEL, GOLD, 14));
        c.addView(text(title, bodySp + 2, HERB, true));
        c.addView(text(body, bodySp, INK, false));
        return c;
    }

    private String winnerShownFor = "";

    /** "King of the Jungle": a small crown over the winner's name, which pops in and then keeps breathing. */
    private View winnerBox(String name) {
        LinearLayout win = new LinearLayout(this);
        win.setOrientation(LinearLayout.VERTICAL);
        win.setGravity(Gravity.CENTER);
        win.setPadding(dp(12), dp(10), dp(12), dp(12));
        win.setBackground(rounded(HERB_DARK, GOLD, 16));
        TextView title = text(T("ملك الغابة"), 18, GOLD, true);
        title.setGravity(Gravity.CENTER);
        win.addView(title);
        ImageView crown = new ImageView(this);
        crown.setImageResource(R.drawable.ic_crown);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(42), dp(42));
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        cp.topMargin = dp(4);
        win.addView(crown, cp);
        TextView w = text(name, 30, GOLD_LIGHT, true);
        w.setGravity(Gravity.CENTER);
        win.addView(w);
        boolean first = !name.equals(winnerShownFor);
        winnerShownFor = name;
        if (first) {            // entrance: drop the crown on, pop the name in
            crown.setTranslationY(-dp(40)); crown.setAlpha(0f);
            crown.animate().translationY(0).alpha(1f).setStartDelay(350).setDuration(450).start();
            w.setScaleX(0.3f); w.setScaleY(0.3f); w.setAlpha(0f);
            w.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(550).start();
        }
        android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(w,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f));
        pulse.setStartDelay(first ? 600 : 0);
        pulse.setDuration(800);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.start();
        android.animation.ObjectAnimator tilt = android.animation.ObjectAnimator.ofFloat(crown, "rotation", -6f, 6f);
        tilt.setStartDelay(first ? 800 : 0);
        tilt.setDuration(900);
        tilt.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        tilt.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        tilt.start();
        return win;
    }

    // ================================================================ picture effects

    private boolean picking;

    /** A picture with a golden halo behind it (hidden until it shines). */
    private FrameLayout haloWrap(View pic, int size) {
        FrameLayout f = new FrameLayout(this);
        int outer = Math.round(size * 1.22f);
        View halo = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(outer / 2f);
        g.setColors(new int[]{Color.argb(255, 255, 226, 120), Color.argb(170, 255, 190, 40), Color.argb(0, 255, 170, 0)});
        halo.setBackground(g);
        halo.setAlpha(0f);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(outer, outer);
        hp.gravity = Gravity.CENTER;
        f.addView(halo, hp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        f.addView(pic, lp);
        f.setTag(halo);
        return f;
    }

    private FrameLayout findHalo(View v) {
        if (v instanceof FrameLayout && v.getTag() instanceof View) return (FrameLayout) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                FrameLayout f = findHalo(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Tapping the card spins the picture once with a golden flash, then makes the choice. */
    private void tapToChoose(View card, Runnable choose) {
        card.setClickable(true);
        card.setOnClickListener(x -> {
            if (picking) return;
            picking = true;
            FrameLayout f = findHalo(card);
            if (f == null) { choose.run(); return; }
            View halo = (View) f.getTag(), pic = f.getChildAt(1);
            halo.setScaleX(0.8f); halo.setScaleY(0.8f);
            halo.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(350).start();
            pic.animate().rotationY(360f).scaleX(1.1f).scaleY(1.1f).setDuration(700)
               .withEndAction(choose).start();
        });
    }

    /** Endless soft golden pulse behind the winner's picture. */
    private void shine(FrameLayout f) {
        View halo = (View) f.getTag();
        halo.setAlpha(0.5f);
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofPropertyValuesHolder(halo,
            android.animation.PropertyValuesHolder.ofFloat("alpha", 0.45f, 1f),
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.92f, 1.1f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.92f, 1.1f));
        a.setDuration(750);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.start();
    }

    // ================================================================ home carousel

    private int homeGen;
    private final java.util.Random uiRnd = new java.util.Random();

    /** Four animal badges that keep turning into other random animals while the player types a name. */
    private View animalCarousel() {
        final int gen = ++homeGen;
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER);
        final ImageView[] slots = new ImageView[4];
        final java.util.List<String> shown = new java.util.ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            String k = randomAnimalKey(shown);
            shown.add(k);
            iv.setImageResource(drawableFor(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), dp(68));
            lp.setMarginStart(dp(4));
            lp.setMarginEnd(dp(4));
            r.addView(iv, lp);
            slots[i] = iv;
        }
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (gen != homeGen || !slots[0].isAttachedToWindow()) return;   // the home screen is gone
                int i = uiRnd.nextInt(slots.length);
                ImageView iv = slots[i];
                String k = randomAnimalKey(shown);
                shown.set(i, k);
                iv.animate().rotationY(90f).setDuration(220).withEndAction(() -> {
                    iv.setImageResource(drawableFor(k));
                    iv.setRotationY(-90f);
                    iv.animate().rotationY(0f).setDuration(220).start();
                }).start();
                ui_handler.postDelayed(this, 700 + uiRnd.nextInt(900));
            }
        };
        ui_handler.postDelayed(tick, 900);
        return r;
    }

    private String randomAnimalKey(java.util.List<String> avoid) {
        for (int t = 0; t < 20; t++) {
            String k = Cards.ANIMALS.get(uiRnd.nextInt(Cards.ANIMALS.size())).key;
            if (!avoid.contains(k)) return k;
        }
        return Cards.ANIMALS.get(0).key;
    }

    private int drawableFor(String key) {
        int res = getResources().getIdentifier("a_" + key, "drawable", getPackageName());
        return res == 0 ? R.drawable.logo : res;
    }

    // ================================================================ background music

    private android.media.MediaPlayer bg;
    private String bgName = "";
    private float bgVolume = 0.5f;

    /** Which loop fits the screen: the round's event when it changes the mood, otherwise its environment. */
    private String musicFor(JSONObject v) {
        String env = v.optString("envKey"), ev = v.optString("eventKey");
        if (v.optString("mode").equals("duel")) {
            JSONArray ds = v.optJSONArray("duels");
            JSONObject last = ds == null || ds.length() == 0 ? null : ds.optJSONObject(ds.length() - 1);
            if (last == null) return v.optString("phase").equals("lobby") ? "menu" : "tense";
            env = last.optString("envKey"); ev = last.optString("eventKey");
        }
        if (v.optString("phase").equals("lobby") || env.isEmpty()) return "menu";
        if (v.optString("phase").equals("over")) return "savanna";
        switch (ev) {
            case "night": return "night";
            case "fire": return "fire";
            case "clash": case "earthquake": case "hunter": case "eclipse": return "tense";
            default: return env;
        }
    }

    private void music(String name) {
        if (!soundOn()) { stopMusic(); return; }
        if (name.equals(bgName) && bg != null) return;
        int res = getResources().getIdentifier("m_" + name, "raw", getPackageName());
        if (res == 0) return;
        stopMusic();
        try {
            bg = android.media.MediaPlayer.create(this, res);
            if (bg == null) return;
            bg.setLooping(true);
            bg.setVolume(bgVolume, bgVolume);
            bg.start();
            bgName = name;
        } catch (RuntimeException e) { bg = null; bgName = ""; }
    }

    private void stopMusic() {
        if (bg != null) {
            try { bg.release(); } catch (RuntimeException ignored) { }
            bg = null;
        }
        bgName = "";
    }

    /** Lower the music while an animal calls, then bring it back. */
    private void duckMusic(long ms) {
        if (bg == null) return;
        try { bg.setVolume(0.15f, 0.15f); } catch (RuntimeException ignored) { }
        ui_handler.postDelayed(() -> {
            if (bg != null) try { bg.setVolume(bgVolume, bgVolume); } catch (RuntimeException ignored) { }
        }, ms);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bg != null) try { bg.pause(); } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bg != null && soundOn()) try { bg.start(); } catch (RuntimeException ignored) { }
    }

    // ================================================================ sound

    private android.media.MediaPlayer player;

    private boolean soundOn() { return getSharedPreferences("jc", MODE_PRIVATE).getBoolean("sound", true); }

    private Button soundButton() {
        Button b = button(soundOn() ? "🔊" : "🔇", Color.parseColor("#6B4E1E"), null);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16 * fontUi);
        b.setMinHeight(dp(44));
        b.setMinWidth(dp(44));
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(x -> {
            boolean on = !soundOn();
            getSharedPreferences("jc", MODE_PRIVATE).edit().putBoolean("sound", on).apply();
            if (!on) { stopSound(); stopMusic(); }
            else music(lastView != null && current == null ? musicFor(lastView) : "menu");
            b.setText(on ? "🔊" : "🔇");
        });
        return b;
    }

    /** The winning animal's real call (3 s), played once when its card flips in. */
    private void playAnimal(JSONObject animal, long delayMs) {
        if (animal == null || !soundOn()) return;
        int res = getResources().getIdentifier("s_" + animal.optString("key"), "raw", getPackageName());
        if (res == 0) return;
        ui_handler.postDelayed(() -> {
            if (!soundOn()) return;
            stopSound();
            try {
                player = android.media.MediaPlayer.create(this, res);
                if (player == null) return;
                player.setOnCompletionListener(mp -> { mp.release(); if (player == mp) player = null; });
                player.start();
                duckMusic(3000);
            } catch (RuntimeException ignored) { player = null; }
        }, delayMs);
    }

    private void stopSound() {
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
    }

    // ================================================================ language

    private String T(String ar) { return Lang.t(ar, lang); }

    private int dir() { return Lang.en(lang) ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL; }

    private Button langButton() {
        Button b = button(Lang.en(lang) ? "ع" : "EN", Color.parseColor("#6B4E1E"), x -> toggleLang());
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15 * fontUi);
        b.setMinHeight(dp(44));
        b.setMinWidth(dp(44));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    /** Switch Arabic ⇄ English, remember it, and tell the host so this phone's views change language too. */
    private void toggleLang() {
        lang = Lang.en(lang) ? Lang.AR : Lang.EN;
        getSharedPreferences("jc", MODE_PRIVATE).edit().putString("lang", lang).apply();
        getWindow().getDecorView().setLayoutDirection(dir());
        root.setLayoutDirection(dir());
        Runnable again = current;
        if (host != null) {
            host.setLang(HostController.LOCAL_ID, lang);
        } else if (hostEndpoint != null) {
            JSONObject m = new JSONObject();
            try {
                m.put("t", "lang");
                m.put("lang", lang);
            } catch (JSONException ignored) { }
            link.send(hostEndpoint, m.toString());
        }
        if (again != null) again.run();
        else if (lastView != null) { lastJson = null; render(lastView); }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp * fontUi);
        t.setTextColor(color);
        t.setTextDirection(Lang.en(lang) ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16 * fontUi);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(color, GOLD, 14));
        b.setPadding(dp(10), dp(9), dp(10), dp(9));
        b.setMinHeight(dp(46));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    /** Attack / hide choice drawn as a picture: claw marks for an attack, the watching bush for hiding. */
    private View decisionButton(String id, String label, View.OnClickListener l) {
        boolean attack = id.startsWith("attack:");
        LinearLayout b = row();
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(8), dp(4), dp(12), dp(4));
        b.setBackground(rounded(attack ? Color.parseColor("#9E2E22") : Color.parseColor("#2E5B3A"), GOLD, 16));
        b.setClickable(true);
        b.setOnClickListener(l);
        ImageView icon = new ImageView(this);
        icon.setImageResource(attack ? R.drawable.ic_attack : R.drawable.ic_hide);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        String prefix = T("هجوم على ");
        String main = attack ? (label.startsWith(prefix) ? label.substring(prefix.length()) : label) : T("اختباء");
        TextView name = text(main, 18, Color.WHITE, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
        np.setMarginStart(dp(12));
        b.addView(name, np);
        TextView hint = text(attack ? T("هجوم +5 / −3") : T("تجاهل الخصومات"), 13,
                             attack ? Color.parseColor("#FFD9CF") : Color.parseColor("#CFEBD3"), false);
        b.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        b.setContentDescription(label);
        return b;
    }

    /** Puts a small icon (Bluetooth / Wi-Fi) at the start of a button. */
    private Button withIcon(int res, Button b) {
        android.graphics.drawable.Drawable d = getResources().getDrawable(res, null);
        int s = dp(24);
        d.setBounds(0, 0, s, s);
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(8));
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutDirection(dir());
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(4));
        lp.setMarginEnd(dp(4));
        return lp;
    }

    private void space(int h) {
        View v = new View(this);
        root.addView(v, new LinearLayout.LayoutParams(1, Math.max(1, dp(h) * 11 / 20)));
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(2), stroke);
        return g;
    }

    private void toastDialog(String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton(T("حسناً"), null).show();
    }

    /** Sizes follow the phone's screen width: a 392dp-wide phone is the reference. */
    private void computeScale() {
        android.content.res.Configuration c = getResources().getConfiguration();
        int w = c.screenWidthDp > 0 ? c.screenWidthDp : 392;
        int h = c.screenHeightDp > 0 ? c.screenHeightDp : 800;
        ui = Math.max(0.72f, Math.min(1.3f, Math.min(w / 392f, h / 760f)));
        float fs = Math.max(0.9f, Math.min(1.15f, c.fontScale));
        fontUi = ui * fs;
        narrow = w < 360;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * ui, getResources().getDisplayMetrics()));
    }

    private int screenWidthPx() { return getResources().getDisplayMetrics().widthPixels; }

    /** Two boxes side by side, or stacked on narrow phones. */
    private LinearLayout pair(View a, View b) {
        LinearLayout l = row();
        if (narrow) {
            l.setOrientation(LinearLayout.VERTICAL);
            l.addView(a, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            l.addView(b, lp);
        } else {
            l.addView(a, weight());
            l.addView(b, weight());
        }
        return l;
    }

    // ================================================================ encyclopedia, rules, tutorial

    private JSONObject animalJson(Cards.Animal a) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", a.key);
            o.put("name", Lang.name(a, lang));
            o.put("power", a.power);
            o.put("pred", a.predator);
            o.put("abilityName", Lang.abilityName(a, lang));
            o.put("abilityText", Lang.abilityText(a, lang));
        } catch (JSONException ignored) { }
        return o;
    }

    /** Where the back button of an info screen returns to. */
    private void backFromInfo() {
        if (lastView != null && (host != null || hostEndpoint != null)) render(lastView);
        else if (searching) showSearch(T("اختر الغرفة التي تريد الانضمام إليها:"));
        else showHome();
    }

    private void showEncyclopedia() {
        current = this::showEncyclopedia;
        root.removeAllViews();
        header(T("موسوعة الغابة"));
        root.addView(text(T("26 حيواناً من الأقوى إلى الأضعف، مع قدرة كل حيوان في اللعبة ومعلومة حقيقية عنه."), 14, MUTED, false));
        space(8);
        root.addView(button(T("رجوع"), Color.DKGRAY, x -> backFromInfo()));
        for (Cards.Animal a : Cards.ANIMALS) {
            JSONObject j = animalJson(a);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(10), dp(10), dp(10), dp(10));
            box.setBackground(rounded(a.predator ? PRED_DARK : HERB_DARK, GOLD, 14));
            LinearLayout top = row();
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(animalPic(j, 76), new LinearLayout.LayoutParams(dp(76), dp(76)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(10), 0, dp(10), 0);
            mid.addView(text(Lang.name(a, lang), 20, Color.WHITE, true));
            mid.addView(text(T("القوة ") + a.power + " · " + Lang.typeName(a, lang), 14, GOLD_LIGHT, true));
            top.addView(mid, weight());
            box.addView(top);
            LinearLayout ab = new LinearLayout(this);
            ab.setOrientation(LinearLayout.VERTICAL);
            ab.setPadding(dp(10), dp(6), dp(10), dp(8));
            ab.setBackground(rounded(PANEL, PANEL, 10));
            ab.addView(text(T("في اللعبة: ") + Lang.abilityName(a, lang), 15, a.predator ? PRED : HERB, true));
            ab.addView(text(Lang.abilityText(a, lang), 14, INK, false));
            ab.addView(text(T("هل تعلم؟"), 15, Color.parseColor("#6B4E1E"), true));
            ab.addView(text(Lang.fact(a.key, lang), 14, INK, false));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
            ap.topMargin = dp(5);
            box.addView(ab, ap);
            space(10);
            root.addView(box);
        }
        space(12);
        root.addView(button(T("رجوع"), Color.DKGRAY, x -> backFromInfo()));
    }

    static final String RULES =
        "الهدف\nأن تكون ملك الغابة بعد 3 جولات.\n\n"
      + "كل جولة\n"
      + "1. تُكشف بيئة وحدث.\n"
      + "2. يرى كل لاعب حيوانين ويختار واحداً سراً.\n"
      + "3. الزرافة ترى حيوان لاعب، والثعلب يبادل حيوانه.\n"
      + "4. يختار كل لاعب: هجوم على لاعب أو اختباء. وأرواح الغابة تمنح +2.\n"
      + "5. تُكشف الحيوانات: الفهد قد يغيّر قراره، والحمار الوحشي يلغي صيد مفترس.\n"
      + "6. تُحسب النقاط ويُقصى الأقل.\n\n"
      + "حساب النقاط\n"
      + "• القوة من 1 إلى 26.\n"
      + "• الصيد: +3 للمفترس عن كل فريسة أضعف منه.\n"
      + "• الفريسة: −2 إن وُجد مفترس أقوى.\n"
      + "• القطيع: +2 لغير المفترس إن كان معه غير مفترس آخر.\n"
      + "• البيئة والحدث والقدرات الخاصة.\n"
      + "• رشاقة الصغار: +3 للحيوانات من 1 إلى 8 إذا كافأتها البيئة.\n"
      + "• الهجوم: +5 إن تفوّقت على خصمك، وإلا −3.\n"
      + "• الاختباء: تتجاهل الخصومات وتخسر مكافأتي الصيد والقطيع.\n\n"
      + "الإقصاء\n"
      + "3 لاعبين: لا أحد ثم 1 · 4: 1 ثم 1 · 5: 2 ثم 1 · 6: 2 ثم 2. الجولة الثالثة نهائي.\n"
      + "حماية وحيد القرن والغزالة تعمل مرة واحدة في اللعبة.\n\n"
      + "لاعبان\n"
      + "الوضع العادي: الأعلى في كل جولة يأخذ تاجاً، وأول من يجمع تاجين يفوز.\n"
      + "وضع المبارزة: تُعرض 6 حيوانات ويختار كل لاعب 3 منها سراً في نفس الوقت (يمكن أن تختارا نفس الحيوان)، ثم ترتيب سري، وضربة قوية +4 على مبارزة واحدة، و3 مبارزات لكل منها بيئة وحدث عشوائيان. من يفوز بمبارزات أكثر (ثم بمجموع النقاط) يأخذ تاجاً، وعند التعادل التام لا تاج لأحد.\n\n"
      + "الانقطاع\n"
      + "إذا انقطع اتصالك يبقى مكانك محفوظاً، واضغط «إعادة الاتصال» للعودة إلى نفس اللعبة.";

    static final String RULES_EN =
        "Goal\nBe the King of the Jungle after 3 rounds.\n\n"
      + "Each round\n"
      + "1. An environment and an event are revealed.\n"
      + "2. Each player sees two animals and picks one secretly.\n"
      + "3. The giraffe peeks at a player's animal, and the fox swaps its animal.\n"
      + "4. Each player chooses: attack a player or hide. Jungle Spirits give +2.\n"
      + "5. Animals are revealed: the cheetah may change its decision, and the zebra cancels a predator's hunt.\n"
      + "6. Points are scored and the lowest is eliminated.\n\n"
      + "Scoring\n"
      + "• Power from 1 to 26.\n"
      + "• Hunt: +3 to a predator for each weaker prey.\n"
      + "• Prey: −2 if a stronger predator is present.\n"
      + "• Herd: +2 to a non-predator if another non-predator is with it.\n"
      + "• Environment, event and special abilities.\n"
      + "• Small & agile: +3 for animals 1 to 8 when the environment rewards them.\n"
      + "• Attack: +5 if you beat your target, otherwise −3.\n"
      + "• Hide: ignore penalties but lose the hunt and herd bonuses.\n\n"
      + "Elimination\n"
      + "3 players: none then 1 · 4: 1 then 1 · 5: 2 then 1 · 6: 2 then 2. Round 3 is the final.\n"
      + "The rhino's and gazelle's protection works once per game.\n\n"
      + "Two players\n"
      + "Normal mode: the top score each round takes a crown; first to 2 crowns wins.\n"
      + "Duel mode: 6 animals are shown and each player secretly picks 3 at the same time (you may both pick the same animal), then a secret order, a +4 Power Strike on one duel, and 3 duels each with a random environment and event. Whoever wins more duels (then more points) takes a crown; a full tie gives no crown.\n\n"
      + "Disconnection\n"
      + "If you get disconnected your place is kept. Tap “Reconnect” to return to the same game.";

    private void showRules() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle(T("قواعد صراع الغابة"))
            .setMessage(Lang.en(lang) ? RULES_EN : RULES)
            .setPositiveButton(T("حسناً"), null)
            .show();
    }

    private static final String[][] TUTORIAL = {
        {"مرحباً في صراع الغابة", "لعبة من 2 إلى 6 لاعبين، كل لاعب على جواله في نفس المكان. تستمر اللعبة 3 جولات، والفائز يُتوَّج «ملك الغابة».", "lion"},
        {"حيوانك", "في كل جولة ترى حيوانين وتختار واحداً سراً. لكل حيوان قوة من 1 إلى 26، وقدرة خاصة مكتوبة على بطاقته.", "elephant"},
        {"المفترس والفريسة", "المفترس يأخذ +3 عن كل فريسة أضعف منه في الساحة. والفريسة تخسر 2 إن وُجد مفترس أقوى منها. لكن غير المفترسين يحمون بعضهم: +2 حماية القطيع.", "cheetah"},
        {"البيئة والحدث", "لكل جولة بيئة تكافئ حيوانات معيّنة، مثل النهر للتمساح وفرس النهر. وحدث مفاجئ قد يقلب كل شيء، مثل الكسوف الذي يجعل الضعيف قوياً.", "croc"},
        {"قرارك السري", "هجوم على لاعب تختاره: +5 إن تفوّقت عليه، وإلا −3. أو اختباء: تتجاهل كل الخصومات، لكنك تخسر مكافأتي الصيد والقطيع.", "wolf"},
        {"الإقصاء وأرواح الغابة", "الأقل نقاطاً يخرج حسب عدد اللاعبين. ومن يخرج لا يجلس متفرجاً: يصبح «روح الغابة» ويمنح +2 لمن يشاء في كل جولة.", "deer"},
        {"وضع المبارزة", "للاعبَين فقط: يختار كل منكما 3 حيوانات سراً من 6 معروضة في نفس الوقت، فلا أفضلية لأحد. ثم ترتّب حيواناتك سراً وتضع ضربة قوية +4 على مبارزة واحدة. من يفوز بمبارزات أكثر يأخذ تاجاً.", "gazelle"},
        {"أنت جاهز!", "أنشئ غرفة أو انضم إلى غرفة قريبة. تجد القواعد دائماً في زر ؟، وتعرّف على كل الحيوانات في «موسوعة الغابة».", "owl"}
    };

    private static final String[][] TUTORIAL_EN = {
        {"Welcome to Jungle Clash", "A game for 2 to 6 players, each on their own phone in the same place. The game lasts 3 rounds, and the winner is crowned “King of the Jungle”.", "lion"},
        {"Your animal", "Each round you see two animals and pick one secretly. Every animal has a power from 1 to 26 and a special ability written on its card.", "elephant"},
        {"Predator and prey", "A predator gets +3 for each weaker prey in the arena. Prey lose 2 if a stronger predator is there. But non-predators protect each other: +2 herd protection.", "cheetah"},
        {"Environment and event", "Each round has an environment that rewards certain animals, like the river for the crocodile and hippo, and a surprise event that can turn everything around, like the eclipse that makes the weak strong.", "croc"},
        {"Your secret decision", "Attack a player you choose: +5 if you beat them, otherwise −3. Or hide: ignore all penalties, but lose the hunt and herd bonuses.", "wolf"},
        {"Elimination and Jungle Spirits", "The lowest scores are out, depending on the number of players. But those who are out don't just watch: they become a “Jungle Spirit” and give +2 to anyone they like each round.", "deer"},
        {"Duel mode", "Two players only: each of you secretly picks 3 of 6 shown animals at the same time, so no one has an advantage. Then you arrange your animals secretly and put a +4 Power Strike on one duel. Whoever wins more duels takes a crown.", "gazelle"},
        {"You're ready!", "Create a room or join a nearby one. The rules are always behind the ? button, and you can meet every animal in the “Jungle Encyclopedia”.", "owl"}
    };

    private void showTutorial(int i) {
        current = () -> showTutorial(i);
        root.removeAllViews();
        header(T("جولة تعليمية ") + (i + 1) + T(" من ") + TUTORIAL.length);
        String[] step = (Lang.en(lang) ? TUTORIAL_EN : TUTORIAL)[i];
        Cards.Animal a = Cards.animal(step[2]);
        if (a != null) {
            int size = Math.min(dp(170), (int) (screenWidthPx() * 0.45f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(animalPic(animalJson(a), 170), lp);
            space(12);
        }
        root.addView(infoBox(step[0], step[1]));
        space(16);
        if (i < TUTORIAL.length - 1) {
            root.addView(button(T("التالي"), HERB, x -> showTutorial(i + 1)));
            space(8);
        } else {
            root.addView(button(T("لنبدأ اللعب"), ATTACK, x -> backFromInfo()));
            space(8);
        }
        if (i > 0) {
            root.addView(button(T("السابق"), Color.DKGRAY, x -> showTutorial(i - 1)));
            space(8);
        }
        if (i < TUTORIAL.length - 1) root.addView(button(T("تخطّي"), Color.DKGRAY, x -> backFromInfo()));
    }
}
