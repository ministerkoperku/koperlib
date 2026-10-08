package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KontraPlayerVisualState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class KontraPlayerRenderStateMixin implements KontraPlayerVisualState {
    @Unique
    private float[] koperlib$kontraRot;
    @Unique
    private float[] koperlib$kontraOffset;

    @Override
    public void koperlib$setKontraRot(float[] rot) {
        this.koperlib$kontraRot = rot;
    }

    @Override
    public float[] koperlib$getKontraRot() {
        return this.koperlib$kontraRot;
    }

    @Override
    public void koperlib$setKontraOffset(float[] offset) {
        this.koperlib$kontraOffset = offset;
    }

    @Override
    public float[] koperlib$getKontraOffset() {
        return this.koperlib$kontraOffset;
    }
}
