package com.koper.koper_lib.kui;

import com.koper.koper_lib.KoperLib;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.nio.file.Files;
import java.nio.file.Path;

// one place that opens a kui gui for a player — container guis become a synced menu, the rest a plain screen.
// used by the /koperlib gui open command and by koper.gui.open(...) from lua, so items/blocks can pop guis too.
public final class KuiOpen {
    private KuiOpen() {}

    // shuts whatever kui screen the player is looking at. container guis close through the menu,
    // plain ones need the packet, so both go out
    public static void close(ServerPlayer player) {
        if (player == null) return;
        player.closeContainer();
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
            player, new com.koper.koper_lib.network.KuiClosePayload());
    }

    // returns false only if the gui id isn't registered.
    // rawId may carry an instance suffix "guiId#n" — same layout/script, separate stored container.
    public static boolean open(ServerPlayer player, String rawId) {
        int hash = rawId.indexOf('#');
        String guiId = hash >= 0 ? rawId.substring(0, hash) : rawId;
        final String containerKey = rawId; // includes the #instance, so each instance has its own items
        KuiPage page = KuiBook.get(guiId);
        if (page == null) return false;
        KuiPoke.opened(player, rawId);

        final String layoutText = page.layoutFile != null ? readOr(page.layoutFile, "") : "";
        var els = KuiLayout.parse(layoutText);

        byte[] png = new byte[0];
        if (page.isTexture() && page.textureFile != null) {
            try { png = Files.readAllBytes(Path.of(page.textureFile)); }
            catch (Exception e) { KoperLib.LOGGER.warn("[Kui] couldn't read texture {}: {}", page.textureFile, e.getMessage()); }
        }
        final String state = KuiSessions.stateJson(player.getUUID(), page.id);

        if (KuiMenus.hasContainer(els)) {
            final var data = new KuiMenuData(page.id, page.title, page.w, page.h, page.mode, layoutText, state, png);
            final int slots = Math.max(1, KuiMenus.inputCellCount(els));
            ServerPlayNetworking.send(player, new com.koper.koper_lib.network.KuiMenuDataPayload(data));
            player.openMenu(new net.minecraft.world.MenuProvider() {
                @Override public Component getDisplayName() { return Component.literal(page.title); }
                @Override public AbstractContainerMenu createMenu(int cid, Inventory inv, Player p) {
                    return new KuiMenu(cid, inv, data, resolveContainer(player, containerKey, slots));
                }
            });
            return true;
        }

        String layout = page.isTexture() ? (page.regionsFile != null ? readOr(page.regionsFile, "") : "") : layoutText;
        ServerPlayNetworking.send(player,
            new com.koper.koper_lib.network.KuiOpenPayload(page.id, page.title, page.w, page.h, page.mode, layout, state, png));
        return true;
    }

    // page made up on the spot (bedrock forms), never goes into KuiBook so a reload cant eat it mid-click.
    // plain screens only, no container slots
    public static void openLive(ServerPlayer player, KuiPage page, String layout) {
        KuiPoke.opened(player, page.id);
        ServerPlayNetworking.send(player,
            new com.koper.koper_lib.network.KuiOpenPayload(page.id, page.title, page.w, page.h, "json", layout, "{}", new byte[0]));
    }

    // per-block guis live in the block's own BlockEntity now — that's what makes hoppers and
    // comparators work. shared guis (and blocks with no brain) stay in the keyed store.
    private static net.minecraft.world.Container resolveContainer(ServerPlayer player, String key, int slots) {
        int hash = key.indexOf('#');
        if (hash < 0 || !(player.level() instanceof net.minecraft.server.level.ServerLevel level))
            return KuiContainers.get(key, slots);

        BlockPos pos = posFromKey(key.substring(hash + 1));
        if (pos == null) return KuiContainers.get(key, slots);
        if (!(level.getBlockEntity(pos) instanceof com.koper.koper_lib.block.KoperBlockBrain brain))
            return KuiContainers.get(key, slots);

        KuiContainers.moveInto(key, brain);
        return brain;
    }

    // key tail is "<sanitised dim>_<x>_<y>_<z>" — the dim part can hold underscores, the coords can't
    private static BlockPos posFromKey(String tail) {
        String[] bits = tail.split("_");
        if (bits.length < 3) return null;
        try {
            int z = Integer.parseInt(bits[bits.length - 1]);
            int y = Integer.parseInt(bits[bits.length - 2]);
            int x = Integer.parseInt(bits[bits.length - 3]);
            return new BlockPos(x, y, z);
        } catch (NumberFormatException notAPos) {
            return null; // connected-vault keys use a different tail shape
        }
    }

    public static int slotCountFor(String rawId) {
        int hash = rawId.indexOf('#');
        String guiId = hash >= 0 ? rawId.substring(0, hash) : rawId;
        KuiPage page = KuiBook.get(guiId);
        if (page == null || page.layoutFile == null) return 0;
        String layoutText = readOr(page.layoutFile, "");
        return Math.max(1, KuiMenus.inputCellCount(KuiLayout.parse(layoutText)));
    }

    public static String readOr(String path, String def) {
        try { return Files.readString(Path.of(path)); }
        catch (Exception e) { KoperLib.LOGGER.warn("[Kui] read failed {}: {}", path, e.getMessage()); return def; }
    }
}
