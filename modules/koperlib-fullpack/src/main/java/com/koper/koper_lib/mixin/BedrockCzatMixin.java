package com.koper.koper_lib.mixin;

import com.koper.koper_lib.bedrock.BedrockUszy;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// chatSend. cancelling here means the message never goes out to anyone
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BedrockCzatMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void koperlib$bedrockChat(PlayerChatMessage msg, CallbackInfo ci) {
        String text = msg.signedContent();
        if (BedrockUszy.beforeChat(player, text)) { ci.cancel(); return; }
        BedrockUszy.afterChat(player, text);
    }
}
