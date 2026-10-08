package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import net.minecraft.resources.Identifier;

import static com.koper.koper_lib.factory.FactoryUtils.normalize;

// converts koperlib loot_table JSON to vanilla loot table JSON and injects via VirtualResourcePack bla bla
public class LootTableFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) {
            KoperLib.LOGGER.warn("LootTableFactory: missing 'id', skipping.");
            return;
        }

        String rawId = json.get("id").getAsString();
        Identifier id = Identifier.tryParse(rawId);
        if (id == null) {
            KoperLib.LOGGER.warn("LootTableFactory: invalid id '{}', skipping.", rawId);
            return;
        }

        if (!json.has("pools")) {
            KoperLib.LOGGER.warn("LootTableFactory: '{}' has no 'pools', skipping.", rawId);
            return;
        }

        try {
            String mcLootJson = buildMcLootTable(json);
            Identifier assetId = Identifier.fromNamespaceAndPath(id.getNamespace(), "loot_table/" + id.getPath() + ".json");
            KoperLib.VIRTUAL_PACK.addServerAsset(assetId, mcLootJson);

            if (json.has("entity")) {
                String entityId = json.get("entity").getAsString();
                Identifier eId = Identifier.tryParse(entityId);
                if (eId != null) {
                    Identifier entityLootId = Identifier.fromNamespaceAndPath(eId.getNamespace(),
                        "loot_table/entities/" + eId.getPath() + ".json");
                    KoperLib.VIRTUAL_PACK.addServerAsset(entityLootId, mcLootJson);
                }
            }

            if (json.has("script")) {
                String scriptId = json.get("script").getAsString();
                com.koper.koper_lib.scripting.UniversalScriptEngine.loadScript(scriptId);
            }

            KoperLib.LOGGER.info("LootTableFactory: Registered loot table: {}", rawId);
        } catch (Exception e) {
            KoperLib.LOGGER.warn("LootTableFactory: Failed to build '{}': {}", rawId, e.getMessage());
        }
    }

    private static String buildMcLootTable(JsonObject json) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "minecraft:entity");

        JsonArray mcPools = new JsonArray();
        for (JsonElement poolEl : json.getAsJsonArray("pools")) {
            JsonObject pool = poolEl.getAsJsonObject();
            JsonObject mcPool = new JsonObject();

            if (pool.has("rolls")) {
                JsonElement rolls = pool.get("rolls");
                if (rolls.isJsonPrimitive()) {
                    mcPool.addProperty("rolls", rolls.getAsInt());
                } else {
                    JsonObject r = rolls.getAsJsonObject();
                    // 26.3 rolls are an int provider; 26.2 rounded the float roll the same way
                    JsonObject mcRolls = new JsonObject();
                    mcRolls.addProperty("type", "minecraft:uniform");
                    mcRolls.addProperty("min", Math.round(r.has("min") ? r.get("min").getAsFloat() : 1f));
                    mcRolls.addProperty("max", Math.round(r.has("max") ? r.get("max").getAsFloat() : 1f));
                    mcPool.add("rolls", mcRolls);
                }
            } else {
                mcPool.addProperty("rolls", 1);
            }
            if (pool.has("condition")) {
                mcPool.add("condition", buildCondition(pool.get("condition").getAsString()));
            }
            JsonArray mcEntries = new JsonArray();
            if (pool.has("entries")) {
                for (JsonElement entEl : pool.getAsJsonArray("entries")) {
                    JsonObject ent = entEl.getAsJsonObject();
                    JsonObject mcEntry = new JsonObject();
                    mcEntry.addProperty("type", "minecraft:item");
                    mcEntry.addProperty("name", normalize(ent.get("item").getAsString()));
                    if (ent.has("weight")) mcEntry.addProperty("weight", ent.get("weight").getAsInt());

                    // count function
                    if (ent.has("count")) {
                        JsonObject countFunc = new JsonObject();
                        countFunc.addProperty("type", "minecraft:set_count");
                        JsonElement countEl = ent.get("count");
                        if (countEl.isJsonPrimitive()) {
                            countFunc.addProperty("count", countEl.getAsInt());
                        } else {
                            JsonObject c = countEl.getAsJsonObject();
                            JsonObject countVal = new JsonObject();
                            countVal.addProperty("type", "minecraft:uniform");
                            countVal.addProperty("min", Math.round(c.has("min") ? c.get("min").getAsFloat() : 1f));
                            countVal.addProperty("max", Math.round(c.has("max") ? c.get("max").getAsFloat() : 1f));
                            countFunc.add("count", countVal);
                        }
                        mcEntry.add("modifier", countFunc);
                    }

                    if (ent.has("condition")) {
                        mcEntry.add("condition", buildCondition(ent.get("condition").getAsString()));
                    }

                    mcEntries.add(mcEntry);
                }
            }
            mcPool.add("entries", mcEntries);
            mcPools.add(mcPool);
        }

        out.add("pools", mcPools);
        return out.toString();
    }

    // 26.3 loot: one condition object, keyed by "type"
    private static JsonObject buildCondition(String condition) {
        JsonObject cond = new JsonObject();
        switch (condition.toLowerCase()) {
            case "killed_by_player" ->
                cond.addProperty("type", "minecraft:killed_by_player");
            case "survives_explosion" ->
                cond.addProperty("type", "minecraft:survives_explosion");
            default -> {
                KoperLib.LOGGER.error("LootTableFactory: unknown loot condition '{}', it is IGNORED (always passes). "
                    + "Known: killed_by_player, survives_explosion", condition);
                cond.addProperty("type", "minecraft:random_chance");
                cond.addProperty("chance", 1.0f);
            }
        }
        return cond;
    }
}
