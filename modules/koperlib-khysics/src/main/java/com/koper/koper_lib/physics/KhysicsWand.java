package com.koper.koper_lib.physics;

import com.koper.koper_lib.panama.KoperPhysBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// physics gun — blast / pull kontraktions
// right-click (air or block) → server OBB raycast → blast (500N) or sneak-pull (300N)
public class KhysicsWand extends Item {

    public KhysicsWand(Properties props) {
        super(props);
    }

    // fires when looking at air (physics blocks are air in real world)
    @Override
    public InteractionResult use(Level level, Player user, InteractionHand hand) {
        if (!(level instanceof ServerLevel sl)) return InteractionResult.PASS;
        if (!(user instanceof ServerPlayer player)) return InteractionResult.PASS;
        return doWandAction(sl, player);
    }

    // fires when looking at a real block — still run the raycast so we can hit nearby physics blocks
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (!(level instanceof ServerLevel sl)) return InteractionResult.PASS;
        ServerPlayer player = (ServerPlayer) ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        return doWandAction(sl, player);
    }

    private InteractionResult doWandAction(ServerLevel sl, ServerPlayer player) {
        long wh = KoperPhys.getWorldHandle(sl);
        if (wh <= 0) return InteractionResult.PASS;

        Vec3 eye  = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        float[] hitPos  = new float[3];
        long kontraId = KoperPhysBridge.raycast(wh,
            (float)eye.x, (float)eye.y, (float)eye.z,
            (float)look.x, (float)look.y, (float)look.z,
            6.0f, hitPos);
        if (kontraId < 0) return InteractionResult.PASS;

        KontraEntry data = KoperPhys.all().get(kontraId);
        if (data == null) return InteractionResult.PASS;

        float[] pos = KoperPhys.getCachedPos(kontraId);
        if (pos == null) return InteractionResult.PASS;

        double dx = pos[0] - eye.x, dy = pos[1] - eye.y, dz = pos[2] - eye.z;
        double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 0.001) return InteractionResult.SUCCESS;
        float nx = (float)(dx/len), ny = (float)(dy/len), nz = (float)(dz/len);

        if (player.isShiftKeyDown()) {
            KoperPhysBridge.applyImpulseGroup(data.worldHandle(), kontraId, -nx*300f, -ny*300f, -nz*300f);
            player.sendSystemMessage(Component.literal("§b[KoperLib] Pull!"));
        } else {
            KoperPhysBridge.applyImpulseGroup(data.worldHandle(), kontraId, nx*500f, ny*500f + 200f, nz*500f);
            player.sendSystemMessage(Component.literal("§c[KoperLib] Blast!"));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isFoil(ItemStack stack) { return true; }
}
