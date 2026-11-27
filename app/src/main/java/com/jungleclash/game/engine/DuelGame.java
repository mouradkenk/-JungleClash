package com.jungleclash.game.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * «المبارزة»: two-player mode.
 * Each round: 6 animals face up, snake draft 1-2-2-1, each player secretly orders
 * their 3 animals and puts the +4 strike on one, then 3 duels are revealed one by one.
 * Every duel draws its own random environment and event at the moment it is revealed.
 * Win 2 duels = one crown. First to 2 crowns wins.
 */
public class DuelGame {

    public static final int STRIKE = 4;

    public static class Side {
        public final String id, name;
        public int crowns;
        public final List<Cards.Animal> hand = new ArrayList<>();
        public final List<Cards.Animal> order = new ArrayList<>();
        public int strike = -1;   // duel index 0..2
        public int duelsWon;

        Side(String id, String name) { this.id = id; this.name = name; }

        public boolean arranged() { return order.size() == 3 && strike >= 0; }
    }

    public static class Duel {
        public int index;
        public Cards.Env env;
        public Cards.Event event;
        public Cards.Animal[] animals = new Cards.Animal[2];
        public boolean[] strike = new boolean[2];
        public List<List<GameEngine.Part>> parts = new ArrayList<>();
        public int[] total = new int[2];
        public int winner;   // 0 or 1
    }

    public final Side[] sides = new Side[2];
    public int round = 0;
    public int firstPicker = 0;
    public final List<Cards.Animal> market = new ArrayList<>();
    private final int[] draftPattern = new int[6];
    public int draftStep = 0;
    public final List<Duel> duels = new ArrayList<>();
    public Side winner;
    public Side roundWinner;
    private final Random rnd;

    public DuelGame(Random rnd, String idA, String nameA, String idB, String nameB) {
        this.rnd = rnd;
        sides[0] = new Side(idA, nameA);
        sides[1] = new Side(idB, nameB);
    }

    public int indexOf(String id) {
        if (sides[0].id.equals(id)) return 0;
        if (sides[1].id.equals(id)) return 1;
        return -1;
    }

    public void startRound() {
        round++;
        if (round > 1) firstPicker = 1 - firstPicker;
        market.clear();
        List<Cards.Animal> deck = new ArrayList<>(Cards.ANIMALS);
        Collections.shuffle(deck, rnd);
        market.addAll(deck.subList(0, 6));
        int f = firstPicker, s = 1 - firstPicker;
        int[] p = {f, s, s, f, f, s};
        System.arraycopy(p, 0, draftPattern, 0, 6);
        draftStep = 0;
        duels.clear();
        roundWinner = null;
        for (Side side : sides) {
            side.hand.clear();
            side.order.clear();
            side.strike = -1;
            side.duelsWon = 0;
        }
    }

    public boolean drafting() { return draftStep < 6; }

    /** Index of the side whose turn it is to draft, or -1 when the draft is over. */
    public int drafter() { return drafting() ? draftPattern[draftStep] : -1; }

    public boolean draft(int side, String key) {
        if (side != drafter()) return false;
        for (Cards.Animal a : market) {
            if (a.key.equals(key)) {
                market.remove(a);
                sides[side].hand.add(a);
                draftStep++;
                return true;
            }
        }
        return false;
    }

    /** Puts one of the hand's animals in the next duel slot. The last one fills itself. */
    public boolean place(int side, String key) {
        Side s = sides[side];
        if (s.order.size() >= 3) return false;
        for (Cards.Animal a : s.hand) {
            if (a.key.equals(key) && !s.order.contains(a)) {
                s.order.add(a);
                if (s.order.size() == 2) for (Cards.Animal b : s.hand) if (!s.order.contains(b)) { s.order.add(b); break; }
                return true;
            }
        }
        return false;
    }

    public boolean setStrike(int side, int duelIndex) {
        if (duelIndex < 0 || duelIndex > 2 || sides[side].order.size() < 3) return false;
        sides[side].strike = duelIndex;
        return true;
    }

    public boolean ready() { return !drafting() && sides[0].arranged() && sides[1].arranged(); }

    public boolean allRevealed() { return duels.size() == 3; }

    /** Reveals the next duel with its own random environment and event. */
    public Duel revealNext() {
        Duel d = new Duel();
        d.index = duels.size();
        d.env = Cards.ENVS.get(rnd.nextInt(Cards.ENVS.size()));
        Cards.Event e;
        do { e = Cards.EVENTS.get(rnd.nextInt(Cards.EVENTS.size())); } while (e.key.equals("footprints"));
        d.event = e;
        d.animals[0] = sides[0].order.get(d.index);
        d.animals[1] = sides[1].order.get(d.index);
        if (e.key.equals("migration")) {
            Cards.Animal t = d.animals[0];
            d.animals[0] = d.animals[1];
            d.animals[1] = t;
        }
        d.strike[0] = sides[0].strike == d.index;
        d.strike[1] = sides[1].strike == d.index;
        score(d);
        duels.add(d);
        sides[d.winner].duelsWon++;
        if (allRevealed()) {
            roundWinner = sides[0].duelsWon > sides[1].duelsWon ? sides[0] : sides[1];
            roundWinner.crowns++;
            if (roundWinner.crowns >= 2) winner = roundWinner;
        }
        return d;
    }

    private int eff(Duel d, Cards.Animal a) {
        return d.event.key.equals("eclipse") ? 27 - a.power : a.power;
    }

    private void score(Duel d) {
        String ev = d.event.key;
        boolean truce = ev.equals("truce"), fire = ev.equals("fire");
        for (int i = 0; i < 2; i++) {
            Cards.Animal me = d.animals[i], op = d.animals[1 - i];
            List<GameEngine.Part> parts = new ArrayList<>();
            parts.add(new GameEngine.Part("القوة", eff(d, me), "base"));
            boolean opPrey = !op.predator && !op.key.equals("chameleon");
            if (me.predator && opPrey && !truce && eff(d, op) < eff(d, me)) {
                int per = me.key.equals("lion") ? 5 : 3;
                if (ev.equals("night")) per *= 2;
                boolean cancelled = !me.key.equals("lynx") && (op.key.equals("elephant") || op.key.equals("gorilla") || op.key.equals("zebra"));
                parts.add(cancelled ? new GameEngine.Part("الصيد (أُلغي بقدرة " + op.name + ")", 0, "hunt")
                                    : new GameEngine.Part("الصيد", per, "hunt"));
            }
            if (!me.predator && op.predator && !truce && eff(d, op) > eff(d, me)) {
                if (me.key.equals("hippo")) parts.add(new GameEngine.Part("سيد الماء", 2, "ability"));
                else if (!me.key.equals("baboon") && !me.key.equals("hedgehog") && !me.key.equals("chameleon"))
                    parts.add(new GameEngine.Part("فريسة", -2, "prey"));
            }
            if (me.predator && op.key.equals("hedgehog") && eff(d, me) > eff(d, op)) parts.add(new GameEngine.Part("أشواك القنفذ", -3, "ability"));
            if (me.key.equals("owl") && (ev.equals("night") || ev.equals("eclipse"))) parts.add(new GameEngine.Part("عين الليل", 5, "ability"));
            if (me.key.equals("raccoon")) parts.add(new GameEngine.Part("اللص الماكر", 2, "ability"));
            if (op.key.equals("raccoon")) parts.add(new GameEngine.Part("سرقه الراكون", -2, "ability"));
            if (!fire) {
                Integer v = d.env.effects.get(me.key);
                if (v != null) parts.add(new GameEngine.Part("البيئة: " + d.env.name, v, "env"));
                if (me.key.equals("croc") && d.env.key.equals("river")) parts.add(new GameEngine.Part("كمين الماء", 5, "ability"));
                if (me.power <= 8 && v != null && v > 0) parts.add(new GameEngine.Part("رشاقة الصغار", 3, "ability"));
                if (me.key.equals("ostrich") && (d.env.key.equals("savanna") || d.env.key.equals("desert"))) parts.add(new GameEngine.Part("الركض السريع", 3, "ability"));
            }
            if (me.key.equals("elephant") && op.key.equals("cobra")) parts.add(new GameEngine.Part("لدغة الكوبرا", -6, "ability"));
            if (ev.equals("drought")) parts.add(new GameEngine.Part("الجفاف", -2, "event"));
            if (ev.equals("rain") && !me.predator) parts.add(new GameEngine.Part("المطر", 2, "event"));
            if (ev.equals("clash") && me.predator != op.predator)
                parts.add(eff(d, me) > eff(d, op) ? new GameEngine.Part("فاز في الصدام", 4, "event") : new GameEngine.Part("خسر الصدام", -4, "event"));
            if (d.strike[i]) parts.add(new GameEngine.Part("الضربة القوية", STRIKE, "decision"));
            d.parts.add(parts);
            d.total[i] = sum(parts);
        }
        if (ev.equals("hunter")) {
            int top = leader(d);
            d.parts.get(top).add(new GameEngine.Part("الصياد", -5, "event"));
            d.total[top] = sum(d.parts.get(top));
        }
        if (ev.equals("earthquake") && d.total[0] != d.total[1]) {
            int diff = d.total[0] - d.total[1];
            d.parts.get(0).add(new GameEngine.Part("الزلزال", -diff, "event"));
            d.parts.get(1).add(new GameEngine.Part("الزلزال", diff, "event"));
            d.total[0] = sum(d.parts.get(0));
            d.total[1] = sum(d.parts.get(1));
        }
        d.winner = leader(d);
    }

    private int leader(Duel d) {
        if (d.total[0] != d.total[1]) return d.total[0] > d.total[1] ? 0 : 1;
        return d.animals[0].power >= d.animals[1].power ? 0 : 1;
    }

    private static int sum(List<GameEngine.Part> parts) {
        int t = 0;
        for (GameEngine.Part p : parts) t += p.value;
        return t;
    }
}
