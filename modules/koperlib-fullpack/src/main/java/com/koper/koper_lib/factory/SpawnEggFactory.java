package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.ContentRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

public final class SpawnEggFactory {
    private SpawnEggFactory() {}

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id") || !json.has("entity")) {
            KoperLib.LOGGER.warn("[SpawnEggFactory] spawn egg needs id + entity");
            return;
        }
        Identifier id = Identifier.tryParse(json.get("id").getAsString());
        Identifier entityId = Identifier.tryParse(json.get("entity").getAsString());
        if (id == null || entityId == null) return;

        var entityType = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);
        if (entityType == null) {
            KoperLib.LOGGER.warn("[SpawnEggFactory] unknown entity for {}: {}", id, entityId);
            return;
        }

        Item.Properties props = ContentRegistry.createItemSettings(id.toString());
        if (json.has("max_stack")) props = props.stacksTo(json.get("max_stack").getAsInt());
        Item egg = new KoperSpawnEggItem(props, entityType);
        ContentRegistry.registerItem(id.toString(), egg, json.has("creative_tab") ? json.get("creative_tab").getAsString() : null);

        String texture = json.has("texture") ? json.get("texture").getAsString() : id.getNamespace() + ":item/" + id.getPath();
        KoperLib.VIRTUAL_PACK.addPresetModel(id, texture, "item");
        KoperLib.VIRTUAL_PACK.addTranslation("item." + id.getNamespace() + "." + id.getPath(),
            json.has("name") ? json.get("name").getAsString() : FactoryUtils.capitalizeWords(id.getPath()));
        KoperLib.LOGGER.info("SpawnEggFactory: Registered spawn egg {} -> {}", id, entityId);
    }
}
