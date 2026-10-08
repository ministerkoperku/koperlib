package com.koper.koper_lib.factory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.resources.Identifier;

// creates and registers custom sounds from JSON; writes sounds.json into the virtual resource pack
public class SoundFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) return;
        String idStr = json.get("id").getAsString();
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        boolean alreadyRegistered = BuiltInRegistries.SOUND_EVENT.containsKey(id);

        if (!alreadyRegistered) {
            SoundEvent event = SoundEvent.createVariableRangeEvent(id);
            Registry.register(BuiltInRegistries.SOUND_EVENT, id, event);
            KoperLib.LOGGER.info("SoundFactory: Registered sound event: {}", idStr);
        }

        String soundKey = id.getPath(); 

        JsonObject soundEntry = new JsonObject();

        if (json.has("sounds") && json.get("sounds").isJsonArray()) {
            JsonArray soundsArray = json.getAsJsonArray("sounds");
            // Rebuild with normalized names
            JsonArray normalized = new JsonArray();
            for (var elem : soundsArray) {
                if (elem.isJsonObject()) {
                    normalized.add(elem.getAsJsonObject());
                } else if (elem.isJsonPrimitive()) {
                    // bare string: just the name
                    JsonObject s = new JsonObject();
                    s.addProperty("name", elem.getAsString());
                    normalized.add(s);
                }
            }
            soundEntry.add("sounds", normalized);
        } else {
            JsonArray defaultSounds = new JsonArray();
            JsonObject defaultEntry = new JsonObject();
            defaultEntry.addProperty("name", id.getNamespace() + ":sounds/" + soundKey);
            defaultEntry.addProperty("stream", false);
            defaultSounds.add(defaultEntry);
            soundEntry.add("sounds", defaultSounds);
        }

        if (json.has("subtitle")) {
            soundEntry.addProperty("subtitle", json.get("subtitle").getAsString());
            // Register subtitle translation
            KoperLib.VIRTUAL_PACK.addTranslation(
                "subtitles." + id.getNamespace() + "." + soundKey,
                json.get("subtitle").getAsString()
            );
        }

        KoperLib.VIRTUAL_PACK.mergeSoundsJson(id.getNamespace(), soundKey, soundEntry);
    }
}
