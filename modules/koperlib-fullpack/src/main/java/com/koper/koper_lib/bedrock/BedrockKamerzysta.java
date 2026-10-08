package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.network.BedrockKameraPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// server half of bedrock's camera: player.camera.setCamera / fade / clear from scripts and the /camera
// command. it only packs what the pack asked for and sends it to that player, BedrockKamera (client) does the rest
final class BedrockKamerzysta {

    private BedrockKamerzysta() {}

    static void wyslij(ServerPlayer p, JsonObject o) {
        if (ServerPlayNetworking.canSend(p, BedrockKameraPayload.TYPE)) ServerPlayNetworking.send(p, new BedrockKameraPayload(o.toString()));
    }

    // scripts: {"op":"camera","e":<player>,"a":"set"|"clear"|"fade", ...the payload fields}
    static JsonElement handle(JsonObject q) {
        Entity e = BedrockPytajnik.find(BedrockPytajnik.s(q, "e"));
        if (!(e instanceof ServerPlayer p)) return null;
        JsonObject o = q.deepCopy();
        o.remove("op");
        o.remove("e");
        // the client knows entities by network id, scripts by uuid
        if (o.has("facingUuid")) {
            Entity f = BedrockPytajnik.find(o.remove("facingUuid").getAsString());
            if (f != null) o.addProperty("facingEntity", f.getId());
        }
        wyslij(p, o);
        return null;
    }

    // /camera <who> clear | fade [time <in> <hold> <out>] [color <r> <g> <b>]
    //   | set <preset> [ease <time> <type>] [pos <x y z>] [rot <xRot> <yRot>] [facing <x y z>] [default]
    static JsonObject parse(String[] w, CommandSourceStack src) {
        JsonObject o = new JsonObject();
        if (w.length == 0) return null;
        String a = w[0].toLowerCase(Locale.ROOT);
        Vec3 at = src.getPosition();
        switch (a) {
            case "clear" -> o.addProperty("a", "clear");
            case "fade" -> {
                o.addProperty("a", "fade");
                // bedrock's defaults when a part is left out
                o.addProperty("in", 0.5f);
                o.addProperty("hold", 0.5f);
                o.addProperty("out", 0.5f);
                for (int i = 1; i < w.length; i++) {
                    if (w[i].equals("time") && i + 3 < w.length) {
                        o.addProperty("in", Float.parseFloat(w[i + 1]));
                        o.addProperty("hold", Float.parseFloat(w[i + 2]));
                        o.addProperty("out", Float.parseFloat(w[i + 3]));
                        i += 3;
                    } else if (w[i].equals("color") && i + 3 < w.length) {
                        int r = Integer.parseInt(w[i + 1]), g = Integer.parseInt(w[i + 2]), b = Integer.parseInt(w[i + 3]);
                        o.addProperty("rgb", (r & 255) << 16 | (g & 255) << 8 | (b & 255));
                        i += 3;
                    }
                }
            }
            case "set" -> {
                if (w.length < 2) return null;
                o.addProperty("a", "set");
                o.addProperty("preset", w[1].contains(":") ? w[1] : "minecraft:" + w[1]);
                for (int i = 2; i < w.length; i++) {
                    switch (w[i]) {
                        case "ease" -> {
                            if (i + 2 < w.length) { o.addProperty("ease", Float.parseFloat(w[i + 1])); o.addProperty("easeType", w[i + 2]); i += 2; }
                        }
                        case "pos" -> {
                            if (i + 3 < w.length) { o.add("pos", v(wsp(w[i + 1], at.x), wsp(w[i + 2], at.y), wsp(w[i + 3], at.z))); i += 3; }
                        }
                        case "rot" -> {
                            if (i + 2 < w.length) {
                                JsonArray r = new JsonArray();
                                r.add(wsp(w[i + 1], src.getRotation().x));
                                r.add(wsp(w[i + 2], src.getRotation().y));
                                o.add("rot", r);
                                i += 2;
                            }
                        }
                        case "facing" -> {
                            if (i + 3 < w.length && czyLiczba(w[i + 1])) {
                                o.add("facing", v(wsp(w[i + 1], at.x), wsp(w[i + 2], at.y), wsp(w[i + 3], at.z)));
                                i += 3;
                            } else if (i + 1 < w.length) {
                                try {
                                    var ents = net.minecraft.commands.arguments.EntityArgument.entity()
                                        .parse(new com.mojang.brigadier.StringReader(w[i + 1])).findSingleEntity(src);
                                    o.addProperty("facingEntity", ents.getId());
                                } catch (Exception ignored) {}
                                i += 1;
                            }
                        }
                        default -> {}
                    }
                }
            }
            default -> { return null; }
        }
        return o;
    }

    private static boolean czyLiczba(String s) {
        return s.startsWith("~") || s.startsWith("^") || s.matches("-?[0-9.]+");
    }

    private static double wsp(String w, double base) {
        if (w.startsWith("~") || w.startsWith("^")) return base + (w.length() > 1 ? Double.parseDouble(w.substring(1)) : 0);
        return Double.parseDouble(w);
    }

    private static JsonArray v(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
