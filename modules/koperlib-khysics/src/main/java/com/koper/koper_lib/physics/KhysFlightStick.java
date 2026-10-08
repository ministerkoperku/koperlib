package com.koper.koper_lib.physics;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

// debug flight stick — stand on a kontra, hold this, and it flies where you look.
// WASD = move in the look frame (look up + W = climb), space/shift = straight up/down,
// sprint = afterburner, no input = hover brake. the actual flying happens server-side
// in KoperPhys.flightStickLoop, this item is just the "am i holding it" marker + a hint.
public class KhysFlightStick extends Item {

    public KhysFlightStick(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult use(Level level, Player user, InteractionHand hand) {
        if (level.isClientSide() || !(user instanceof ServerPlayer player)) return InteractionResult.PASS;
        long deck = KontraGlue.mind(player).deckId;
        if (deck == 0L) {
            player.sendSystemMessage(Component.literal("§e[KoperLib] Stand on a kontraktion and just hold the stick — WASD flies where you look, space/shift = up/down."));
        } else {
            player.sendSystemMessage(Component.literal("§b[KoperLib] Flying kontraktion §f" + deck + "§b. Swap the stick away to walk around."));
        }
        return InteractionResult.SUCCESS;
    }
}
