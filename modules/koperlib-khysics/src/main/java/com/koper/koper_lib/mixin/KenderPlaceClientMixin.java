package com.koper.koper_lib.mixin;

import com.koper.koper_lib.kender.KenderClientState;
import com.koper.koper_lib.kender.KenderTargeting;
import com.koper.koper_lib.network.KenderUsePayload;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// intercepts right-click-to-place when KenderTargeting found a physics block
// also prevents vanilla from placing real blocks when targeting a physics block via ClientLevelAccessMixin
@Mixin(Minecraft.class)
public class KenderPlaceClientMixin {

    // vanilla's own right-click cooldown. startUseItem normally sets this to 4, but we cancel before it
    // runs — so we set/read it ourselves and get the exact vanilla place cadence instead of clean spam.
    @Shadow private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void koperlib$physicsPlace(CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        if (mc.player == null || mc.level == null) return;

        KenderTargeting.PhysHit hit = KenderTargeting.refreshHit(mc);

        if (hit != null) {
            // vanilla gates only the HOLD path with rightClickDelay — fresh clicks always fire
            // (consumeClick loop has no delay check). gating fresh ones ate clicks.
            boolean fresh = KenderTargeting.freshRightClick;
            if (fresh) KenderTargeting.freshRightClick = false;
            else if (rightClickDelay > 0) { ci.cancel(); return; }
            rightClickDelay = 4;

            if (com.koper.koper_lib.kender.KenderUseClientHooks.fire(mc, hit)) {
                ci.cancel();
                return;
            }
            if (FabricLoader.getInstance().isModLoaded("create")
                    && com.koper.koper_lib.compat.create.KenderCreateValueSettings.tryActivate(mc, hit)) {
                ci.cancel();
                return;
            }
            if (KenderClientState.getById(hit.kontraId()) != null)
                ClientPlayNetworking.send(new KenderUsePayload(hit.networkRef()));
            ci.cancel();
            return;
        }

        // KenderTargeting missed — but vanilla hitResult might be pointing at a physics block position
        // block vanilla placement there so you don't get real blocks inside kontraktion blocks
        // guard the cell a block would be PUT into, and only while holding a block. it used to guard the
        // clicked cell: a machine part overlapping that cell (a bearing's turning half, a suspension
        // head) then ate every right click on the real block under it, UI and building included
        if (mc.hitResult instanceof BlockHitResult bhr
                && mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem) {
            var into = bhr.getBlockPos().relative(bhr.getDirection());
            var projected = KenderClientState.getClientBlockStateAt(into);
            if (projected != null
                    && projected.getRenderShape() != net.minecraft.world.level.block.RenderShape.INVISIBLE) {
                ci.cancel();
            }
        }
    }
}
