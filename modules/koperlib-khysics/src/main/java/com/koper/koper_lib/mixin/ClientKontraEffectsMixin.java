package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientKontraEffectsMixin {
    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridParticle(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShow,
                                       double x, double y, double z, double xd, double yd, double zd,
                                       CallbackInfo ci) {
        var grid=KenderClientState.activeGrid();
        if (grid == null) return;
        double[] point=KenderClientState.gridPointToWorld(grid,x,y,z);
        double[] velocity=KenderClientState.gridVectorToWorld(grid,xd,yd,zd);
        if (point == null || velocity == null) return;
        ClientLevel level=(ClientLevel)(Object)this;
        KenderClientState.outsideGrid(() -> {
            level.addParticle(particle,overrideLimiter,alwaysShow,point[0],point[1],point[2],
                velocity[0],velocity[1],velocity[2]);
            return null;
        });
        ci.cancel();
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$gridSound(Entity except, double x, double y, double z, Holder<SoundEvent> sound,
                                    SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        var grid=KenderClientState.activeGrid();
        if (grid == null) return;
        double[] point=KenderClientState.gridPointToWorld(grid,x,y,z);
        if (point == null) return;
        ClientLevel level=(ClientLevel)(Object)this;
        KenderClientState.outsideGrid(() -> {
            level.playSeededSound(except,point[0],point[1],point[2],sound,source,volume,pitch,seed);
            return null;
        });
        ci.cancel();
    }

    @Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$gridLocalSound(double x, double y, double z, SoundEvent sound,
                                         SoundSource source, float volume, float pitch,
                                         boolean distanceDelay, CallbackInfo ci) {
        var grid=KenderClientState.activeGrid();
        if (grid == null) return;
        double[] point=KenderClientState.gridPointToWorld(grid,x,y,z);
        if (point == null) return;
        ClientLevel level=(ClientLevel)(Object)this;
        KenderClientState.outsideGrid(() -> {
            level.playLocalSound(point[0],point[1],point[2],sound,source,volume,pitch,distanceDelay);
            return null;
        });
        ci.cancel();
    }
}
