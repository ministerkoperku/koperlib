package com.koper.koper_lib.api.attachment;

import com.koper.koper_lib.network.PlacementRotationPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

@Environment(EnvType.CLIENT)
public final class KoperPlacementClient {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("koper_lib", "building"));
    private static final KeyMapping ROTATE = new KeyMapping(
            "key.koper_lib.rotate_placement", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY);
    private static final List<BooleanSupplier> ROTATE_HOOKS = new CopyOnWriteArrayList<>();
    private static int turns;
    private static int axisTurns;

    private KoperPlacementClient() {}

    public static void register() {
        KeyMappingHelper.registerKeyMapping(ROTATE);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (ROTATE.consumeClick()) {
                boolean handled = false;
                for (BooleanSupplier hook : ROTATE_HOOKS) {
                    if (hook.getAsBoolean()) {
                        handled = true;
                        break;
                    }
                }
                if (handled) continue;
                if (client.player == null || client.gui.screen() != null
                        || !KoperAttachments.rotatesPlacement(client.player.getMainHandItem().getItem())) continue;
                if (client.player.isShiftKeyDown()) axisTurns = Math.floorMod(axisTurns + 1, 6);
                else turns = Math.floorMod(turns + 1, 4);
                ClientPlayNetworking.send(new PlacementRotationPayload(turns, axisTurns));
                client.player.sendOverlayMessage(Component.literal(
                        client.player.isShiftKeyDown()
                                ? "Placement axis: " + (axisTurns + 1) + "/6"
                                : "Placement rotation: " + (turns * 90) + "°"));
            }
        });
    }

    public static void addRotateHook(BooleanSupplier hook) {
        if (hook != null) ROTATE_HOOKS.add(hook);
    }

    public static BlockState apply(BlockState state) {
        return KoperPlacementRotation.apply(state, new KoperPlacementRotation.State(turns, axisTurns));
    }
}
