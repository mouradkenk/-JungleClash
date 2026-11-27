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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jungleclash.game.engine.Cards;
import com.jungleclash.game.net.NearbyLink;

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
    private NearbyLink link;
    private HostController host;          // only on the host phone
    private String hostEndpoint;          // only on player phones
    private String myName = "";
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
        computeScale();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(14), dp(16), dp(14), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        // Keep everything clear of the status bar, notch and navigation bar (edge-to-edge phones).
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                         insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        link = new NearbyLink(this, this);
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
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (host == null && hostEndpoint == null && !searching) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
            .setMessage("هل تريد مغادرة اللعبة؟")
            .setPositiveButton("مغادرة", (d, w) -> leave())
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void leave() {
        autoJoin = null;
        hostName = null;
        link.shutdown();
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
            toastDialog("اللعب بدون إنترنت يحتاج إذن البلوتوث والأجهزة القريبة والموقع. اسمح بها من إعدادات التطبيق ثم حاول مرة أخرى.");
        }
    }

    // ================================================================ home / lobby screens

    private void showHome() {
        root.removeAllViews();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(150));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(16);
        root.addView(logo, lp);
        TextView title = text("صراع الغابة", 40, GOLD_LIGHT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = text("لعبة القوة والدهاء · من 2 إلى 6 لاعبين · بدون إنترنت", 14, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
        space(24);

        EditText name = new EditText(this);
        name.setHint("اكتب اسمك");
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

        root.addView(button("إنشاء غرفة (أنا المضيف)", HERB, v -> {
            if (!saveName(name)) return;
            withPermissions(this::startHosting);
        }));
        space(10);
        root.addView(button("الانضمام إلى غرفة قريبة", ATTACK, v -> {
            if (!saveName(name)) return;
            withPermissions(this::startSearching);
        }));
        space(18);
        LinearLayout extras = pair(button("موسوعة الغابة", EVENT, v -> showEncyclopedia()),
                                   button("القواعد", HIDE, v -> showRules()));
        root.addView(extras);
        space(8);
        root.addView(button("جولة تعليمية", Color.parseColor("#6B4E1E"), v -> showTutorial(0)));
        space(18);
        root.addView(infoBox("كيف تلعب",
            "• شغّلوا البلوتوث والموقع (GPS) على كل الهواتف.\n"
          + "• لاعب واحد ينشئ الغرفة، والباقون ينضمون إليها.\n"
          + "• كل لاعب يرى حيوانه وقراره سراً على هاتفه.\n"
          + "• 3 جولات، والأعلى نقاطاً في النهائي يُتوَّج ملك الغابة."));
    }

    private boolean saveName(EditText e) {
        String n = e.getText().toString().trim();
        if (n.isEmpty()) { e.setError("اكتب اسمك أولاً"); return false; }
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
        host.onHello(HostController.LOCAL_ID, myName);
    }

    private void startSearching() {
        searching = true;
        found.clear();
        link.setGate(null);
        link.startSearching();
        showSearch("ابحث عن غرفة قريبة…");
    }

    private void showReconnect() {
        root.removeAllViews();
        header("انقطع الاتصال");
        root.addView(infoBox("لا تقلق", "مكانك في اللعبة محفوظ عند المضيف. ستُلعب اختياراتك بأمان (اختباء) حتى تعود."));
        space(14);
        root.addView(button("إعادة الاتصال بغرفة " + (hostName == null ? "المضيف" : hostName), HERB, x -> {
            withPermissions(() -> {
                searching = true;
                found.clear();
                autoJoin = hostName;
                link.setGate(null);
                link.startSearching();
                showSearch("أبحث عن غرفة " + (hostName == null ? "المضيف" : hostName) + "…");
            });
        }));
        space(10);
        root.addView(button("مغادرة اللعبة", Color.DKGRAY, x -> leave()));
    }

    private void showSearch(String status) {
        root.removeAllViews();
        header("الانضمام إلى غرفة");
        root.addView(text(status, 16, MUTED, false));
        space(12);
        if (found.isEmpty()) {
            root.addView(text("لم تظهر غرف بعد. تأكد أن المضيف أنشأ الغرفة وأن البلوتوث يعمل.", 14, MUTED, false));
        }
        for (Map.Entry<String, String> e : found.entrySet()) {
            String id = e.getKey();
            root.addView(button("غرفة " + e.getValue(), HERB, v -> {
                hostName = e.getValue();
                showSearch("أُرسل طلبك إلى غرفة " + e.getValue() + ". بانتظار موافقة المضيف…");
                link.join(myName, id);
            }));
            space(8);
        }
        space(16);
        root.addView(button("رجوع", Color.DKGRAY, v -> leave()));
    }

    // ================================================================ network callbacks

    @Override
    public void onPeerFound(String endpointId, String name) {
        runOnUiThread(() -> {
            found.put(endpointId, name);
            if (autoJoin != null && autoJoin.equals(name) && hostEndpoint == null) {
                showSearch("وجدت غرفة " + name + ". جارٍ العودة إلى اللعبة…");
                link.join(myName, endpointId);
                autoJoin = null;
                return;
            }
            if (searching && hostEndpoint == null) showSearch("اختر الغرفة التي تريد الانضمام إليها:");
        });
    }

    @Override
    public void onPeerLost(String endpointId) {
        runOnUiThread(() -> {
            found.remove(endpointId);
            if (searching && hostEndpoint == null) showSearch("ابحث عن غرفة قريبة…");
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
            } catch (JSONException ignored) { }
            link.send(endpointId, hello.toString());
            showSearch("تم الاتصال! بانتظار المضيف…");
        });
    }

    @Override
    public void onDisconnected(String endpointId) {
        runOnUiThread(() -> {
            if (host != null) {
                host.removeRequest(endpointId);
                host.onDisconnected(endpointId);
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
                    if (t.equals("hello")) host.onHello(endpointId, m.optString("name"));
                    else if (t.equals("choice")) host.onChoice(host.playerFor(endpointId), m.optString("kind"), m.optString("value"));
                } else if (t.equals("view")) {
                    render(m);
                }
            } catch (JSONException ignored) { }
        });
    }

    @Override
    public void onError(String message) { runOnUiThread(() -> toastDialog(message)); }

    @Override
    public void onConnectFailed(String endpointId, boolean rejected) {
        runOnUiThread(() -> {
            if (host != null) { host.removeRequest(endpointId); return; }
            toastDialog(rejected ? "رفض المضيف طلب الانضمام." : "تعذّر الاتصال بالغرفة. حاول مرة أخرى.");
            if (searching) showSearch("اختر الغرفة التي تريد الانضمام إليها:");
        });
    }

    /** HostController.Sender: deliver a view to a player (or to this phone). */
    @Override
    public void send(String playerId, String json) {
        if (HostController.LOCAL_ID.equals(playerId)) {
            runOnUiThread(() -> {
                try { render(new JSONObject(json)); } catch (JSONException ignored) { }
            });
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
        root.removeAllViews();
        String phase = v.optString("phase");
        boolean isHost = v.optBoolean("isHost");

        if (phase.equals("lobby")) { renderLobby(v, isHost); return; }
        if (v.optString("mode").equals("duel")) { renderDuel(v, isHost); return; }

        // ---- header
        int round = v.optInt("round");
        String rTitle = v.optBoolean("twoPlayer") ? "الجولة " + round
                      : (v.optBoolean("finalRound") ? "الجولة " + round + " · النهائي" : "الجولة " + round);
        header(rTitle);
        requestsBox(v);
        root.addView(pair(chip("البيئة: " + v.optString("envName"), v.optString("envText"), HERB),
                          chip("الحدث: " + v.optString("eventName"), v.optString("eventText"), EVENT)));
        space(12);

        // ---- my animal or spirit
        JSONObject animal = v.optJSONObject("animal");
        if (v.optBoolean("alive") && animal == null) {
            // choosing phase: the hand is shown with the prompt below
        } else if (v.optBoolean("alive")) {
            root.addView(animalCard(animal, true));
            if (!phase.equals("pre") && !phase.equals("decide")) {
                space(6);
                TextView d = text("قرارك: " + v.optString("decisionText"), 15, MUTED, true);
                d.setGravity(Gravity.CENTER);
                root.addView(d);
            }
        } else {
            root.addView(infoBox("أنت روح الغابة", "خرجت من المنافسة، لكنك تمنح +2 لأحد اللاعبين في كل جولة."));
        }
        space(10);

        // ---- private notes
        JSONArray notes = v.optJSONArray("notes");
        if (notes != null && notes.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < notes.length(); i++) sb.append(i > 0 ? "\n" : "").append(notes.optString(i));
            root.addView(infoBox("سرّي لك", sb.toString()));
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

        // ---- revealed arena / results
        JSONArray arena = v.optJSONArray("arena");
        if (arena != null) {
            space(12);
            boolean scored = v.optBoolean("scored");
            root.addView(text(scored ? "نتيجة الجولة" : "الحيوانات في الساحة", 20, GOLD_LIGHT, true));
            space(6);
            String flipKey = v.optInt("round") + ":" + scored;
            boolean animate = !flipKey.equals(lastFlipKey);
            lastFlipKey = flipKey;
            for (int i = 0; i < arena.length(); i++) {
                View row = resultRow(arena.optJSONObject(i), scored);
                root.addView(row);
                if (animate) flipIn(row, 250L * i);
                space(8);
            }
        }

        if (phase.equals("over")) {
            space(8);
            LinearLayout win = new LinearLayout(this);
            win.setOrientation(LinearLayout.VERTICAL);
            win.setGravity(Gravity.CENTER);
            win.setPadding(dp(16), dp(16), dp(16), dp(16));
            win.setBackground(rounded(HERB_DARK, GOLD, 16));
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.logo);
            win.addView(logo, new LinearLayout.LayoutParams(dp(90), dp(90)));
            TextView w = text("ملك الغابة: " + v.optString("winner"), 26, GOLD_LIGHT, true);
            w.setGravity(Gravity.CENTER);
            win.addView(w);
            root.addView(win);
        }

        if (isHost && (phase.equals("result") || phase.equals("over"))) {
            space(14);
            if (phase.equals("result")) root.addView(button("الجولة التالية", HERB, x -> { answered.clear(); host.nextRound(); }));
            else newGameButtons(v);
        } else if (phase.equals("result") || phase.equals("over")) {
            space(10);
            if (phase.equals("over")) { root.addView(roomBoard(v)); space(8); }
            TextView t = text("بانتظار المضيف ليبدأ " + (phase.equals("over") ? "لعبة جديدة…" : "الجولة التالية…"), 14, MUTED, false);
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
            root.addView(button("لعبة جديدة: وضع المبارزة", ATTACK, x -> { answered.clear(); host.newGame("duel"); }));
            space(8);
            root.addView(button("لعبة جديدة: الوضع العادي", HERB, x -> { answered.clear(); host.newGame("normal"); }));
        } else {
            root.addView(button("لعبة جديدة بنفس اللاعبين", HERB, x -> { answered.clear(); host.newGame("normal"); }));
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
              .append(p.optInt("roomCrowns")).append(p.optInt("roomCrowns") == 1 ? " تاج" : " تيجان");
        return infoBox("تيجان الغرفة", sb.toString());
    }

    // ================================================================ duel mode (2 players)

    private void renderDuel(JSONObject v, boolean isHost) {
        String phase = v.optString("phase");
        header("المبارزة · الجولة " + v.optInt("duelRound"));
        requestsBox(v);
        String score = "أنت: " + v.optInt("myCrowns") + " تاج   ·   " + v.optString("opponent") + ": " + v.optInt("opCrowns") + " تاج";
        TextView sc = text(score, 16, GOLD_LIGHT, true);
        sc.setGravity(Gravity.CENTER);
        root.addView(sc);
        if (v.optInt("revealed") > 0) {
            TextView dw = text("المبارزات في هذه الجولة: أنت " + v.optInt("myDuels") + " – " + v.optInt("opDuels"), 14, MUTED, false);
            dw.setGravity(Gravity.CENTER);
            root.addView(dw);
        }
        space(10);
        JSONObject prompt = v.optJSONObject("prompt");
        if (prompt == null) answered.clear();
        boolean canAnswer = prompt != null && !answered.contains(prompt.optString("kind") + "#" + v.optInt("duelRound") + "#" + v.optJSONArray("order").length() + "#" + v.optJSONArray("hand").length());

        if (phase.equals("draft")) {
            root.addView(infoBox("اختيار الحيوانات بالتناوب", "الدور على: " + v.optString("drafter")
                + "\nالترتيب: الأول يختار 1، ثم الثاني 2، ثم الأول 2، وتبقى الأخيرة للثاني."));
            space(8);
            root.addView(text("في الساحة", 18, GOLD_LIGHT, true));
            JSONArray market = v.optJSONArray("market");
            for (int i = 0; market != null && i < market.length(); i++) {
                JSONObject a = market.optJSONObject(i);
                String key = a.optString("key");
                space(6);
                root.addView(compactAnimal(a, canAnswer ? "اختر" : null, canAnswer ? x -> duelChoose(v, "draft", key) : null));
            }
            space(10);
            root.addView(text("حيواناتك", 16, GOLD_LIGHT, true));
            addCompactList(v.optJSONArray("hand"));
            space(6);
            root.addView(text("حيوانات " + v.optString("opponent"), 16, GOLD_LIGHT, true));
            addCompactList(v.optJSONArray("opHand"));
        } else if (phase.equals("arrange")) {
            root.addView(infoBox("رتّب حيواناتك سراً", "اختر من يدخل كل مبارزة، ثم ضع الضربة القوية (+4) على مبارزة واحدة. البيئة والحدث يُسحبان عشوائياً عند كشف كل مبارزة."));
            space(8);
            JSONArray order = v.optJSONArray("order");
            for (int i = 0; order != null && i < order.length(); i++)
                root.addView(text("المبارزة " + (i + 1) + ": " + order.optJSONObject(i).optString("name"), 16, Color.WHITE, true));
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
                        root.addView(compactAnimal(a, "للمبارزة " + (order.length() + 1), x -> duelChoose(v, "place", key)));
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
            TextView pl = text("خطتك:  " + plan, 14, MUTED, false);
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
                if (i == revealed - 1 && justRevealed == revealed) { flipIn(block, 0); justRevealed = 0; }
            }
            space(14);
            if (countingDown || revealed > shownDuels) {
                TextView t = text("استعدّوا…", 16, MUTED, true);
                t.setGravity(Gravity.CENTER);
                root.addView(t);
            } else if (phase.equals("duel")) {
                int next = v.optInt("revealed") + 1;
                if (isHost) root.addView(button("اكشف المبارزة " + next, ATTACK, x -> host.revealNextDuel()));
                else {
                    TextView t = text("بانتظار المضيف ليكشف المبارزة " + next + "…", 15, MUTED, false);
                    t.setGravity(Gravity.CENTER);
                    root.addView(t);
                }
            } else if (phase.equals("result")) {
                root.addView(infoBox("انتهت الجولة", v.optString("roundWinner") + " فاز بالجولة وأخذ تاجاً."));
                space(10);
                if (isHost) root.addView(button("الجولة التالية", HERB, x -> { answered.clear(); host.nextRound(); }));
            } else if (phase.equals("over")) {
                LinearLayout win = new LinearLayout(this);
                win.setOrientation(LinearLayout.VERTICAL);
                win.setGravity(Gravity.CENTER);
                win.setPadding(dp(16), dp(16), dp(16), dp(16));
                win.setBackground(rounded(HERB_DARK, GOLD, 16));
                ImageView logo = new ImageView(this);
                logo.setImageResource(R.drawable.logo);
                win.addView(logo, new LinearLayout.LayoutParams(dp(90), dp(90)));
                TextView w = text("ملك الغابة: " + v.optString("winner"), 26, GOLD_LIGHT, true);
                w.setGravity(Gravity.CENTER);
                win.addView(w);
                root.addView(win);
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
        countingDown = true;
        ui_handler.postDelayed(() -> { if (countdownLabel != null) countdownLabel.setText("2"); }, 800);
        ui_handler.postDelayed(() -> { if (countdownLabel != null) countdownLabel.setText("1"); }, 1600);
        ui_handler.postDelayed(() -> {
            countingDown = false;
            shownDuels = revealed;
            justRevealed = revealed;
            if (lastView != null) render(lastView);
        }, 2400);
    }

    /** Card-flip entrance: the view turns from its edge to face the player. */
    private void flipIn(View v, long delay) {
        v.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        v.setRotationY(90f);
        v.setAlpha(0f);
        v.animate().rotationY(0f).alpha(1f).setStartDelay(delay).setDuration(450).start();
    }

    private void renderDuelPrompt(JSONObject v, JSONObject prompt) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(rounded(PANEL, GOLD, 14));
        box.addView(text(prompt.optString("title"), 18, INK, true));
        JSONArray opts = prompt.optJSONArray("options");
        String kind = prompt.optString("kind");
        for (int i = 0; opts != null && i < opts.length(); i++) {
            JSONObject o = opts.optJSONObject(i);
            String id = o.optString("id");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
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
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.setBackground(rounded(pred ? PRED_DARK : HERB_DARK, GOLD, 12));
        box.addView(animalPic(a, 64), new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, dp(10), 0);
        mid.addView(text(a.optString("name") + "  (" + a.optInt("power") + ")", 17, Color.WHITE, true));
        mid.addView(text((pred ? "مفترس" : "غير مفترس") + " · " + a.optString("abilityName"), 13, MUTED, false));
        box.addView(mid, weight());
        if (action != null) {
            Button b = button(action, pred ? PRED : HERB, onClick);
            b.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            b.setMinHeight(dp(44));
            box.addView(b);
        }
        return box;
    }

    private View duelBlock(JSONObject d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.setBackground(rounded(Color.parseColor("#0B1C12"), GOLD, 14));
        box.addView(text("المبارزة " + d.optInt("n"), 19, GOLD_LIGHT, true));
        LinearLayout chips = pair(chip("البيئة: " + d.optString("envName"), d.optString("envText"), HERB),
                                  chip("الحدث: " + d.optString("eventName"), d.optString("eventText"), EVENT));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(6);
        box.addView(chips, cp);
        JSONArray rows = d.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            try {
                r.put("decisionText", r.optBoolean("strike") ? "الضربة القوية" : "بدون ضربة");
                r.put("out", false);
            } catch (JSONException ignored) { }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(8);
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
            root.addView(text("طلبات الانضمام", 18, GOLD_LIGHT, true));
            for (int i = 0; i < rq.length(); i++) {
                JSONObject r = rq.optJSONObject(i);
                String id = r.optString("id");
                LinearLayout line = row();
                if (narrow) line.setOrientation(LinearLayout.VERTICAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(dp(10), dp(8), dp(10), dp(8));
                line.setBackground(rounded(PANEL, GOLD, 12));
                line.addView(text(r.optString("label") + (r.optBoolean("rejoin") ? " يريد العودة إلى اللعبة" : " يريد الانضمام"), 16, INK, true),
                             narrow ? new LinearLayout.LayoutParams(-1, -2) : weight());
                Button ok = button("قبول", HERB, x -> { link.accept(id); host.removeRequest(id); });
                ok.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
                ok.setMinHeight(dp(44));
                line.addView(ok);
                Button no = button("رفض", PRED, x -> { link.reject(id); host.removeRequest(id); });
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
        header(isHost ? "غرفتك جاهزة" : "في الغرفة");
        root.addView(text(isHost ? "اطلب من اللاعبين الضغط على «الانضمام إلى غرفة قريبة»." : "بانتظار أن يبدأ المضيف اللعبة…", 15, MUTED, false));
        space(12);
        JSONArray ps = v.optJSONArray("players");
        int n = ps == null ? 0 : ps.length();
        root.addView(text("اللاعبون (" + n + " من 6)", 18, GOLD_LIGHT, true));
        for (int i = 0; i < n; i++) {
            JSONObject p = ps.optJSONObject(i);
            TextView t = text("• " + p.optString("name") + (p.optBoolean("you") ? " (أنت)" : ""), 18, Color.WHITE, false);
            t.setPadding(0, dp(4), 0, dp(4));
            root.addView(t);
        }
        requestsBox(v);
        space(16);
        if (isHost) {
            if (n == 2) {
                root.addView(infoBox("أسلوب اللعب", "مع لاعبَين ننصح بوضع «المبارزة»: تختاران 6 حيوانات بالتناوب، ثم 3 مبارزات تُسحب لكل منها بيئة وحدث عشوائيان."));
                space(10);
                root.addView(button("ابدأ: وضع المبارزة (مقترح)", ATTACK, x -> {
                    link.stopAdvertising();
                    answered.clear();
                    host.startGame("duel");
                }));
                space(8);
            }
            Button start = button(n >= 2 ? (n == 2 ? "ابدأ: الوضع العادي" : "ابدأ اللعب") : "بانتظار لاعب واحد على الأقل…", HERB, x -> {
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
        root.addView(button("مغادرة", Color.DKGRAY, x -> leave()));
    }

    private void renderPrompt(JSONObject prompt) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
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
            lp.topMargin = dp(8);
            box.addView(button(o.optString("label"), color, x -> choose(kind, id)), lp);
        }
        root.addView(box);
    }

    private void renderHand(JSONArray hand) {
        TextView t = text("اختر حيوانك لهذه الجولة سراً", 20, GOLD_LIGHT, true);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        root.addView(text("الحيوان الآخر يعود إلى الكومة.", 14, MUTED, false));
        for (int i = 0; hand != null && i < hand.length(); i++) {
            JSONObject a = hand.optJSONObject(i);
            space(10);
            root.addView(animalCard(a, true));
            space(6);
            String key = a.optString("key");
            root.addView(button("أختار " + a.optString("name"), a.optBoolean("pred") ? PRED : HERB, x -> choose("pick", key)));
        }
    }

    private View waitingBox(JSONObject v) {
        JSONArray ps = v.optJSONArray("players");
        StringBuilder names = new StringBuilder();
        for (int i = 0; ps != null && i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p.optBoolean("waiting")) names.append(names.length() > 0 ? "، " : "").append(p.optString("name"));
        }
        return infoBox("انتظر قليلاً", names.length() > 0 ? "بانتظار: " + names : "جارٍ التحضير…");
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

    private View animalCard(JSONObject a, boolean big) {
        boolean pred = a.optBoolean("pred");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(rounded(pred ? PRED_DARK : HERB_DARK, GOLD, 18));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(a.optString("name"), big ? 26 : 18, Color.WHITE, true);
        top.addView(name, weight());
        TextView power = text(String.valueOf(a.optInt("power")), big ? 22 : 16, Color.parseColor("#2B1D00"), true);
        power.setGravity(Gravity.CENTER);
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(GOLD_LIGHT);
        badge.setStroke(dp(2), Color.WHITE);
        power.setBackground(badge);
        int s = dp(big ? 48 : 36);
        top.addView(power, new LinearLayout.LayoutParams(s, s));
        card.addView(top);

        int size = Math.min(dp(big ? 200 : 110), (int) (screenWidthPx() * (big ? 0.55f : 0.3f)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.topMargin = dp(6);
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(animalPic(a, big ? 200 : 110), ip);

        TextView type = text(pred ? "مفترس" : "غير مفترس", 13, Color.WHITE, true);
        type.setGravity(Gravity.CENTER);
        type.setPadding(dp(12), dp(2), dp(12), dp(2));
        type.setBackground(rounded(pred ? PRED : HERB, GOLD, 30));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.CENTER_HORIZONTAL;
        tp.topMargin = dp(6);
        card.addView(type, tp);

        if (big) {
            LinearLayout ab = new LinearLayout(this);
            ab.setOrientation(LinearLayout.VERTICAL);
            ab.setPadding(dp(10), dp(6), dp(10), dp(8));
            ab.setBackground(rounded(PANEL, PANEL, 10));
            ab.addView(text(a.optString("abilityName"), 16, pred ? PRED : HERB, true));
            ab.addView(text(a.optString("abilityText"), 14, INK, false));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
            ap.topMargin = dp(6);
            card.addView(ab, ap);
        }
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
        top.addView(animalPic(a, 58), new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, dp(10), 0);
        String status = r.optBoolean("out") ? " · خرج" : (r.optBoolean("won") ? " · الأعلى" : "");
        mid.addView(text(r.optString("name") + status, 17, Color.WHITE, true));
        String dec = scored ? " · " + r.optString("decisionText") : "";
        mid.addView(text(a.optString("name") + " (" + a.optInt("power") + ")" + dec, 14, MUTED, false));
        if (r.optInt("crowns") > 0) mid.addView(text("التيجان: " + r.optInt("crowns"), 13, GOLD_LIGHT, true));
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
                if (p.optBoolean("ignored")) sb.append(" (تجاهله الاختباء)");
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
            if (p.optBoolean("offline")) sb.append(" (انقطع)");
            if (p.optInt("roomCrowns") > 0) sb.append(" · تيجان الغرفة ").append(p.optInt("roomCrowns"));
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
        Button help = button("؟", HIDE, x -> showRules());
        help.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20 * fontUi);
        help.setMinHeight(dp(44));
        help.setMinWidth(dp(44));
        help.setPadding(0, 0, 0, 0);
        h.addView(help, new LinearLayout.LayoutParams(dp(46), dp(46)));
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

    private View infoBox(String title, String body) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(rounded(PANEL, GOLD, 14));
        c.addView(text(title, 17, HERB, true));
        c.addView(text(body, 15, INK, false));
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp * fontUi);
        t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17 * fontUi);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(color, GOLD, 14));
        b.setPadding(dp(12), dp(14), dp(12), dp(14));
        b.setMinHeight(dp(52));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
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
        root.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
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
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("حسناً", null).show();
    }

    /** Sizes follow the phone's screen width: a 392dp-wide phone is the reference. */
    private void computeScale() {
        android.content.res.Configuration c = getResources().getConfiguration();
        int w = c.screenWidthDp > 0 ? c.screenWidthDp : 392;
        ui = Math.max(0.78f, Math.min(1.3f, w / 392f));
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
            o.put("name", a.name);
            o.put("power", a.power);
            o.put("pred", a.predator);
            o.put("abilityName", a.abilityName);
            o.put("abilityText", a.abilityText);
        } catch (JSONException ignored) { }
        return o;
    }

    /** Where the back button of an info screen returns to. */
    private void backFromInfo() {
        if (lastView != null && (host != null || hostEndpoint != null)) render(lastView);
        else if (searching) showSearch("اختر الغرفة التي تريد الانضمام إليها:");
        else showHome();
    }

    private void showEncyclopedia() {
        root.removeAllViews();
        header("موسوعة الغابة");
        root.addView(text("26 حيواناً من الأقوى إلى الأضعف، مع قدرة كل حيوان في اللعبة ومعلومة حقيقية عنه.", 14, MUTED, false));
        space(8);
        root.addView(button("رجوع", Color.DKGRAY, x -> backFromInfo()));
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
            mid.addView(text(a.name, 20, Color.WHITE, true));
            mid.addView(text("القوة " + a.power + " · " + a.typeName(), 14, GOLD_LIGHT, true));
            top.addView(mid, weight());
            box.addView(top);
            LinearLayout ab = new LinearLayout(this);
            ab.setOrientation(LinearLayout.VERTICAL);
            ab.setPadding(dp(10), dp(6), dp(10), dp(8));
            ab.setBackground(rounded(PANEL, PANEL, 10));
            ab.addView(text("في اللعبة: " + a.abilityName, 15, a.predator ? PRED : HERB, true));
            ab.addView(text(a.abilityText, 14, INK, false));
            ab.addView(text("هل تعلم؟", 15, Color.parseColor("#6B4E1E"), true));
            ab.addView(text(Cards.fact(a.key), 14, INK, false));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
            ap.topMargin = dp(8);
            box.addView(ab, ap);
            space(10);
            root.addView(box);
        }
        space(12);
        root.addView(button("رجوع", Color.DKGRAY, x -> backFromInfo()));
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
      + "وضع المبارزة: اختيار 6 حيوانات بالتناوب (1-2-2-1)، ترتيب سري، ضربة قوية +4 على مبارزة واحدة، و3 مبارزات لكل منها بيئة وحدث عشوائيان. من يفوز بمبارزتين يأخذ تاجاً.\n\n"
      + "الانقطاع\n"
      + "إذا انقطع اتصالك يبقى مكانك محفوظاً، واضغط «إعادة الاتصال» للعودة إلى نفس اللعبة.";

    private void showRules() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle("قواعد صراع الغابة")
            .setMessage(RULES)
            .setPositiveButton("حسناً", null)
            .show();
    }

    private static final String[][] TUTORIAL = {
        {"مرحباً في صراع الغابة", "لعبة من 2 إلى 6 لاعبين، كل لاعب على جواله في نفس المكان. تستمر اللعبة 3 جولات، والفائز يُتوَّج «ملك الغابة».", "lion"},
        {"حيوانك", "في كل جولة ترى حيوانين وتختار واحداً سراً. لكل حيوان قوة من 1 إلى 26، وقدرة خاصة مكتوبة على بطاقته.", "elephant"},
        {"المفترس والفريسة", "المفترس يأخذ +3 عن كل فريسة أضعف منه في الساحة. والفريسة تخسر 2 إن وُجد مفترس أقوى منها. لكن غير المفترسين يحمون بعضهم: +2 حماية القطيع.", "cheetah"},
        {"البيئة والحدث", "لكل جولة بيئة تكافئ حيوانات معيّنة، مثل النهر للتمساح وفرس النهر. وحدث مفاجئ قد يقلب كل شيء، مثل الكسوف الذي يجعل الضعيف قوياً.", "croc"},
        {"قرارك السري", "هجوم على لاعب تختاره: +5 إن تفوّقت عليه، وإلا −3. أو اختباء: تتجاهل كل الخصومات، لكنك تخسر مكافأتي الصيد والقطيع.", "wolf"},
        {"الإقصاء وأرواح الغابة", "الأقل نقاطاً يخرج حسب عدد اللاعبين. ومن يخرج لا يجلس متفرجاً: يصبح «روح الغابة» ويمنح +2 لمن يشاء في كل جولة.", "deer"},
        {"وضع المبارزة", "للاعبَين فقط: تختاران 6 حيوانات بالتناوب، ثم ترتّب حيواناتك سراً وتضع ضربة قوية +4 على مبارزة واحدة. من يفوز بمبارزتين يأخذ تاجاً.", "gazelle"},
        {"أنت جاهز!", "أنشئ غرفة أو انضم إلى غرفة قريبة. تجد القواعد دائماً في زر ؟، وتعرّف على كل الحيوانات في «موسوعة الغابة».", "owl"}
    };

    private void showTutorial(int i) {
        root.removeAllViews();
        header("جولة تعليمية " + (i + 1) + " من " + TUTORIAL.length);
        String[] step = TUTORIAL[i];
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
            root.addView(button("التالي", HERB, x -> showTutorial(i + 1)));
            space(8);
        } else {
            root.addView(button("لنبدأ اللعب", ATTACK, x -> backFromInfo()));
            space(8);
        }
        if (i > 0) {
            root.addView(button("السابق", Color.DKGRAY, x -> showTutorial(i - 1)));
            space(8);
        }
        if (i < TUTORIAL.length - 1) root.addView(button("تخطّي", Color.DKGRAY, x -> backFromInfo()));
    }
}
