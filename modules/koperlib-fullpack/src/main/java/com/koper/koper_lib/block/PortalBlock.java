package com.koper.koper_lib.block;

import com.koper.koper_lib.scripting.UniversalScriptEngine;
import com.koper.koper_lib.scripting.ScriptEvent;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;

public class PortalBlock extends Block {
    private final String targetDimension;

    public PortalBlock(BlockBehaviour.Properties settings, String targetDimension) {
        super(settings);
        this.targetDimension = targetDimension;
    }

    // Removed @Override due to potential signature mismatch in 1.21.11 (old claude info idk why he wrote that we arent on 1.21.11?)
    public void onEntityCollision(BlockState state, Level Level, BlockPos pos, Entity entity) {
        if (!Level.isClientSide() && !entity.isPassenger() && !entity.isVehicle()) {
            if (entity.getPortalCooldown() <= 0) {
                Identifier dimId = Identifier.tryParse(targetDimension);
                if (dimId != null) {
                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimId);
                    ServerLevel targetWorld = ((ServerLevel) Level).getServer().getLevel(key);
                    if (targetWorld != null && Level.dimension() != key) {
                        net.minecraft.world.phys.Vec3 entityPos = new net.minecraft.world.phys.Vec3(entity.getX(), entity.getY(), entity.getZ());
                        net.minecraft.world.level.portal.TeleportTransition target = new net.minecraft.world.level.portal.TeleportTransition(
                            targetWorld, entityPos, entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING
                        );
                        entity.teleport(target);
                        entity.setPortalCooldown(80);
                    }
                }
            }
        }
    }
}
