package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockKamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// bedrock's "minecraft:free" camera: after java placed the view on the player, put it where the pack said.
// detached so the player's own body shows, like on bedrock
@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class BedrockKameraMixin {

    @Shadow private boolean detached;
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "alignWithEntity", at = @At("TAIL"), require = 0)
    private void koperlib$bedrockKamera(float partialTick, CallbackInfo ci) {
        if (BedrockKamera.wolna()) {
            double[] w = BedrockKamera.widok();
            this.setRotation((float) w[3], (float) w[4]);
            this.setPosition(new Vec3(w[0], w[1], w[2]));
            this.detached = true;
        }
        // /camerashake, on top of whatever camera it is
        double[] t = BedrockKamera.shake();
        if (t == null) return;
        Camera self = (Camera) (Object) this;
        this.setRotation(self.yRot() + (float) t[0], self.xRot() + (float) t[1]);
        this.setPosition(self.position().add(t[2], t[3], t[4]));
    }
}
