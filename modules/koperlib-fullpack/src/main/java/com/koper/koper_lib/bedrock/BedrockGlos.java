package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;

// the voice of a mob from a bedrock pack: what BedrockTlumacz.glosy put in the sidecar, asked by
// KoperMobEntity's getAmbientSound / getHurtSound / getDeathSound / playStepSound. server side only,
// the server sends the sound to everyone like vanilla mobs do
public final class BedrockGlos {

    private BedrockGlos() {}

    private static JsonObject wpis(Entity e, String ev) {
        if (e.level().isClientSide() || BedrockSkrypciarz.VOICES.isEmpty()) return null;
        String typ = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        JsonElement g = BedrockSkrypciarz.VOICES.get(typ);
        JsonObject one = g != null && g.isJsonObject() ? BedrockTlumacz.obj(g.getAsJsonObject(), ev) : null;
        if (one == null) {
            JsonElement all = BedrockSkrypciarz.VOICES.get("*");
            one = all != null && all.isJsonObject() ? BedrockTlumacz.obj(all.getAsJsonObject(), ev) : null;
        }
        return one;
    }

    // the sound for this event, null = let java decide (usually silence for a custom mob)
    public static SoundEvent dzwiek(Entity e, String ev) {
        JsonObject one = wpis(e, ev);
        if (one == null || !one.has("s")) return null;
        Identifier id = Identifier.tryParse(one.get("s").getAsString());
        return id == null ? null : SoundEvent.createVariableRangeEvent(id);
    }

    public static float glosnosc(Entity e, String ev, float def) {
        JsonObject one = wpis(e, ev);
        return one != null && one.has("v") && one.get("v").isJsonPrimitive() ? one.get("v").getAsFloat() : def;
    }

    // bedrock's pitch is a number or a [min, max] range, a new pick every time
    public static float ton(Entity e, String ev, float def) {
        JsonObject one = wpis(e, ev);
        if (one == null || !one.has("p")) return def;
        JsonElement p = one.get("p");
        if (p.isJsonPrimitive()) return p.getAsFloat();
        if (p.isJsonArray() && p.getAsJsonArray().size() == 2) {
            float lo = p.getAsJsonArray().get(0).getAsFloat(), hi = p.getAsJsonArray().get(1).getAsFloat();
            return lo + e.getRandom().nextFloat() * (hi - lo);
        }
        return def;
    }
}
