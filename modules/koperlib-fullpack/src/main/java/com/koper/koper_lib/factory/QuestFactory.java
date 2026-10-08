package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperQuestData;
import com.koper.koper_lib.quest.QuestBook;

// quests/<name>.json -> QuestBook. nothing hits a vanilla registry here, a quest is pure data,
// which is why it survives a reload without a restart
public class QuestFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) {
            KoperLib.LOGGER.warn("QuestFactory: quest without an id, skipped");
            return;
        }

        KoperQuestData quest = new KoperQuestData();
        quest.applyJson(json);

        if (quest.goals.isEmpty())
            KoperLib.LOGGER.warn("QuestFactory: '{}' has no goals, it can only be finished by hand", quest.id);

        for (KoperQuestData.Goal goal : quest.goals) {
            if (!KNOWN_GOALS.contains(goal.type))
                KoperLib.LOGGER.warn("QuestFactory: '{}' wants goal type '{}' which nothing reports. have: {}",
                    quest.id, goal.type, String.join(", ", KNOWN_GOALS));
        }

        QuestBook.put(quest);
        KoperLib.LOGGER.info("QuestFactory: Registered quest: {}", quest.id);
    }

    // keep this honest with what QuestChase actually listens to
    private static final java.util.Set<String> KNOWN_GOALS = java.util.Set.of(
        "kill", "craft", "get", "have", "advancement", "dimension",
        "break", "place", "talk", "die", "dialog", "script");
}
