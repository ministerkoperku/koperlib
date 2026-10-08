package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrCzastki;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// bedrock particle quads go into the same world pass as entities, same depth buffer
@Mixin(LevelRenderer.class)
public abstract class BrSwiatMixin {

    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void koperlib$bedrockParticles(LevelRenderState state, SubmitNodeCollector collector, boolean outlines, CallbackInfo ci) {
        try {
            BrCzastki.submit(collector, state.cameraRenderState);
        } catch (Exception broke) {
            com.koper.koper_lib.coremod.KoperCore.LOGGER.error("[Kodel/Bedrock] particles failed this frame", broke);
        }
    }
}
