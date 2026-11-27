package com.jungleclash.game.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Arabic / English text. Arabic is the source language; English is looked up by the Arabic text or the card key. */
public final class Lang {
    private Lang() {}

    public static final String AR = "ar", EN = "en";

    public static boolean en(String lang) { return EN.equals(lang); }

    private static final Map<String, String> UI = new HashMap<>();
    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<String, String[]> ANIMALS = new HashMap<>();
    private static final Map<String, String[]> ENVS = new HashMap<>();
    private static final Map<String, String[]> EVENTS = new HashMap<>();
    private static final Map<String, String> AR_NAME_TO_KEY = new HashMap<>();

    private static void ui(String ar, String en) { UI.put(ar, en); }

    static {
        ui("هل تريد مغادرة اللعبة؟", "Leave the game?");
        ui("مغادرة", "Leave");
        ui("إلغاء", "Cancel");
        ui("اللعب بدون إنترنت يحتاج إذن البلوتوث والأجهزة القريبة والموقع. اسمح بها من إعدادات التطبيق ثم حاول مرة أخرى.", "Offline play needs Bluetooth, Nearby devices and Location permissions. Allow them in the app settings and try again.");
        ui("صراع الغابة", "Jungle Clash");
        ui("لعبة القوة والدهاء · من 2 إلى 6 لاعبين · بدون إنترنت", "A game of power and cunning · 2 to 6 players · no internet");
        ui("اكتب اسمك", "Enter your name");
        ui("إنشاء غرفة (أنا المضيف)", "Create a room (I'm the host)");
        ui("الانضمام إلى غرفة قريبة", "Join a nearby room");
        ui("موسوعة الغابة", "Jungle Encyclopedia");
        ui("القواعد", "Rules");
        ui("جولة تعليمية", "Tutorial");
        ui("كيف تلعب", "How to play");
        ui("• شغّلوا البلوتوث والموقع (GPS) على كل الهواتف.\n", "• Turn on Bluetooth and Location (GPS) on every phone.\n");
        ui("• لاعب واحد ينشئ الغرفة، والباقون ينضمون إليها.\n", "• One player creates the room, the others join it.\n");
        ui("• كل لاعب يرى حيوانه وقراره سراً على هاتفه.\n", "• Each player sees their animal and decision secretly on their phone.\n");
        ui("• 3 جولات، والأعلى نقاطاً في النهائي يُتوَّج ملك الغابة.", "• 3 rounds; the highest score in the final is crowned King of the Jungle.");
        ui("اكتب اسمك أولاً", "Enter your name first");
        ui("ابحث عن غرفة قريبة…", "Looking for a nearby room…");
        ui("انقطع الاتصال", "Connection lost");
        ui("لا تقلق", "Don't worry");
        ui("مكانك في اللعبة محفوظ عند المضيف. ستُلعب اختياراتك بأمان (اختباء) حتى تعود.", "Your place in the game is kept by the host. Safe choices (hide) will be played for you until you return.");
        ui("إعادة الاتصال بغرفة ", "Reconnect to room ");
        ui("المضيف", "the host");
        ui("أبحث عن غرفة ", "Looking for room ");
        ui("مغادرة اللعبة", "Leave game");
        ui("الانضمام إلى غرفة", "Join a room");
        ui("لم تظهر غرف بعد. تأكد أن المضيف أنشأ الغرفة وأن البلوتوث يعمل.", "No rooms yet. Make sure the host created a room and Bluetooth is on.");
        ui("غرفة ", "Room ");
        ui("أُرسل طلبك إلى غرفة ", "Request sent to room ");
        ui(". بانتظار موافقة المضيف…", ". Waiting for the host to accept…");
        ui("رجوع", "Back");
        ui("وجدت غرفة ", "Found room ");
        ui(". جارٍ العودة إلى اللعبة…", ". Returning to the game…");
        ui("اختر الغرفة التي تريد الانضمام إليها:", "Choose the room you want to join:");
        ui("تم الاتصال! بانتظار المضيف…", "Connected! Waiting for the host…");
        ui("رفض المضيف طلب الانضمام.", "The host declined your request.");
        ui("تعذّر الاتصال بالغرفة. حاول مرة أخرى.", "Couldn't connect to the room. Try again.");
        ui("الجولة ", "Round ");
        ui(" · النهائي", " · Final");
        ui("البيئة: ", "Environment: ");
        ui("الحدث: ", "Event: ");
        ui("قرارك: ", "Your decision: ");
        ui("أنت روح الغابة", "You are a Jungle Spirit");
        ui("خرجت من المنافسة، لكنك تمنح +2 لأحد اللاعبين في كل جولة.", "You're out of the race, but each round you give +2 to one player.");
        ui("سرّي لك", "Secret to you");
        ui("ملك الغابة: ", "King of the Jungle: ");
        ui("الجولة التالية", "Next round");
        ui("بانتظار المضيف ليبدأ ", "Waiting for the host to start ");
        ui("لعبة جديدة…", "a new game…");
        ui("الجولة التالية…", "the next round…");
        ui("لعبة جديدة: وضع المبارزة", "New game: Duel mode");
        ui("لعبة جديدة: الوضع العادي", "New game: Normal mode");
        ui("لعبة جديدة بنفس اللاعبين", "New game with the same players");
        ui(" تاج", " crown");
        ui(" تيجان", " crowns");
        ui("تيجان الغرفة", "Room crowns");
        ui("المبارزة · الجولة ", "Duel · Round ");
        ui("أنت: ", "You: ");
        ui(" تاج   ·   ", " crown   ·   ");
        ui("المبارزات في هذه الجولة: أنت ", "Duels this round: You ");
        ui("اختيار سري في نفس الوقت", "Secret, simultaneous picks");
        ui("يختار كل لاعب 3 حيوانات من الستة سراً، ويمكن أن تختارا نفس الحيوان. لا أفضلية لأحد.", "Each player secretly picks 3 of the 6 animals, and you may both pick the same animal. No one has an advantage.");
        ui(" اختار ", " picked ");
        ui(" من 3.", " of 3.");
        ui("في الساحة", "In the arena");
        ui("اختر", "Pick");
        ui("حيواناتك", "Your animals");
        ui("رتّب حيواناتك سراً", "Arrange your animals secretly");
        ui("اختر من يدخل كل مبارزة، ثم ضع الضربة القوية (+4) على مبارزة واحدة. البيئة والحدث يُسحبان عشوائياً عند كشف كل مبارزة.", "Choose who enters each duel, then put the Power Strike (+4) on one duel. The environment and event are drawn at random when each duel is revealed.");
        ui("المبارزة ", "Duel ");
        ui("للمبارزة ", "for duel ");
        ui("خطتك:  ", "Your plan:  ");
        ui("استعدّوا…", "Get ready…");
        ui("اكشف المبارزة ", "Reveal duel ");
        ui("بانتظار المضيف ليكشف المبارزة ", "Waiting for the host to reveal duel ");
        ui("انتهت الجولة", "Round over");
        ui("تعادل في هذه الجولة، لا تاج لأحد.", "This round is a draw, no crown for anyone.");
        ui(" فاز بالجولة وأخذ تاجاً.", " won the round and takes a crown.");
        ui("نتيجة الجولة", "Round result");
        ui("الحيوانات في الساحة", "Animals in the arena");
        ui("مفترس", "Predator");
        ui("غير مفترس", "Non-predator");
        ui("الضربة القوية", "Power Strike");
        ui("بدون ضربة", "No strike");
        ui("طلبات الانضمام", "Join requests");
        ui(" يريد العودة إلى اللعبة", " wants to rejoin the game");
        ui(" يريد الانضمام", " wants to join");
        ui("قبول", "Accept");
        ui("رفض", "Decline");
        ui("غرفتك جاهزة", "Your room is ready");
        ui("في الغرفة", "In the room");
        ui("اطلب من اللاعبين الضغط على «الانضمام إلى غرفة قريبة».", "Ask players to tap “Join a nearby room”.");
        ui("بانتظار أن يبدأ المضيف اللعبة…", "Waiting for the host to start the game…");
        ui("اللاعبون (", "Players (");
        ui(" من 6)", " of 6)");
        ui(" (أنت)", " (you)");
        ui("أسلوب اللعب", "Game mode");
        ui("مع لاعبَين ننصح بوضع «المبارزة»: يختار كل منكما 3 حيوانات سراً من 6 معروضة، ثم 3 مبارزات تُسحب لكل منها بيئة وحدث عشوائيان.", "With two players we recommend Duel mode: each of you secretly picks 3 of 6 shown animals, then 3 duels, each with a random environment and event.");
        ui("ابدأ: وضع المبارزة (مقترح)", "Start: Duel mode (recommended)");
        ui("ابدأ: الوضع العادي", "Start: Normal mode");
        ui("ابدأ اللعب", "Start game");
        ui("بانتظار لاعب واحد على الأقل…", "Waiting for at least one player…");
        ui("اختر حيوانك لهذه الجولة سراً", "Choose your animal for this round secretly");
        ui("الحيوان الآخر يعود إلى الكومة.", "The other animal goes back to the deck.");
        ui("أختار ", "Choose ");
        ui("، ", ", ");
        ui("انتظر قليلاً", "Please wait");
        ui("بانتظار: ", "Waiting for: ");
        ui("جارٍ التحضير…", "Preparing…");
        ui(" · خرج", " · Out");
        ui(" · الأعلى", " · Top");
        ui("التيجان: ", "Crowns: ");
        ui(" (تجاهله الاختباء)", " (ignored by hiding)");
        ui(" (انقطع)", " (disconnected)");
        ui(" · تيجان الغرفة ", " · Room crowns ");
        ui("؟", "?");
        ui("حسناً", "OK");
        ui("26 حيواناً من الأقوى إلى الأضعف، مع قدرة كل حيوان في اللعبة ومعلومة حقيقية عنه.", "26 animals from strongest to weakest, with each animal's in-game ability and a real fact about it.");
        ui("القوة ", "Power ");
        ui("في اللعبة: ", "In the game: ");
        ui("هل تعلم؟", "Did you know?");
        ui("قواعد صراع الغابة", "Jungle Clash rules");
        ui("جولة تعليمية ", "Tutorial ");
        ui(" من ", " of ");
        ui("التالي", "Next");
        ui("لنبدأ اللعب", "Let's play");
        ui("السابق", "Previous");
        ui("تخطّي", "Skip");
        ui("لاعب", "Player");
        ui("عدت إلى اللعبة. أهلاً بعودتك!", "You're back in the game. Welcome back!");
        ui("لاعب ", "Player ");
        ui("الهجرة: انتقل إليك حيوان جارك، وهو الآن: ", "Migration: your neighbour's animal moved to you. It is now: ");
        ui("آثار الأقدام:", "Footprints:");
        ui("العين العالية: ", "High view: ");
        ui(" لديه ", " has ");
        ui("المكر: بدّلت حيوانك مع ", "Cunning: you swapped your animal with ");
        ui(". حيوانك الآن: ", ". Your animal is now: ");
        ui("الثعلب أخذ حيوانك! حيوانك الآن: ", "The fox took your animal! Your animal is now: ");
        ui("اختباء", "Hide");
        ui("هجوم على ", "Attack ");
        ui("اختر حيوانك لهذه الجولة", "Choose your animal for this round");
        ui("العين العالية: اختر لاعباً لترى حيوانه", "High view: choose a player to see their animal");
        ui("المكر: اختر لاعباً تبادل حيوانك معه", "Cunning: choose a player to swap animals with");
        ui("اختر قرارك سراً: هجوم على لاعب (+5 إن تفوّقت عليه، وإلا −3) أو اختباء", "Choose your decision secretly: attack a player (+5 if you beat them, otherwise −3) or hide");
        ui("اختباء: تتجاهل الخصومات وتخسر مكافأتي الصيد والقطيع", "Hide: ignore penalties but lose the hunt and herd bonuses");
        ui("روح الغابة: امنح +2 لأحد اللاعبين", "Jungle Spirit: give +2 to one player");
        ui("السرعة: رأيت كل الحيوانات. هل تغيّر قرارك؟", "Speed: you saw all the animals. Change your decision?");
        ui("أبقِ قراري كما هو", "Keep my decision");
        ui("الخطوط المربكة: اختر مفترساً يخسر مكافأة الصيد", "Confusing stripes: choose a predator to lose its hunt bonus");
        ui("اختر 3 حيوانات سراً (اخترت ", "Pick 3 animals secretly (picked ");
        ui(" من 3)", " of 3)");
        ui("اختر الحيوان الذي يدخل المبارزة ", "Choose the animal entering duel ");
        ui("ضع الضربة القوية (+", "Put the Power Strike (+");
        ui(") على مبارزة واحدة", ") on one duel");
        LABELS.put("القوة", "Power");
        LABELS.put("نجاة الأيل من الجولة السابقة", "Deer survived last round");
        LABELS.put("الصيد (أُلغي)", "Hunt (cancelled)");
        LABELS.put("أشواك القنفذ", "Hedgehog spines");
        LABELS.put("سيد الماء", "Master of water");
        LABELS.put("فريسة", "Prey");
        LABELS.put("القطيع (ألغاه النمر)", "Herd (cancelled by tiger)");
        LABELS.put("القطيع (فرّقه النسر)", "Herd (scattered by eagle)");
        LABELS.put("حماية القطيع", "Herd protection");
        LABELS.put("عواء القطيع", "Pack howl");
        LABELS.put("كمين الماء", "Water ambush");
        LABELS.put("رشاقة الصغار", "Small & agile");
        LABELS.put("الركض السريع", "Fast run");
        LABELS.put("السبات", "Hibernation");
        LABELS.put("عين الليل", "Night eye");
        LABELS.put("القمّام", "Scavenger");
        LABELS.put("لدغة الكوبرا", "Cobra bite");
        LABELS.put("الجفاف", "Drought");
        LABELS.put("المطر", "Rain");
        LABELS.put("أرواح الغابة", "Jungle spirits");
        LABELS.put("فاز في الصدام", "Won the clash");
        LABELS.put("خسر الصدام", "Lost the clash");
        LABELS.put("سرقه الراكون", "Robbed by the raccoon");
        LABELS.put("الصياد", "Hunter");
        LABELS.put("الصياد (نجا بالاختباء)", "Hunter (escaped by hiding)");
        LABELS.put("هجوم ناجح", "Successful attack");
        LABELS.put("هجوم فاشل", "Failed attack");
        LABELS.put("حريق الغابة: لا مكان للاختباء", "Forest fire: nowhere to hide");
        LABELS.put("الزلزال", "Earthquake");
        LABELS.put("الصيد", "Hunt");
        LABELS.put("اللص الماكر", "Sly thief");
        LABELS.put("الضربة القوية", "Power Strike");
        LABELS.put("الجلد الصلب: نجا من الإقصاء", "Tough hide: survived elimination");
        LABELS.put("القفزة: نجت من الإقصاء", "Leap: survived elimination");
        ANIMALS.put("elephant", new String[]{"Elephant", "Giant Shield", "All predators lose their hunt bonus this round.", "The largest land animal on Earth. It uses its trunk to drink and spray water, and elephants talk with very low sounds heard kilometres away."});
        ANIMALS.put("lion", new String[]{"Lion", "King's Roar", "Gets +5 instead of +3 for each prey in the arena.", "The only big cat that lives in groups, called prides. Its roar can be heard about 8 km away."});
        ANIMALS.put("rhino", new String[]{"Rhino", "Tough Hide", "Once per game: cannot be eliminated, whatever its score.", "Its horn is made of keratin, the same material as human nails and hair."});
        ANIMALS.put("bear", new String[]{"Brown Bear", "Hibernation", "If it chooses to hide, it gets +4.", "It has a very strong sense of smell, and spends winter hibernating in its den."});
        ANIMALS.put("croc", new String[]{"Crocodile", "Water Ambush", "Gets +5 if the round's environment is the river.", "It can stay underwater for more than an hour, and the Nile crocodile lives for decades."});
        ANIMALS.put("tiger", new String[]{"Tiger", "Ambush", "All non-predators in the arena lose herd protection.", "Every tiger has its own stripe pattern that never repeats, like a human fingerprint."});
        ANIMALS.put("buffalo", new String[]{"Buffalo", "Raging Bull", "Its herd protection is +5 instead of +2.", "It defends its herd bravely; buffalo can gang up to drive off lions."});
        ANIMALS.put("hippo", new String[]{"Hippo", "Master of Water", "Doesn't lose prey points; gets +2 instead.", "Its skin releases a reddish fluid that protects it from the sun, and it spends most of the day in water."});
        ANIMALS.put("gorilla", new String[]{"Gorilla", "Chest Beat", "The weakest predator in the arena loses its hunt bonus.", "One of our closest relatives, and mostly vegetarian despite its great strength."});
        ANIMALS.put("cheetah", new String[]{"Cheetah", "Speed", "Changes its decision (attack/hide) after everyone is revealed.", "The fastest land animal, reaching about 100 km/h over short distances."});
        ANIMALS.put("hyena", new String[]{"Hyena", "Scavenger", "Gets +2 for each player eliminated last round.", "The spotted hyena is a skilled hunter, not just a scavenger, with jaws strong enough to crush bone."});
        ANIMALS.put("wolf", new String[]{"Wolf", "Pack Howl", "Gets +2 for each other predator in the arena.", "Wolves live in organised packs and howl to keep in touch with each other."});
        ANIMALS.put("lynx", new String[]{"Lynx", "Silent Ambush", "Its hunt bonus can't be cancelled by any ability.", "It has black tufts on its ears, and its wide paws help it walk silently on snow."});
        ANIMALS.put("eagle", new String[]{"Eagle", "Swoop", "The prey it hunts lose herd protection.", "Its eyesight is so sharp it spots prey while soaring very high."});
        ANIMALS.put("giraffe", new String[]{"Giraffe", "High View", "Sees one player's animal before the reveal.", "The tallest animal on Earth, with a tongue about half a metre long."});
        ANIMALS.put("ostrich", new String[]{"Ostrich", "Fast Run", "+3 in the savanna and the desert.", "The largest bird in the world. It can't fly, but runs at almost 70 km/h."});
        ANIMALS.put("gazelle", new String[]{"Gazelle", "Leap", "Once per game: if it would be eliminated it survives, and the next player is eliminated instead.", "It leaps high on the spot when it sees a predator, to show it is fast and hard to catch."});
        ANIMALS.put("zebra", new String[]{"Zebra", "Confusing Stripes", "Chooses one predator in the arena to lose its hunt bonus.", "No two zebras have the same stripe pattern."});
        ANIMALS.put("raccoon", new String[]{"Raccoon", "Sly Thief", "Steals 2 points from the player with the highest total.", "Its front paws are very skilful; it feels its food with them and often wets it in water."});
        ANIMALS.put("cobra", new String[]{"Cobra", "Bite", "If the elephant is in the arena, the elephant loses 6 points.", "It spreads its hood when threatened, to look bigger and scare enemies."});
        ANIMALS.put("owl", new String[]{"Owl", "Night Eye", "+5 on the Night or Eclipse event.", "It can turn its head about three-quarters of a full circle, and flies almost silently."});
        ANIMALS.put("fox", new String[]{"Fox", "Cunning", "Swaps its animal with another player's before the reveal.", "Its hearing is so sharp it hears rodents moving underground and under snow."});
        ANIMALS.put("chameleon", new String[]{"Chameleon", "Camouflage", "Is never prey: doesn't lose prey points and can't be hunted.", "Each eye moves on its own, it changes colour, and its tongue can be longer than its body."});
        ANIMALS.put("baboon", new String[]{"Baboon", "Climbing", "Never loses prey points.", "Baboons live in large troops with a clear order and ranking."});
        ANIMALS.put("hedgehog", new String[]{"Hedgehog", "Spines", "Doesn't lose prey points, and every stronger predator loses 3.", "Thousands of spines cover its back, and it curls into a prickly ball when in danger."});
        ANIMALS.put("deer", new String[]{"Deer", "Survival", "If it survives elimination, it gets +5 next round.", "Male deer shed their antlers every year and then grow new ones."});
        ENVS.put("forest", new String[]{"Forest", "+2 for tiger, cheetah, gorilla, owl, raccoon, fox, baboon and deer"});
        ENVS.put("river", new String[]{"River", "+3 for hippo and crocodile, +2 for elephant, −2 for lion"});
        ENVS.put("desert", new String[]{"Desert", "+3 for gazelle, +2 for ostrich, fox, cobra and chameleon, −2 for hippo"});
        ENVS.put("savanna", new String[]{"Savanna", "+2 for lion, elephant, buffalo, giraffe, ostrich, zebra and baboon"});
        ENVS.put("mountains", new String[]{"Mountains", "+3 for eagle, +2 for bear, wolf, lynx, rhino and hedgehog, −2 for giraffe"});
        EVENTS.put("drought", new String[]{"Drought", "Every player loses 2 points."});
        EVENTS.put("night", new String[]{"Night", "The hunt bonus is doubled."});
        EVENTS.put("hunter", new String[]{"Hunter", "The player with the highest total loses 5 points."});
        EVENTS.put("migration", new String[]{"Migration", "Every player swaps animals with their neighbour before the reveal."});
        EVENTS.put("rain", new String[]{"Rain", "Every non-predator gets +2."});
        EVENTS.put("calm", new String[]{"Calm Day", "Nothing happens."});
        EVENTS.put("eclipse", new String[]{"Eclipse", "Power flips: an animal's power = 27 minus its number."});
        EVENTS.put("fire", new String[]{"Forest Fire", "The environment is cancelled, and anyone who hides loses 3."});
        EVENTS.put("clash", new String[]{"Clash", "The strongest predator and strongest non-predator face off: the higher gets +4, the other −4."});
        EVENTS.put("truce", new String[]{"Truce", "No hunting: the hunt bonus and prey penalty are cancelled."});
        EVENTS.put("earthquake", new String[]{"Earthquake", "After scoring, the highest and lowest totals swap their points."});
        EVENTS.put("footprints", new String[]{"Footprints", "Before deciding, each player's animal type is announced: predator or non-predator."});
        ui("اللعب عبر الإنترنت", "Online play");
        ui("إنشاء غرفة على الإنترنت", "Create online room");
        ui("الانضمام برمز الغرفة", "Join with a room code");
        ui("اكتب رمز الغرفة المكوّن من 4 أرقام", "Enter the 4-digit room code");
        ui("انضم", "Join");
        ui("رمز الغرفة", "Room code");
        ui("جارٍ إنشاء الغرفة…", "Creating the room…");
        ui("أعطِ هذا الرمز لأصدقائك ليضغطوا «الانضمام برمز الغرفة».", "Give this code to your friends so they can tap “Join with a room code”.");
        ui("لا يوجد اتصال بالإنترنت أو تعذّر الوصول إلى الخادم.", "No internet connection, or the server can't be reached.");
        ui("قاعدة البيانات غير جاهزة. أنشئ Realtime Database في Firebase.", "The database isn't ready. Create a Realtime Database in Firebase.");
        ui("قاعدة البيانات ترفض الدخول. حدّث قواعد Realtime Database في Firebase.", "The database refused access. Update the Realtime Database rules in Firebase.");
        ui("لا توجد غرفة بهذا الرمز.", "There's no room with this code.");
        ui("العب وحدك ضد الكمبيوتر", "Play solo vs the computer");
        ui("العب وحدك", "Solo play");
        ui("عدد الخصوم", "Opponents");
        ui("المستوى", "Level");
        ui("سهل", "Easy");
        ui("صعب", "Hard");
        ui("خصوم الكمبيوتر يلعبون فوراً، وأنت المضيف: تتحكم في بدء الجولات وكشف المبارزات.", "Computer opponents play instantly. You are the host: you start rounds and reveal duels.");
        for (Cards.Animal a : Cards.ANIMALS) AR_NAME_TO_KEY.put(a.name, a.key);
        for (Cards.Env e : Cards.ENVS) AR_NAME_TO_KEY.put(e.name, "env:" + e.key);
    }

    /** Translate a piece of UI text (an Arabic literal or fragment). */
    public static String t(String ar, String lang) {
        if (!en(lang) || ar == null) return ar;
        String e = UI.get(ar);
        return e == null ? ar : e;
    }

    private static String[] a(String key) { return ANIMALS.get(key); }

    public static String name(Cards.Animal x, String lang) { return en(lang) && a(x.key) != null ? a(x.key)[0] : x.name; }
    public static String abilityName(Cards.Animal x, String lang) { return en(lang) && a(x.key) != null ? a(x.key)[1] : x.abilityName; }
    public static String abilityText(Cards.Animal x, String lang) { return en(lang) && a(x.key) != null ? a(x.key)[2] : x.abilityText; }
    public static String fact(String key, String lang) { return en(lang) && a(key) != null ? a(key)[3] : Cards.fact(key); }
    public static String typeName(Cards.Animal x, String lang) {
        return en(lang) ? (x.predator ? "Predator" : "Non-predator") : x.typeName();
    }
    public static String name(Cards.Env x, String lang) { return en(lang) && ENVS.get(x.key) != null ? ENVS.get(x.key)[0] : x.name; }
    public static String text(Cards.Env x, String lang) { return en(lang) && ENVS.get(x.key) != null ? ENVS.get(x.key)[1] : x.text; }
    public static String name(Cards.Event x, String lang) { return en(lang) && EVENTS.get(x.key) != null ? EVENTS.get(x.key)[0] : x.name; }
    public static String text(Cards.Event x, String lang) { return en(lang) && EVENTS.get(x.key) != null ? EVENTS.get(x.key)[1] : x.text; }

    /** Translate an Arabic animal or environment name that appears inside a score label. */
    private static String cardName(String arName) {
        String k = AR_NAME_TO_KEY.get(arName);
        if (k == null) return arName;
        if (k.startsWith("env:")) { String[] e = ENVS.get(k.substring(4)); return e == null ? arName : e[0]; }
        String[] an = ANIMALS.get(k);
        return an == null ? arName : an[0];
    }

    private static final Pattern HUNT = Pattern.compile("^الصيد: (\\d+) فريسة$");
    private static final Pattern CANCELLED_BY = Pattern.compile("^الصيد \\(أُلغي بقدرة (.+)\\)$");

    /** Translate a score-part label produced by the engine. */
    public static String label(String ar, String lang) {
        if (!en(lang) || ar == null) return ar;
        String e = LABELS.get(ar);
        if (e != null) return e;
        Matcher m = HUNT.matcher(ar);
        if (m.matches()) return "Hunt: " + m.group(1) + " prey";
        m = CANCELLED_BY.matcher(ar);
        if (m.matches()) return "Hunt (cancelled by " + cardName(m.group(1)) + ")";
        if (ar.startsWith("البيئة: ")) return "Environment: " + cardName(ar.substring(8));
        if (ar.startsWith("اللص الماكر: سرق من ")) return "Sly thief: stole from " + ar.substring("اللص الماكر: سرق من ".length());
        for (String head : new String[]{"هجوم ناجح", "هجوم فاشل"}) {
            if (ar.startsWith(head + " على ")) return LABELS.get(head) + " on " + ar.substring((head + " على ").length());
        }
        return ar;
    }
}
