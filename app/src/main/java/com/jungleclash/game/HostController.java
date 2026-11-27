package com.jungleclash.game;

import com.jungleclash.game.engine.Cards;
import com.jungleclash.game.engine.DuelGame;
import com.jungleclash.game.engine.GameEngine;
import com.jungleclash.game.engine.Lang;

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
    private String phase = "lobby"; // lobby, choose, pre, decide, post, result, over
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Map<String, List<String>> notes = new HashMap<>();
    private final List<String> preSteps = new ArrayList<>();
    private String mode = "normal";          // normal | duel
    private DuelGame duel;
    private final Map<String, Integer> roomCrowns = new HashMap<>(); // games won in this room
    private final Map<String, String> requests = new LinkedHashMap<>(); // join requests waiting for the host
    private final java.util.Set<String> rejoinRequests = new java.util.HashSet<>();
    // Reconnect: a player keeps their id; the phone connection (endpoint) may change.
    private final Map<String, String> endpointOf = new HashMap<>(); // playerId -> endpoint
    private final Map<String, String> playerOf = new HashMap<>();   // endpoint -> playerId
    private final java.util.Set<String> offline = new java.util.HashSet<>();
    private boolean settling;

    public HostController(Sender sender) { this.sender = sender; }

    public String phase() { return phase; }

    public boolean acceptsPlayers() { return phase.equals("lobby") && engine.players.size() < MAX_PLAYERS; }

    public int playerCount() { return engine.players.size(); }

    // ---------------------------------------------------------------- lobby

    // ---------------------------------------------------------------- language

    private final Map<String, String> langs = new HashMap<>();
    /** Language of the view being built right now. */
    private String L = Lang.AR;

    private String tr(String ar) { return Lang.t(ar, L); }

    public String langOf(String id) {
        String l = langs.get(id);
        return l == null ? Lang.AR : l;
    }

    /** A player switched the app language; resend their view in that language. */
    public void setLang(String id, String lang) {
        if (id == null) return;
        langs.put(id, Lang.EN.equals(lang) ? Lang.EN : Lang.AR);
        broadcast();
    }

    // ---------------------------------------------------------------- join requests

    /** A phone asked to join. Returns false if it must be refused right away. */
    public boolean addRequest(String id, String name) {
        String clean = name == null || name.trim().isEmpty() ? "لاعب" : name.trim();
        if (!phase.equals("lobby")) {
            if (offlinePlayerNamed(clean) == null) return false;
            requests.put(id, clean);
            rejoinRequests.add(id);
            broadcast();
            return true;
        }
        if (!acceptsPlayers() || engine.players.size() + requests.size() >= MAX_PLAYERS + 2) return false;
        requests.put(id, clean);
        broadcast();
        return true;
    }

    public void removeRequest(String id) {
        rejoinRequests.remove(id);
        if (requests.remove(id) != null) broadcast();
    }

    /** True while at least one player of the running game is disconnected. */
    public boolean hasOffline() { return !offline.isEmpty() && !phase.equals("lobby"); }

    /** The player behind a phone connection (changes after a reconnect). */
    public String playerFor(String endpoint) {
        String p = playerOf.get(endpoint);
        return p == null ? endpoint : p;
    }

    private GameEngine.Player offlinePlayerNamed(String name) {
        for (GameEngine.Player p : engine.players) if (offline.contains(p.id) && p.name.equals(name)) return p;
        return null;
    }

    /** Hello with the phone's language, so its views are built in that language. */
    public void onHello(String id, String name, String lang) {
        String l = Lang.EN.equals(lang) ? Lang.EN : Lang.AR;
        langs.put(id, l);
        onHello(id, name);
        String pid = playerFor(id);
        if (pid != null && !pid.equals(id)) { langs.put(pid, l); broadcast(); }
    }

    public void onHello(String id, String name) {
        if (!phase.equals("lobby")) {
            GameEngine.Player back = offlinePlayerNamed(name == null ? "" : name.trim());
            if (back == null) return;
            String old = endpointOf.get(back.id);
            if (old != null) playerOf.remove(old);
            endpointOf.put(back.id, id);
            playerOf.put(id, back.id);
            offline.remove(back.id);
            note(back.id, Lang.t("عدت إلى اللعبة. أهلاً بعودتك!", langOf(back.id)));
            broadcast();
            return;
        }
        if (!acceptsPlayers() || engine.byId(id) != null) return;
        String clean = name == null || name.trim().isEmpty() ? "لاعب " + (engine.players.size() + 1) : name.trim();
        if (clean.length() > 20) clean = clean.substring(0, 20);
        engine.addPlayer(id, clean);
        broadcast();
    }

    public void onDisconnected(String endpoint) {
        String id = playerFor(endpoint);
        GameEngine.Player p = engine.byId(id);
        if (p == null) return;
        if (phase.equals("lobby")) {
            engine.players.remove(p);
        } else {
            // The player stays in the game and is played safely until they reconnect.
            offline.add(id);
        }
        broadcast();
    }

    private String progress(String who) {
        String d = duel == null ? "" : duel.sides[0].hand.size() + "," + duel.sides[1].hand.size() + ","
                + duel.sides[0].order.size() + "," + duel.sides[1].order.size() + "," + duel.duels.size();
        return pending.get(who) + "|" + pending.size() + "|" + phase + "|" + d;
    }

    /** Answers pending choices of disconnected players: hide, no vote, skip abilities. */
    private boolean settleOffline() {
        boolean changed = false;
        settling = true;
        try {
            for (int guard = 0; guard < 60; guard++) {
                String who = null;
                for (String pid : pending.keySet()) if (offline.contains(pid)) { who = pid; break; }
                if (who == null) break;
                String before = progress(who);
                if (mode.equals("duel")) autoDuel(who);
                else applyDefault(who, pending.get(who));
                String after = progress(who);
                if (before.equals(after) && pending.containsKey(who)) break; // nothing more can be done for now
                changed = true;
            }
        } finally {
            settling = false;
        }
        return changed;
    }

    public void startGame(String wanted) {
        if (!phase.equals("lobby") || engine.players.size() < 2) return;
        beginGame(wanted);
    }

    private void beginGame(String wanted) {
        mode = "duel".equals(wanted) && engine.players.size() == 2 ? "duel" : "normal";
        if (mode.equals("duel")) {
            GameEngine.Player a = engine.players.get(0), b = engine.players.get(1);
            duel = new DuelGame(new Random(), a.id, a.name, b.id, b.name);
            newDuelRound();
        } else {
            duel = null;
            newRound();
        }
    }

    public void nextRound() {
        if (!phase.equals("result")) return;
        if (mode.equals("duel")) newDuelRound(); else newRound();
    }

    /** After a game ends: start a new game with the same players. */
    public void newGame(String wanted) {
        if (!phase.equals("over")) return;
        List<GameEngine.Player> old = new ArrayList<>(engine.players);
        engine = new GameEngine(new Random());
        for (GameEngine.Player p : old) if (!p.id.startsWith("gone:")) engine.addPlayer(p.id, p.name);
        if (engine.players.size() < 2) { phase = "lobby"; broadcast(); return; }
        beginGame(wanted);
    }

    private void addRoomCrown(String id) {
        Integer n = roomCrowns.get(id);
        roomCrowns.put(id, n == null ? 1 : n + 1);
    }

    // ---------------------------------------------------------------- duel mode

    private void newDuelRound() {
        duel.startRound();
        notes.clear();
        pending.clear();
        phase = "draft";
        for (DuelGame.Side sd : duel.sides) pending.put(sd.id, "draft");
        broadcast();
    }

    /** Host taps to reveal the next duel. */
    public void revealNextDuel() {
        if (!phase.equals("duel") || duel.allRevealed()) return;
        duel.revealNext();
        if (duel.allRevealed()) {
            if (duel.winner != null) {
                phase = "over";
                addRoomCrown(duel.winner.id);
            } else phase = "result";
        }
        broadcast();
    }

    private void onDuelChoice(String id, String kind, String value) {
        int i = duel.indexOf(id);
        if (i < 0 || value == null) return;
        switch (kind) {
            case "draft":
                if (!duel.draft(i, value)) return;
                if (duel.draftDone(i)) pending.remove(id);
                if (!duel.drafting()) {
                    phase = "arrange";
                    for (DuelGame.Side sd : duel.sides) pending.put(sd.id, "place");
                }
                break;
            case "place":
                if (!duel.place(i, value)) return;
                if (duel.sides[i].order.size() >= 3) pending.put(id, "strike");
                break;
            case "strike":
                int idx;
                try { idx = Integer.parseInt(value); } catch (NumberFormatException e) { return; }
                if (!duel.setStrike(i, idx)) return;
                pending.remove(id);
                break;
            default:
                return;
        }
        if (phase.equals("arrange") && duel.ready()) {
            pending.clear();
            phase = "duel";
        }
        broadcast();
    }

    // ---------------------------------------------------------------- rounds

    private void newRound() {
        engine.startRound();
        notes.clear();
        pending.clear();
        preSteps.clear();
        preSteps.add("giraffe");
        preSteps.add("fox");
        phase = "choose";
        for (GameEngine.Player p : engine.alive()) pending.put(p.id, "pick");
        broadcast();
    }

    private void startPre() {
        engine.afterPicks();
        if ("migration".equals(engine.event.key)) {
            for (GameEngine.Player p : engine.alive()) note(p.id, Lang.t("الهجرة: انتقل إليك حيوان جارك، وهو الآن: ", langOf(p.id)) + Lang.name(p.animal, langOf(p.id)) + ".");
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
            for (GameEngine.Player r : engine.players) {
                String lg = langOf(r.id);
                StringBuilder sb = new StringBuilder(Lang.t("آثار الأقدام:", lg));
                for (GameEngine.Player p : engine.alive()) sb.append("\n• ").append(p.name).append(": ").append(Lang.typeName(p.animal, lg));
                note(r.id, sb.toString());
            }
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
        if (engine.isOver()) addRoomCrown(engine.winner.id);
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
        if (mode.equals("duel")) { onDuelChoice(id, kind, value); return; }
        GameEngine.Player me = engine.byId(id);
        GameEngine.Player target = value == null ? null : engine.byId(value);
        switch (kind) {
            case "pick":
                if (!engine.pick(me, value)) return;
                break;
            case "giraffe":
                if (target == null || target == me || !target.alive) return;
                note(id, Lang.t("العين العالية: ", langOf(id)) + target.name + Lang.t(" لديه ", langOf(id)) + Lang.name(target.animal, langOf(id)) + " (" + target.animal.power + ").");
                break;
            case "fox":
                if (target == null || target == me || !target.alive) return;
                engine.foxSwap(me, target);
                note(id, Lang.t("المكر: بدّلت حيوانك مع ", langOf(id)) + target.name + Lang.t(". حيوانك الآن: ", langOf(id)) + Lang.name(me.animal, langOf(id)) + ".");
                note(target.id, Lang.t("الثعلب أخذ حيوانك! حيوانك الآن: ", langOf(target.id)) + Lang.name(target.animal, langOf(target.id)) + ".");
                break;
            case "decision":
                if (!applyDecision(me, value)) return;
                break;
            case "vote":
                if (target == null || !target.alive) return;
                me.voteFor = target.id;
                break;
            case "cheetah":
                if (!"keep".equals(value) && !applyDecision(me, value)) return;
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

    /** Plays the remaining duel choices for a player who left. */
    private void autoDuel(String id) {
        int i = duel.indexOf(id);
        for (int guard = 0; guard < 12 && pending.containsKey(id); guard++) {
            String k = pending.get(id);
            String v = k.equals("draft") ? firstUnpicked(duel.sides[i])
                     : k.equals("place") ? firstUnplaced(duel.sides[i]) : "0";
            onDuelChoice(id, k, v);
        }
    }

    private String firstUnpicked(DuelGame.Side s) {
        for (Cards.Animal a : duel.market) if (!s.hand.contains(a)) return a.key;
        return "";
    }

    private String firstUnplaced(DuelGame.Side s) {
        for (Cards.Animal a : s.hand) if (!s.order.contains(a)) return a.key;
        return "";
    }

    /** value is "hide" or "attack:<playerId>". */
    private boolean applyDecision(GameEngine.Player me, String value) {
        if (GameEngine.HIDE.equals(value)) {
            me.decision = GameEngine.HIDE;
            me.attackTarget = null;
            return true;
        }
        if (value != null && value.startsWith("attack:")) {
            GameEngine.Player t = engine.byId(value.substring(7));
            if (t == null || t == me || !t.alive) return false;
            me.decision = GameEngine.ATTACK;
            me.attackTarget = t.id;
            return true;
        }
        return false;
    }

    private void applyDefault(String id, String kind) {
        GameEngine.Player me = engine.byId(id);
        if (kind.equals("decision")) me.decision = GameEngine.HIDE;
        if (kind.equals("pick") && me.animal == null && !me.hand.isEmpty()) me.animal = me.hand.get(0);
        pending.remove(id);
        afterChoice();
    }

    private void afterChoice() {
        if (!pending.isEmpty()) { broadcast(); return; }
        switch (phase) {
            case "choose": startPre(); break;
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
        if (!settling && settleOffline()) {
            broadcast(); // state moved on while settling: send the final views
            return;
        }
        if (settling) return;
        for (GameEngine.Player p : engine.players) {
            if (offline.contains(p.id)) continue;
            String to = endpointOf.containsKey(p.id) ? endpointOf.get(p.id) : p.id;
            try {
                sender.send(to, viewFor(p.id).toString());
            } catch (JSONException ignored) { }
        }
    }

    private JSONObject animalJson(Cards.Animal a) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("key", a.key);
        o.put("name", Lang.name(a, L));
        o.put("power", a.power);
        o.put("pred", a.predator);
        o.put("abilityName", Lang.abilityName(a, L));
        o.put("type", Lang.typeName(a, L));
        o.put("abilityText", Lang.abilityText(a, L));
        return o;
    }

    private JSONObject option(String id, String label) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        return o;
    }

    public JSONObject viewFor(String id) throws JSONException {
        L = langOf(id);
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
            o.put("offline", offline.contains(p.id));
            Integer rc = roomCrowns.get(p.id);
            o.put("roomCrowns", rc == null ? 0 : rc);
            if (duel != null && duel.indexOf(p.id) >= 0) o.put("crowns", duel.sides[duel.indexOf(p.id)].crowns);
            ps.put(o);
        }
        v.put("players", ps);
        v.put("mode", mode);
        if (LOCAL_ID.equals(id)) {
            JSONArray rq = new JSONArray();
            for (Map.Entry<String, String> e : requests.entrySet()) {
                JSONObject o = option(e.getKey(), e.getValue());
                o.put("rejoin", rejoinRequests.contains(e.getKey()));
                rq.put(o);
            }
            v.put("requests", rq);
        }
        v.put("canDuel", engine.players.size() == 2);
        if (phase.equals("lobby") || me == null) return v;
        if (mode.equals("duel")) return duelView(v, id);

        v.put("envName", Lang.name(engine.env, L));
        v.put("envText", Lang.text(engine.env, L));
        v.put("eventName", Lang.name(engine.event, L));
        v.put("eventText", Lang.text(engine.event, L));
        v.put("alive", me.alive);
        if (me.alive && me.animal != null) v.put("animal", animalJson(me.animal));
        if (me.alive) {
            String dec = GameEngine.HIDE.equals(me.decision) ? "hide" : "attack";
            v.put("decision", dec);
            GameEngine.Player t = me.attackTarget == null ? null : engine.byId(me.attackTarget);
            v.put("decisionText", GameEngine.HIDE.equals(me.decision) ? tr("اختباء") : tr("هجوم على ") + (t == null ? "" : t.name));
            if (phase.equals("choose")) {
                JSONArray hand = new JSONArray();
                for (Cards.Animal a : me.hand) hand.put(animalJson(a));
                v.put("hand", hand);
            }
        }

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
                case "pick":
                    title = tr("اختر حيوانك لهذه الجولة");
                    for (Cards.Animal a : me.hand) opts.put(option(a.key, tr("أختار ") + Lang.name(a, L) + " (" + a.power + ")"));
                    break;
                case "giraffe":
                    title = tr("العين العالية: اختر لاعباً لترى حيوانه");
                    for (GameEngine.Player p : engine.alive()) if (p != me) opts.put(option(p.id, p.name));
                    break;
                case "fox":
                    title = tr("المكر: اختر لاعباً تبادل حيوانك معه");
                    for (GameEngine.Player p : engine.alive()) if (p != me) opts.put(option(p.id, p.name));
                    break;
                case "decision":
                    title = tr("اختر قرارك سراً: هجوم على لاعب (+5 إن تفوّقت عليه، وإلا −3) أو اختباء");
                    for (GameEngine.Player p : engine.alive()) if (p != me) opts.put(option("attack:" + p.id, tr("هجوم على ") + p.name));
                    opts.put(option(GameEngine.HIDE, tr("اختباء: تتجاهل الخصومات وتخسر مكافأتي الصيد والقطيع")));
                    break;
                case "vote":
                    title = tr("روح الغابة: امنح +2 لأحد اللاعبين");
                    for (GameEngine.Player p : engine.alive()) opts.put(option(p.id, p.name));
                    break;
                case "cheetah":
                    title = tr("السرعة: رأيت كل الحيوانات. هل تغيّر قرارك؟");
                    opts.put(option("keep", tr("أبقِ قراري كما هو")));
                    for (GameEngine.Player p : engine.alive()) if (p != me) opts.put(option("attack:" + p.id, tr("هجوم على ") + p.name));
                    opts.put(option(GameEngine.HIDE, tr("اختباء")));
                    break;
                case "zebra":
                    title = tr("الخطوط المربكة: اختر مفترساً يخسر مكافأة الصيد");
                    for (GameEngine.Player p : predators()) opts.put(option(p.id, p.name + " (" + Lang.name(p.animal, L) + ")"));
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
                    GameEngine.Player at = p.attackTarget == null ? null : engine.byId(p.attackTarget);
                    r.put("decisionText", GameEngine.HIDE.equals(p.decision) ? tr("اختباء") : tr("هجوم على ") + (at == null ? "" : at.name));
                    r.put("total", p.total);
                    r.put("out", p.eliminatedThisRound);
                    r.put("won", p.wonRound);
                    r.put("crowns", p.crowns);
                    JSONArray parts = new JSONArray();
                    for (GameEngine.Part part : p.parts) {
                        JSONObject po = new JSONObject();
                        po.put("label", Lang.label(part.label, L));
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

    private JSONArray partsJson(List<GameEngine.Part> parts) throws JSONException {
        JSONArray arr = new JSONArray();
        for (GameEngine.Part part : parts) {
            JSONObject po = new JSONObject();
            po.put("label", Lang.label(part.label, L));
            po.put("value", part.value);
            po.put("ignored", part.ignored);
            arr.put(po);
        }
        return arr;
    }

    private JSONObject duelView(JSONObject v, String id) throws JSONException {
        int me = duel.indexOf(id), op = 1 - me;
        DuelGame.Side mine = duel.sides[me], theirs = duel.sides[op];
        v.put("duelRound", duel.round);
        v.put("opponent", theirs.name);
        v.put("myCrowns", mine.crowns);
        v.put("opCrowns", theirs.crowns);
        v.put("myDuels", mine.duelsWon);
        v.put("opDuels", theirs.duelsWon);
        JSONArray hand = new JSONArray();
        for (Cards.Animal a : mine.hand) hand.put(animalJson(a));
        v.put("hand", hand);
        JSONArray opHand = new JSONArray();   // secret until the duels are revealed
        if (!phase.equals("draft") && !phase.equals("arrange")) for (Cards.Animal a : theirs.hand) opHand.put(animalJson(a));
        v.put("opHand", opHand);
        JSONArray market = new JSONArray();
        for (Cards.Animal a : duel.market) market.put(animalJson(a));
        v.put("market", market);
        v.put("opPicked", theirs.hand.size());
        JSONArray order = new JSONArray();
        for (Cards.Animal a : mine.order) order.put(animalJson(a));
        v.put("order", order);
        v.put("strike", mine.strike);

        String kind = pending.get(id);
        if (kind != null) {
            JSONObject pr = new JSONObject();
            pr.put("kind", kind);
            JSONArray opts = new JSONArray();
            String title = "";
            if (kind.equals("draft")) {
                title = tr("اختر 3 حيوانات سراً (اخترت ") + mine.hand.size() + tr(" من 3)");
                for (Cards.Animal a : duel.market) if (!mine.hand.contains(a)) opts.put(option(a.key, Lang.name(a, L) + " (" + a.power + ")"));
            } else if (kind.equals("place")) {
                int slot = mine.order.size() + 1;
                title = tr("اختر الحيوان الذي يدخل المبارزة ") + slot;
                for (Cards.Animal a : mine.hand) if (!mine.order.contains(a)) opts.put(option(a.key, Lang.name(a, L) + " (" + a.power + ")"));
            } else if (kind.equals("strike")) {
                title = tr("ضع الضربة القوية (+") + DuelGame.STRIKE + tr(") على مبارزة واحدة");
                for (int i = 0; i < mine.order.size(); i++)
                    opts.put(option(String.valueOf(i), tr("المبارزة ") + (i + 1) + ": " + Lang.name(mine.order.get(i), L)));
            }
            pr.put("title", title);
            pr.put("options", opts);
            v.put("prompt", pr);
        }

        JSONArray ds = new JSONArray();
        for (DuelGame.Duel d : duel.duels) {
            JSONObject o = new JSONObject();
            o.put("n", d.index + 1);
            o.put("envName", Lang.name(d.env, L));
            o.put("envText", Lang.text(d.env, L));
            o.put("eventName", Lang.name(d.event, L));
            o.put("eventText", Lang.text(d.event, L));
            JSONArray rows = new JSONArray();
            for (int k : new int[]{me, op}) {
                JSONObject r = new JSONObject();
                r.put("name", duel.sides[k].name);
                r.put("animal", animalJson(d.animals[k]));
                r.put("total", d.total[k]);
                r.put("won", d.winner == k);
                r.put("strike", d.strike[k]);
                r.put("parts", partsJson(d.parts.get(k)));
                rows.put(r);
            }
            o.put("rows", rows);
            ds.put(o);
        }
        v.put("duels", ds);
        v.put("revealed", duel.duels.size());
        if (duel.roundWinner != null) v.put("roundWinner", duel.roundWinner.name);
        v.put("roundDraw", duel.allRevealed() && duel.roundWinner == null);
        if (duel.winner != null) v.put("winner", duel.winner.name);
        return v;
    }
}
