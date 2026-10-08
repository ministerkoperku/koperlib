package com.koper.koper_lib.quest;

import com.koper.koper_lib.data.KoperBookData;
import com.koper.koper_lib.data.KoperQuestData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// every quest book that exists. a quest lands in one of three ways, in this order:
//   1. its own "book" field
//   2. the book that shares its namespace, so a mod's quests find that mod's book on their own
//   3. the catch-all, which only appears when something actually needs it
public final class BookShelf {
    public static final String LOOSE = "koperlib:loose";

    private static final Map<String, KoperBookData> BOOKS = new LinkedHashMap<>();

    private BookShelf() {}

    public static void put(KoperBookData book) {
        if (book != null && book.id != null) BOOKS.put(book.id, book);
    }

    public static KoperBookData get(String id) { return id == null ? null : BOOKS.get(id); }

    public static void clear() { BOOKS.clear(); }

    public static String bookOf(KoperQuestData quest) {
        if (quest == null) return LOOSE;
        if (quest.book != null && !quest.book.isBlank() && BOOKS.containsKey(quest.book)) return quest.book;

        String ns = quest.id == null ? "" : (quest.id.contains(":") ? quest.id.substring(0, quest.id.indexOf(':')) : "");
        for (KoperBookData book : BOOKS.values())
            if (book.namespace().equals(ns)) return book.id;

        return LOOSE;
    }

    // books that actually have something in them, plus the catch-all only if it is needed
    public static List<KoperBookData> inUse() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (KoperQuestData quest : QuestBook.all())
            counts.merge(bookOf(quest), 1, Integer::sum);

        List<KoperBookData> out = new ArrayList<>();
        for (KoperBookData book : BOOKS.values())
            if (counts.containsKey(book.id)) out.add(book);

        if (counts.containsKey(LOOSE)) {
            KoperBookData loose = new KoperBookData();
            loose.id = LOOSE;
            loose.title = "§7Everything else";
            loose.icon = "minecraft:book";
            loose.order = 999;
            out.add(loose);
        }

        out.sort(Comparator.comparingInt((KoperBookData b) -> b.order).thenComparing(b -> b.id));
        return out;
    }
}
