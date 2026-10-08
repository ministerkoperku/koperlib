package com.koper.koper_lib.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.kui.KuiBook;
import com.koper.koper_lib.kui.KuiJson;
import com.koper.koper_lib.kui.KuiOpen;
import com.koper.koper_lib.kui.KuiPage;
import com.koper.koper_lib.kui.KuiPoke;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperLibDirectories;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

// quest editor. a browser over the files in a pack plus a three step form, because one screen with
// everything on it was unusable and a form wants one column with its labels above the fields.
// what it writes is the same json you'd type, and unknown fields survive a round trip
public final class QuestMaker {
    private QuestMaker() {}

    public static final String BROWSER = "koperlib:quest_edit";
    public static final String STEP1 = "koperlib:quest_form1";
    public static final String STEP2 = "koperlib:quest_form2";
    public static final String STEP3 = "koperlib:quest_form3";
    public static final String ACTIONS = "koperlib:quest_actions";

    // verb, label, what the argument means. every shape here was read out of KoperActions, not guessed
    private static final String[][] VERBS = {
        { "give",    "give item",       "item id, count in the number box" },
        { "command", "run command",     "/time set day" },
        { "lua",     "call lua",        "script id, e.g. mypack:reward" },
        { "java",    "call java hook",  "hook id from your pack java" },
        { "sound",   "play sound",      "minecraft:entity.player.levelup" },
        { "message", "chat message",    "text shown to the player" },
        { "kfx",     "kfx effect",      "effect id" },
        { "custom",  "custom json",     "raw action object, for anything else" },
    };

    private static final List<String> GOAL_TYPES = List.of(
        "kill", "craft", "get", "have", "advancement", "dimension",
        "break", "place", "talk", "die", "dialog", "script");
    private static final List<String> CATEGORIES = List.of("main", "side", "secret");

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final int PAGE_FACE = 0xFF232A36;
    private static final int FADED = 0xFF8E98AA;
    private static final int ACCENT = 0xFFE39A4A;
    private static final int GO = 0xFF2F5D3A;
    private static final int STOP = 0xFF5D2F2F;

    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> PACK_ROWS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<Path>> FILE_ROWS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> TAG_ROWS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> REQ_ROWS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> BOOK_ROWS = new ConcurrentHashMap<>();

    private static final class Draft {
        String pack = "";
        Path file;                 // null until saved once, then the file we are editing
        JsonObject raw = new JsonObject(); // whatever was in the file, so unknown keys survive

        String id = "";
        String name = "";
        String desc = "";
        String icon = "minecraft:book";
        String category = "main";
        String book = "";
        boolean autoStart = true;
        final List<String> requires = new ArrayList<>();
        final List<GoalDraft> goals = new ArrayList<>();

        final List<JsonObject> onStart = new ArrayList<>();
        final List<JsonObject> onComplete = new ArrayList<>();

        String type = "kill";
        String target = "";
        int count = 1;
        int editingGoal = -1;

        String slot = "on_complete";   // which action list the editor is looking at
        String verb = "give";
        String arg = "";
        int amount = 1;

        List<JsonObject> slotList() {
            if (slot.startsWith("goal:")) {
                int at = Integer.parseInt(slot.substring(5));
                return at >= 0 && at < goals.size() ? goals.get(at).onDone : new ArrayList<>();
            }
            return "on_start".equals(slot) ? onStart : onComplete;
        }
    }

    private static final class GoalDraft {
        String type, target, count;
        final List<JsonObject> onDone = new ArrayList<>();
        GoalDraft(String type, String target, String count) {
            this.type = type; this.target = target; this.count = count;
        }
    }

    private static Draft draft(ServerPlayer player) {
        return DRAFTS.computeIfAbsent(player.getUUID(), u -> new Draft());
    }

    // ── pages ───────────────────────────────────────────────────────────────

    public static void bakeAndRegister() {
        try {
            Path dir = KoperLibDirectories.ROOT.resolve("ui");
            Files.createDirectories(dir);
            put(dir, BROWSER, "quest_edit", "Quests", 264, 208, browserLayout());
            put(dir, STEP1, "quest_form1", "New Quest", 264, 216, step1Layout());
            put(dir, STEP2, "quest_form2", "Goals", 280, 250, step2Layout());
            put(dir, STEP3, "quest_form3", "Finish", 264, 210, step3Layout());
            put(dir, ACTIONS, "quest_actions", "Actions", 280, 236, actionsLayout());
        } catch (Exception broken) {
            KoperLib.LOGGER.warn("[Quest] couldn't bake the editor: {}", broken.getMessage());
        }
    }

    private static void put(Path dir, String id, String file, String title, int w, int h, String json)
            throws java.io.IOException {
        Path layout = dir.resolve(file + ".layout.json");
        Files.writeString(layout, json);

        KuiPage page = new KuiPage();
        page.id = id;
        page.namespace = "koperlib";
        page.mode = "json";
        page.title = title;
        page.w = w;
        page.h = h;
        page.layoutFile = layout.toAbsolutePath().toString();
        KuiBook.put(page);
    }

    private static String browserLayout() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 264, 208, null);
        j.label("title", 0, 8, 264, "§6Quests", "center", null);
        j.rule("head", 10, 20, 244, 1, ACCENT);
        j.panel("body", 8, 26, 248, 148, PAGE_FACE);

        j.label("packlbl", 14, 31, 236, "§7pack", "left", FADED);
        j.list("packs", 14, 42, 236, 40);
        j.label("filelbl", 14, 86, 236, "§7quests in this pack", "left", FADED);
        j.list("files", 14, 97, 236, 70);

        j.label("say", 8, 178, 248, "", "left", FADED);
        j.button("new", 8, 188, 80, 16, "New", GO);
        j.button("edit", 92, 188, 80, 16, "Edit", null);
        j.button("delete", 176, 188, 80, 16, "Delete", STOP);
        return j.done();
    }

    private static String step1Layout() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 264, 216, null);
        j.label("title", 0, 8, 264, "§6Basics §8· step 1 of 3", "center", null);
        j.rule("head", 10, 20, 244, 1, ACCENT);
        j.panel("body", 8, 26, 248, 156, PAGE_FACE);

        int y = 32;
        j.label("l1", 14, y, 236, "§7id §c*", "left", FADED);
        j.input("qid", 14, y + 10, 236, 16, "waking_up");
        y += 32;
        j.label("l2", 14, y, 236, "§7name §c*", "left", FADED);
        j.input("qname", 14, y + 10, 236, 16, "Waking Up");
        y += 32;
        j.label("l3", 14, y, 236, "§7description §8(optional)", "left", FADED);
        j.input("qdesc", 14, y + 10, 236, 16, "what the player is being told");
        y += 32;
        j.label("l4", 14, y, 236, "§7icon §8(optional)", "left", FADED);
        j.input("qicon", 14, y + 10, 152, 16, "minecraft:book");
        j.button("iconhand", 170, y + 10, 80, 16, "From hand", null);
        y += 32;
        j.label("l5", 14, y, 110, "§7category", "left", FADED);
        j.selector("category", 14, y + 10, 110, 16, CATEGORIES, "main");
        j.toggle("autostart", 140, y + 10, 110, 16, "auto start", true);

        j.label("say", 8, 186, 248, "", "left", FADED);
        j.button("cancel", 8, 196, 100, 16, "Cancel", null);
        j.button("next1", 156, 196, 100, 16, "Next →", GO);
        return j.done();
    }

    private static String step2Layout() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 280, 250, null);
        j.label("title", 0, 8, 280, "§6Goals §8· step 2 of 3", "center", null);
        j.rule("head", 10, 20, 260, 1, ACCENT);
        j.panel("body", 8, 26, 264, 166, PAGE_FACE);

        j.label("l1", 14, 31, 252, "§7goals on this quest", "left", FADED);
        j.list("goals", 14, 42, 252, 46);

        j.rule("split", 14, 92, 252, 1, 0xFF465164);
        j.label("l2", 14, 97, 252, "§7type and target", "left", FADED);
        j.selector("gtype", 14, 108, 110, 16, GOAL_TYPES, "kill");
        j.input("gcount", 130, 108, 34, 16, "1");
        j.button("hand", 170, 108, 96, 16, "From hand", null);
        j.input("gtarget", 14, 128, 252, 16, "minecraft:zombie or #minecraft:logs");

        j.label("l3", 14, 148, 252, "§7tags of the held item", "left", FADED);
        j.list("tags", 14, 158, 252, 30);

        j.button("addgoal", 14, 194, 60, 16, "Add", GO);
        j.button("replace", 78, 194, 66, 16, "Replace", null);
        j.button("goalact", 148, 194, 60, 16, "Actions", null);
        j.button("delgoal", 212, 194, 54, 16, "Remove", STOP);

        j.label("say", 8, 214, 264, "", "left", FADED);
        j.button("prev2", 8, 226, 100, 16, "← Back", null);
        j.button("next2", 172, 226, 100, 16, "Next →", GO);
        return j.done();
    }

    private static String step3Layout() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 264, 210, null);
        j.label("title", 0, 8, 264, "§6Finish §8· step 3 of 3", "center", null);
        j.rule("head", 10, 20, 244, 1, ACCENT);
        j.panel("body", 8, 26, 248, 150, PAGE_FACE);

        j.label("l1", 14, 31, 236, "§7actions §8(lua, java, commands, items…)", "left", FADED);
        j.button("edstart", 14, 42, 114, 16, "On start", null);
        j.button("edcomplete", 138, 42, 112, 16, "On complete", null);

        j.label("l2", 14, 64, 236, "§7needs these done first §8(click to toggle)", "left", FADED);
        j.list("requires", 14, 75, 236, 40);

        j.label("l3", 14, 118, 236, "§7which book §8(blank = the one for this namespace)", "left", FADED);
        j.list("book", 14, 128, 236, 26);

        j.rule("split", 14, 158, 236, 1, 0xFF465164);
        j.label("preview", 14, 162, 236, "", "left", FADED);
        j.label("check", 14, 172, 236, "", "left", FADED);

        j.label("say", 8, 180, 248, "", "left", FADED);
        j.button("prev3", 8, 190, 100, 16, "← Back", null);
        j.button("save", 156, 190, 100, 16, "Save", GO);
        return j.done();
    }

    private static String actionsLayout() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 280, 236, null);
        j.label("title", 0, 8, 280, "§6Actions", "center", null);
        j.rule("head", 10, 20, 260, 1, ACCENT);
        j.panel("body", 8, 26, 264, 152, PAGE_FACE);

        j.label("l1", 14, 31, 252, "§7what should happen", "left", FADED);
        j.list("verbs", 14, 42, 252, 44);
        j.label("hint", 14, 90, 252, "", "left", FADED);
        j.input("arg", 14, 100, 208, 16, "argument");
        j.input("amount", 226, 100, 40, 16, "1");
        j.button("addact", 14, 120, 82, 16, "Add", GO);
        j.button("handact", 100, 120, 82, 16, "From hand", null);
        j.button("delact", 186, 120, 80, 16, "Remove", STOP);
        j.label("l2", 14, 140, 252, "§7list in order", "left", FADED);
        j.list("acts", 14, 150, 252, 24);

        j.label("say", 8, 182, 264, "", "left", FADED);
        j.label("preview", 8, 194, 264, "", "left", FADED);
        j.button("actback", 8, 212, 264, 16, "← Done", null);
        return j.done();
    }

    private static void openActions(ServerPlayer player, Draft d, String slot) {
        d.slot = slot;
        if (KuiBook.get(ACTIONS) == null) bakeAndRegister();
        KuiOpen.open(player, ACTIONS);

        List<String> rows = new ArrayList<>();
        for (String[] verb : VERBS) rows.add("§f" + verb[1] + " §8" + verb[0]);
        KuiPoke.options(player, "verbs", rows);
        KuiPoke.set(player, ACTIONS, "verbs", "§f" + labelOf(d.verb) + " §8" + d.verb);
        KuiPoke.text(player, "title", "§6Actions §8· " + (slot.startsWith("goal:")
            ? "goal " + (Integer.parseInt(slot.substring(5)) + 1) + " done"
            : slot.replace('_', ' ')));
        KuiPoke.set(player, ACTIONS, "arg", d.arg);
        KuiPoke.set(player, ACTIONS, "amount", String.valueOf(d.amount));
        redrawActions(player, d);
    }

    private static String labelOf(String verb) {
        for (String[] row : VERBS) if (row[0].equals(verb)) return row[1];
        return verb;
    }

    private static String hintOf(String verb) {
        for (String[] row : VERBS) if (row[0].equals(verb)) return row[2];
        return "";
    }

    private static void redrawActions(ServerPlayer player, Draft d) {
        List<String> rows = new ArrayList<>();
        for (JsonObject action : d.slotList()) rows.add("§7" + cut(action.toString(), 40));
        KuiPoke.options(player, "acts", rows);
        KuiPoke.text(player, "hint", "§8" + hintOf(d.verb));
        KuiPoke.text(player, "preview", "§8→ " + cut(buildAction(d).toString(), 44));
        say(player, d.slotList().isEmpty() ? "§7nothing happens yet" : "§7" + d.slotList().size() + " action(s)");
    }

    // every shape below was read out of KoperActions, so what gets written actually runs
    private static JsonObject buildAction(Draft d) {
        JsonObject out = new JsonObject();
        String arg = d.arg;
        switch (d.verb) {
            case "give" -> {
                JsonObject give = new JsonObject();
                give.addProperty("item", arg);
                give.addProperty("count", Math.max(1, d.amount));
                out.add("give", give);
            }
            case "command" -> out.addProperty("command", arg);
            case "lua" -> out.addProperty("lua", arg);
            case "java" -> out.addProperty("java", arg);
            case "kfx" -> out.addProperty("kfx", arg);
            case "sound" -> {
                out.addProperty("action", "play_sound");
                out.addProperty("sound", arg);
            }
            case "message" -> {
                out.addProperty("action", "message");
                out.addProperty("message", arg);
            }
            case "custom" -> {
                try {
                    return JsonParser.parseString(arg.isBlank() ? "{}" : arg).getAsJsonObject();
                } catch (Exception notJson) {
                    out.addProperty("_broken", arg);
                }
            }
            default -> out.addProperty(d.verb, arg);
        }
        return out;
    }

    private static boolean actions(KuiPoke.Poke poke) {
        ServerPlayer player = poke.player();
        Draft d = draft(player);

        switch (poke.widget()) {
            case "verbs" -> {
                int index = (int) poke.value();
                if (index >= 0 && index < VERBS.length) d.verb = VERBS[index][0];
            }
            case "arg" -> d.arg = poke.text().trim();
            case "amount" -> {
                try { d.amount = Math.max(1, Integer.parseInt(poke.text().trim())); }
                catch (NumberFormatException notANumber) { d.amount = 1; }
            }
            case "handact" -> {
                String held = heldId(player);
                if (held == null) { say(player, "§chand is empty"); return true; }
                d.arg = held;
                KuiPoke.set(player, ACTIONS, "arg", held);
            }
            case "addact" -> {
                if (d.arg.isBlank()) { say(player, "§cthat action needs an argument"); return true; }
                JsonObject built = buildAction(d);
                if (built.has("_broken")) { say(player, "§cthat is not valid json"); return true; }
                d.slotList().add(built);
            }
            case "delact" -> {
                int index = SELECTED_ACTION.getOrDefault(player.getUUID(), -1);
                if (index < 0 || index >= d.slotList().size()) { say(player, "§cpick one to remove"); return true; }
                d.slotList().remove(index);
                SELECTED_ACTION.remove(player.getUUID());
            }
            case "acts" -> SELECTED_ACTION.put(player.getUUID(), (int) poke.value());
            case "actback" -> {
                if (d.slot.startsWith("goal:")) openStep2(player, d); else openStep3(player, d);
                return true;
            }
            default -> { return false; }
        }
        redrawActions(player, d);
        return true;
    }

    private static final Map<UUID, Integer> SELECTED_ACTION = new ConcurrentHashMap<>();

    // ── browser ─────────────────────────────────────────────────────────────

    public static void open(ServerPlayer player) {
        if (KuiBook.get(BROWSER) == null) bakeAndRegister();
        KuiOpen.open(player, BROWSER);

        List<String> packs = new ArrayList<>(FullPackLoader.getAllPacks().keySet());
        PACK_ROWS.put(player.getUUID(), packs);
        KuiPoke.options(player, "packs", packs);

        Draft d = draft(player);
        if (d.pack.isEmpty() && !packs.isEmpty()) d.pack = packs.getFirst();
        listFiles(player, d);
    }

    private static void listFiles(ServerPlayer player, Draft d) {
        List<Path> files = new ArrayList<>();
        List<String> rows = new ArrayList<>();

        Path questsDir = KoperLibDirectories.FULLPACKS.resolve(d.pack).resolve("quests");
        if (Files.isDirectory(questsDir)) {
            try (Stream<Path> walk = Files.list(questsDir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
            } catch (Exception ignored) {
                // an unreadable pack folder is the author's problem, the list just stays empty
            }
        }
        for (Path file : files) rows.add("§f" + file.getFileName().toString().replace(".json", ""));

        FILE_ROWS.put(player.getUUID(), files);
        KuiPoke.options(player, "files", rows);
        say(player, rows.isEmpty()
            ? "§7no quests in §f" + d.pack + " §7yet, hit New"
            : "§7" + rows.size() + " in §f" + d.pack);
    }

    // ── steps ───────────────────────────────────────────────────────────────

    private static void openStep1(ServerPlayer player, Draft d) {
        KuiOpen.open(player, STEP1);
        KuiPoke.set(player, STEP1, "qid", d.id);
        KuiPoke.set(player, STEP1, "qname", d.name);
        KuiPoke.set(player, STEP1, "qdesc", d.desc);
        KuiPoke.set(player, STEP1, "qicon", d.icon);
        KuiPoke.set(player, STEP1, "category", d.category);
        KuiPoke.set(player, STEP1, "autostart", d.autoStart);
        checkStep1(player, d);
    }

    private static void checkStep1(ServerPlayer player, Draft d) {
        String problem = problemWithBasics(d);
        say(player, problem == null ? "§awriting into §f" + d.pack : "§c" + problem);
    }

    private static String problemWithBasics(Draft d) {
        if (d.pack.isEmpty()) return "pick a pack first";
        if (d.id.isBlank()) return "id is required";
        if (!d.id.matches("[a-z0-9_/.-]+")) return "id: lowercase letters, digits and _ only";
        if (d.name.isBlank()) return "name is required";
        return null;
    }

    private static void openStep2(ServerPlayer player, Draft d) {
        KuiOpen.open(player, STEP2);
        KuiPoke.set(player, STEP2, "gtype", d.type);
        KuiPoke.set(player, STEP2, "gtarget", d.target);
        KuiPoke.set(player, STEP2, "gcount", String.valueOf(d.count));
        redrawGoals(player, d);
    }

    private static void redrawGoals(ServerPlayer player, Draft d) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < d.goals.size(); i++) {
            GoalDraft goal = d.goals.get(i);
            boolean editing = i == d.editingGoal;
            String hung = goal.onDone.isEmpty() ? "" : " §6*" + goal.onDone.size();
            rows.add((editing ? "§e» " : "§f") + goal.type + " §7" + cut(goal.target, 20)
                + " §8x" + goal.count + hung);
        }
        KuiPoke.options(player, "goals", rows);
        say(player, d.goals.isEmpty()
            ? "§7add at least one goal or it can never finish"
            : "§7" + d.goals.size() + " goal(s)");
    }

    private static void openStep3(ServerPlayer player, Draft d) {
        KuiOpen.open(player, STEP3);
        KuiPoke.text(player, "edstart", "On start (" + d.onStart.size() + ")");
        KuiPoke.text(player, "edcomplete", "On complete (" + d.onComplete.size() + ")");

        List<String> ids = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        String mine = d.pack + ":" + d.id;
        for (var quest : QuestBook.all()) {
            if (quest.id.equals(mine)) continue;
            ids.add(quest.id);
            rows.add((d.requires.contains(quest.id) ? "§a✔ " : "§8○ §7") + cut(QuestChase.label(quest), 26));
        }
        REQ_ROWS.put(player.getUUID(), ids);
        KuiPoke.options(player, "requires", rows);

        List<String> bookIds = new ArrayList<>();
        List<String> bookRows = new ArrayList<>();
        bookIds.add("");
        bookRows.add((d.book.isBlank() ? "§a✔ " : "§8○ §7") + "auto by namespace");
        for (var shelved : BookShelf.inUse()) {
            if (BookShelf.LOOSE.equals(shelved.id)) continue;
            bookIds.add(shelved.id);
            bookRows.add((shelved.id.equals(d.book) ? "§a✔ " : "§8○ §7") + cut(shelved.title, 26));
        }
        BOOK_ROWS.put(player.getUUID(), bookIds);
        KuiPoke.options(player, "book", bookRows);

        KuiPoke.text(player, "preview", "§8" + d.pack + "/quests/" + fileName(d) + ".json");
        String problem = problemWithBasics(d);
        if (problem == null && d.goals.isEmpty()) problem = "no goals, it would never finish";
        KuiPoke.text(player, "check", problem == null ? "§aready to save" : "§c" + problem);
        say(player, problem == null ? "" : "§cfix that on the earlier step");
    }

    private static String fileName(Draft d) {
        String path = d.id.contains(":") ? d.id.substring(d.id.indexOf(':') + 1) : d.id;
        return path.trim().toLowerCase().replace(' ', '_');
    }

    private static void say(ServerPlayer player, String text) {
        KuiPoke.text(player, "say", text);
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - (max - 1));
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    public static void register() {
        KuiPoke.on(BROWSER, poke -> browser(poke));
        KuiPoke.on(STEP1, poke -> step1(poke));
        KuiPoke.on(STEP2, poke -> step2(poke));
        KuiPoke.on(STEP3, poke -> step3(poke));
        KuiPoke.on(ACTIONS, poke -> actions(poke));
        bakeAndRegister();
    }

    private static boolean browser(KuiPoke.Poke poke) {
        ServerPlayer player = poke.player();
        Draft d = draft(player);

        switch (poke.widget()) {
            case "packs" -> {
                List<String> packs = PACK_ROWS.getOrDefault(player.getUUID(), List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < packs.size()) {
                    d.pack = packs.get(index);
                    listFiles(player, d);
                }
            }
            case "files" -> {
                SELECTED_FILE.put(player.getUUID(), (int) poke.value());
                say(player, "§7" + poke.text() + " §8— Edit or Delete");
            }
            case "new" -> {
                Draft fresh = new Draft();
                fresh.pack = d.pack;
                DRAFTS.put(player.getUUID(), fresh);
                openStep1(player, fresh);
            }
            case "edit" -> {
                Path file = selectedFile(player);
                if (file == null) { say(player, "§cpick a quest in the list first"); return true; }
                Draft loaded = load(file, d.pack);
                if (loaded == null) { say(player, "§ccouldn't read that file"); return true; }
                DRAFTS.put(player.getUUID(), loaded);
                openStep1(player, loaded);
            }
            case "delete" -> {
                Path file = selectedFile(player);
                if (file == null) { say(player, "§cpick a quest in the list first"); return true; }
                try {
                    Files.delete(file);
                    player.sendSystemMessage(Component.literal("§7[Quest] deleted §f" + file.getFileName()));
                    listFiles(player, d);
                    reload(player);
                } catch (Exception failed) {
                    say(player, "§ccouldn't delete it, see log");
                    KoperLib.LOGGER.warn("[Quest] delete failed: {}", failed.toString());
                }
            }
            default -> { return false; }
        }
        return true;
    }

    // the list keeps its own selection, so the buttons ask it what is highlighted
    private static Path selectedFile(ServerPlayer player) {
        List<Path> files = FILE_ROWS.getOrDefault(player.getUUID(), List.of());
        int index = SELECTED_FILE.getOrDefault(player.getUUID(), -1);
        return index >= 0 && index < files.size() ? files.get(index) : null;
    }

    private static final Map<UUID, Integer> SELECTED_FILE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SELECTED_GOAL = new ConcurrentHashMap<>();

    private static boolean step1(KuiPoke.Poke poke) {
        ServerPlayer player = poke.player();
        Draft d = draft(player);

        switch (poke.widget()) {
            case "qid" -> d.id = poke.text().trim().toLowerCase();
            case "qname" -> d.name = poke.text().trim();
            case "qdesc" -> d.desc = poke.text().trim();
            case "qicon" -> d.icon = poke.text().trim();
            case "category" -> d.category = poke.text();
            case "autostart" -> d.autoStart = poke.checked();
            case "iconhand" -> {
                String held = heldId(player);
                if (held == null) { say(player, "§chand is empty"); return true; }
                d.icon = held;
                KuiPoke.set(player, STEP1, "qicon", held);
            }
            case "cancel" -> { open(player); return true; }
            case "next1" -> {
                String problem = problemWithBasics(d);
                if (problem != null) { say(player, "§c" + problem); return true; }
                openStep2(player, d);
                return true;
            }
            default -> { return false; }
        }
        checkStep1(player, d);
        return true;
    }

    private static boolean step2(KuiPoke.Poke poke) {
        ServerPlayer player = poke.player();
        Draft d = draft(player);

        switch (poke.widget()) {
            case "gtype" -> {
                d.type = poke.text();
                offerTargets(player, d);
            }
            case "gtarget" -> d.target = poke.text().trim();
            case "gcount" -> {
                try { d.count = Math.max(1, Integer.parseInt(poke.text().trim())); }
                catch (NumberFormatException notANumber) { d.count = 1; }
            }
            case "goals" -> {
                int index = (int) poke.value();
                SELECTED_GOAL.put(player.getUUID(), index);
                if (index >= 0 && index < d.goals.size()) {
                    GoalDraft goal = d.goals.get(index);
                    d.editingGoal = index;
                    d.type = goal.type;
                    d.target = goal.target;
                    d.count = Integer.parseInt(goal.count);
                    KuiPoke.set(player, STEP2, "gtype", d.type);
                    KuiPoke.set(player, STEP2, "gtarget", d.target);
                    KuiPoke.set(player, STEP2, "gcount", goal.count);
                    redrawGoals(player, d);
                }
            }
            case "tags" -> {
                List<String> tags = TAG_ROWS.getOrDefault(player.getUUID(), List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < tags.size()) {
                    String picked = tags.get(index);
                    // item tags need the #, a dialog or advancement id is already the whole thing
                    d.target = picked.contains(":") && !picked.startsWith("#")
                        && (d.type.equals("dialog") || d.type.equals("advancement")) ? picked : "#" + picked;
                    KuiPoke.set(player, STEP2, "gtarget", d.target);
                    say(player, "§7target §f" + d.target);
                }
            }
            case "hand" -> fromHand(player, d);
            case "goalact" -> {
                int index = SELECTED_GOAL.getOrDefault(player.getUUID(), -1);
                if (index < 0 || index >= d.goals.size()) { say(player, "§cpick a goal first"); return true; }
                openActions(player, d, "goal:" + index);
                return true;
            }
            case "addgoal" -> {
                if (d.target.isBlank() && !d.type.equals("kill")) { say(player, "§cthis goal needs a target"); return true; }
                d.goals.add(new GoalDraft(d.type, d.target, String.valueOf(d.count)));
                d.editingGoal = -1;
                redrawGoals(player, d);
            }
            case "replace" -> {
                if (d.editingGoal < 0 || d.editingGoal >= d.goals.size()) {
                    say(player, "§cpick a goal in the list to replace");
                    return true;
                }
                GoalDraft existing = d.goals.get(d.editingGoal);
                existing.type = d.type;
                existing.target = d.target;
                existing.count = String.valueOf(d.count);
                redrawGoals(player, d);
            }
            case "delgoal" -> {
                int index = SELECTED_GOAL.getOrDefault(player.getUUID(), -1);
                if (index < 0 || index >= d.goals.size()) { say(player, "§cpick a goal to remove"); return true; }
                d.goals.remove(index);
                d.editingGoal = -1;
                SELECTED_GOAL.remove(player.getUUID());
                redrawGoals(player, d);
            }
            case "prev2" -> { openStep1(player, d); return true; }
            case "next2" -> { openStep3(player, d); return true; }
            default -> { return false; }
        }
        return true;
    }

    private static boolean step3(KuiPoke.Poke poke) {
        ServerPlayer player = poke.player();
        Draft d = draft(player);

        switch (poke.widget()) {
            case "edstart" -> { openActions(player, d, "on_start"); return true; }
            case "edcomplete" -> { openActions(player, d, "on_complete"); return true; }
            case "requires" -> {
                List<String> ids = REQ_ROWS.getOrDefault(player.getUUID(), List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < ids.size()) {
                    String id = ids.get(index);
                    if (!d.requires.remove(id)) d.requires.add(id);
                    openStep3(player, d);
                }
            }
            case "book" -> {
                List<String> ids = BOOK_ROWS.getOrDefault(player.getUUID(), List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < ids.size()) {
                    d.book = ids.get(index);
                    openStep3(player, d);
                }
            }
            case "prev3" -> { openStep2(player, d); return true; }
            case "save" -> save(player, d);
            default -> { return false; }
        }
        return true;
    }

    private static String heldId(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return null;
        var key = BuiltInRegistries.ITEM.getKey(held.getItem());
        return key == null ? null : key.toString();
    }

    private static void offerTargets(ServerPlayer player, Draft d) {
        List<String> rows = new ArrayList<>();
        switch (d.type) {
            case "dialog" -> rows.addAll(DialogBook.ids());
            case "advancement" -> {
                var server = player.level().getServer();
                if (server != null)
                    server.getAdvancements().getAllAdvancements().forEach(a -> {
                        if (rows.size() < 60) rows.add(a.id().toString());
                    });
            }
            default -> { }
        }
        TAG_ROWS.put(player.getUUID(), rows);
        KuiPoke.options(player, "tags", rows);
        if (!rows.isEmpty()) say(player, "§7" + rows.size() + " to pick from below");
    }

    private static void fromHand(ServerPlayer player, Draft d) {
        ItemStack held = player.getMainHandItem();
        String id = heldId(player);
        if (id == null) { say(player, "§chand is empty"); return; }

        d.target = id;
        KuiPoke.set(player, STEP2, "gtarget", id);

        Set<String> tags = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.getTags().forEach(named -> {
            for (var holder : named)
                if (holder.value() == held.getItem()) {
                    tags.add(named.key().location().toString());
                    return;
                }
        });
        List<String> rows = new ArrayList<>(tags);
        TAG_ROWS.put(player.getUUID(), rows);
        KuiPoke.options(player, "tags", rows);
        say(player, "§7" + id + " §8(" + rows.size() + " tags)");
    }

    // ── files ───────────────────────────────────────────────────────────────

    private static Draft load(Path file, String pack) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Draft d = new Draft();
            d.pack = pack;
            d.file = file;
            d.raw = root;

            var data = new com.koper.koper_lib.data.KoperQuestData();
            data.applyJson(root);

            d.id = data.id == null ? file.getFileName().toString().replace(".json", "")
                : (data.id.contains(":") ? data.id.substring(data.id.indexOf(':') + 1) : data.id);
            d.name = data.name;
            d.desc = data.description;
            d.icon = data.icon;
            d.category = data.category;
            d.autoStart = data.autoStart;
            d.book = data.book;
            d.requires.addAll(data.requires);
            for (var goal : data.goals) {
                GoalDraft made = new GoalDraft(goal.type, goal.target, String.valueOf(goal.count));
                if (goal.onDone != null) {
                    if (goal.onDone.isJsonArray())
                        for (var el : goal.onDone.getAsJsonArray())
                            if (el.isJsonObject()) made.onDone.add(el.getAsJsonObject());
                    else if (goal.onDone.isJsonObject()) made.onDone.add(goal.onDone.getAsJsonObject());
                }
                d.goals.add(made);
            }

            readActions(root, "on_start", d.onStart);
            readActions(root, "on_complete", d.onComplete);
            return d;
        } catch (Exception broken) {
            KoperLib.LOGGER.warn("[Quest] load failed for {}: {}", file, broken.toString());
            return null;
        }
    }

    // an action written by hand can be a bare string or a single object, both are legal
    private static void readActions(JsonObject root, String key, List<JsonObject> into) {
        if (!root.has(key)) return;
        var raw = root.get(key);
        if (raw.isJsonArray()) {
            for (var el : raw.getAsJsonArray())
                if (el.isJsonObject()) into.add(el.getAsJsonObject());
        } else if (raw.isJsonObject()) {
            into.add(raw.getAsJsonObject());
        }
    }

    private static void save(ServerPlayer player, Draft d) {
        String problem = problemWithBasics(d);
        if (problem == null && d.goals.isEmpty()) problem = "no goals, it would never finish";
        if (problem != null) { say(player, "§c" + problem); return; }

        Path packDir = KoperLibDirectories.FULLPACKS.resolve(d.pack);
        if (!Files.isDirectory(packDir)) { say(player, "§cno such pack on disk"); return; }

        try {
            Path questsDir = packDir.resolve("quests");
            Files.createDirectories(questsDir);
            Path file = questsDir.resolve(fileName(d) + ".json");

            Files.writeString(file, PRETTY.toJson(build(d)) + "\n");
            d.file = file;

            player.sendSystemMessage(Component.literal("§a[Quest] wrote §f" + file));
            DRAFTS.remove(player.getUUID());
            reload(player);
            open(player);
        } catch (Exception failed) {
            KoperLib.LOGGER.warn("[Quest] save failed: {}", failed.toString());
            say(player, "§csave failed, see log");
        }
    }

    // starts from whatever was in the file so a key this editor never heard of is not thrown away
    private static JsonObject build(Draft d) {
        JsonObject root = d.raw.deepCopy();
        root.addProperty("type", "quest");
        root.addProperty("id", d.pack + ":" + fileName(d));
        root.addProperty("name", d.name);
        root.addProperty("description", d.desc);
        root.addProperty("icon", d.icon);
        root.addProperty("category", d.category);
        root.addProperty("auto_start", d.autoStart);
        if (d.book.isBlank()) root.remove("book"); else root.addProperty("book", d.book);

        JsonArray requires = new JsonArray();
        d.requires.forEach(requires::add);
        root.add("requires", requires);

        JsonArray goals = new JsonArray();
        for (int i = 0; i < d.goals.size(); i++) {
            GoalDraft goal = d.goals.get(i);
            JsonObject one = new JsonObject();
            one.addProperty("id", "g" + i);
            one.addProperty("type", goal.type);
            one.addProperty("target", goal.target);
            one.addProperty("count", Integer.parseInt(goal.count));
            if (!goal.onDone.isEmpty()) {
                JsonArray hung = new JsonArray();
                goal.onDone.forEach(hung::add);
                one.add("on_done", hung);
            }
            goals.add(one);
        }
        root.add("goals", goals);

        writeActions(root, "on_start", d.onStart);
        writeActions(root, "on_complete", d.onComplete);
        return root;
    }

    private static void writeActions(JsonObject root, String key, List<JsonObject> actions) {
        if (actions.isEmpty()) {
            root.remove(key);
            return;
        }
        JsonArray out = new JsonArray();
        actions.forEach(out::add);
        root.add(key, out);
    }

    private static void reload(ServerPlayer player) {
        var server = player.level().getServer();
        if (server != null) com.koper.koper_lib.loader.FullpackReloader.reload(server);
    }
}
