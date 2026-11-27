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
        new Animal("elephant", "الفيل", 26, false, "الدرع العملاق", "تُلغى مكافأة الصيد لكل المفترسين في هذه الجولة."),
        new Animal("lion", "الأسد", 25, true, "زئير الملك", "يأخذ +5 بدل +3 عن كل فريسة في الساحة."),
        new Animal("rhino", "وحيد القرن", 24, false, "الجلد الصلب", "مرة واحدة في اللعبة: لا يمكن إقصاؤه مهما كانت نقاطه."),
        new Animal("bear", "الدب البني", 23, true, "السبات", "إذا اختار الاختباء يأخذ +4."),
        new Animal("croc", "التمساح", 22, true, "كمين الماء", "يأخذ +5 إذا كانت بيئة الجولة هي النهر."),
        new Animal("tiger", "النمر", 21, true, "الكمين", "تُلغى حماية القطيع لكل غير المفترسين في الساحة."),
        new Animal("buffalo", "الجاموس", 20, false, "الثور الهائج", "حماية القطيع له +5 بدل +2."),
        new Animal("hippo", "فرس النهر", 19, false, "سيد الماء", "لا يخسر نقاط الفريسة، بل يأخذ +2 بدلاً منها."),
        new Animal("gorilla", "الغوريلا", 18, false, "قرع الصدر", "أضعف مفترس في الساحة يخسر مكافأة الصيد."),
        new Animal("cheetah", "الفهد", 17, true, "السرعة", "يغيّر قراره (هجوم/اختباء) بعد كشف الجميع."),
        new Animal("hyena", "الضبع", 16, true, "القمّام", "يأخذ +2 عن كل لاعب خرج في الجولة السابقة."),
        new Animal("wolf", "الذئب", 15, true, "عواء القطيع", "يأخذ +2 عن كل مفترس آخر في الساحة."),
        new Animal("lynx", "الوشق", 14, true, "الكمين الصامت", "لا تُلغى مكافأة صيده بأي قدرة."),
        new Animal("eagle", "النسر", 13, true, "الانقضاض", "الفرائس التي يصطادها تخسر حماية القطيع."),
        new Animal("giraffe", "الزرافة", 12, false, "العين العالية", "ترى حيوان لاعب واحد قبل الكشف."),
        new Animal("ostrich", "النعامة", 11, false, "الركض السريع", "+3 في السافانا والصحراء."),
        new Animal("gazelle", "الغزالة", 10, false, "القفزة", "مرة واحدة في اللعبة: إن كانت ستُقصى تنجو، ويُقصى التالي."),
        new Animal("zebra", "الحمار الوحشي", 9, false, "الخطوط المربكة", "يختار مفترساً واحداً في الساحة يخسر مكافأة الصيد."),
        new Animal("raccoon", "الراكون", 8, false, "اللص الماكر", "يسرق نقطتين من صاحب أعلى مجموع."),
        new Animal("cobra", "الكوبرا", 7, true, "اللدغة", "إن كان الفيل في الساحة يخسر 6 نقاط."),
        new Animal("owl", "البومة", 6, true, "عين الليل", "+5 في حدث الليل أو الكسوف."),
        new Animal("fox", "الثعلب", 5, true, "المكر", "يبدّل حيوانه مع حيوان لاعب آخر قبل الكشف."),
        new Animal("chameleon", "الحرباء", 4, false, "التمويه", "لا تُعتبر فريسة: لا تخسر نقاط الفريسة ولا يصطادها أحد."),
        new Animal("baboon", "القرد", 3, false, "التسلق", "لا يخسر نقاط الفريسة أبداً."),
        new Animal("hedgehog", "القنفذ", 2, false, "الأشواك", "لا يخسر نقاط الفريسة، وكل مفترس أقوى منه يخسر 3."),
        new Animal("deer", "الأيل", 1, false, "النجاة", "إن نجا من الإقصاء يأخذ +5 في الجولة التالية.")
    ));

    private static Map<String, Integer> fx(Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    public static final List<Env> ENVS = Collections.unmodifiableList(Arrays.asList(
        new Env("forest", "الغابة", "+2 للنمر والفهد والغوريلا والبومة والراكون والثعلب والقرد والأيل",
            fx("tiger", 2, "cheetah", 2, "gorilla", 2, "owl", 2, "raccoon", 2, "fox", 2, "baboon", 2, "deer", 2)),
        new Env("river", "النهر", "+3 لفرس النهر والتمساح، +2 للفيل، −2 للأسد",
            fx("hippo", 3, "croc", 3, "elephant", 2, "lion", -2)),
        new Env("desert", "الصحراء", "+3 للغزالة، +2 للنعامة والثعلب والكوبرا والحرباء، −2 لفرس النهر",
            fx("gazelle", 3, "ostrich", 2, "fox", 2, "cobra", 2, "chameleon", 2, "hippo", -2)),
        new Env("savanna", "السافانا", "+2 للأسد والفيل والجاموس والزرافة والنعامة والحمار الوحشي والقرد",
            fx("lion", 2, "elephant", 2, "buffalo", 2, "giraffe", 2, "ostrich", 2, "zebra", 2, "baboon", 2)),
        new Env("mountains", "الجبال", "+3 للنسر، +2 للدب والذئب والوشق ووحيد القرن والقنفذ، −2 للزرافة",
            fx("eagle", 3, "bear", 2, "wolf", 2, "lynx", 2, "rhino", 2, "hedgehog", 2, "giraffe", -2))
    ));

    public static final List<Event> EVENTS = Collections.unmodifiableList(Arrays.asList(
        new Event("drought", "الجفاف", "يخسر كل لاعب نقطتين."),
        new Event("night", "الليل", "تتضاعف مكافأة الصيد."),
        new Event("hunter", "الصياد", "صاحب أعلى مجموع يخسر 5 نقاط."),
        new Event("migration", "الهجرة", "يبدّل كل لاعب حيوانه مع جاره قبل الكشف."),
        new Event("rain", "المطر", "يأخذ كل حيوان غير مفترس +2."),
        new Event("calm", "يوم هادئ", "لا يحدث شيء."),
        new Event("eclipse", "الكسوف", "تنقلب القوة: قوة الحيوان = 27 ناقص رقمه."),
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

    private static final Map<String, String> FACTS = new HashMap<>();
    static {
        String[] kv = {
        "elephant", "أكبر حيوان بري على الأرض. يستعمل خرطومه للشرب ورشّ الماء، وتتواصل الفيلة بأصوات منخفضة جداً تُسمع على بعد كيلومترات.",
        "lion", "القط الكبير الوحيد الذي يعيش في جماعات تُسمّى «الزمرة»، ويُسمع زئيره على بعد نحو 8 كيلومترات.",
        "rhino", "قرنه مصنوع من الكيراتين، وهي نفس المادة التي تتكوّن منها أظافر الإنسان وشعره.",
        "bear", "حاسة الشم عنده قوية جداً، ويقضي فصل الشتاء في سبات داخل جحره.",
        "croc", "يستطيع البقاء تحت الماء لأكثر من ساعة، ويعيش التمساح النيلي عشرات السنين.",
        "tiger", "لكل نمر نقش خطوط خاص به لا يتكرر، مثل بصمة الإصبع عند الإنسان.",
        "buffalo", "يدافع عن قطيعه بشجاعة، وقد تتجمّع الجواميس معاً لتصدّ هجوم الأسود.",
        "hippo", "يفرز جلده سائلاً مائلاً للحمرة يحميه من أشعة الشمس، ويقضي معظم نهاره في الماء.",
        "gorilla", "من أقرب الحيوانات إلى الإنسان، وغذاؤها نباتي في الغالب رغم قوتها الكبيرة.",
        "cheetah", "أسرع حيوان بري، تصل سرعته إلى نحو 100 كيلومتر في الساعة لمسافات قصيرة.",
        "hyena", "الضبع المرقّط صياد ماهر وليس قمّاماً فقط، وفكّاه قويان بما يكفي لكسر العظام.",
        "wolf", "تعيش الذئاب في قطعان منظمة، وتعوي لتتواصل مع أفراد قطيعها.",
        "lynx", "على أطراف أذنيه خصل شعر سوداء، وأقدامه العريضة تساعده على المشي بصمت فوق الثلج.",
        "eagle", "بصره حاد جداً، فيرى فريسته وهو يحلّق على ارتفاع شاهق.",
        "giraffe", "أطول حيوان على الأرض، ولسانها طويل يصل إلى نحو نصف متر.",
        "ostrich", "أكبر طائر في العالم، لا تطير لكنها تجري بسرعة تقارب 70 كيلومتراً في الساعة.",
        "gazelle", "تقفز قفزات عالية في مكانها حين ترى مفترساً، لتُظهر له أنها سريعة ويصعب صيدها.",
        "zebra", "لا يتشابه حماران وحشيان في نقش الخطوط، فلكل واحد نقشه الخاص.",
        "raccoon", "يداه الأماميتان ماهرتان جداً، يتحسّس بهما طعامه وكثيراً ما يبلّله بالماء.",
        "cobra", "تفرد غطاء رقبتها حين تشعر بالخطر، لتبدو أكبر وتخيف أعداءها.",
        "owl", "تستطيع أن تدير رأسها نحو ثلاثة أرباع دورة كاملة، وتطير بصمت تقريباً.",
        "fox", "سمعه حاد جداً، فيسمع حركة القوارض تحت الأرض وتحت الثلج.",
        "chameleon", "تحرّك كل عين وحدها في اتجاه مختلف، وتغيّر لونها، ولسانها قد يكون أطول من جسمها.",
        "baboon", "يعيش قرد البابون في جماعات كبيرة لها نظام وترتيب واضح.",
        "hedgehog", "يغطي ظهره آلاف الأشواك، ويلتفّ على شكل كرة شائكة عند الخطر.",
        "deer", "يفقد ذكر الأيل قرونه كل عام، ثم تنمو له قرون جديدة."
        };
        for (int i = 0; i < kv.length; i += 2) FACTS.put(kv[i], kv[i + 1]);
    }

    /** A real fact about the animal, for the encyclopedia. */
    public static String fact(String key) {
        String f = FACTS.get(key);
        return f == null ? "" : f;
    }
}
