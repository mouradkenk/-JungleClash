package com.jungleclash.game.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All card definitions of «صراع الغابة». Pure Java, no Android dependencies. */
public final class Cards {
    private Cards() {}

    public static final class Animal {
        public final String key, name, abilityName, abilityText;
        public final int power;
        public final boolean predator;

        Animal(String key, String name, int power, boolean predator, String abilityName, String abilityText) {
            this.key = key; this.name = name; this.power = power; this.predator = predator;
            this.abilityName = abilityName; this.abilityText = abilityText;
        }

        public String typeName() { return predator ? "مفترس" : "غير مفترس"; }
    }

    public static final class Env {
        public final String key, name, text;
        public final Map<String, Integer> effects;

        Env(String key, String name, String text, Map<String, Integer> effects) {
            this.key = key; this.name = name; this.text = text; this.effects = effects;
        }
    }

    public static final class Event {
        public final String key, name, text;

        Event(String key, String name, String text) { this.key = key; this.name = name; this.text = text; }
    }

    public static final List<Animal> ANIMALS = Collections.unmodifiableList(Arrays.asList(
        new Animal("elephant", "الفيل", 18, false, "الدرع العملاق", "تُلغى مكافأة الصيد لكل المفترسين في هذه الجولة."),
        new Animal("lion", "الأسد", 17, true, "زئير الملك", "يأخذ +5 بدل +3 عن كل فريسة في الساحة."),
        new Animal("rhino", "وحيد القرن", 16, false, "الجلد الصلب", "مرة واحدة في اللعبة: لا يمكن إقصاؤه مهما كانت نقاطه."),
        new Animal("croc", "التمساح", 15, true, "كمين الماء", "يأخذ +5 إذا كانت بيئة الجولة هي النهر."),
        new Animal("tiger", "النمر", 14, true, "الكمين", "تُلغى حماية القطيع لكل غير المفترسين في الساحة."),
        new Animal("buffalo", "الجاموس", 13, false, "الثور الهائج", "حماية القطيع له +5 بدل +2."),
        new Animal("hippo", "فرس النهر", 12, false, "سيد الماء", "لا يخسر نقاط الفريسة، بل يأخذ +2 بدلاً منها."),
        new Animal("gorilla", "الغوريلا", 11, false, "قرع الصدر", "أضعف مفترس في الساحة يخسر مكافأة الصيد."),
        new Animal("cheetah", "الفهد", 10, true, "السرعة", "يغيّر قراره (هجوم/اختباء) بعد كشف الجميع."),
        new Animal("hyena", "الضبع", 9, true, "القمّام", "يأخذ +2 عن كل لاعب خرج في الجولة السابقة."),
        new Animal("wolf", "الذئب", 8, true, "عواء القطيع", "يأخذ +2 عن كل مفترس آخر في الساحة."),
        new Animal("giraffe", "الزرافة", 7, false, "العين العالية", "ترى حيوان لاعب واحد قبل الكشف."),
        new Animal("gazelle", "الغزالة", 6, false, "القفزة", "مرة واحدة في اللعبة: إن كانت ستُقصى تنجو، ويُقصى التالي."),
        new Animal("zebra", "الحمار الوحشي", 5, false, "الخطوط المربكة", "يختار مفترساً واحداً في الساحة يخسر مكافأة الصيد."),
        new Animal("cobra", "الكوبرا", 4, true, "اللدغة", "إن كان الفيل في الساحة يخسر 6 نقاط."),
        new Animal("fox", "الثعلب", 3, true, "المكر", "يبدّل حيوانه مع حيوان لاعب آخر قبل الكشف."),
        new Animal("baboon", "القرد", 2, false, "التسلق", "لا يخسر نقاط الفريسة أبداً."),
        new Animal("deer", "الأيل", 1, false, "النجاة", "إن نجا من الإقصاء يأخذ +5 في الجولة التالية.")
    ));

    private static Map<String, Integer> fx(Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    public static final List<Env> ENVS = Collections.unmodifiableList(Arrays.asList(
        new Env("forest", "الغابة", "+2 للنمر والفهد والغوريلا والثعلب والقرد والأيل",
            fx("tiger", 2, "cheetah", 2, "gorilla", 2, "fox", 2, "baboon", 2, "deer", 2)),
        new Env("river", "النهر", "+3 لفرس النهر والتمساح، +2 للفيل، −2 للأسد",
            fx("hippo", 3, "croc", 3, "elephant", 2, "lion", -2)),
        new Env("desert", "الصحراء", "+3 للغزالة، +2 للثعلب والكوبرا، −2 لفرس النهر",
            fx("gazelle", 3, "fox", 2, "cobra", 2, "hippo", -2)),
        new Env("savanna", "السافانا", "+2 للأسد والفيل والجاموس والزرافة والحمار الوحشي والقرد",
            fx("lion", 2, "elephant", 2, "buffalo", 2, "giraffe", 2, "zebra", 2, "baboon", 2)),
        new Env("mountains", "الجبال", "+2 للذئب ووحيد القرن، −2 للزرافة",
            fx("wolf", 2, "rhino", 2, "giraffe", -2))
    ));

    public static final List<Event> EVENTS = Collections.unmodifiableList(Arrays.asList(
        new Event("drought", "الجفاف", "يخسر كل لاعب نقطتين."),
        new Event("night", "الليل", "تتضاعف مكافأة الصيد."),
        new Event("hunter", "الصياد", "صاحب أعلى مجموع يخسر 5 نقاط."),
        new Event("migration", "الهجرة", "يبدّل كل لاعب حيوانه مع جاره قبل الكشف."),
        new Event("rain", "المطر", "يأخذ كل حيوان غير مفترس +2."),
        new Event("calm", "يوم هادئ", "لا يحدث شيء."),
        new Event("eclipse", "الكسوف", "تنقلب القوة: قوة الحيوان = 19 ناقص رقمه."),
        new Event("fire", "حريق الغابة", "تُلغى البيئة، ومن اختار الاختباء يخسر 3."),
        new Event("clash", "الصدام", "أقوى مفترس وأقوى غير مفترس يتواجهان: الأعلى +4 والآخر −4."),
        new Event("truce", "الهدنة", "لا صيد: تُلغى مكافأة الصيد وعقوبة الفريسة."),
        new Event("earthquake", "الزلزال", "بعد الحساب يتبادل صاحب أعلى مجموع وصاحب أقل مجموع نقاطهما."),
        new Event("footprints", "آثار الأقدام", "قبل القرار يُعلن نوع حيوان كل لاعب: مفترس أو غير مفترس.")
    ));

    public static Animal animal(String key) {
        for (Animal a : ANIMALS) if (a.key.equals(key)) return a;
        return null;
    }

    public static Env env(String key) {
        for (Env e : ENVS) if (e.key.equals(key)) return e;
        return null;
    }

    public static Event event(String key) {
        for (Event e : EVENTS) if (e.key.equals(key)) return e;
        return null;
    }
}
