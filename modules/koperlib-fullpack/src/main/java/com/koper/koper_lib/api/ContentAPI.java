package com.koper.koper_lib.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

// low-level registry-ish access — when you need EntityType not KoperEntityRef
public interface ContentAPI {

    List<String> loadedIds();

    Optional<EntityType<?>> entityType(String fullId);
    Optional<Block> block(String fullId);

    Optional<LivingEntity> spawnEntity(String fullId, ServerLevel level, BlockPos pos);
    Optional<LivingEntity> spawnEntity(String fullId, ServerLevel level, double x, double y, double z);

    List<String> knownEntityAiGoals();
}
