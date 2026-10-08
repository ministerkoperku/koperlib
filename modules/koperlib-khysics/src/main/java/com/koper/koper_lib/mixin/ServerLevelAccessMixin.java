package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KoperPhys;
import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

// makes physics blocks visible to all server-side queries (redstone, mods, BEs)
@Mixin(ServerLevel.class)
public abstract class ServerLevelAccessMixin {

    @Inject(method = "getBlockTicks", at = @At("RETURN"))
    private void koperlib$rememberBlockTicks(CallbackInfoReturnable<LevelTicks<Block>> cir) {
        KoperPhys.bindLevelTicks(cir.getReturnValue(), (ServerLevel)(Object)this);
    }

    @Inject(method = "getFluidTicks", at = @At("RETURN"))
    private void koperlib$rememberFluidTicks(CallbackInfoReturnable<LevelTicks<Fluid>> cir) {
        KoperPhys.bindLevelTicks(cir.getReturnValue(), (ServerLevel)(Object)this);
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"))
    private void koperlib$gridEntitySpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        KontraGrid grid = KontraGridContext.active();
        if (grid != null) KoperPhys.gridEntitySpawn(grid, entity);
    }

    // 26.3: every short sendParticles overload now ends in this one (with a RandomizationType),
    // not in the (ZZDDDIDDDD) one this used to hook, so particles from a kontra's block entities
    // went out at raw grid coordinates and nobody saw them
    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDDDDLnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket$RandomizationType;)I",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$gridParticles(ParticleOptions particle, boolean overrideLimiter, boolean alwaysShow,
                                        double x, double y, double z, int count,
                                        double xDist, double yDist, double zDist,
                                        double xSpeed, double ySpeed, double zSpeed,
                                        ClientboundLevelParticlesPacket.RandomizationType randomization,
                                        CallbackInfoReturnable<Integer> cir) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) return;
        float[] point = KoperPhys.gridPointToWorld(grid, x, y, z);
        float[] vx = KoperPhys.gridVectorToWorld(grid, xDist, 0, 0);
        float[] vy = KoperPhys.gridVectorToWorld(grid, 0, yDist, 0);
        float[] vz = KoperPhys.gridVectorToWorld(grid, 0, 0, zDist);
        if (point == null || vx == null || vy == null || vz == null) return;
        double wx=Math.abs(vx[0])+Math.abs(vy[0])+Math.abs(vz[0]);
        double wy=Math.abs(vx[1])+Math.abs(vy[1])+Math.abs(vz[1]);
        double wz=Math.abs(vx[2])+Math.abs(vy[2])+Math.abs(vz[2]);
        // with no count the speeds are one velocity, which turns with the kontra; otherwise they
        // are per-axis spreads like the distances
        double sx=xSpeed, sy=ySpeed, sz=zSpeed;
        if (count == 0) {
            float[] v = KoperPhys.gridVectorToWorld(grid, xSpeed, ySpeed, zSpeed);
            if (v != null) { sx=v[0]; sy=v[1]; sz=v[2]; }
        }
        double fx=sx, fy=sy, fz=sz;
        ServerLevel level = (ServerLevel)(Object)this;
        cir.setReturnValue(KontraGridContext.outside(() -> level.sendParticles(particle, overrideLimiter,
            alwaysShow, point[0], point[1], point[2], count, wx, wy, wz, fx, fy, fz, randomization)));
    }

    @Inject(method = "gameEvent", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridGameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context,
                                        CallbackInfo ci) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) return;
        float[] point = KoperPhys.gridPointToWorld(grid, position.x, position.y, position.z);
        if (point == null) return;
        ServerLevel level = (ServerLevel)(Object)this;
        KontraGridContext.outside(() -> {
            level.gameEvent(event, new Vec3(point[0], point[1], point[2]), context);
            return null;
        });
        ci.cancel();
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"), cancellable = true)
    private void koperlib$gridCoordinateSound(Entity except, double x, double y, double z,
                                               Holder<SoundEvent> sound, SoundSource source,
                                               float volume, float pitch, long seed, CallbackInfo ci) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) return;
        float[] point = KoperPhys.gridPointToWorld(grid, x, y, z);
        if (point == null) return;
        ServerLevel level = (ServerLevel)(Object)this;
        KontraGridContext.outside(() -> {
            level.playSeededSound(except, point[0], point[1], point[2], sound, source, volume, pitch, seed);
            return null;
        });
        ci.cancel();
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
    private void koperlib$gridBreakProgress(int id, BlockPos pos, int progress, CallbackInfo ci) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) return;
        BlockPos worldPos = grid.toWorldPos(pos);
        if (worldPos == null) return;
        ServerLevel level = (ServerLevel)(Object)this;
        KontraGridContext.outside(() -> {
            level.destroyBlockProgress(id, worldPos, progress);
            return null;
        });
        ci.cancel();
    }

    // getBlockState/getBlockEntity/playSound used to be injected here too — ServerLevel doesn't
    // declare ANY of them (all on Level) so those handlers with require=0 silently never applied.
    // the live ones sit in ServerLevelBlockAccessMixin. fuck me that took a while to spot.

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void koperlib$physicsBlockEvent(BlockPos pos, Block block, int eventId, int eventData, CallbackInfo ci) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) grid = KoperPhys.gridAtLogical((ServerLevel)(Object)this, pos);
        if (grid == null) return;
        // queue like vanilla — running it inline made pistons extend+retract inside one neighborChanged
        grid.queueBlockEvent(pos, block, eventId, eventData);
        ci.cancel();
    }

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
    private void koperlib$physicsBlockEntityUpdate(BlockPos pos, BlockState oldState, BlockState newState,
                                                    int flags, CallbackInfo ci) {
        KontraGrid grid = KontraGridContext.active();
        boolean fromContext = grid != null;
        if (grid == null) grid = KoperPhys.gridAtLogical((ServerLevel)(Object)this, pos);
        if (grid == null) return;
        // sendBlockUpdated is THE call that tells clients a block changed. cancelling it for a
        // world cell means that client never hears, and keeps the block with collision and all.
        // shout when that happens somewhere a grid does not actually own.
        if (com.koper.koper_lib.config.KoperLibConfig.get().debugMode)
            com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
                "[GhostProbe] sendBlockUpdated CANCELLED at {} ({} -> {}) viaActiveContext={}",
                pos, oldState.getBlock(), newState.getBlock(), fromContext);
        grid.syncBlockEntity((ServerLevel)(Object)this, pos);
        ci.cancel();
    }

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$physicsGridLevelEvent(Entity source, int eventId, BlockPos pos, int data, CallbackInfo ci) {
        KontraGrid grid = KontraGridContext.active();
        if (grid == null) return;
        BlockPos worldPos = grid.toWorldPos(pos);
        if (worldPos != null) KontraGridContext.outside(() -> {
            ((ServerLevel)(Object)this).levelEvent(source, eventId, worldPos, data);
            return null;
        });
        ci.cancel();
    }
}
