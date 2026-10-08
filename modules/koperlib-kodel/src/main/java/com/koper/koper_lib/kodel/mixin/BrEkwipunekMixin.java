package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the paperdoll in the inventory: bedrock packs get q.is_in_ui / v.is_paperdoll and their own actor
@Mixin(InventoryScreen.class)
public abstract class BrEkwipunekMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("HEAD"))
    private static void koperlib$uiOn(LivingEntity e, CallbackInfoReturnable<EntityRenderState> cir) {
        BrAktorzy.wUi = true;
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
    private static void koperlib$uiOff(LivingEntity e, CallbackInfoReturnable<EntityRenderState> cir) {
        BrAktorzy.wUi = false;
    }
}
