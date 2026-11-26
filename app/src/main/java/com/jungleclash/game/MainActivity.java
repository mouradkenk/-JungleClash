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
    private NearbyLink link;
    private HostController host;          // only on the host phone
    private String hostEndpoint;          // only on player phones
    private String myName = "";
    private Runnable afterPermissions;
    private JSONObject lastView;
    private final Map<String, String> found = new LinkedHashMap<>();
    private boolean searching;
    private final List<String> answered = new ArrayList<>(); // prompt keys already answered locally

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        link = new NearbyLink(this, this);
        SharedPreferences prefs = getSharedPreferences("jc", MODE_PRIVATE);
        myName = prefs.getString("name", "");
        showHome();
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
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
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
        space(24);
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
        link.setAcceptFilter(id -> host != null && host.acceptsPlayers());
        link.startHosting(myName);
        host.onHello(HostController.LOCAL_ID, myName);
    }

    private void startSearching() {
        searching = true;
        found.clear();
        link.setAcceptFilter(id -> true);
        link.startSearching();
        showSearch("ابحث عن غرفة قريبة…");
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
                showSearch("جارٍ الاتصال بغرفة " + e.getValue() + "…");
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
                host.onDisconnected(endpointId);
            } else if (endpointId.equals(hostEndpoint)) {
                hostEndpoint = null;
                toastDialog("انقطع الاتصال بالمضيف.");
                leave();
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
                    else if (t.equals("choice")) host.onChoice(endpointId, m.optString("kind"), m.optString("value"));
                } else if (t.equals("view")) {
                    render(m);
                }
            } catch (JSONException ignored) { }
        });
    }

    @Override
    public void onError(String message) { runOnUiThread(() -> toastDialog(message)); }

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

        // ---- header
        int round = v.optInt("round");
        String rTitle = v.optBoolean("twoPlayer") ? "الجولة " + round
                      : (v.optBoolean("finalRound") ? "الجولة " + round + " · النهائي" : "الجولة " + round);
        header(rTitle);
        LinearLayout chips = row();
        chips.addView(chip("البيئة: " + v.optString("envName"), v.optString("envText"), HERB), weight());
        chips.addView(chip("الحدث: " + v.optString("eventName"), v.optString("eventText"), EVENT), weight());
        root.addView(chips);
        space(12);

        // ---- my animal or spirit
        JSONObject animal = v.optJSONObject("animal");
        if (v.optBoolean("alive") && animal != null) {
            root.addView(animalCard(animal, true));
            String dec = v.optString("decision");
            if (!phase.equals("pre") && !phase.equals("decide")) {
                space(6);
                TextView d = text("قرارك: " + (dec.equals("attack") ? "هجوم" : "اختباء"), 15, MUTED, true);
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
            renderPrompt(prompt);
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
            for (int i = 0; i < arena.length(); i++) {
                root.addView(resultRow(arena.optJSONObject(i), scored));
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
            root.addView(button(phase.equals("over") ? "لعبة جديدة بنفس اللاعبين" : "الجولة التالية", HERB, x -> {
                answered.clear();
                host.nextRound();
            }));
        } else if (phase.equals("result") || phase.equals("over")) {
            space(10);
            TextView t = text("بانتظار المضيف ليبدأ " + (phase.equals("over") ? "لعبة جديدة…" : "الجولة التالية…"), 14, MUTED, false);
            t.setGravity(Gravity.CENTER);
            root.addView(t);
        }

        space(18);
        root.addView(playersStrip(v));
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
        space(16);
        if (isHost) {
            Button start = button(n >= 2 ? "ابدأ اللعب" : "بانتظار لاعب واحد على الأقل…", HERB, x -> {
                link.stopAdvertising();
                answered.clear();
                host.startGame();
            });
            start.setEnabled(n >= 2);
            start.setAlpha(n >= 2 ? 1f : 0.5f);
            root.addView(start);
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

        ImageView img = new ImageView(this);
        int res = getResources().getIdentifier("a_" + a.optString("key"), "drawable", getPackageName());
        if (res != 0) img.setImageResource(res);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(rounded(Color.BLACK, GOLD, 10));
        img.setClipToOutline(true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(big ? 190 : 110));
        ip.topMargin = dp(6);
        card.addView(img, ip);

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
        ImageView img = new ImageView(this);
        int res = getResources().getIdentifier("a_" + a.optString("key"), "drawable", getPackageName());
        if (res != 0) img.setImageResource(res);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(rounded(Color.BLACK, GOLD, 8));
        img.setClipToOutline(true);
        top.addView(img, new LinearLayout.LayoutParams(dp(72), dp(52)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, dp(10), 0);
        String status = r.optBoolean("out") ? " · خرج" : (r.optBoolean("won") ? " · الأعلى" : "");
        mid.addView(text(r.optString("name") + status, 17, Color.WHITE, true));
        String dec = scored ? (r.optString("decision").equals("attack") ? " · هجوم" : " · اختباء") : "";
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
                String sign = val > 0 ? "+" + val : String.valueOf(val);
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
            if (p.optInt("crowns") > 0) sb.append(" (").append(p.optInt("crowns")).append(" تاج)");
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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
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

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
