package com.koper.koper_lib.quest;

import com.koper.koper_lib.data.KoperQuestData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// every quest that exists right now. filled by QuestFactory on load, wiped on reload phase 0,
// same deal as KuiBook
public final class QuestBook {
    private static final Map<String, KoperQuestData> QUESTS = new LinkedHashMap<>();

    private QuestBook() {}

    public static void put(KoperQuestData quest) {
        if (quest != null && quest.id != null) QUESTS.put(quest.id, quest);
    }

    public static KoperQuestData get(String id) { return id == null ? null : QUESTS.get(id); }

    public static Collection<KoperQuestData> all() { return QUESTS.values(); }

    public static Set<String> ids() { return QUESTS.keySet(); }

    public static void clear() { QUESTS.clear(); }
}
