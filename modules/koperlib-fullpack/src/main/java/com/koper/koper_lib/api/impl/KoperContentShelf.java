package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.ContentAPI;
import com.koper.koper_lib.factory.EntityFactory;
import com.koper.koper_lib.loader.UniversalLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

public class KoperContentShelf implements ContentAPI {

    private static final List<String> AI_GOALS = List.of(
        "swim", "attack_melee", "wander", "look_at_player", "look_around",
        "flee_player", "follow_player", "target_nearest_player", "revenge"
    );

    @Override
    public List<String> loadedIds() {
        return List.copyOf(UniversalLoader.getLoadedIds());
    }

    @Override
    public Optional<EntityType<?>> entityType(String fullId) {
        Identifier id = Identifier.tryParse(fullId);
        if (id == null) return Optional.empty();
        EntityType<?> t = EntityFactory.getRegisteredEntities().get(id);
        if (t != null) return Optional.of(t);
        t = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        return t != null ? Optional.of(t) : Optional.empty();
    }

    @Override
    public Optional<Block> block(String fullId) {
        Identifier id = Identifier.tryParse(fullId);
        if (id == null) return Optional.empty();
        Block b = BuiltInRegistries.BLOCK.getValue(id);
        return b != null ? Optional.of(b) : Optional.empty();
    }

    @Override
    public Optional<LivingEntity> spawnEntity(String fullId, ServerLevel level, BlockPos pos) {
        return spawnEntity(fullId, level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    @Override
    public Optional<LivingEntity> spawnEntity(String fullId, ServerLevel level, double x, double y, double z) {
        return entityType(fullId).flatMap(type -> {
            var entity = type.create(level, null, BlockPos.containing(x, y, z), EntitySpawnReason.COMMAND, false, false);
            if (!(entity instanceof LivingEntity spawned)) return Optional.empty();
            spawned.setPos(x, y, z);
            if (!level.addFreshEntity(spawned)) return Optional.empty();
            return Optional.of(spawned);
        });
    }

    @Override
    public List<String> knownEntityAiGoals() {
        return AI_GOALS;
    }
}
