package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockZachowanie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// a player starting to see one of java's own mobs a behavior pack redefines gets its bedrock state
// (properties, groups) right away, not only on its next change. addon mobs do this in KoperMobEntity
@Mixin(Entity.class)
public abstract class BedrockWidzeMixin {

    @Inject(method = "startSeenByPlayer", at = @At("TAIL"), require = 0)
    private void koperlib$bedrockWidzi(ServerPlayer player, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (BedrockZachowanie.nakladka(self)) BedrockZachowanie.seenBy((Mob) self, player);
    }
}
