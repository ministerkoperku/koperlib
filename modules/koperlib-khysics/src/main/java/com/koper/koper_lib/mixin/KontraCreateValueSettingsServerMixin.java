package com.koper.koper_lib.mixin;

import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import com.zurrtum.create.AllHandle;
import com.zurrtum.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.ValueSettings;
import com.zurrtum.create.foundation.blockEntity.behaviour.ValueSettingsHandleBehaviour;
import com.zurrtum.create.foundation.blockEntity.behaviour.filtering.ServerFilteringBehaviour;
import com.zurrtum.create.foundation.blockEntity.behaviour.scrollValue.ServerScrollValueBehaviour;
import com.zurrtum.create.infrastructure.packet.c2s.ValueSettingsPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AllHandle.class, remap = false)
public abstract class KontraCreateValueSettingsServerMixin {

    @Inject(method = "onValueSettings", at = @At("HEAD"), cancellable = true)
    private static void koperlib$logicalValueSettings(ServerGamePacketListenerImpl listener,
                                                       ValueSettingsPacket packet, CallbackInfo ci) {
        ServerPlayer player = listener.player;
        ServerLevel level = player.level();
        KontraGrid grid = KoperPhys.gridAtLogical(level, packet.pos());
        if (grid == null || !grid.hasLocalBlock(packet.pos())) return;
        ci.cancel();

        BlockPos worldPos = grid.toWorldPos(packet.pos());
        if (worldPos == null || player.isSpectator() || !player.mayBuild()
                || !player.isWithinBlockInteractionRange(worldPos, 1.0)
                || !level.mayInteract(player, worldPos)
                || level.getServer().isUnderSpawnProtection(level, worldPos, player))
            return;

        BlockEntity blockEntity = grid.getBlockEntity(level, packet.pos());
        if (!(blockEntity instanceof SmartBlockEntity smart)) return;
        boolean handled = KontraGridContext.call(grid, () -> {
            if (smart instanceof FactoryPanelBlockEntity panel) {
                for (var behaviour : panel.panels.values())
                    if (koperlib$apply(player, behaviour, packet)) return true;
                return false;
            }
            ServerScrollValueBehaviour scroll = smart.getBehaviour(ServerScrollValueBehaviour.TYPE);
            if (koperlib$apply(player, scroll, packet)) return true;
            ServerFilteringBehaviour filter = smart.getBehaviour(ServerFilteringBehaviour.TYPE);
            return koperlib$apply(player, filter, packet);
        });
        if (!handled) return;
        player.resetLastActionTime();
        blockEntity.setChanged();
        grid.syncBlockEntity(level, packet.pos());
    }

    private static boolean koperlib$apply(ServerPlayer player,
                                           ValueSettingsHandleBehaviour behaviour,
                                           ValueSettingsPacket packet) {
        if (behaviour == null || !behaviour.acceptsValueSettings()
                || behaviour.netId() != packet.behaviourIndex())
            return false;
        if (packet.interactHand() != null) {
            behaviour.onShortInteract(player, packet.interactHand(), packet.side(), packet.hitResult());
        } else {
            behaviour.setValueSettings(player,
                new ValueSettings(packet.row(), packet.value()), packet.ctrlDown());
        }
        return true;
    }
}
