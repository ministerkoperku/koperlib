package com.koper.koper_lib.enchantment;

import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record KoperScriptEnchantmentEffect(String script, String event) implements EnchantmentEntityEffect {
    public static final com.mojang.serialization.MapCodec<KoperScriptEnchantmentEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> 
        instance.group(
            Codec.STRING.fieldOf("script").forGetter(KoperScriptEnchantmentEffect::script),
            Codec.STRING.optionalFieldOf("event", "on_hit").forGetter(KoperScriptEnchantmentEffect::event)
        ).apply(instance, KoperScriptEnchantmentEffect::new)
    );

    @Override
    public void apply(ServerLevel Level, int level, net.minecraft.world.item.enchantment.EnchantedItemInUse context, Entity target, Vec3 pos) {
        UniversalScriptEngine.call(script, scriptEvent(event), context.owner(), target, level);
    }

    @Override
    public com.mojang.serialization.MapCodec<? extends EnchantmentEntityEffect> codec() {
        return CODEC;
    }

    private static ScriptEvent scriptEvent(String event) {
        if (event == null) return ScriptEvent.ON_HIT;
        return switch (event.toLowerCase()) {
            case "tick", "on_tick" -> ScriptEvent.ON_TICK;
            case "damage", "on_damage" -> ScriptEvent.ON_DAMAGE;
            case "death", "on_death" -> ScriptEvent.ON_DEATH;
            case "use", "on_use" -> ScriptEvent.ON_USE;
            case "place", "on_place" -> ScriptEvent.ON_PLACE;
            case "break", "on_break" -> ScriptEvent.ON_BREAK;
            case "step", "on_step" -> ScriptEvent.ON_STEP;
            case "spawn", "on_spawn" -> ScriptEvent.ON_SPAWN;
            case "interact", "on_interact" -> ScriptEvent.ON_INTERACT;
            case "target", "on_target" -> ScriptEvent.ON_TARGET;
            case "equip", "on_equip" -> ScriptEvent.ON_EQUIP;
            case "unequip", "on_unequip" -> ScriptEvent.ON_UNEQUIP;
            case "consume", "on_consume" -> ScriptEvent.ON_CONSUME;
            case "craft", "on_craft" -> ScriptEvent.ON_CRAFT;
            default -> ScriptEvent.ON_HIT;
        };
    }
}
