package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;

// creates and registers custom entity attributes from JSON
public class AttributeFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) return;
        String idStr = json.get("id").getAsString();
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;
        boolean alreadyRegistered = BuiltInRegistries.ATTRIBUTE.containsKey(id);

        double defaultValue = json.has("default_value") ? json.get("default_value").getAsDouble() : 0.0;
        double min = json.has("min") ? json.get("min").getAsDouble() : 0.0;
        double max = json.has("max") ? json.get("max").getAsDouble() : 1024.0;

        String translationKey = "attribute.name." + id.getNamespace() + "." + id.getPath();

        if (!alreadyRegistered) {
            Attribute attribute = new RangedAttribute(translationKey, defaultValue, min, max);
            Registry.register(BuiltInRegistries.ATTRIBUTE, id, attribute);
        }

        // Add translation  always regenerate for hotreload
        String name = json.has("name") ? json.get("name").getAsString() :
                FactoryUtils.capitalizeWords(id.getPath());
        KoperLib.VIRTUAL_PACK.addTranslation(translationKey, name);

        KoperLib.LOGGER.info("AttributeFactory: Registered attribute: " + idStr);
    }
}
