package com.koper.koper_lib.kodel.bedrock;

import com.koper.koper_lib.coremod.KoperCore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

// dev only peeking tool, KOPER_PODGLAD=1 in the env. walks the player around in third person with
// bedrock mobs next to it, takes screenshots and dumps what the pack's queries read. lets somebody
// without a controller in hand (an agent, a ci box) see what the bedrock layer draws. then quits
public final class BrPodglad {

    private static int t = -1;
    private static final String[] RZECZY = {"air", "diamond_sword", "iron_shovel", "bucket", "dirt", "torch", "bow"};

    private BrPodglad() {}

    public static void wlacz() {
        if (System.getenv("KOPER_PODGLAD") == null) return;
        KoperCore.LOGGER.info("[Podglad] armed, waiting for a world");
        ClientTickEvents.END_CLIENT_TICK.register(BrPodglad::tik);
    }

    private static void komenda(Minecraft mc, String cmd) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS), cmd));
    }

    private static void fotka(Minecraft mc, String co) {
        String plik = String.format("podglad_%03d_%s.png", t, co.replace(' ', '_'));
        KoperCore.LOGGER.info("[Podglad] shot {} at t={} -> {}", co, t, plik);
        // no chat line per shot, it piled up over what we want to see
        Screenshot.grab(mc.gameDirectory, plik, mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
    }

    private static String ostatni = "";
    private static boolean kliknal;

    private static void tik(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            // stuck before the world? say on what screen, a quick play can stop at a question
            var sc = mc.gui.screen();
            String ekran = sc == null ? "none" : sc.getClass().getName() + " '" + sc.getTitle().getString() + "'";
            if (!ekran.equals(ostatni)) KoperCore.LOGGER.info("[Podglad] screen {}", ostatni = ekran);
            // "experimental settings" on worlds whose datapacks add registry entries: a person clicks
            // through it, we have nobody to click. proceed without a backup
            if (sc instanceof net.minecraft.client.gui.screens.BackupConfirmScreen b && !kliknal) {
                kliknal = true;
                try {
                    var f = net.minecraft.client.gui.screens.BackupConfirmScreen.class.getDeclaredField("onProceed");
                    f.setAccessible(true);
                    ((net.minecraft.client.gui.screens.BackupConfirmScreen.Listener) f.get(b)).proceed(false, false);
                } catch (ReflectiveOperationException nope) {
                    KoperCore.LOGGER.warn("[Podglad] cant click through {}", nope.toString());
                }
            }
            return;
        }
        t++;
        var opts = mc.options;
        switch (t) {
            case 20 -> {
                String who = mc.player.getName().getString();
                for (String c : new String[] {
                    "gamerule sendCommandFeedback false", "time set noon", "weather clear", "gamerule doDaylightCycle false", "gamemode creative " + who,
                    "tp " + who + " 0 -60 0 0 0",
                    "item replace entity " + who + " weapon.mainhand with golden_apple",
                    "item replace entity " + who + " weapon.offhand with shield",
                    // behind the player as seen from the front camera, off to the sides
                    "summon sheep -3 -60 -5 {NoAI:1b,Color:14}", "summon slime 3 -60 -5 {NoAI:1b,Size:1}",
                    "summon cow 0 -60 -9 {NoAI:1b}", "summon iron_golem 6 -60 -9 {NoAI:1b}", "summon warden -7 -60 -10 {NoAI:1b}"})
                    komenda(mc, c);
            }
            case 80 -> {
                opts.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
                opts.setCameraType(CameraType.THIRD_PERSON_FRONT);
            }
            case 100 -> { fotka(mc, "front standing"); zrzut(mc); }
            case 110 -> { opts.setCameraType(CameraType.THIRD_PERSON_BACK); }
            case 120 -> fotka(mc, "back standing");
            case 125 -> opts.keyUp.setDown(true);
            case 140, 146 -> { fotka(mc, "back walking"); zrzut(mc); }
            case 150 -> { opts.keyUp.setDown(false); opts.setCameraType(CameraType.THIRD_PERSON_FRONT); }
            case 151 -> komenda(mc, "tp " + mc.player.getName().getString() + " 0 -60 0 0 0");
            case 165 -> fotka(mc, "front mobs");
            case 170 -> { opts.setCameraType(CameraType.FIRST_PERSON); }
            case 185 -> fotka(mc, "first person golden_apple");
            default -> {
                // then every test item: first person, then third person front. 40 ticks each
                int i = (t - 200) / 40, faza = (t - 200) % 40;
                if (t >= 200 && i < RZECZY.length) {
                    String who = mc.player.getName().getString();
                    if (faza == 0) {
                        komenda(mc, "item replace entity " + who + " weapon.mainhand with " + RZECZY[i]);
                        opts.setCameraType(CameraType.FIRST_PERSON);
                    }
                    if (faza == 15) { fotka(mc, "first person " + RZECZY[i]); zrzut(mc); }
                    if (faza == 20) opts.setCameraType(CameraType.THIRD_PERSON_FRONT);
                    if (faza == 35) fotka(mc, "front " + RZECZY[i]);
                } else if (t >= 200 + RZECZY.length * 40) {
                    reszta(mc, t - (200 + RZECZY.length * 40));
                }
            }
        }
    }

    // swings, turning on the spot, the inventory paperdoll. d = ticks since the item shots ended
    private static void reszta(Minecraft mc, int d) {
        var opts = mc.options;
        var p = mc.player;
        String who = p.getName().getString();
        switch (d) {
            case 0 -> {
                komenda(mc, "item replace entity " + who + " weapon.mainhand with diamond_sword");
                opts.setCameraType(CameraType.FIRST_PERSON);
            }
            case 10, 30 -> opts.keyAttack.setDown(true);
            case 11, 31 -> opts.keyAttack.setDown(false);
            case 13 -> fotka(mc, "first person swing");
            case 20 -> opts.setCameraType(CameraType.THIRD_PERSON_FRONT);
            case 33 -> fotka(mc, "front swing");
            case 40 -> opts.setCameraType(CameraType.THIRD_PERSON_BACK);
            case 45, 50, 55, 65, 80 -> fotka(mc, "back turning " + d);
            case 95 -> komenda(mc, "gamemode survival " + who);
            case 100 -> mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(p));
            case 110 -> fotka(mc, "inventory");
            case 115 -> mc.gui.setScreen(null);
            // armor on the player, seen from the front
            case 120 -> {
                for (String c : new String[] {"armor.head with iron_helmet", "armor.chest with iron_chestplate",
                    "armor.legs with iron_leggings", "armor.feet with iron_boots"})
                    komenda(mc, "item replace entity " + who + " " + c);
                p.setYRot(0f);
                opts.setCameraType(CameraType.THIRD_PERSON_FRONT);
            }
            case 135 -> fotka(mc, "front armor");
            // mining a block in survival: look down, hold attack
            case 140 -> {
                komenda(mc, "item replace entity " + who + " weapon.mainhand with iron_pickaxe");
                opts.setCameraType(CameraType.FIRST_PERSON);
                p.setXRot(70f);
            }
            case 145 -> {}
            case 152, 158 -> { fotka(mc, "first person mining " + d); zrzut(mc); }
            case 160 -> opts.setCameraType(CameraType.THIRD_PERSON_FRONT);
            case 170 -> fotka(mc, "front mining");
            case 175 -> p.setXRot(0f);
            // spyglass up to the eye
            case 180 -> komenda(mc, "item replace entity " + who + " weapon.mainhand with spyglass");
            case 185 -> opts.keyUse.setDown(true);
            case 195 -> fotka(mc, "front spyglass");
            case 200 -> opts.keyUse.setDown(false);
            case 210 -> {
                opts.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.FULL);
                KoperCore.LOGGER.info("[Podglad] done");
                mc.stop();
            }
            default -> {}
        }
        // an unfocused window gets no attack key: swing like mining does, every few ticks
        if (d >= 145 && d < 175 && d % 4 == 1) p.swing(net.minecraft.world.InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
        // standing still, the camera swings 90 degrees to the right over 20 ticks
        if (d > 41 && d <= 61) p.setYRot(p.getYRot() + 4.5f);
    }

    private static void zrzut(Minecraft mc) {
        KoperCore.LOGGER.info("[Podglad] t={} {}{}", t, BrAktorzy.zrzut(mc.player), BrAktorzy.zrzutPrzyczep(mc.player));
    }
}
