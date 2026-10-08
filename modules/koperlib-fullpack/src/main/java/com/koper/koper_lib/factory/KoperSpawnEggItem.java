package com.koper.koper_lib.factory;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class KoperSpawnEggItem extends Item {
    private final EntityType<?> entityType;

    public KoperSpawnEggItem(Item.Properties settings, EntityType<?> entityType) {
        super(settings);
        this.entityType = entityType;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level Level = context.getLevel();
        if (Level.isClientSide()) return InteractionResult.SUCCESS;

        ServerLevel ServerLevel = (ServerLevel) Level;
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());

        Entity entity = entityType.create(ServerLevel, EntitySpawnReason.COMMAND);
        if (entity != null) {
            entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            entity.setYRot(Level.getRandom().nextFloat() * 360.0f);
            entity.setXRot(0.0f);
            ServerLevel.addFreshEntity(entity);
            context.getItemInHand().consume(1, context.getPlayer());
        }

        return InteractionResult.SUCCESS;
    }

    public EntityType<?> getEntityType() {
        return entityType;
    }
}
