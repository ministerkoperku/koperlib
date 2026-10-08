package com.koper.koper_lib.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.data.KoperDialogData;
import com.koper.koper_lib.kui.KuiBook;
import com.koper.koper_lib.kui.KuiJson;
import com.koper.koper_lib.kui.KuiOpen;
import com.koper.koper_lib.kui.KuiPage;
import com.koper.koper_lib.kui.KuiPoke;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.loader.KoperLibDirectories;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

// writes dialogs/<name>.json from in game. a conversation is a list of nodes and each node is a
// line plus some choices, so the editor is a list of nodes and a list of choices, nothing cleverer
public final class DialogMaker {
    private DialogMaker() {}

    public static final String BROWSER = "koperlib:dialog_edit";
    public static final String NODES = "koperlib:dialog_nodes";

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final int PAGE_FACE = 0xFF232A36;
    private static final int FADED = 0xFF8E98AA;
    private static final int ACCENT = 0xFFE39A4A;
    private static final int GO = 0xFF2F5D3A;
    private static final int STOP = 0xFF5D2F2F;

    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> PACK_ROWS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<Path>> FILE_ROWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PICKED_NODE = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PICKED_CHOICE = new ConcurrentHashMap<>();

    private static final class Node {
        String id;
        String text = "";
        final List<String[]> choices = new ArrayList<>(); // text, go
        Node(String id) { this.id = id; }
    }

    private static final class Draft {
        String pack = "";
        String id = "";
        String title = "";
        String speaker = "";
        final List<Node> nodes = new ArrayList<>();

        String nodeText = "";
        String choiceText = "";
        String choiceGo = "";

        Node current() {
            return nodes.isEmpty() ? null : nodes.get(Math.clamp(cursor, 0, nodes.size() - 1));
        }
        int cursor = 0;
    }

    private static Draft draft(ServerPlayer player) {
        return DRAFTS.computeIfAbsent(player.getUUID(), u -> new Draft());
    }

    // ── pages ───────────────────────────────────────────────────────────────

    public static void bakeAndRegister() {
        try {
            Path dir = KoperLibDirectories.ROOT.resolve("ui");
            Files.createDirectories(dir);

            Path a = dir.resolve("dialog_edit.layout.json");
            Files.writeString(a, browserLayout());
            put(BROWSER, "Dialogs", 264, 236, a);

            Path b = dir.resolve("dialog_nodes.layout.json");
            Files.writeString(b, nodesLayout());
            put(NODES, "Lines", 284, 252, b);
        } catch (Exception broken) {
            KoperLib.LOGGER.warn("[Dialog] couldn't bake the editor: {}", broken.getMessage());
        }
    }

    private static void put(String id, String title, int w, int h, Path layout) {
        KuiPage page = new KuiPage();
        page.id = id;
        page.namespace = "koperlib";
        page.mode = "json";
        page.title = title;
        page.w = w;
        page.h = h;
        page.layoutFile = layout.toAbsolutePath().toString();
        KuiBook.put(page);
    }

    private static String browserLayout() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 264, 236, null);
        j.label("title", 0, 8, 264, "§6Dialogs", "center", null);
        j.rule("head", 10, 20, 244, 1, ACCENT);
        j.panel("body", 8, 26, 248, 176, PAGE_FACE);

        j.label("l0", 14, 31, 236, "§7pack", "left", FADED);
        j.list("packs", 14, 42, 236, 34);
        j.label("l1", 14, 80, 236, "§7dialogs in this pack", "left", FADED);
        j.list("files", 14, 91, 236, 40);

        j.rule("split", 14, 136, 236, 1, 0xFF465164);
        j.label("l2", 14, 141, 236, "§7id §c*", "left", FADED);
        j.input("did", 14, 151, 236, 16, "boss_intro");
        j.label("l3", 14, 171, 236, "§7speaker §8(mob id, #tag, or tag:marker)", "left", FADED);
        j.input("speaker", 14, 181, 152, 16, "minecraft:villager");
        j.button("look", 170, 181, 80, 16, "From aim", null);

        j.label("say", 8, 206, 248, "", "left", FADED);
        j.button("new", 8, 216, 60, 16, "New", GO);
        j.button("edit", 72, 216, 60, 16, "Edit", null);
        j.button("lines", 136, 216, 60, 16, "Lines →", null);
        j.button("delete", 200, 216, 56, 16, "Delete", STOP);
        return j.done();
    }

    private static String nodesLayout() {
        KuiJson j = new KuiJson();
        j.panel("bg", 0, 0, 284, 252, null);
        j.label("title", 0, 8, 284, "§6Lines", "center", null);
        j.rule("head", 10, 20, 264, 1, ACCENT);
        j.panel("body", 8, 26, 268, 186, PAGE_FACE);

        j.label("l1", 14, 31, 256, "§7lines §8(click one to edit it)", "left", FADED);
        j.list("nodes", 14, 42, 256, 40);
        j.input("ntext", 14, 86, 176, 16, "what they say");
        j.button("addnode", 194, 86, 76, 16, "Add line", GO);

        j.rule("split", 14, 108, 256, 1, 0xFF465164);
        j.label("l2", 14, 113, 256, "§7choices on the selected line", "left", FADED);
        j.list("choices", 14, 124, 256, 34);
        j.input("ctext", 14, 162, 130, 16, "what the player says");
        j.input("cgo", 148, 162, 60, 16, "go to");
        j.button("addchoice", 212, 162, 58, 16, "Add", GO);
        j.button("delchoice", 14, 182, 126, 16, "Remove choice", STOP);
        j.button("delnode", 144, 182, 126, 16, "Remove line", STOP);

        j.label("say", 8, 216, 268, "", "left", FADED);
        j.button("back", 8, 228, 130, 16, "← Back", null);
        j.button("save", 146, 228, 130, 16, "Save + reload", GO);
        return j.done();
    }

    // ── browser ─────────────────────────────────────────────────────────────

    public static void open(ServerPlayer player) {
        if (KuiBook.get(BROWSER) == null) bakeAndRegister();
        KuiOpen.open(player, BROWSER);

        List<String> packs = new ArrayList<>(FullPackLoader.getAllPacks().keySet());
        PACK_ROWS.put(player.getUUID(), packs);
        KuiPoke.options(player, "packs", packs);

        Draft d = draft(player);
        if (d.pack.isEmpty() && !packs.isEmpty()) d.pack = packs.getFirst();
        KuiPoke.set(player, BROWSER, "did", d.id);
        KuiPoke.set(player, BROWSER, "speaker", d.speaker);
        listFiles(player, d);
    }

    private static void listFiles(ServerPlayer player, Draft d) {
        List<Path> files = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        Path dir = KoperLibDirectories.FULLPACKS.resolve(d.pack).resolve("dialogs");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.list(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
            } catch (Exception ignored) { }
        }
        for (Path f : files) rows.add("§f" + f.getFileName().toString().replace(".json", ""));
        FILE_ROWS.put(player.getUUID(), files);
        KuiPoke.options(player, "files", rows);
        say(player, rows.isEmpty() ? "§7no dialogs in §f" + d.pack : "§7" + rows.size() + " in §f" + d.pack);
    }

    private static void say(ServerPlayer player, String text) {
        KuiPoke.text(player, "say", text);
    }

    // ── node editor ─────────────────────────────────────────────────────────

    private static void openNodes(ServerPlayer player, Draft d) {
        if (KuiBook.get(NODES) == null) bakeAndRegister();
        KuiOpen.open(player, NODES);
        redrawNodes(player, d);
    }

    private static void redrawNodes(ServerPlayer player, Draft d) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < d.nodes.size(); i++) {
            Node node = d.nodes.get(i);
            rows.add((i == d.cursor ? "§e» " : "§f") + node.id + " §7" + cut(node.text, 26));
        }
        KuiPoke.options(player, "nodes", rows);
        redrawChoices(player, d);
        KuiPoke.text(player, "title", "§6Lines §8· " + (d.id.isBlank() ? "?" : d.id));
    }

    private static void redrawChoices(ServerPlayer player, Draft d) {
        List<String> rows = new ArrayList<>();
        Node node = d.current();
        if (node != null)
            for (String[] choice : node.choices)
                rows.add("§f" + cut(choice[0], 24) + " §8→ " + (choice[1].isBlank() ? "end" : choice[1]));
        KuiPoke.options(player, "choices", rows);
        say(player, node == null ? "§cadd a line first"
            : "§7" + d.nodes.size() + " line(s), " + node.choices.size() + " choice(s) on §f" + node.id);
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ── wiring ──────────────────────────────────────────────────────────────

    public static void register() {
        KuiPoke.on(BROWSER, DialogMaker::browser);
        KuiPoke.on(NODES, DialogMaker::nodes);
        bakeAndRegister();
    }

    private static boolean browser(KuiPoke.Poke poke) {
        ServerPlayer player = poke.player();
        Draft d = draft(player);

        switch (poke.widget()) {
            case "packs" -> {
                List<String> packs = PACK_ROWS.getOrDefault(player.getUUID(), List.of());
                int index = (int) poke.value();
                if (index >= 0 && index < packs.size()) { d.pack = packs.get(index); listFiles(player, d); }
            }
            case "files" -> {
                PICKED_NODE.put(player.getUUID(), (int) poke.value());
                say(player, "§7" + poke.text() + " §8— Edit or Delete");
            }
            case "did" -> d.id = poke.text().trim().toLowerCase();
            case "speaker" -> d.speaker = poke.text().trim();
            case "look" -> {
                var aimed = lookedAt(player);
                if (aimed == null) { say(player, "§clook at a mob first"); return true; }
                d.speaker = aimed;
                KuiPoke.set(player, BROWSER, "speaker", aimed);
                say(player, "§7speaker §f" + aimed);
            }
            case "new" -> {
                Draft fresh = new Draft();
                fresh.pack = d.pack;
                DRAFTS.put(player.getUUID(), fresh);
                KuiPoke.set(player, BROWSER, "did", "");
                KuiPoke.set(player, BROWSER, "speaker", "");
                say(player, "§7blank dialog, fill the id then hit Lines");
            }
            case "edit" -> {
                Path file = selectedFile(player);
                if (file == null) { say(player, "§cpick one in the list"); return true; }
                Draft loaded = load(file, d.pack);
                if (loaded == null) { say(player, "§ccouldn't read it"); return true; }
                DRAFTS.put(player.getUUID(), loaded);
                KuiPoke.set(player, BROWSER, "did", loaded.id);
                KuiPoke.set(player, BROWSER, "speaker", loaded.speaker);
                say(player, "§7loaded §f" + loaded.id + " §8(" + loaded.nodes.size() + " lines)");
            }
            case "delete" -> {
                Path file = selectedFile(player);
                if (file == null) { say(player, "§cpick one in the list"); return true; }
                try {
                    Files.delete(file);
                    player.sendSystemMessage(Component.literal("§7[Dialog] deleted §f" + file.getFileName()));
                    listFiles(player, d);
                    reload(player);
                } catch (Exception failed) {
                    say(player, "§ccouldn't delete it");
                }
            }
            case "lines" -> {
                if (d.id.isBlank()) { say(player, "§cid first"); return true; }
                openNodes(player, d);
            }
            default -> { return false; }
        }
        return true;
    }

    private static boolean nodes(KuiPoke.Poke poke) {
        ServerPlayer player = poke.player();
        Draft d = draft(player);

        switch (poke.widget()) {
            case "ntext" -> d.nodeText = poke.text().trim();
            case "ctext" -> d.choiceText = poke.text().trim();
            case "cgo" -> d.choiceGo = poke.text().trim();
            case "nodes" -> {
                d.cursor = (int) poke.value();
                PICKED_NODE.put(player.getUUID(), d.cursor);
                redrawNodes(player, d);
            }
            case "choices" -> PICKED_CHOICE.put(player.getUUID(), (int) poke.value());
            case "addnode" -> {
                if (d.nodeText.isBlank()) { say(player, "§ca line needs text"); return true; }
                Node node = new Node(d.nodes.isEmpty() ? "intro" : "n" + d.nodes.size());
                node.text = d.nodeText;
                d.nodes.add(node);
                d.cursor = d.nodes.size() - 1;
                redrawNodes(player, d);
            }
            case "addchoice" -> {
                Node node = d.current();
                if (node == null) { say(player, "§cadd a line first"); return true; }
                if (d.choiceText.isBlank()) { say(player, "§ca choice needs text"); return true; }
                node.choices.add(new String[]{ d.choiceText, d.choiceGo });
                redrawChoices(player, d);
            }
            case "delchoice" -> {
                Node node = d.current();
                int index = PICKED_CHOICE.getOrDefault(player.getUUID(), -1);
                if (node == null || index < 0 || index >= node.choices.size()) { say(player, "§cpick a choice"); return true; }
                node.choices.remove(index);
                PICKED_CHOICE.remove(player.getUUID());
                redrawChoices(player, d);
            }
            case "delnode" -> {
                if (d.nodes.isEmpty()) return true;
                d.nodes.remove(Math.clamp(d.cursor, 0, d.nodes.size() - 1));
                d.cursor = 0;
                redrawNodes(player, d);
            }
            case "back" -> { open(player); return true; }
            case "save" -> save(player, d);
            default -> { return false; }
        }
        return true;
    }

    private static Path selectedFile(ServerPlayer player) {
        List<Path> files = FILE_ROWS.getOrDefault(player.getUUID(), List.of());
        int index = PICKED_NODE.getOrDefault(player.getUUID(), -1);
        return index >= 0 && index < files.size() ? files.get(index) : null;
    }

    // whatever mob the player is aiming at, so a speaker is one click instead of typing an id
    private static String lookedAt(ServerPlayer player) {
        var eye = player.getEyePosition();
        var reach = eye.add(player.getLookAngle().scale(6.0));
        var box = player.getBoundingBox().expandTowards(player.getLookAngle().scale(6.0)).inflate(1.0);

        var hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
            player, eye, reach, box, e -> !e.isSpectator() && e != player, 6.0 * 6.0);
        if (hit == null) return null;

        var tags = hit.getEntity().entityTags();
        if (!tags.isEmpty()) return "tag:" + tags.iterator().next();

        var key = BuiltInRegistries.ENTITY_TYPE.getKey(hit.getEntity().getType());
        return key == null ? null : key.toString();
    }

    // ── files ───────────────────────────────────────────────────────────────

    private static Draft load(Path file, String pack) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            KoperDialogData data = new KoperDialogData();
            data.applyJson(root);

            Draft d = new Draft();
            d.pack = pack;
            d.id = data.id == null ? file.getFileName().toString().replace(".json", "")
                : (data.id.contains(":") ? data.id.substring(data.id.indexOf(':') + 1) : data.id);
            d.title = data.title;
            d.speaker = data.speaker;

            for (var entry : data.nodes.entrySet()) {
                Node node = new Node(entry.getKey());
                node.text = entry.getValue().text;
                for (var choice : entry.getValue().choices)
                    node.choices.add(new String[]{ choice.text, choice.go });
                d.nodes.add(node);
            }
            return d;
        } catch (Exception broken) {
            KoperLib.LOGGER.warn("[Dialog] load failed for {}: {}", file, broken.toString());
            return null;
        }
    }

    private static void save(ServerPlayer player, Draft d) {
        if (d.pack.isEmpty()) { say(player, "§cpick a pack"); return; }
        if (d.id.isBlank()) { say(player, "§cid is required"); return; }
        if (d.nodes.isEmpty()) { say(player, "§cnothing to say yet"); return; }

        Path packDir = KoperLibDirectories.FULLPACKS.resolve(d.pack);
        if (!Files.isDirectory(packDir)) { say(player, "§cno such pack on disk"); return; }

        try {
            Path dir = packDir.resolve("dialogs");
            Files.createDirectories(dir);
            Path file = dir.resolve(d.id + ".json");
            Files.writeString(file, PRETTY.toJson(build(d)) + "\n");

            player.sendSystemMessage(Component.literal("§a[Dialog] wrote §f" + file));
            say(player, "§awrote " + d.id + ".json");
            reload(player);
        } catch (Exception failed) {
            KoperLib.LOGGER.warn("[Dialog] save failed: {}", failed.toString());
            say(player, "§csave failed, see log");
        }
    }

    private static JsonObject build(Draft d) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "dialog");
        root.addProperty("id", d.pack + ":" + d.id);
        root.addProperty("title", d.title.isBlank() ? d.id : d.title);
        if (!d.speaker.isBlank()) root.addProperty("speaker", d.speaker);
        root.addProperty("start", d.nodes.isEmpty() ? "intro" : d.nodes.getFirst().id);

        JsonObject nodes = new JsonObject();
        for (Node node : d.nodes) {
            JsonObject one = new JsonObject();
            one.addProperty("text", node.text);
            if (!node.choices.isEmpty()) {
                JsonArray choices = new JsonArray();
                for (String[] choice : node.choices) {
                    JsonObject c = new JsonObject();
                    c.addProperty("text", choice[0]);
                    if (!choice[1].isBlank()) c.addProperty("go", choice[1]);
                    choices.add(c);
                }
                one.add("choices", choices);
            }
            nodes.add(node.id, one);
        }
        root.add("nodes", nodes);
        return root;
    }

    private static void reload(ServerPlayer player) {
        var server = player.level().getServer();
        if (server != null) com.koper.koper_lib.loader.FullpackReloader.reload(server);
    }
}
