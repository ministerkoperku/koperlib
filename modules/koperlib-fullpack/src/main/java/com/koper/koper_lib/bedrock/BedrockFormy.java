package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koper.koper_lib.KoperLib;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// @minecraft/server-ui forms shown as vanilla dialogs. every button is a "custom" click action
// that comes back to us as a ServerboundCustomClickActionPacket (see BedrockDialogMixin)
public final class BedrockFormy {

    public static final Identifier CLICK = Identifier.fromNamespaceAndPath(KoperLib.MOD_ID, "bedrock_form");

    private record Czeka(BedrockSkrypciarz.Addon addon, UUID player, String kind, java.util.List<String> inputs) {}

    private static final Map<Integer, Czeka> CZEKA = new HashMap<>();
    private static int nextId = 1;

    private BedrockFormy() {}

    static void clear() {
        CZEKA.clear();
    }

    static JsonElement show(ServerPlayer p, JsonObject f, BedrockSkrypciarz.Addon who) {
        if (who == null) return null;
        int fid = nextId++;
        // bedrock never stacks forms: player still loading, in a menu or looking at another form -> UserBusy.
        // packs that re-show a form every few ticks until it gets answered (rlcraft does) rely on this
        if (isBusy(p)) {
            JsonObject msg = new JsonObject();
            msg.addProperty("t", "form");
            msg.addProperty("fid", fid);
            msg.addProperty("canceled", true);
            msg.addProperty("reason", "UserBusy");
            BedrockSkrypciarz.fireTo(who, msg.toString());
            return new JsonPrimitive(fid);
        }
        String kind = f.get("kind").getAsString();
        JsonObject d = new JsonObject();
        d.addProperty("type", "minecraft:multi_action");
        d.add("title", f.get("title"));
        d.addProperty("can_close_with_escape", true);
        d.addProperty("after_action", "close");
        JsonArray body = new JsonArray();
        JsonArray inputs = new JsonArray();
        JsonArray actions = new JsonArray();
        if (f.has("body") && !f.get("body").toString().equals("{\"text\":\"\"}")) body.add(plain(f.get("body")));

        int inputCount = 0;
        java.util.List<String> kinds = new java.util.ArrayList<>();
        switch (kind) {
            case "action" -> {
                int button = 0;
                for (JsonElement it : f.getAsJsonArray("items")) {
                    JsonObject i = it.getAsJsonObject();
                    switch (i.get("t").getAsString()) {
                        case "button" -> actions.add(button(i.get("label"), fid, button++));
                        case "label", "header" -> body.add(plain(i.get("label")));
                        default -> {}
                    }
                }
            }
            case "message" -> {
                // bedrock: button1 is selection 0, button2 is selection 1
                actions.add(button(f.get("b1"), fid, 0));
                // a 2.x MessageBox may have only the one button
                if (f.has("b2") && !f.get("b2").isJsonNull()) actions.add(button(f.get("b2"), fid, 1));
            }
            case "modal" -> {
                inputCount = pola(f, body, inputs, kinds, null, fid);
                JsonObject submit = new JsonObject();
                submit.add("label", f.get("submit"));
                JsonObject act = new JsonObject();
                act.addProperty("type", "minecraft:dynamic/custom");
                act.addProperty("id", CLICK.toString());
                JsonObject add = new JsonObject();
                add.addProperty("fid", fid);
                act.add("additions", add);
                submit.add("action", act);
                actions.add(submit);
            }
            case "custom" -> {
                // 2.x CustomForm: its buttons, each sending the fields along (dynamic/custom), and the fields
                inputCount = pola(f, body, inputs, kinds, actions, fid);
                // fields and no button: java needs something to send them with, a "done" that clicks nothing
                if (actions.isEmpty() && inputCount > 0) {
                    JsonObject done = new JsonObject();
                    done.addProperty("t", "button");
                    JsonObject napis = new JsonObject();
                    napis.addProperty("translate", "gui.done");
                    done.add("label", napis);
                    done.addProperty("n", -2);
                    JsonObject fake = new JsonObject();
                    JsonArray one = new JsonArray();
                    one.add(done);
                    fake.add("items", one);
                    pola(fake, new JsonArray(), new JsonArray(), new java.util.ArrayList<>(), actions, fid);
                }
            }
            default -> { return null; }
        }
        if (!body.isEmpty()) d.add("body", body);
        if (!inputs.isEmpty()) d.add("inputs", inputs);
        if (actions.isEmpty()) actions.add(button(new JsonPrimitive("OK"), fid, 0));
        d.add("actions", actions);
        JsonObject exit = new JsonObject();
        exit.add("label", new JsonPrimitive("x"));
        exit.add("action", click(fid, -1));
        d.add("exit_action", exit);
        d.addProperty("columns", 1);

        var parsed = Dialog.DIRECT_CODEC.parse(p.level().getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE), d);
        if (parsed.error().isPresent()) {
            KoperLib.LOGGER.warn("[Bedrock] form did not turn into a dialog: {}", parsed.error().get().message());
            return null;
        }
        CZEKA.put(fid, new Czeka(who, p.getUUID(), kind, kinds));
        p.openDialog(Holder.direct(parsed.result().get()));
        return new JsonPrimitive(fid);
    }

    private static boolean isBusy(ServerPlayer p) {
        if (!p.connection.hasClientLoaded() || p.containerMenu != p.inventoryMenu) return true;
        for (Czeka c : CZEKA.values()) if (c.player().equals(p.getUUID())) return true;
        return false;
    }

    // modal and custom forms: labels into the body, fields into inputs (key i<n>), and for a custom form its
    // buttons as actions that send the fields along. returns how many fields there are
    private static int pola(JsonObject f, JsonArray body, JsonArray inputs, java.util.List<String> kinds, JsonArray buttons, int fid) {
        int inputCount = 0;
        for (JsonElement it : f.getAsJsonArray("items")) {
            JsonObject i = it.getAsJsonObject();
            String t = i.get("t").getAsString();
            if (t.equals("button")) {
                if (buttons == null) continue;
                JsonObject b = new JsonObject();
                b.add("label", i.get("label"));
                JsonObject act = new JsonObject();
                act.addProperty("type", "minecraft:dynamic/custom");
                act.addProperty("id", CLICK.toString());
                JsonObject add = new JsonObject();
                add.addProperty("fid", fid);
                add.addProperty("b", i.get("n").getAsInt());
                act.add("additions", add);
                b.add("action", act);
                buttons.add(b);
                continue;
            }
            if (t.equals("label") || t.equals("header") || t.equals("divider")) {
                if (!t.equals("divider")) body.add(plain(i.get("label")));
                continue;
            }
            JsonObject in = new JsonObject();
            in.addProperty("key", "i" + inputCount++);
            in.add("label", i.get("label"));
            switch (t) {
                case "text" -> {
                    in.addProperty("type", "minecraft:text");
                    in.addProperty("initial", i.get("def").getAsString());
                    in.addProperty("max_length", 256);
                }
                case "toggle" -> {
                    in.addProperty("type", "minecraft:boolean");
                    in.addProperty("initial", i.get("def").getAsBoolean());
                }
                case "dropdown" -> {
                    in.addProperty("type", "minecraft:single_option");
                    JsonArray opts = new JsonArray();
                    int n = 0, def = i.get("def").getAsInt();
                    for (JsonElement o : i.getAsJsonArray("opts")) {
                        JsonObject op = new JsonObject();
                        op.addProperty("id", String.valueOf(n));
                        op.add("display", o);
                        if (n == def) op.addProperty("initial", true);
                        opts.add(op);
                        n++;
                    }
                    in.add("options", opts);
                }
                case "slider" -> {
                    in.addProperty("type", "minecraft:number_range");
                    in.addProperty("start", i.get("min").getAsFloat());
                    in.addProperty("end", i.get("max").getAsFloat());
                    in.addProperty("step", i.get("step").getAsFloat());
                    in.addProperty("initial", i.get("def").getAsFloat());
                }
                default -> { inputCount--; continue; }
            }
            kinds.add(t);
            inputs.add(in);
        }
        return inputCount;
    }

    private static JsonObject plain(JsonElement text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:plain_message");
        o.add("contents", text);
        return o;
    }

    private static JsonObject click(int fid, int button) {
        JsonObject act = new JsonObject();
        act.addProperty("type", "minecraft:custom");
        act.addProperty("id", CLICK.toString());
        JsonObject payload = new JsonObject();
        payload.addProperty("fid", fid);
        payload.addProperty("b", button);
        act.add("payload", payload);
        return act;
    }

    private static JsonObject button(JsonElement label, int fid, int n) {
        JsonObject b = new JsonObject();
        b.add("label", label);
        b.add("action", click(fid, n));
        return b;
    }

    // BedrockDialogMixin lands here, already on the server thread
    public static void clicked(ServerPlayer p, Identifier id, Optional<Tag> payload) {
        if (!CLICK.equals(id) || payload.isEmpty()) return;
        JsonElement j = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, payload.get());
        if (!j.isJsonObject() || !j.getAsJsonObject().has("fid")) return;
        JsonObject o = j.getAsJsonObject();
        int fid = o.get("fid").getAsInt();
        Czeka c = CZEKA.remove(fid);
        if (c == null || !c.player().equals(p.getUUID())) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("t", "form");
        msg.addProperty("fid", fid);
        int b = o.has("b") ? o.get("b").getAsInt() : 0;
        // -1 is the x in the corner, -2 a custom form's "done" that clicks no button
        if (b == -1) {
            msg.addProperty("canceled", true);
            msg.addProperty("reason", "UserClosed");
        } else if (c.kind().equals("modal") || c.kind().equals("custom")) {
            JsonArray vals = new JsonArray();
            for (int i = 0; i < c.inputs().size(); i++) {
                JsonElement v = o.get("i" + i);
                if (v == null || !v.isJsonPrimitive()) { vals.add(com.google.gson.JsonNull.INSTANCE); continue; }
                switch (c.inputs().get(i)) {
                    // dropdown ids are the option index as a string, bedrock wants the number
                    case "dropdown" -> vals.add(Integer.parseInt(v.getAsString()));
                    // nbt booleans come in as bytes
                    case "toggle" -> vals.add(v.getAsJsonPrimitive().isBoolean() ? v.getAsBoolean() : v.getAsInt() != 0);
                    case "slider" -> vals.add(v.getAsDouble());
                    default -> vals.add(v.getAsString());
                }
            }
            msg.add("vals", vals);
            if (c.kind().equals("custom")) msg.addProperty("sel", b);
        } else {
            msg.addProperty("sel", b);
        }
        BedrockSkrypciarz.fireTo(c.addon(), msg.toString());
    }

    static void close(ServerPlayer p) {
        p.closeContainer();
        CZEKA.entrySet().removeIf(e -> {
            if (!e.getValue().player().equals(p.getUUID())) return false;
            JsonObject msg = new JsonObject();
            msg.addProperty("t", "form");
            msg.addProperty("fid", e.getKey());
            msg.addProperty("canceled", true);
            msg.addProperty("reason", "UserClosed");
            BedrockSkrypciarz.fireTo(e.getValue().addon(), msg.toString());
            return true;
        });
    }

    public static void playerLeft(ServerPlayer p) {
        CZEKA.entrySet().removeIf(e -> {
            if (!e.getValue().player().equals(p.getUUID())) return false;
            JsonObject msg = new JsonObject();
            msg.addProperty("t", "form");
            msg.addProperty("fid", e.getKey());
            msg.addProperty("reject", "PlayerQuit");
            BedrockSkrypciarz.fireTo(e.getValue().addon(), msg.toString());
            return true;
        });
    }
}
