package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockFormy;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// dialog buttons of bedrock forms come back as custom click actions. vanilla just logs those
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class BedrockDialogMixin {

    @Shadow @Final protected MinecraftServer server;

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockForm(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if (!BedrockFormy.CLICK.equals(packet.id())) return;
        ci.cancel();
        if (!((Object) this instanceof ServerGamePacketListenerImpl game)) return;
        var who = game.player;
        server.execute(() -> BedrockFormy.clicked(who, packet.id(), packet.payload()));
    }
}
