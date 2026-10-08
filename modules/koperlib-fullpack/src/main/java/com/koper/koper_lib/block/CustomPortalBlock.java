package com.koper.koper_lib.block;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.Level;

public class CustomPortalBlock extends Block {
    private final String targetDimensionId;

    public CustomPortalBlock(BlockBehaviour.Properties settings, String targetDimensionId) {
        super(settings.noCollision().lightLevel(state -> 11));
        this.targetDimensionId = targetDimensionId;
    }

    public void onEntityCollision(BlockState state, Level Level, BlockPos pos, Entity entity) {
        if (!Level.isClientSide() && !entity.isPassenger() && !entity.isVehicle()) {
            if (entity.getPortalCooldown() <= 0) {
                Identifier dimId = Identifier.tryParse(targetDimensionId);
                if (dimId == null) return;
                
                ResourceKey<Level> targetKey = ResourceKey.create(Registries.DIMENSION, dimId);
                ServerLevel targetWorld = ((ServerLevel) Level).getServer().getLevel(targetKey);
                
                if (targetWorld != null && Level.dimension() != targetKey) {
                    BlockPos dest = new BlockPos((int)entity.getX(), 100, (int)entity.getZ());
                    
                    // Platform generation (i copied it from gamini i fucked something up now it works :>>>)
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            targetWorld.setBlockAndUpdate(dest.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
                            for (int y = 0; y < 3; y++) {
                                targetWorld.setBlockAndUpdate(dest.offset(dx, y, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    
                    TeleportTransition target = new TeleportTransition(
                        targetWorld, new Vec3(dest.getX()+0.5, dest.getY(), dest.getZ()+0.5), entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING
                    );
                    entity.teleport(target);
                    entity.setPortalCooldown(80); 
                }
            }
        }
    }
}
