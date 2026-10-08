package com.koper.koper_lib.quest;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.api.KoperActions;
import com.koper.koper_lib.api.KoperContext;
import com.koper.koper_lib.data.KoperDialogData;
import com.koper.koper_lib.kui.KuiBook;
import com.koper.koper_lib.kui.KuiJson;
import com.koper.koper_lib.kui.KuiOpen;
import com.koper.koper_lib.kui.KuiPage;
import com.koper.koper_lib.kui.KuiPoke;
import com.koper.koper_lib.loader.KoperLibDirectories;
import com.koper.koper_lib.scripting.KoperSnitch;
import com.koper.koper_lib.state.KoperSoulVault;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// runs one conversation for one player. the screen is fixed at six choice slots and blanks the
// spare ones, which beats baking seven layouts for seven choice counts
public final class DialogRunner {
    private DialogRunner() {}

    public static final String PAGE = "koperlib:dialog";
    private static final int CHOICES = 6;
    /** How much one node can say: the speech box holds LINES lines of about WRAP characters. */
    public static final int LINES = 5;
    public static final int WRAP = 46;

    private static final int PAGE_FACE = 0xFF232A36;
    private static final int FADED = 0xFF8E98AA;
    private static final int ACCENT = 0xFFE39A4A;

    private static final Map<UUID, String> TALKING = new ConcurrentHashMap<>();   // dialog id
    private static final Map<UUID, String> AT_NODE = new ConcurrentHashMap<>();
    private static final Map<UUID, List<KoperDialogData.Choice>> SHOWN = new ConcurrentHashMap<>();

    // ── page ────────────────────────────────────────────────────────────────

    public static void bakeAndRegister() {
        try {
            Path dir = KoperLibDirectories.ROOT.resolve("ui");
            Files.createDirectories(dir);
            Path layout = dir.resolve("dialog.layout.json");
            Files.writeString(layout, layoutJson());

            KuiPage page = new KuiPage();
            page.id = PAGE;
            page.namespace = "koperlib";
            page.mode = "json";
            page.title = "Talk";
            page.w = 300;
            page.h = 214;
            page.layoutFile = layout.toAbsolutePath().toString();
            KuiBook.put(page);
        } catch (Exception broken) {
            KoperLib.LOGGER.warn("[Dialog] couldn't bake the talk page: {}", broken.getMessage());
        }
    }

    private static String layoutJson() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 300, 214, null);
        j.label("who", 10, 8, 280, "", "left", ACCENT);
        j.rule("head", 10, 20, 280, 1, ACCENT);
        j.panel("said", 8, 26, 284, 66, PAGE_FACE);

        int y = 31;
        for (int line = 0; line < LINES; line++) {
            j.label("line" + line, 14, y, 272, "", "left", 0xFFE7EBF2);
            y += 11;
        }

        y = 96;
        for (int slot = 0; slot < CHOICES; slot++) {
            j.button("c" + slot, 8, y, 284, 16, "", null);
            y += 18;
        }
        return j.done();
    }

    // ── running ─────────────────────────────────────────────────────────────

    public static boolean open(ServerPlayer player, String dialogId, String speakerName) {
        KoperDialogData dialog = DialogBook.get(dialogId);
        if (dialog == null) return false;
        if (KuiBook.get(PAGE) == null) bakeAndRegister();

        TALKING.put(player.getUUID(), dialogId);
        KuiOpen.open(player, PAGE);
        KuiPoke.text(player, "who", "§6" + (dialog.title.isBlank() ? speakerName : dialog.title));
        show(player, dialog, dialog.start);
        return true;
    }

    private static void show(ServerPlayer player, KoperDialogData dialog, String nodeId) {
        KoperDialogData.Node node = dialog.node(nodeId);
        if (node == null) { finish(player, dialog); return; }

        AT_NODE.put(player.getUUID(), nodeId);

        if (node.onShow != null)
            KoperActions.run(node.onShow, KoperContext.ofEquip(player, player.getMainHandItem()),
                "on_show", dialog.id + "#" + nodeId);

        KoperSnitch.snitch(player, "dialog:node", "id", dialog.id, "node", nodeId);

        if (!node.name.isBlank()) KuiPoke.text(player, "who", "§6" + node.name);

        List<String> lines = speechLines(node.text);
        boolean more = lines.size() > LINES;
        if (more)
            KoperLib.LOGGER.warn("[Dialog] {}#{} says {} lines and the box holds {}: the rest is only on hover."
                + " Split it with \"next\".", dialog.id, nodeId, lines.size(), LINES);
        String whole = more ? node.text.trim() : "";
        for (int line = 0; line < LINES; line++) {
            String text = line < lines.size() ? lines.get(line) : "";
            if (more && line == LINES - 1) text += " ...";
            KuiPoke.text(player, "line" + line, text, whole);
        }

        List<KoperDialogData.Choice> usable = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (KoperDialogData.Choice choice : node.choices) {
            if (choice.once && burned(player, dialog.id, nodeId, node.choices.indexOf(choice))) continue;
            boolean allowed = allows(player, choice.when);
            if (!allowed && choice.hide) continue;
            usable.add(allowed ? choice : null);       // null slot = shown but dead
            labels.add(allowed ? choice.text : "§8" + choice.text);
            if (usable.size() == CHOICES) break;
        }
        SHOWN.put(player.getUUID(), usable);

        for (int slot = 0; slot < CHOICES; slot++)
            KuiPoke.text(player, "c" + slot, slot < labels.size() ? labels.get(slot) : "");

        // a node with nothing to pick is the end of the road, so offer the way out
        if (usable.isEmpty()) {
            KuiPoke.text(player, "c0", node.next.isBlank() ? "§7[ leave ]" : "§7[ continue ]");
            SHOWN.put(player.getUUID(), List.of());
        }
    }

    // a small, explicit gate. anything fancier belongs in a script, which a choice can call
    private static boolean allows(ServerPlayer player, com.google.gson.JsonObject when) {
        if (when == null) return true;
        boolean ok = true;

        if (when.has("quest_done"))
            ok &= QuestChase.done(player, when.get("quest_done").getAsString());
        if (when.has("quest_active"))
            ok &= QuestChase.ACTIVE.equals(QuestChase.status(player, when.get("quest_active").getAsString()));
        if (when.has("quest_none"))
            ok &= QuestChase.NONE.equals(QuestChase.status(player, when.get("quest_none").getAsString()));
        if (when.has("spoke_to"))
            ok &= spokenTo(player, when.get("spoke_to").getAsString());
        if (when.has("has")) {
            String id = when.get("has").getAsString();
            int need = when.has("count") ? when.get("count").getAsInt() : 1;
            ok &= holds(player, id) >= need;
        }
        // whatever the mod itself keeps in the soul vault, so a boss gate needs no koperlib support
        if (when.has("state"))
            ok &= soulSays(player, when.get("state").getAsString());
        if (when.has("state_num")) {
            double need = when.has("at_least") ? when.get("at_least").getAsDouble() : 1;
            ok &= soulNum(player, when.get("state_num").getAsString()) >= need;
        }
        if (when.has("not") && when.get("not").getAsBoolean()) ok = !ok;
        return ok;
    }

    // "koper_mod_fabric:boss.unlocked.springikoper" -> namespace before the colon, key keeps its dots
    private static boolean soulSays(ServerPlayer player, String path) {
        String[] split = splitSoul(path);
        var server = player.level().getServer();
        return split != null && server != null
            && KoperSoulVault.getBool(server, player.getUUID(), split[0], split[1], false);
    }

    private static double soulNum(ServerPlayer player, String path) {
        String[] split = splitSoul(path);
        var server = player.level().getServer();
        if (split == null || server == null) return 0;
        return KoperSoulVault.getNum(server, player.getUUID(), split[0], split[1], 0);
    }

    private static String[] splitSoul(String path) {
        if (path == null) return null;
        int colon = path.indexOf(':');
        if (colon < 1 || colon == path.length() - 1) return null;
        return new String[] { path.substring(0, colon), path.substring(colon + 1) };
    }

    private static int holds(ServerPlayer player, String target) {
        int total = 0;
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            var stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key != null && QuestChase.matches(target, key.toString(), false)) total += stack.getCount();
        }
        return total;
    }

    private static boolean burned(ServerPlayer player, String dialogId, String nodeId, int index) {
        var server = player.level().getServer();
        if (server == null) return false;
        return KoperSoulVault.has(server, player.getUUID(), "dialog", dialogId + "#" + nodeId + "#" + index);
    }

    private static void burn(ServerPlayer player, String dialogId, String nodeId, int index) {
        var server = player.level().getServer();
        if (server == null) return;
        KoperSoulVault.setRaw(server, player.getUUID(), "dialog", dialogId + "#" + nodeId + "#" + index, "b1");
    }

    private static void finish(ServerPlayer player, KoperDialogData dialog) {
        TALKING.remove(player.getUUID());
        AT_NODE.remove(player.getUUID());
        SHOWN.remove(player.getUUID());
        KuiOpen.close(player);

        var server = player.level().getServer();
        if (server != null)
            KoperSoulVault.setRaw(server, player.getUUID(), "dialog", dialog.id, "b1");

        // this is what a "dialog" goal listens for
        KoperSnitch.snitch(player, "dialog:done", "id", dialog.id);
    }

    public static boolean spokenTo(ServerPlayer player, String dialogId) {
        var server = player.level().getServer();
        return server != null && KoperSoulVault.has(server, player.getUUID(), "dialog", dialogId);
    }

    /** A node's text as the speech box breaks it, every line. More than {@link #LINES} does not fit. */
    public static List<String> speechLines(String text) {
        return wrap(text, WRAP, Integer.MAX_VALUE);
    }

    private static List<String> wrap(String text, int width, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == maxLines) return out;
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty() && out.size() < maxLines) out.add(line.toString());
        return out;
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    public static void register() {
        KuiPoke.on(PAGE, poke -> {
            ServerPlayer player = poke.player();
            if (!"click".equals(poke.action()) || !poke.widget().startsWith("c")) return false;

            String dialogId = TALKING.get(player.getUUID());
            KoperDialogData dialog = DialogBook.get(dialogId);
            if (dialog == null) return true;

            String nodeId = AT_NODE.get(player.getUUID());
            KoperDialogData.Node node = dialog.node(nodeId);
            List<KoperDialogData.Choice> usable = SHOWN.getOrDefault(player.getUUID(), List.of());

            int slot;
            try { slot = Integer.parseInt(poke.widget().substring(1)); }
            catch (NumberFormatException notASlot) { return true; }

            if (usable.isEmpty()) {
                if (node != null && !node.next.isBlank()) show(player, dialog, node.next);
                else finish(player, dialog);
                return true;
            }
            if (slot >= usable.size()) return true;

            KoperDialogData.Choice choice = usable.get(slot);
            if (choice == null) return true;   // greyed out, it is there to be seen not picked
            if (choice.once && node != null) burn(player, dialogId, nodeId, node.choices.indexOf(choice));

            if (choice.actions != null)
                KoperActions.run(choice.actions, KoperContext.ofEquip(player, player.getMainHandItem()),
                    "on_choice", dialogId);

            if (choice.go.isBlank()) finish(player, dialog);
            else show(player, dialog, choice.go);
            return true;
        });

        // right clicking a mob opens whatever it has to say, quest givers still run first
        KoperSnitch.listen(KoperSnitch.TALK, tattle -> {
            ServerPlayer player = tattle.who();
            if (TALKING.containsKey(player.getUUID())) return;
            KoperDialogData dialog = DialogBook.forSpeaker(tattle.id(), tattle.bit("tags"));
            if (dialog != null) open(player, dialog.id, tattle.bit("name", "?"));
        });

        // {"action": "dialog", "id": "mypack:warden_words"} from any action list. that covers
        // "start talking when the quest starts" without a mob being involved at all
        com.koper.koper_lib.api.KoperCalls.register("dialog", ctx -> {
            ServerPlayer player = ctx.player();
            String id = ctx.string("id", ctx.string("dialog", ""));
            if (player != null && !id.isBlank()) open(player, id, "");
            return net.minecraft.world.InteractionResult.PASS;
        });

        bakeAndRegister();
    }
}
