package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrAktorzy;
import com.koper.koper_lib.kodel.bedrock.BrNosiciel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// every entity passes through here once a frame, bedrock actors tick right after vanilla filled its state
@Mixin(EntityRenderer.class)
public abstract class BrWyciagMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void koperlib$bedrockPose(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        ((BrNosiciel) state).koperlib$br(BrAktorzy.extract(entity, partialTick));
        ((BrNosiciel) state).koperlib$att(BrAktorzy.attachables(entity, partialTick));
        // java's flames are hitbox height / (width * 1.4) quads tall: a 0 wide pack mob on fire made that infinite
        // and blew the vertex buffer. these only get drawn at all since BrDrawDistanceMixin, java never met a 0 wide mob
        if (state.displayFireAnimation && state.boundingBoxWidth < 0.01f) state.displayFireAnimation = false;
    }
}
