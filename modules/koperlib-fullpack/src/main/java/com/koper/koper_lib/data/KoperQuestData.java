package com.koper.koper_lib.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

// quests/<name>.json. goals only cover things the snitch can actually report, so there is no
// goal type here that silently never completes
public class KoperQuestData {
    public String id;
    public String name = "";
    public String description = "";
    public String icon = "minecraft:book";
    public String category = "main";    // book chapter. "side" is just another category, nothing special
    public String book = "";            // which book it belongs to. blank means work it out from the namespace

    public List<String> requires = new ArrayList<>();
    public boolean repeatable = false;
    public boolean autoStart = false;   // starts itself the moment requires is satisfied
    public boolean hidden = false;      // stays out of the book until it starts

    public List<Goal> goals = new ArrayList<>();

    // koperlib action lists, same language items and blocks use
    public JsonElement onStart;
    public JsonElement onComplete;

    // who hands this out. entity id, #entity_tag, or tag:<scoreboard tag> for one specific mob
    public String giver = "";

    public static class Goal {
        public String id = "";
        public String type = "kill";
        public String target = "";
        public int count = 1;
        public String text = "";
        public boolean optional = false;
        public JsonElement onDone;   // actions fired when this one goal fills, not the whole quest
    }

    public void applyJson(JsonObject json) {
        if (json.has("id")) id = json.get("id").getAsString();
        if (json.has("name")) name = json.get("name").getAsString();
        if (json.has("description")) description = json.get("description").getAsString();
        if (json.has("icon")) icon = json.get("icon").getAsString();
        if (json.has("category")) category = json.get("category").getAsString().trim().toLowerCase();
        if (category.isBlank()) category = "main";
        if (json.has("book")) book = json.get("book").getAsString().trim();
        if (json.has("giver")) giver = json.get("giver").getAsString().trim();
        if (json.has("repeatable")) repeatable = json.get("repeatable").getAsBoolean();
        if (json.has("auto_start")) autoStart = json.get("auto_start").getAsBoolean();
        if (json.has("hidden")) hidden = json.get("hidden").getAsBoolean();

        if (json.has("requires")) {
            JsonElement req = json.get("requires");
            if (req.isJsonPrimitive()) requires.add(req.getAsString());
            else if (req.isJsonArray()) req.getAsJsonArray().forEach(e -> requires.add(e.getAsString()));
        }

        if (json.has("goals")) {
            int nth = 0;
            for (JsonElement raw : json.getAsJsonArray("goals")) {
                if (!raw.isJsonObject()) continue;
                JsonObject g = raw.getAsJsonObject();
                Goal goal = new Goal();
                goal.type = g.has("type") ? g.get("type").getAsString().toLowerCase() : "kill";
                goal.target = g.has("target") ? g.get("target").getAsString() : "";
                goal.count = g.has("count") ? g.get("count").getAsInt() : 1;
                goal.text = g.has("text") ? g.get("text").getAsString() : "";
                goal.optional = g.has("optional") && g.get("optional").getAsBoolean();
                if (g.has("on_done")) goal.onDone = g.get("on_done");
                // an author who never names a goal still gets stable keys, as long as they don't reorder
                goal.id = g.has("id") ? g.get("id").getAsString() : (goal.type + nth);
                if (goal.count < 1) goal.count = 1;
                goals.add(goal);
                nth++;
            }
        }

        if (json.has("on_start")) onStart = json.get("on_start");
        if (json.has("on_complete")) onComplete = json.get("on_complete");
    }
}
