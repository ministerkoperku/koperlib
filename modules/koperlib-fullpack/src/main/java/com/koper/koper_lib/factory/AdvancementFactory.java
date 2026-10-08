package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import net.minecraft.resources.Identifier;

// converts koperlib advancement JSON to vanilla format and injects via VirtualResourcePack fuck ton of work done works Easy ai helped like aways 
public class AdvancementFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) return;
        String idStr = json.get("id").getAsString();
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        JsonObject advancement = new JsonObject();

       
        JsonObject display = new JsonObject();

        
        String iconItem = json.has("icon") ? json.get("icon").getAsString() : "minecraft:book";
        JsonObject iconObj = new JsonObject();
        iconObj.addProperty("id", iconItem);
        display.add("icon", iconObj);

        
        String title = json.has("title") ? json.get("title").getAsString() : id.getPath();
        String desc = json.has("description") ? json.get("description").getAsString() : "";

        JsonObject titleObj = new JsonObject();
        titleObj.addProperty("translate", "advancements." + id.getNamespace() + "." + id.getPath() + ".title");
        display.add("title", titleObj);

        JsonObject descObj = new JsonObject();
        descObj.addProperty("translate", "advancements." + id.getNamespace() + "." + id.getPath() + ".description");
        display.add("description", descObj);

       
        KoperLib.VIRTUAL_PACK.addTranslation(
            "advancements." + id.getNamespace() + "." + id.getPath() + ".title", title);
        KoperLib.VIRTUAL_PACK.addTranslation(
            "advancements." + id.getNamespace() + "." + id.getPath() + ".description", desc);

        if (json.has("frame")) display.addProperty("frame", json.get("frame").getAsString());
        else display.addProperty("frame", "task");

        // 26.3: a visible root must name a background, anything with a parent must not
        if (!json.has("parent")) {
            display.addProperty("background", json.has("background")
                ? json.get("background").getAsString() : "minecraft:gui/advancements/backgrounds/stone");
        } else if (json.has("background")) {
            KoperLib.LOGGER.error("AdvancementFactory: '{}' has a parent, 26.3 only allows a background on roots; background dropped", id);
        }

        display.addProperty("show_toast", true);
        display.addProperty("announce_to_chat", true);
        advancement.add("display", display);

        
        if (json.has("parent")) {
            advancement.addProperty("parent", json.get("parent").getAsString());
        }

      
        JsonObject criteria = new JsonObject();
        if (json.has("criteria") && json.get("criteria").isJsonArray()) {
            int idx = 0;
            for (var elem : json.getAsJsonArray("criteria")) {
                if (!elem.isJsonObject()) continue;
                JsonObject src = elem.getAsJsonObject();
                String trigger = src.has("trigger") ? src.get("trigger").getAsString() : "minecraft:impossible";
                JsonObject criterion = new JsonObject();
                criterion.addProperty("trigger", trigger);

                JsonObject conditions = buildConditions(id.toString(), trigger, src);
                if (conditions.size() > 0) criterion.add("conditions", conditions);

                criteria.add("criterion_" + (idx++), criterion);
            }
        }
        if (criteria.size() == 0) {
          
            JsonObject impossible = new JsonObject();
            impossible.addProperty("trigger", "minecraft:impossible");
            criteria.add("criterion_0", impossible);
        }
        advancement.add("criteria", criteria);

        
        JsonArray requirements = new JsonArray();
        for (String key : criteria.keySet()) {
            JsonArray req = new JsonArray();
            req.add(key);
            requirements.add(req);
        }
        advancement.add("requirements", requirements);

        
        if (json.has("rewards") && json.get("rewards").isJsonObject()) {
            advancement.add("rewards", json.getAsJsonObject("rewards"));
        }

        
        KoperLib.VIRTUAL_PACK.addServerAsset(
            Identifier.fromNamespaceAndPath(id.getNamespace(), "advancement/" + id.getPath() + ".json"),
            advancement.toString()
        );

        KoperLib.LOGGER.info("AdvancementFactory: Registered advancement: {}", idStr);
    }

    private static JsonObject buildConditions(String id, String trigger, JsonObject src) {
        JsonObject cond = new JsonObject();
        switch (trigger) {
            case "minecraft:inventory_changed" -> {
                if (src.has("items") && src.get("items").isJsonArray()) {
                    JsonArray slots = new JsonArray();
                    for (var item : src.getAsJsonArray("items")) {
                        JsonObject itemCond = new JsonObject();
                        itemCond.addProperty("items", item.getAsString());
                        slots.add(itemCond);
                    }
                    cond.add("slots", new JsonObject()); // any slot
                    JsonArray itemsArr = new JsonArray();
                    for (var item : src.getAsJsonArray("items")) {
                        JsonObject itemObj = new JsonObject();
                        JsonArray itemsTag = new JsonArray();
                        itemsTag.add(item.getAsString());
                        itemObj.add("items", itemsTag);
                        itemsArr.add(itemObj);
                    }
                    cond.add("items", itemsArr);
                }
            }
            case "minecraft:entity_killed_player", "minecraft:player_killed_entity" -> {
                if (src.has("entity")) {
                    // 26.3 dropped the entity predicate shorthand, the check is an explicit entity_properties
                    JsonObject predicate = new JsonObject();
                    predicate.addProperty("type", src.get("entity").getAsString());
                    JsonObject entity = new JsonObject();
                    entity.addProperty("type", "minecraft:entity_properties");
                    entity.addProperty("entity", "this");
                    entity.add("predicate", predicate);
                    cond.add("entity", entity);
                }
            }
            case "minecraft:location" -> {
                if (src.has("biome")) {
                    JsonObject location = new JsonObject();
                    location.addProperty("biome", src.get("biome").getAsString());
                    cond.add("location", location);
                }
            }
            default -> {
                // Pass through any additional conditions verbatim
                for (String key : src.keySet()) {
                    if (!key.equals("trigger")) cond.add(key, src.get(key));
                }
                com.koper.koper_lib.loader.Stary263Wykrywacz.check("advancement " + id + " criterion " + trigger, cond);
            }
        }
        return cond;
    }
}
