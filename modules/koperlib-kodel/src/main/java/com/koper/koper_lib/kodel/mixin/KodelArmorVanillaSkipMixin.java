package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.KodelZbrojaBook;
import net.minecraft.core.registries.BuiltInRegistries;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// model armour items keep a vanilla asset (so MC keeps them in the render state), but the flat vanilla
// piece must not be drawn over the model: skip it, KodelZbrojaLayer draws the real thing
@Mixin(HumanoidArmorLayer.class)
public class KodelArmorVanillaSkipMixin {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$skipModelArmor(PoseStack ps, SubmitNodeCollector tasks, ItemStack stack,
                                       EquipmentSlot slot, int light, HumanoidRenderState state, CallbackInfo ci) {
        if (stack != null && !stack.isEmpty() && KodelZbrojaBook.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()) != null) ci.cancel();
    }
}
