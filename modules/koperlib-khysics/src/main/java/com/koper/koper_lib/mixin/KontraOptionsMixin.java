package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KontraRideClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Arrays;

@Environment(EnvType.CLIENT)
@Mixin(Options.class)
public abstract class KontraOptionsMixin {
    @Shadow @Final @Mutable public KeyMapping[] keyMappings;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;load()V"))
    private void koper$addKontraKeys(Minecraft minecraft, File workingDirectory, CallbackInfo ci) {
        KeyMapping key = KontraRideClient.cameraModeKey();
        for (KeyMapping existing : this.keyMappings) {
            if (existing == key) return;
        }
        KeyMapping[] next = Arrays.copyOf(this.keyMappings, this.keyMappings.length + 1);
        next[next.length - 1] = key;
        this.keyMappings = next;
    }
}
