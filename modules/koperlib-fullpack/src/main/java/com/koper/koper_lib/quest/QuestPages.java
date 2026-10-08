package com.koper.koper_lib.quest;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperBookData;
import com.koper.koper_lib.data.KoperQuestData;
import com.koper.koper_lib.kui.KuiBook;
import com.koper.koper_lib.kui.KuiJson;
import com.koper.koper_lib.kui.KuiOpen;
import com.koper.koper_lib.kui.KuiPage;
import com.koper.koper_lib.kui.KuiPoke;
import com.koper.koper_lib.loader.KoperLibDirectories;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// the book. two pages like a real one: chapters and their quests on the left, the open quest on
// the right. chapters are just the category field, which is all "side quests" ever needed to be
public final class QuestPages {
    private QuestPages() {}

    public static final String BOOK = "koperlib:quest_book";
    public static final String SHELF = "koperlib:quest_shelf";

    private static final int ROWS = 4;      // goal rows on the right page, more would run into the buttons
    private static final int DESC_LINES = 4; // past this the last line ends in "..." and hovering shows it all
    // these are character counts against labels measured in pixels, so they have to be divided
    // out by hand. mc font is ~6px a glyph: desc label is 144 wide = 24, and a goal row has to
    // leave room for the " 128/128" it glues on the end. names are sent whole: the client fits
    // them to the label and shows the rest on hover
    private static final int WRAP = 24;
    private static final int GOAL_CUT = 17;

    private static final int PAGE_FACE = 0xFF232A36;
    private static final int INK = 0xFFE7EBF2;
    private static final int FADED = 0xFF8E98AA;
    private static final int ACCENT = 0xFFE39A4A;

    private static final Map<UUID, List<String>> SHELF_ROWS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> OPEN_BOOK = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> SHOWN = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> CHAPTERS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> OPEN_CHAPTER = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PICKED = new ConcurrentHashMap<>();

    // ── page ────────────────────────────────────────────────────────────────

    public static void bakeAndRegister() {
        try {
            Path dir = KoperLibDirectories.ROOT.resolve("ui");
            Files.createDirectories(dir);
            Path layout = dir.resolve("quest_book.layout.json");
            Files.writeString(layout, layoutJson());

            KuiPage page = new KuiPage();
            page.id = BOOK;
            page.namespace = "koperlib";
            page.mode = "json";
            page.title = "Quest Book";
            page.w = 320;
            page.h = 208;
            page.layoutFile = layout.toAbsolutePath().toString();
            KuiBook.put(page);

            Path shelfLayout = dir.resolve("quest_shelf.layout.json");
            Files.writeString(shelfLayout, shelfJson());

            KuiPage shelf = new KuiPage();
            shelf.id = SHELF;
            shelf.namespace = "koperlib";
            shelf.mode = "json";
            shelf.title = "Quest Books";
            shelf.w = 240;
            shelf.h = 180;
            shelf.layoutFile = shelfLayout.toAbsolutePath().toString();
            KuiBook.put(shelf);
        } catch (Exception broken) {
            KoperLib.LOGGER.warn("[Quest] couldn't bake the book page: {}", broken.getMessage());
        }
    }

    private static String layoutJson() {
        KuiJson j = new KuiJson();

        j.panel("bg", 0, 0, 320, 208, null);
        j.label("title", 0, 8, 320, "§6Quest Book", "center", null);
        j.rule("head", 10, 20, 300, 1, ACCENT);

        // left page
        j.panel("leftpage", 8, 26, 140, 174, PAGE_FACE);
        j.label("chapterlbl", 14, 30, 128, "§7chapters", "left", FADED);
        j.list("chapters", 14, 42, 128, 42);
        j.rule("split", 14, 88, 128, 1, 0xFF465164);
        j.label("questlbl", 14, 93, 128, "§7quests", "left", FADED);
        j.list("quests", 14, 105, 128, 70);

        // right page
        j.panel("rightpage", 156, 26, 156, 174, PAGE_FACE);
        j.item("qicon", 162, 32);
        j.label("qname", 184, 33, 122, "", "left", INK);
        j.label("qstate", 184, 44, 122, "", "left", FADED);
        j.rule("split2", 162, 56, 144, 1, 0xFF465164);

        int y = 62;
        for (int line = 0; line < DESC_LINES; line++) {
            j.label("desc" + line, 162, y, 144, "", "left", FADED);
            y += 10;
        }

        y += 2;
        for (int row = 0; row < ROWS; row++) {
            j.label("goal" + row, 162, y, 144, "", "left", INK);
            j.progress("bar" + row, 162, y + 10, 144, 3);
            y += 17;
        }

        j.rule("split3", 162, 176, 144, 1, 0xFF465164);
        j.button("start", 162, 180, 70, 16, "Start", 0xFF2F5D3A);
        j.button("refresh", 236, 180, 70, 16, "Refresh", null);
        j.button("shelf", 8, 180, 140, 16, "← Books", null);

        return j.done();
    }

    private static String shelfJson() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 240, 180, null);
        j.label("title", 0, 8, 240, "§6Quest Books", "center", null);
        j.rule("head", 10, 20, 220, 1, ACCENT);
        j.panel("body", 8, 26, 224, 122, PAGE_FACE);
        j.label("hint", 14, 31, 212, "§7one per mod, click to open", "left", FADED);
        j.list("books", 14, 42, 212, 100);
        j.label("say", 8, 152, 224, "", "left", FADED);
        j.button("close", 8, 160, 224, 16, "Close", null);
        return j.done();
    }

    // ── opening and driving ─────────────────────────────────────────────────

    // a book nobody can open anything in yet has no business on the shelf. a fresh player used to
    // see every book the packs ship, all of them 0/8 and all of them locked, which reads as a wall
    private static boolean opened(ServerPlayer player, String bookId) {
        for (KoperQuestData quest : QuestBook.all()) {
            if (!BookShelf.bookOf(quest).equals(bookId) || hiddenFrom(player, quest)) continue;
            if (!QuestChase.NONE.equals(QuestChase.status(player, quest.id))) return true;
            if (QuestChase.canStart(player, quest)) return true;
        }
        return false;
    }

    private static List<KoperBookData> shelfFor(ServerPlayer player) {
        List<KoperBookData> out = new ArrayList<>();
        for (KoperBookData book : BookShelf.inUse())
            if (opened(player, book.id)) out.add(book);
        // never hand back nothing; an empty shelf looks broken where a full one only looks busy
        return out.isEmpty() ? BookShelf.inUse() : out;
    }

    // one book goes straight in, several show the shelf first. nobody wants a menu to pick from one thing
    public static void open(ServerPlayer player) {
        if (KuiBook.get(BOOK) == null) bakeAndRegister();

        var books = shelfFor(player);
        if (books.size() <= 1) {
            OPEN_BOOK.put(player.getUUID(), books.isEmpty() ? BookShelf.LOOSE : books.getFirst().id);
            openBook(player);
            return;
        }
        openShelf(player);
    }

    public static void openShelf(ServerPlayer player) {
        if (KuiBook.get(SHELF) == null) bakeAndRegister();
        KuiOpen.open(player, SHELF);

        List<String> ids = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (var book : shelfFor(player)) {
            int total = 0, done = 0;
            for (KoperQuestData quest : QuestBook.all()) {
                if (!BookShelf.bookOf(quest).equals(book.id) || hiddenFrom(player, quest)) continue;
                total++;
                if (QuestChase.done(player, quest.id)) done++;
            }
            ids.add(book.id);
            rows.add((done == total && total > 0 ? "§a" : "§f") + cut(book.title, 24) + " §8" + done + "/" + total);
        }
        SHELF_ROWS.put(player.getUUID(), ids);
        KuiPoke.options(player, "books", rows);
        KuiPoke.text(player, "say", "§7" + ids.size() + " book(s)");
    }

    public static void openBook(ServerPlayer player) {
        if (KuiBook.get(BOOK) == null) bakeAndRegister();
        KuiOpen.open(player, BOOK);
        refresh(player);
    }

    public static void refresh(ServerPlayer player) {
        UUID who = player.getUUID();
        String bookId = OPEN_BOOK.computeIfAbsent(who, u -> {
            var books = BookShelf.inUse();
            return books.isEmpty() ? BookShelf.LOOSE : books.getFirst().id;
        });

        var book = BookShelf.get(bookId);
        KuiPoke.text(player, "title", "§6" + (book == null ? "Quest Book" : cut(book.title, 30)));

        Set<String> chapters = new LinkedHashSet<>();
        for (KoperQuestData quest : QuestBook.all()) {
            if (hiddenFrom(player, quest) || !BookShelf.bookOf(quest).equals(bookId)) continue;
            chapters.add(quest.category);
        }
        List<String> chapterIds = new ArrayList<>(chapters);
        CHAPTERS.put(who, chapterIds);

        String open = OPEN_CHAPTER.get(who);
        if (open == null || !chapterIds.contains(open)) {
            open = chapterIds.isEmpty() ? "" : chapterIds.getFirst();
            OPEN_CHAPTER.put(who, open);
        }

        List<String> chapterRows = new ArrayList<>();
        for (String chapter : chapterIds) {
            int total = 0, done = 0;
            for (KoperQuestData quest : QuestBook.all()) {
                if (!quest.category.equals(chapter) || hiddenFrom(player, quest)
                    || !BookShelf.bookOf(quest).equals(bookId)) continue;
                total++;
                if (QuestChase.done(player, quest.id)) done++;
            }
            boolean here = chapter.equals(open);
            chapterRows.add((here ? "§6" : "§7") + pretty(chapter) + " §8" + done + "/" + total);
        }
        KuiPoke.options(player, "chapters", chapterRows);

        List<String> ids = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (KoperQuestData quest : QuestBook.all()) {
            if (!quest.category.equals(open) || hiddenFrom(player, quest)
                || !BookShelf.bookOf(quest).equals(bookId)) continue;
            ids.add(quest.id);
            // whole name: the list shortens it to the row on its own and shows the rest on hover
            rows.add(mark(player, quest) + QuestChase.label(quest));
        }
        SHOWN.put(who, ids);
        KuiPoke.options(player, "quests", rows);

        String picked = PICKED.get(who);
        if (picked == null || !ids.contains(picked)) picked = ids.isEmpty() ? null : ids.getFirst();
        showDetail(player, picked);
    }

    private static boolean hiddenFrom(ServerPlayer player, KoperQuestData quest) {
        return quest.hidden && QuestChase.NONE.equals(QuestChase.status(player, quest.id));
    }

    private static String pretty(String raw) {
        if (raw == null || raw.isEmpty()) return "?";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1).replace('_', ' ');
    }

    private static String mark(ServerPlayer player, KoperQuestData quest) {
        String state = QuestChase.status(player, quest.id);
        if (QuestChase.DONE.equals(state)) return "§a✔ §7";
        if (QuestChase.ACTIVE.equals(state)) return "§e▶ §f";
        return QuestChase.canStart(player, quest) ? "§8○ §f" : "§8✖ §8";
    }

    private static void showDetail(ServerPlayer player, String questId) {
        if (questId == null) {
            KuiPoke.text(player, "qicon", "minecraft:air");
            KuiPoke.text(player, "qname", "§7nothing here yet");
            KuiPoke.text(player, "qstate", "");
            for (int line = 0; line < DESC_LINES; line++) KuiPoke.text(player, "desc" + line, "", "");
            blankRows(player, 0);
            return;
        }

        PICKED.put(player.getUUID(), questId);
        KoperQuestData quest = QuestBook.get(questId);
        if (quest == null) return;

        String state = QuestChase.status(player, questId);
        KuiPoke.text(player, "qicon", quest.icon);
        KuiPoke.text(player, "qname", QuestChase.label(quest));
        KuiPoke.text(player, "qstate", stateLine(player, quest, state));

        List<String> lines = wrap(quest.description, WRAP, Integer.MAX_VALUE);
        boolean more = lines.size() > DESC_LINES;
        String whole = more ? quest.description.trim() : "";
        for (int line = 0; line < DESC_LINES; line++) {
            String text = line < lines.size() ? lines.get(line) : "";
            if (more && line == DESC_LINES - 1) text += " ...";
            KuiPoke.text(player, "desc" + line, text.isEmpty() ? "" : "§7" + text, whole);
        }

        int row = 0;
        for (KoperQuestData.Goal goal : quest.goals) {
            if (row >= ROWS) break;
            int at = Math.min(QuestChase.progress(player, questId, goal.id), goal.count);
            boolean full = at >= goal.count;
            String goalText = QuestChase.goalText(goal);
            String shortText = cut(goalText, GOAL_CUT);
            KuiPoke.text(player, "goal" + row,
                (full ? "§a✔ " : "§f") + shortText
                    + " §8" + at + "/" + goal.count + (goal.optional ? " §8(opt)" : ""),
                shortText.equals(goalText) ? "" : goalText);
            KuiPoke.text(player, "bar" + row, Float.toString((float) at / goal.count));
            row++;
        }
        blankRows(player, row);
    }

    private static String stateLine(ServerPlayer player, KoperQuestData quest, String state) {
        if (QuestChase.DONE.equals(state)) return "§adone";
        if (QuestChase.ACTIVE.equals(state)) return "§ein progress";
        for (String needed : quest.requires) {
            if (QuestChase.done(player, needed)) continue;
            KoperQuestData before = QuestBook.get(needed);
            return "§8locked by §7" + cut(before == null ? needed : QuestChase.label(before), 18);
        }
        return "§7ready to start";
    }

    private static void blankRows(ServerPlayer player, int from) {
        for (int row = from; row < ROWS; row++) {
            KuiPoke.text(player, "goal" + row, "", "");
            KuiPoke.text(player, "bar" + row, "0");
        }
    }

    // kui clips on width, it does not wrap, so long descriptions have to be broken up here
    private static List<String> wrap(String text, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == maxLines) return out;
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty() && out.size() < maxLines) out.add(line.toString());
        return out;
    }

    // colour codes draw nothing, so they must not count against the width. "§6Name" was being
    // measured as two characters longer than it looks and losing its tail for no reason
    private static String cut(String s, int max) {
        if (s == null) return "";
        int seen = 0;
        int fits = s.length();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '§') { i++; continue; }
            seen++;
            if (seen == max) fits = i;
        }
        if (seen <= max) return s;
        // back up to the last whole word rather than leave half of one hanging
        String head = s.substring(0, fits);
        int space = head.lastIndexOf(' ');
        if (space > head.length() / 2) head = head.substring(0, space);
        return head.stripTrailing() + "…";
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    public static void register() {
        KuiPoke.on(BOOK, poke -> {
            ServerPlayer player = poke.player();
            UUID who = player.getUUID();

            if (poke.is("chapters") && "select".equals(poke.action())) {
                List<String> chapters = CHAPTERS.getOrDefault(who, List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < chapters.size()) {
                    OPEN_CHAPTER.put(who, chapters.get(index));
                    PICKED.remove(who);
                    refresh(player);
                }
                return true;
            }

            if (poke.is("quests") && "select".equals(poke.action())) {
                List<String> ids = SHOWN.getOrDefault(who, List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < ids.size()) showDetail(player, ids.get(index));
                return true;
            }

            if (poke.is("start") && "click".equals(poke.action())) {
                String picked = PICKED.get(who);
                if (picked != null && !QuestChase.start(player, picked))
                    player.sendSystemMessage(Component.literal("§7Not yet."), true);
                refresh(player);
                return true;
            }

            if (poke.is("refresh") && "click".equals(poke.action())) {
                refresh(player);
                return true;
            }

            if (poke.is("shelf") && "click".equals(poke.action())) {
                openShelf(player);
                return true;
            }
            return false;
        });

        KuiPoke.on(SHELF, poke -> {
            ServerPlayer player = poke.player();
            if (poke.is("books") && "select".equals(poke.action())) {
                List<String> ids = SHELF_ROWS.getOrDefault(player.getUUID(), List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < ids.size()) {
                    OPEN_BOOK.put(player.getUUID(), ids.get(index));
                    PICKED.remove(player.getUUID());
                    OPEN_CHAPTER.remove(player.getUUID());
                    openBook(player);
                }
                return true;
            }
            if (poke.is("close") && "click".equals(poke.action())) {
                com.koper.koper_lib.kui.KuiOpen.close(player);
                return true;
            }
            return false;
        });

        // goals move while the book is open. only a state change rebuilds the lists, everything
        // else redraws the right page, or the list would jump under the cursor while scrolling
        com.koper.koper_lib.scripting.KoperSnitch.listen("*", tattle -> {
            ServerPlayer player = tattle.who();
            if (!KuiPoke.looking(player, BOOK)) return;
            if (tattle.what().startsWith("quest:")) refresh(player);
            else showDetail(player, PICKED.get(player.getUUID()));
        });

        bakeAndRegister();
    }
}
