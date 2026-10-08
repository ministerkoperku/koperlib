package com.koper.koper_lib.mixin;

import com.koper.koper_lib.scripting.KoperSnitch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// fabric has no "block was placed" event, and USE_ITEM_ON fires whether or not anything landed.
// taking it here means a failed placement doesn't count
@Mixin(BlockItem.class)
public abstract class KoperSnitchPlaceMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void koperlib$snitchPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) return;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return;

        var key = BuiltInRegistries.BLOCK.getKey(((BlockItem) (Object) this).getBlock());
        if (key == null) return;

        var pos = context.getClickedPos();
        KoperSnitch.snitch(player, KoperSnitch.PLACE, "id", key.toString(),
            "x", String.valueOf(pos.getX()), "y", String.valueOf(pos.getY()), "z", String.valueOf(pos.getZ()));
    }
}
