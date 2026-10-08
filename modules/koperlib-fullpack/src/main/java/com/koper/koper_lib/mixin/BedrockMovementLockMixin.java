package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockKamera;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// bedrock's movement input permission: the keys read as nothing pressed
@Environment(EnvType.CLIENT)
@Mixin(KeyboardInput.class)
public abstract class BedrockMovementLockMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void koperlib$movementLocked(CallbackInfo ci) {
        if (!BedrockKamera.movementLocked()) return;
        this.keyPresses = Input.EMPTY;
        this.moveVector = Vec2.ZERO;
    }
}
