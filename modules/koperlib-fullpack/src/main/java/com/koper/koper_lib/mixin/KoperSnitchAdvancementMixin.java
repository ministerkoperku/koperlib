package com.koper.koper_lib.mixin;

import com.koper.koper_lib.scripting.KoperSnitch;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// fabric has no advancement event so we take it at the source. award() returns true when a
// criterion actually moved, and we only care once the whole thing is done
@Mixin(PlayerAdvancements.class)
public abstract class KoperSnitchAdvancementMixin {

    @Shadow private ServerPlayer player;

    @Inject(method = "award", at = @At("RETURN"))
    private void koperlib$snitchAdvancement(AdvancementHolder holder, String criterion,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() || player == null || holder == null) return;

        PlayerAdvancements self = (PlayerAdvancements) (Object) this;
        if (!self.getOrStartProgress(holder).isDone()) return;

        KoperSnitch.snitch(player, KoperSnitch.ADVANCE,
            "id", holder.id().toString(),
            "criterion", criterion == null ? "" : criterion);
    }
}
