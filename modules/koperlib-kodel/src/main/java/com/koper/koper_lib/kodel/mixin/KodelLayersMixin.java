package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.KodelPlayerModelLayer;
import com.koper.koper_lib.kodel.KodelZbrojaLayer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

// bolts the model armour layer onto every humanoid renderer (player, zombie, skeleton...), so any
// armour item with a "model" is worn as that model. non-humanoid renderers skip.
@Mixin(LivingEntityRenderer.class)
public abstract class KodelLayersMixin {

    @Shadow @Final protected List layers; // RenderLayer list — we just append

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void koperlib$addKodelLayers(EntityRendererProvider.Context ctx, EntityModel model, float shadow, CallbackInfo ci) {
        if (!(model instanceof HumanoidModel)) return;
        layers.add(new KodelZbrojaLayer((RenderLayerParent) (Object) this));
        // players also get the player model layer (replaces the vanilla model when one is set)
        if (model instanceof net.minecraft.client.model.player.PlayerModel) {
            layers.add(new KodelPlayerModelLayer((RenderLayerParent) (Object) this));
        }
    }
}
