package com.koper.koper_lib.data;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public class KoperEnchantmentData {
    public String id;
    public String name;
    public int maxLevel = 1;
    public int minLevel = 1;
    public String script;
    public List<String> scripts = new ArrayList<>();
    // New fields for proper MC 1.21.11 enchantment data generation
    public String slot = "all";        // "weapon", "armor", "tool", "all" 
    public int weight = 4;
    public int anvilCost = 1;
    public boolean isCurse = false;
    public JsonObject effects;

    public void applyJson(JsonObject json) {
        if (json.has("id")) id = json.get("id").getAsString();
        if (json.has("name")) name = json.get("name").getAsString();
        if (json.has("max_level")) maxLevel = json.get("max_level").getAsInt();
        if (json.has("min_level")) minLevel = json.get("min_level").getAsInt();
        if (json.has("script")) script = json.get("script").getAsString();
        if (json.has("scripts")) {
            json.get("scripts").getAsJsonArray().forEach(e -> scripts.add(e.getAsString()));
        }
        if (json.has("slot")) slot = json.get("slot").getAsString();
        if (json.has("weight")) weight = json.get("weight").getAsInt();
        if (json.has("anvil_cost")) anvilCost = json.get("anvil_cost").getAsInt();
        if (json.has("is_curse")) isCurse = json.get("is_curse").getAsBoolean();
        if (json.has("effects") && json.get("effects").isJsonObject()) {
            effects = json.getAsJsonObject("effects").deepCopy();
        }
    }
}
// we were on 1.21.11 nothing much changed we are now on around 26.2 written on xD 11.05.2026
