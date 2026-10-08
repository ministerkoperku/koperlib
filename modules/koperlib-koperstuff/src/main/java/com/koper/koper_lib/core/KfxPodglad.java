package com.koper.koper_lib.core;

import com.koper.koper_lib.coremod.KoperCore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

// dev only, KOPER_KFX_PODGLAD=1 in the env. joins the quick play world, spawns the KFX gallery,
// screenshots it by day and by night from two distances, then quits. lets somebody without the game
// in front of them (an agent, a ci box) see what the particle renderer draws
public final class KfxPodglad {

    private static int t = -1;
    private static float pitch;

    private KfxPodglad() {}

    public static void wlacz() {
        if (System.getenv("KOPER_KFX_PODGLAD") == null) return;
        KoperCore.LOGGER.info("[KfxPodglad] armed, waiting for a world");
        ClientTickEvents.END_CLIENT_TICK.register(KfxPodglad::tik);
    }

    private static void komenda(Minecraft mc, String cmd) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withSuppressedOutput()
                .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS), cmd));
    }

    private static void galeria(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            var p = server.getPlayerList().getPlayer(uuid);
            if (p == null) { KoperCore.LOGGER.error("[KfxPodglad] no server player, gallery not spawned"); return; }
            try {
                int n = KfxGallery.spawn((net.minecraft.server.level.ServerLevel) p.level(), p.getEyePosition(), p.getLookAngle(), 7.0);
                KoperCore.LOGGER.info("[KfxPodglad] gallery spawned {} effects", n);
            } catch (RuntimeException e) {
                KoperCore.LOGGER.error("[KfxPodglad] gallery failed", e);
            }
        });
    }

    private static void fotka(Minecraft mc, String co) {
        String plik = String.format("kfx_%03d_%s.png", t, co.replace(' ', '_'));
        KoperCore.LOGGER.info("[KfxPodglad] shot {} -> {}", co, plik);
        Screenshot.grab(mc.gameDirectory, plik, mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
    }

    private static void tik(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        t++;
        String who = mc.player.getName().getString();
        switch (t) {
            case 20 -> {
                for (String c : new String[] {"gamerule send_command_feedback false", "gamerule sendCommandFeedback false",
                    "time set noon", "weather clear", "gamerule advance_time false", "gamerule doDaylightCycle false",
                    "gamemode spectator " + who, "tp " + who + " 0 -50 0 0 0", "koperlib kfx clear"})
                    komenda(mc, c);
                mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
            }
            case 40 -> { mc.player.setYRot(0f); mc.player.setXRot(0f); galeria(mc); }
            case 100 -> fotka(mc, "day");
            case 105 -> komenda(mc, "tp " + who + " 0 -50 4 0 0");
            case 125 -> fotka(mc, "day close");
            case 130 -> { komenda(mc, "time set midnight"); komenda(mc, "tp " + who + " 0 -50 0 0 0"); }
            case 160 -> fotka(mc, "night");
            case 165 -> komenda(mc, "tp " + who + " 0 -50 4 0 0");
            case 185 -> fotka(mc, "night close");
            case 190 -> { pitch = 30f; komenda(mc, "tp " + who + " 0 -47 1.5 0 30"); }
            case 205 -> fotka(mc, "night beams");
            case 210 -> komenda(mc, "time set noon");
            case 225 -> fotka(mc, "day beams");
            case 235 -> {
                mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.FULL);
                KoperCore.LOGGER.info("[KfxPodglad] done");
                mc.stop();
            }
            default -> {}
        }
        // the gallery was placed for a viewer looking south; keep looking there
        if (t > 40) { mc.player.setYRot(0f); mc.player.setXRot(pitch); }
    }
}
