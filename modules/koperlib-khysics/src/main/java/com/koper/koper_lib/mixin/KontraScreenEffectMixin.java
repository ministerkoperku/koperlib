package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import com.koper.koper_lib.physics.KoperPhys;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;

// LevelExtractor.getViewBlockingState() (ScreenEffectRenderer's before 26.3) returns the solid block at the
// player's eye pos and triggers the dark overlay + texture-in-face effect when inside a solid block.
// ClientLevelAccessMixin makes physics blocks appear as real blocks so this fires incorrectly
// when the player stands near a kontraktion. fix: return null when eye pos is a physics block.
@Environment(EnvType.CLIENT)
@Mixin(LevelExtractor.class)
public class KontraScreenEffectMixin {

    @Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true, require = 1)
    private static void koper$skipPhysicsBlockOverlay(LocalPlayer player, Frustum frustum, CallbackInfoReturnable<BlockState> cir) {
        if (KenderClientState.isEmpty()) return;
        // getOverlayBlock samples 8 corners of a width*0.8 box at the eye — old eye+below check
        // missed the real cell on rotated/tilted kontras so the overlay stuck on. match the box.
        AABB bb = player.getBoundingBox();
        if (KenderClientState.eyeBoxHasPhysicsBlock(player.getX(), player.getEyeY(), player.getZ(), bb.maxX - bb.minX))
            cir.setReturnValue(null); // physics block isn't a real wall — no overlay
    }
    @Redirect(method = "getViewBlockingState",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
              require = 1)
    private static BlockState koper$realBlockOnlyForOverlay(Level level, BlockPos pos) {
        return koper$realBlockOnly(level, pos);
    }

    private static BlockState koper$realBlockOnly(Level level, BlockPos pos) {
        boolean prev = KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.get();
        KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(true);
        try {
            return level.getBlockState(pos);
        } finally {
            KoperPhys.CLIENT_BLOCK_LOOKUP_BYPASS.set(prev);
        }
    }
}
