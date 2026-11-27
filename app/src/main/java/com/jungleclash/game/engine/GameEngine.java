package com.jungleclash.game.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Authoritative rules of «صراع الغابة». Runs only on the host device.
 * Pure Java so it can be unit-tested without Android.
 */
public class GameEngine {

    public static final String ATTACK = "attack";
    public static final String HIDE = "hide";

    public static class Part {
        public final String label;
        public final int value;
        public final String cat;
        public boolean ignored;

        Part(String label, int value, String cat) { this.label = label; this.value = value; this.cat = cat; }
    }

    public static class Player {
        public final String id;
        public final String name;
        public boolean alive = true;
        public int crowns = 0;
        public Cards.Animal animal;
        public String decision = HIDE;
        public String attackTarget;     // id of the player attacked
        public List<Cards.Animal> hand = new ArrayList<>();
        public String voteFor;          // spirits only
        public boolean deerBonus;       // from previous round
        public int total;
        public List<Part> parts = new ArrayList<>();
        public boolean eliminatedThisRound;
        public boolean wonRound;

        public Player(String id, String name) { this.id = id; this.name = name; }
    }

    public final List<Player> players = new ArrayList<>();
    public int startCount;
    public int round = 0;
    public Cards.Env env;
    public Cards.Event event;
    public int eliminatedLastRound = 0;
    public String zebraTargetId;
    public final java.util.Set<String> protectionUsed = new java.util.HashSet<>();
    public Player winner;
    private final Random rnd;

    public GameEngine(Random rnd) { this.rnd = rnd; }

    public Player addPlayer(String id, String name) {
        Player p = new Player(id, name);
        players.add(p);
        return p;
    }

    public Player byId(String id) {
        for (Player p : players) if (p.id.equals(id)) return p;
        return null;
    }

    public List<Player> alive() {
        List<Player> out = new ArrayList<>();
        for (Player p : players) if (p.alive) out.add(p);
        return out;
    }

    public List<Player> spirits() {
        List<Player> out = new ArrayList<>();
        if (startCount <= 2) return out;
        for (Player p : players) if (!p.alive) out.add(p);
        return out;
    }

    public Player holder(String animalKey) {
        for (Player p : alive()) if (p.animal != null && p.animal.key.equals(animalKey)) return p;
        return null;
    }

    public boolean isFinalRound() {
        return startCount > 2 && (round >= 3 || alive().size() <= 2);
    }

    public boolean isOver() { return winner != null; }

    /** Starts a new round: draws environment and event, deals each player a hand of two animals. */
    public void startRound() {
        if (round == 0) startCount = players.size();
        round++;
        env = Cards.ENVS.get(rnd.nextInt(Cards.ENVS.size()));
        event = Cards.EVENTS.get(rnd.nextInt(Cards.EVENTS.size()));
        zebraTargetId = null;
        List<Cards.Animal> deck = new ArrayList<>(Cards.ANIMALS);
        Collections.shuffle(deck, rnd);
        List<Player> al = alive();
        for (int i = 0; i < al.size(); i++) {
            Player p = al.get(i);
            p.hand = new ArrayList<>();
            p.hand.add(deck.get(2 * i));
            p.hand.add(deck.get(2 * i + 1));
            p.animal = null;
            p.decision = HIDE;
            p.attackTarget = null;
            p.parts = new ArrayList<>();
            p.total = 0;
            p.eliminatedThisRound = false;
            p.wonRound = false;
        }
        for (Player p : players) { p.voteFor = null; p.eliminatedThisRound = false; p.wonRound = false; }
    }

    /** A player keeps one of the two animals in hand. */
    public boolean pick(Player p, String key) {
        for (Cards.Animal a : p.hand) if (a.key.equals(key)) { p.animal = a; return true; }
        return false;
    }

    /** After everyone picked: the migration event moves every animal to the next player. */
    public void afterPicks() {
        List<Player> al = alive();
        if ("migration".equals(event.key) && al.size() > 1) {
            Cards.Animal first = al.get(0).animal;
            for (int i = 0; i < al.size() - 1; i++) al.get(i).animal = al.get(i + 1).animal;
            al.get(al.size() - 1).animal = first;
        }
    }

    /** Fox ability: swap animals between the fox holder and a target. */
    public void foxSwap(Player fox, Player target) {
        Cards.Animal a = fox.animal;
        fox.animal = target.animal;
        target.animal = a;
    }

    private boolean eclipse() { return "eclipse".equals(event.key); }

    public int eff(Player p) { return eclipse() ? 27 - p.animal.power : p.animal.power; }

    private Comparator<Player> byTotalThenPowerDesc() {
        return (a, b) -> a.total != b.total ? Integer.compare(b.total, a.total) : Integer.compare(b.animal.power, a.animal.power);
    }

    /** Computes all scores for the round, then eliminations / crowns / winner. */
    public void resolveRound() {
        List<Player> arena = alive();
        String ev = event.key;
        boolean truce = ev.equals("truce");
        boolean fire = ev.equals("fire");
        boolean elephant = holder("elephant") != null;
        boolean tiger = holder("tiger") != null;
        boolean gorilla = holder("gorilla") != null;
        int predCount = 0, nonPredCount = 0;
        for (Player p : arena) { if (p.animal.predator) predCount++; else nonPredCount++; }

        Player weakestPred = null;
        if (gorilla) for (Player p : arena) if (p.animal.predator && (weakestPred == null || eff(p) < eff(weakestPred))) weakestPred = p;

        Player hedgehog = holder("hedgehog");
        // The eagle scatters the herd of the prey it hunts.
        Player eagle = holder("eagle");
        boolean eagleHunts = false;
        if (eagle != null && !truce && !huntCancelled(eagle, elephant, weakestPred))
            for (Player q : arena) if (isPrey(q) && eff(q) < eff(eagle)) eagleHunts = true;

        for (Player p : arena) {
            List<Part> parts = new ArrayList<>();
            Cards.Animal a = p.animal;
            parts.add(new Part("القوة", eff(p), "base"));
            if (p.deerBonus) parts.add(new Part("نجاة الأيل من الجولة السابقة", 5, "ability"));

            if (a.predator && !truce) {
                int prey = 0;
                for (Player q : arena) if (isPrey(q) && eff(q) < eff(p)) prey++;
                int per = a.key.equals("lion") ? 5 : 3;
                if (ev.equals("night")) per *= 2;
                boolean cancelled = huntCancelled(p, elephant, weakestPred);
                if (prey > 0) {
                    if (cancelled) parts.add(new Part("الصيد (أُلغي)", 0, "hunt"));
                    else parts.add(new Part("الصيد: " + prey + " فريسة", prey * per, "hunt"));
                }
                if (hedgehog != null && eff(p) > eff(hedgehog)) parts.add(new Part("أشواك القنفذ", -3, "ability"));
            }
            if (!a.predator && !truce) {
                boolean threatened = false;
                for (Player q : arena) if (q.animal.predator && eff(q) > eff(p)) threatened = true;
                if (threatened) {
                    if (a.key.equals("hippo")) parts.add(new Part("سيد الماء", 2, "ability"));
                    else if (isPrey(p) && !a.key.equals("baboon") && !a.key.equals("hedgehog")) parts.add(new Part("فريسة", -2, "prey"));
                }
            }
            if (!a.predator && nonPredCount >= 2) {
                if (tiger) parts.add(new Part("القطيع (ألغاه النمر)", 0, "herd"));
                else if (eagleHunts && isPrey(p) && eff(p) < eff(eagle)) parts.add(new Part("القطيع (فرّقه النسر)", 0, "herd"));
                else parts.add(new Part("حماية القطيع", a.key.equals("buffalo") ? 5 : 2, "herd"));
            }
            if (a.key.equals("wolf") && predCount > 1) parts.add(new Part("عواء القطيع", 2 * (predCount - 1), "ability"));
            if (!fire) {
                Integer d = env.effects.get(a.key);
                if (d != null) parts.add(new Part("البيئة: " + env.name, d, "env"));
                if (a.key.equals("croc") && env.key.equals("river")) parts.add(new Part("كمين الماء", 5, "ability"));
                if (a.power <= 8 && d != null && d > 0) parts.add(new Part("رشاقة الصغار", 3, "ability"));
                if (a.key.equals("ostrich") && (env.key.equals("savanna") || env.key.equals("desert"))) parts.add(new Part("الركض السريع", 3, "ability"));
            }
            if (a.key.equals("bear") && HIDE.equals(p.decision)) parts.add(new Part("السبات", 4, "ability"));
            if (a.key.equals("owl") && (ev.equals("night") || ev.equals("eclipse"))) parts.add(new Part("عين الليل", 5, "ability"));
            if (a.key.equals("hyena") && eliminatedLastRound > 0) parts.add(new Part("القمّام", 2 * eliminatedLastRound, "ability"));
            if (a.key.equals("elephant") && holder("cobra") != null) parts.add(new Part("لدغة الكوبرا", -6, "ability"));
            if (ev.equals("drought")) parts.add(new Part("الجفاف", -2, "event"));
            if (ev.equals("rain") && !a.predator) parts.add(new Part("المطر", 2, "event"));
            int votes = 0;
            for (Player s : spirits()) if (p.id.equals(s.voteFor)) votes++;
            if (votes > 0) parts.add(new Part("أرواح الغابة", 2 * votes, "votes"));
            p.parts = parts;
        }

        if (ev.equals("clash")) {
            Player sp = null, sn = null;
            for (Player p : arena) {
                if (p.animal.predator) { if (sp == null || eff(p) > eff(sp)) sp = p; }
                else if (sn == null || eff(p) > eff(sn)) sn = p;
            }
            if (sp != null && sn != null) {
                Player w = eff(sp) > eff(sn) ? sp : sn, l = w == sp ? sn : sp;
                w.parts.add(new Part("فاز في الصدام", 4, "event"));
                l.parts.add(new Part("خسر الصدام", -4, "event"));
            }
        }

        // Hiding ignores penalties and drops hunt/herd bonuses.
        for (Player p : arena) {
            if (HIDE.equals(p.decision))
                for (Part part : p.parts)
                    if (part.cat.equals("hunt") || part.cat.equals("herd") || part.value < 0) part.ignored = true;
            sum(p);
        }

        Player raccoon = holder("raccoon");
        if (raccoon != null && arena.size() > 1) {
            Player victim = null;
            for (Player q : arena) if (q != raccoon && (victim == null || q.total > victim.total)) victim = q;
            raccoon.parts.add(new Part("اللص الماكر: سرق من " + victim.name, 2, "ability"));
            Part stolen = new Part("سرقه الراكون", -2, "ability");
            if (HIDE.equals(victim.decision)) stolen.ignored = true;
            victim.parts.add(stolen);
            sum(raccoon);
            sum(victim);
        }

        if (ev.equals("hunter")) {
            Player top = Collections.min(arena, byTotalThenPowerDesc());
            if (!HIDE.equals(top.decision)) top.parts.add(new Part("الصياد", -5, "event"));
            else top.parts.add(new Part("الصياد (نجا بالاختباء)", 0, "event"));
            sum(top);
        }

        java.util.Map<String, Integer> before = new java.util.HashMap<>();
        for (Player p : arena) before.put(p.id, p.total);
        for (Player p : arena) {
            if (ATTACK.equals(p.decision)) {
                Player t = p.attackTarget == null ? null : byId(p.attackTarget);
                if (t == null || t == p || !before.containsKey(t.id)) {
                    for (Player q : arena) if (q != p) { t = q; break; }
                }
                boolean ok = t != null && before.get(p.id) > before.get(t.id);
                String who = t == null ? "" : " على " + t.name;
                p.parts.add(ok ? new Part("هجوم ناجح" + who, 5, "decision") : new Part("هجوم فاشل" + who, -3, "decision"));
            }
            if (fire && HIDE.equals(p.decision)) p.parts.add(new Part("حريق الغابة: لا مكان للاختباء", -3, "event"));
        }
        for (Player p : arena) sum(p);

        if (ev.equals("earthquake") && arena.size() >= 2) {
            List<Player> sorted = new ArrayList<>(arena);
            sorted.sort(byTotalThenPowerDesc());
            Player hi = sorted.get(0), lo = sorted.get(sorted.size() - 1);
            int diff = hi.total - lo.total;
            if (diff != 0) {
                hi.parts.add(new Part("الزلزال", -diff, "event"));
                lo.parts.add(new Part("الزلزال", diff, "event"));
                sum(hi); sum(lo);
            }
        }

        settle(arena);
    }

    /** Chameleons are never prey. */
    private boolean isPrey(Player p) { return !p.animal.predator && !p.animal.key.equals("chameleon"); }

    private boolean huntCancelled(Player p, boolean elephant, Player weakestPred) {
        if (p.animal.key.equals("lynx")) return false;
        return elephant || p == weakestPred || p.id.equals(zebraTargetId);
    }

    private void sum(Player p) {
        int t = 0;
        for (Part part : p.parts) if (!part.ignored) t += part.value;
        p.total = t;
    }

    private static final int[][] SCHEDULE = {{}, {}, {}, {0, 1}, {1, 1}, {2, 1}, {2, 2}};

    private void settle(List<Player> arena) {
        List<Player> sorted = new ArrayList<>(arena);
        sorted.sort(byTotalThenPowerDesc());
        for (Player p : players) p.deerBonus = false;

        if (startCount <= 2) {
            Player w = sorted.get(0);
            w.wonRound = true;
            w.crowns++;
            if (w.crowns >= 2 || round >= 3) {
                Player best = w;
                for (Player p : arena) if (p.crowns > best.crowns) best = p;
                winner = best;
            }
            eliminatedLastRound = 0;
            return;
        }

        if (isFinalRound()) {
            winner = sorted.get(0);
            winner.wonRound = true;
            return;
        }

        int idx = Math.min(round - 1, 1);
        int k = startCount < SCHEDULE.length ? SCHEDULE[startCount][idx] : 2;
        k = Math.min(k, arena.size() - 2);
        int out = 0;
        for (int i = sorted.size() - 1; i >= 0 && out < k; i--) {
            Player p = sorted.get(i);
            String key = p.animal.key;
            if ((key.equals("rhino") || key.equals("gazelle")) && !protectionUsed.contains(key)) {
                protectionUsed.add(key);
                p.parts.add(new Part(key.equals("rhino") ? "الجلد الصلب: نجا من الإقصاء" : "القفزة: نجت من الإقصاء", 0, "ability"));
                continue;
            }
            p.alive = false;
            p.eliminatedThisRound = true;
            out++;
        }
        eliminatedLastRound = out;
        sorted.get(0).wonRound = true;
        for (Player p : alive()) if (p.animal.key.equals("deer")) p.deerBonus = true;
        if (alive().size() == 1) winner = alive().get(0);
    }
}
