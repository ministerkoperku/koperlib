package com.koper.koper_lib.factory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.KoperActions;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

// creates and registers custom status effects from JSON
public class StatusEffectFactory {

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) return;
        String idStr = json.get("id").getAsString();
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        boolean alreadyRegistered = BuiltInRegistries.MOB_EFFECT.containsKey(id);

        String catStr = json.has("category") ? json.get("category").getAsString().toLowerCase() : "neutral";
        MobEffectCategory category = switch (catStr) {
            case "beneficial", "good" -> MobEffectCategory.BENEFICIAL;
            case "harmful", "bad" -> MobEffectCategory.HARMFUL;
            default -> MobEffectCategory.NEUTRAL;
        };

        int color = json.has("color") ? json.get("color").getAsInt() : 0xFFFFFF;

        List<String> scripts = new ArrayList<>();
        if (json.has("scripts")) {
            json.getAsJsonArray("scripts").forEach(s -> scripts.add(s.getAsString()));
        }
        if (json.has("script")) scripts.add(json.get("script").getAsString());

        int tickInterval = json.has("tick_interval") ? Math.max(1, json.get("tick_interval").getAsInt()) : 20;
        JsonObject events = collectEvents(json);

        record AttrMod(Holder<Attribute> attr, AttributeModifier.Operation op, double amount) {}
        List<AttrMod> attrMods = new ArrayList<>();
        if (json.has("attribute_modifiers") && json.get("attribute_modifiers").isJsonArray()) {
            for (var elem : json.getAsJsonArray("attribute_modifiers")) {
                if (!elem.isJsonObject()) continue;
                var obj = elem.getAsJsonObject();
                if (!obj.has("attribute") || !obj.has("amount")) continue;
                String attrId = obj.get("attribute").getAsString();
                if (!attrId.contains(":")) attrId = "minecraft:" + attrId;
                if (attrId.startsWith("minecraft:generic.")) {
                    attrId = "minecraft:" + attrId.substring("minecraft:generic.".length());
                }
                double amount = obj.get("amount").getAsDouble();
                String opStr = obj.has("operation") ? obj.get("operation").getAsString() : "add_value";
                AttributeModifier.Operation op = switch (opStr.toLowerCase()) {
                    case "multiply_base", "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                    case "multiply_total", "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                    default -> AttributeModifier.Operation.ADD_VALUE;
                };
                Identifier attrIdent = Identifier.tryParse(attrId);
                if (attrIdent != null) {
                    BuiltInRegistries.ATTRIBUTE.getOptional(attrIdent).ifPresent(attr ->
                        attrMods.add(new AttrMod(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attr), op, amount)));
                }
            }
        }

        MobEffect effect = new MobEffect(category, color) {
            @Override
            public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
                return duration % tickInterval == 0;
            }

            @Override
            public boolean applyEffectTick(net.minecraft.server.level.ServerLevel Level,
                                           LivingEntity entity, int amplifier) {
                for (String script : scripts) {
                    UniversalScriptEngine.call(script, ScriptEvent.ON_TICK, entity, Level);
                }
                runEvent(events, "on_tick", entity, null, idStr);
                return true;
            }

            @Override
            public void onEffectStarted(LivingEntity mob, int amplifier) {
                runEvent(events, "on_started", mob, null, idStr);
            }

            @Override
            public void onEffectAdded(LivingEntity mob, int amplifier) {
                super.onEffectAdded(mob, amplifier);
                runEvent(events, "on_added", mob, null, idStr);
            }

            @Override
            public void onMobRemoved(ServerLevel level, LivingEntity mob, int amplifier, net.minecraft.world.entity.Entity.RemovalReason reason) {
                runEvent(events, "on_removed", mob, null, idStr);
            }

            @Override
            public void onMobHurt(ServerLevel level, LivingEntity mob, int amplifier, net.minecraft.world.damagesource.DamageSource source, float damage) {
                LivingEntity attacker = source.getEntity() instanceof LivingEntity living ? living : null;
                runEvent(events, "on_hurt", mob, attacker, idStr);
            }
        };

        for (var mod : attrMods) {
            effect.addAttributeModifier(mod.attr(), Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath()), mod.amount(), mod.op());
        }

        if (!alreadyRegistered) {
            Registry.register(BuiltInRegistries.MOB_EFFECT, id, effect);
        }

 
        String name = json.has("name") ? json.get("name").getAsString() :
                FactoryUtils.capitalizeWords(id.getPath());
        KoperLib.VIRTUAL_PACK.addTranslation("effect." + id.getNamespace() + "." + id.getPath(), name);

        KoperLib.LOGGER.info("StatusEffectFactory: Registered effect: " + idStr);
    }

    private static JsonObject collectEvents(JsonObject json) {
        JsonObject events = json.has("events") && json.get("events").isJsonObject()
            ? json.getAsJsonObject("events").deepCopy() : new JsonObject();
        for (String ev : new String[]{"on_tick", "on_added", "on_started", "on_removed", "on_hurt"}) {
            if (json.has(ev)) events.add(ev, json.get(ev).deepCopy());
        }
        if (json.has("kfx_on_tick")) {
            JsonObject action = new JsonObject();
            action.addProperty("kfx_at", json.get("kfx_on_tick").getAsString());
            events.add("on_tick", action);
        }
        return events;
    }

    private static void runEvent(JsonObject events, String event, LivingEntity entity, LivingEntity target, String ownerId) {
        if (events == null || !events.has(event) || entity == null || !(entity.level() instanceof ServerLevel)) return;
        JsonElement actions = events.get(event);
        KoperActions.run(actions,
            new KoperContext(entity instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null,
                entity, target, entity.level(), net.minecraft.world.item.ItemStack.EMPTY, entity.blockPosition()),
            event, ownerId);
    }
}
