package com.koper.koper_lib.fullpack;

import com.koper.koper_lib.data.KoperDimensionData;
import com.koper.koper_lib.factory.DimensionFactory;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.function.Function;

// "entity" in a fullpack dimension json: gravity, jump, fall damage and safe fall distance of every
// living entity in that dimension, as transient attribute modifiers. they are put on when an entity
// enters or loads in the dimension and taken off when it is anywhere else, so nothing is saved
public final class DimensionEntityRules {
    private DimensionEntityRules() {}

    private static final Identifier MODIFIER = Identifier.fromNamespaceAndPath("koperlib", "dimension_entity_rules");

    private record Rule(Holder<Attribute> attribute, Function<KoperDimensionData, Double> value) {}

    private static final Rule[] RULES = {
        new Rule(Attributes.GRAVITY, d -> d.entityGravity),
        new Rule(Attributes.JUMP_STRENGTH, d -> d.entityJump),
        new Rule(Attributes.FALL_DAMAGE_MULTIPLIER, d -> d.entityFallDamage),
        new Rule(Attributes.SAFE_FALL_DISTANCE, d -> d.entitySafeFall),
    };

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(DimensionEntityRules::apply);
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
            (player, from, to) -> apply(player, to));
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register(
            (original, moved, from, to) -> apply(moved, to));
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
            (old, fresh, alive) -> apply(fresh, fresh.level()));
    }

    /** after a pack reload the numbers may have changed for entities that never moved */
    public static void applyAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels())
            for (Entity entity : level.getAllEntities()) apply(entity, level);
    }

    public static void apply(Entity entity, ServerLevel level) {
        if (!(entity instanceof LivingEntity living)) return;
        KoperDimensionData data = DimensionFactory.getDimensionData().get(level.dimension().identifier());
        for (Rule rule : RULES) {
            var instance = living.getAttribute(rule.attribute());
            if (instance == null) continue;
            instance.removeModifier(MODIFIER);
            Double multiplier = data == null ? null : rule.value().apply(data);
            if (multiplier != null && multiplier != 1.0)
                instance.addTransientModifier(new AttributeModifier(MODIFIER, multiplier - 1.0,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
}
