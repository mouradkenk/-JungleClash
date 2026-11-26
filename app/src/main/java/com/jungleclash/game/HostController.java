package com.jungleclash.game;

import com.jungleclash.game.engine.Cards;
import com.jungleclash.game.engine.GameEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs on the host phone. Owns the GameEngine, collects every player's choices
 * and sends each player a private "view" of the game as JSON.
 */
public class HostController {

    public interface Sender { void send(String playerId, String json); }

    public static final String LOCAL_ID = "host";
    public static final int MAX_PLAYERS = 6;

    private final Sender sender;
    private GameEngine engine = new GameEngine(new Random());
    private String phase = "lobby"; // lobby, pre, decide, post, result, over
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Map<String, List<String>> notes = new HashMap<>();
    private final List<String> preSteps = new ArrayList<>();

    public HostController(Sender sender) { this.sender = sender; }

    public String phase() { return phase; }

    public boolean acceptsPlayers() { return phase.equals("lobby") && engine.players.size() < MAX_PLAYERS; }

    public int playerCount() { return engine.players.size(); }

    // ---------------------------------------------------------------- lobby

    public void onHello(String id, String name) {
        if (!acceptsPlayers() || engine.byId(id) != null) return;
        String clean = name == null || name.trim().isEmpty() ? "لاعب " + (engine.players.size() + 1) : name.trim();
        if (clean.length() > 20) clean = clean.substring(0, 20);
        engine.addPlayer(id, clean);
        broadcast();
    }

    public void onDisconnected(String id) {
        GameEngine.Player p = engine.byId(id);
        if (p == null) return;
        if (phase.equals("lobby")) {
            engine.players.remove(p);
        } else {
            // Play the absent player safely: hide, no vote, skip abilities.
            String kind = pending.get(id);
            if (kind != null) applyDefault(id, kind);
        }
        broadcast();
    }

    public void startGame() {
        if (!phase.equals("lobby") || engine.players.size() < 2) return;
        newRound();
    }

    public void nextRound() {
        if (phase.equals("result")) newRound();
        else if (phase.equals("over")) {
            List<GameEngine.Player> old = new ArrayList<>(engine.players);
            engine = new GameEngine(new Random());
            for (GameEngine.Player p : old) engine.addPlayer(p.id, p.name);
            newRound();
        }
    }

    // ---------------------------------------------------------------- rounds

    private void newRound() {
        engine.startRound();
        notes.clear();
        pending.clear();
        preSteps.clear();
        preSteps.add("giraffe");
        preSteps.add("fox");
        if ("migration".equals(engine.event.key)) {
            for (GameEngine.Player p : engine.alive()) note(p.id, "الهجرة: انتقل إليك حيوان جارك.");
        }
        phase = "pre";
        advancePre();
    }

    private void advancePre() {
        while (!preSteps.isEmpty()) {
            String step = preSteps.remove(0);
            GameEngine.Player h = engine.holder(step);
            if (h != null && engine.alive().size() > 1) {
                pending.put(h.id, step);
                broadcast();
                return;
            }
        }
        if ("footprints".equals(engine.event.key)) {
            StringBuilder sb = new StringBuilder("آثار الأقدام:");
            for (GameEngine.Player p : engine.alive()) sb.append("\n• ").append(p.name).append(": ").append(p.animal.typeName());
            for (GameEngine.Player p : engine.players) note(p.id, sb.toString());
        }
        phase = "decide";
        for (GameEngine.Player p : engine.alive()) pending.put(p.id, "decision");
        for (GameEngine.Player s : engine.spirits()) pending.put(s.id, "vote");
        broadcast();
    }

    private void startPost() {
        phase = "post";
        GameEngine.Player cheetah = engine.holder("cheetah");
        if (cheetah != null) pending.put(cheetah.id, "cheetah");
        GameEngine.Player zebra = engine.holder("zebra");
        if (zebra != null && !predators().isEmpty()) pending.put(zebra.id, "zebra");
        if (pending.isEmpty()) resolve();
        else broadcast();
    }

    private void resolve() {
        engine.resolveRound();
        phase = engine.isOver() ? "over" : "result";
        broadcast();
    }

    private List<GameEngine.Player> predators() {
        List<GameEngine.Player> out = new ArrayList<>();
        for (GameEngine.Player p : engine.alive()) if (p.animal.predator) out.add(p);
        return out;
    }

    // ---------------------------------------------------------------- choices

    public void onChoice(String id, String kind, String value) {
        if (!kind.equals(pending.get(id))) return;
        GameEngine.Player me = engine.byId(id);
        GameEngine.Player target = value == null ? null : engine.byId(value);
        switch (kind) {
            case "giraffe":
                if (target == null || target == me || !target.alive) return;
                note(id, "العين العالية: " + target.name + " لديه " + target.animal.name + " (" + target.animal.power + ").");
                break;
            case "fox":
                if (target == null || target == me || !target.alive) return;
                engine.foxSwap(me, target);
                note(id, "المكر: بدّلت حيوانك مع " + target.name + ". حيوانك الآن: " + me.animal.name + ".");
                note(target.id, "الثعلب أخذ حيوانك! حيوانك الآن: " + target.animal.name + ".");
                break;
            case "decision":
                if (!GameEngine.ATTACK.equals(value) && !GameEngine.HIDE.equals(value)) return;
                me.decision = value;
                break;
            case "vote":
                if (target == null || !target.alive) return;
                me.voteFor = target.id;
                break;
            case "cheetah":
                if ("switch".equals(value)) me.decision = GameEngine.ATTACK.equals(me.decision) ? GameEngine.HIDE : GameEngine.ATTACK;
                break;
            case "zebra":
                if (target == null || !target.alive || !target.animal.predator) return;
                engine.zebraTargetId = target.id;
                break;
            default:
                return;
        }
        pending.remove(id);
        afterChoice();
    }

    private void applyDefault(String id, String kind) {
        GameEngine.Player me = engine.byId(id);
        if (kind.equals("decision")) me.decision = GameEngine.HIDE;
        pending.remove(id);
        afterChoice();
    }

    private void afterChoice() {
        if (!pending.isEmpty()) { broadcast(); return; }
        switch (phase) {
            case "pre": advancePre(); break;
            case "decide": startPost(); break;
            case "post": resolve(); break;
            default: broadcast();
        }
    }

    private void note(String id, String text) {
        List<String> l = notes.get(id);
        if (l == null) { l = new ArrayList<>(); notes.put(id, l); }
        l.add(text);
    }

    // ---------------------------------------------------------------- views

    public void broadcast() {
        for (GameEngine.Player p : engine.players) {
            try {
                sender.send(p.id, viewFor(p.id).toString());
            } catch (JSONException ignored) { }
        }
    }

    private JSONObject animalJson(Cards.Animal a) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("key", a.key);
        o.put("name", a.name);
        o.put("power", a.power);
        o.put("pred", a.predator);
        o.put("abilityName", a.abilityName);
        o.put("abilityText", a.abilityText);
        return o;
    }

    private JSONObject option(String id, String label) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        return o;
    }

    public JSONObject viewFor(String id) throws JSONException {
        GameEngine.Player me = engine.byId(id);
        JSONObject v = new JSONObject();
        v.put("t", "view");
        v.put("phase", phase);
        v.put("isHost", LOCAL_ID.equals(id));
        v.put("round", engine.round);
        v.put("finalRound", engine.round > 0 && engine.isFinalRound());
        v.put("twoPlayer", engine.startCount == 2 || (phase.equals("lobby") && engine.players.size() == 2));

        JSONArray ps = new JSONArray();
        for (GameEngine.Player p : engine.players) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("alive", p.alive);
            o.put("crowns", p.crowns);
            o.put("you", p.id.equals(id));
            o.put("waiting", pending.containsKey(p.id));
            ps.put(o);
        }
        v.put("players", ps);
        if (phase.equals("lobby") || me == null) return v;

        v.put("envName", engine.env.name);
        v.put("envText", engine.env.text);
        v.put("eventName", engine.event.name);
        v.put("eventText", engine.event.text);
        v.put("alive", me.alive);
        if (me.alive && me.animal != null) v.put("animal", animalJson(me.animal));
        if (me.alive) v.put("decision", me.decision);

        JSONArray ns = new JSONArray();
        List<String> mine = notes.get(id);
        if (mine != null) for (String s : mine) ns.put(s);
        v.put("notes", ns);

        String kind = pending.get(id);
        if (kind != null) {
            JSONObject pr = new JSONObject();
            pr.put("kind", kind);
            JSONArray opts = new JSONArray();
            String title;
            switch (kind) {
                case "giraffe":
                    title = "العين العالية: اختر لاعباً لترى حيوانه";
                    for (GameEngine.Player p : engine.alive()) if (p != me) opts.put(option(p.id, p.name));
                    break;
                case "fox":
                    title = "المكر: اختر لاعباً تبادل حيوانك معه";
                    for (GameEngine.Player p : engine.alive()) if (p != me) opts.put(option(p.id, p.name));
                    break;
                case "decision":
                    title = "اختر قرارك سراً";
                    opts.put(option(GameEngine.ATTACK, "هجوم: +5 إن كنت الأعلى، وإلا −3"));
                    opts.put(option(GameEngine.HIDE, "اختباء: تتجاهل الخصومات وتخسر مكافأتي الصيد والقطيع"));
                    break;
                case "vote":
                    title = "روح الغابة: امنح +2 لأحد اللاعبين";
                    for (GameEngine.Player p : engine.alive()) opts.put(option(p.id, p.name));
                    break;
                case "cheetah":
                    String now = GameEngine.ATTACK.equals(me.decision) ? "هجوم" : "اختباء";
                    String other = GameEngine.ATTACK.equals(me.decision) ? "اختباء" : "هجوم";
                    title = "السرعة: رأيت كل الحيوانات. هل تغيّر قرارك؟";
                    opts.put(option("keep", "أبقِ القرار (" + now + ")"));
                    opts.put(option("switch", "غيّر إلى " + other));
                    break;
                case "zebra":
                    title = "الخطوط المربكة: اختر مفترساً يخسر مكافأة الصيد";
                    for (GameEngine.Player p : predators()) opts.put(option(p.id, p.name + " (" + p.animal.name + ")"));
                    break;
                default:
                    title = "";
            }
            pr.put("title", title);
            pr.put("options", opts);
            v.put("prompt", pr);
        }

        boolean revealed = phase.equals("post") || phase.equals("result") || phase.equals("over");
        if (revealed) {
            JSONArray rows = new JSONArray();
            List<GameEngine.Player> arena = new ArrayList<>();
            for (GameEngine.Player p : engine.players) if (p.alive || p.eliminatedThisRound) arena.add(p);
            boolean scored = !phase.equals("post");
            if (scored) arena.sort((a, b) -> Integer.compare(b.total, a.total));
            for (GameEngine.Player p : arena) {
                JSONObject r = new JSONObject();
                r.put("name", p.name);
                r.put("animal", animalJson(p.animal));
                if (scored) {
                    r.put("decision", p.decision);
                    r.put("total", p.total);
                    r.put("out", p.eliminatedThisRound);
                    r.put("won", p.wonRound);
                    r.put("crowns", p.crowns);
                    JSONArray parts = new JSONArray();
                    for (GameEngine.Part part : p.parts) {
                        JSONObject po = new JSONObject();
                        po.put("label", part.label);
                        po.put("value", part.value);
                        po.put("ignored", part.ignored);
                        parts.put(po);
                    }
                    r.put("parts", parts);
                }
                rows.put(r);
            }
            v.put("arena", rows);
            v.put("scored", scored);
        }
        if (engine.winner != null) v.put("winner", engine.winner.name);
        return v;
    }
}
