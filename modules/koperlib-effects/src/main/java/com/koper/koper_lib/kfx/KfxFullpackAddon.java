package com.koper.koper_lib.kfx;

import com.koper.koper_lib.api.FullpackAddons;
import com.koper.koper_lib.kfx.graph.KfxGraphs;

/** Discovered by Fullpack when both mods exist; not a separate compatibility mod. */
public final class KfxFullpackAddon implements Runnable {
    private static final java.util.concurrent.atomic.AtomicBoolean IMPACT_BRIDGE =
        new java.util.concurrent.atomic.AtomicBoolean();

    @Override public void run() {
        FullpackAddons.contentTypes("effects",
            java.util.List.of("kfx", "koperfx", "visual_effect", "vfx", "particle"),
            KfxBook::register);
        FullpackAddons.reloadHook("effects", KfxBook::clear);
        FullpackAddons.afterReloadHook("effects",
            () -> KfxBook.finishReload(FullpackAddons.reloadHealthy()));
        installImpactBridge();
        com.koper.koper_lib.scripting.LuaAddonRegistry.register("kfx", module -> module
            .function("declare", (context, arguments) -> {
                if (arguments == null || arguments.isEmpty()) {
                    FullpackAddons.markReloadFailure();
                    return new com.google.gson.JsonPrimitive(false);
                }
                try {
                    KfxLuaGraphs.declare(arguments.get(0), "lua:koper.kfx.declare");
                } catch (RuntimeException error) {
                    // The FFM query boundary converts Java exceptions to a Lua nil result. Mark the
                    // scan here so a failed static declaration can never commit a partial snapshot.
                    FullpackAddons.markReloadFailure();
                    throw error;
                }
                return new com.google.gson.JsonPrimitive(true);
            })
        );
        FullpackAddons.effects("effects", new FullpackAddons.Effects() {
            @Override public long spawn(net.minecraft.server.level.ServerLevel level, String effectId,
                                        double sx, double sy, double sz, double ex, double ey, double ez) {
                return KfxApi.spawn(level, effectId, sx, sy, sz, ex, ey, ez);
            }

            @Override public void update(net.minecraft.server.level.ServerLevel level, long id,
                                         double sx, double sy, double sz, double ex, double ey, double ez) {
                KfxApi.update(level, id, sx, sy, sz, ex, ey, ez);
            }

            @Override public void stop(net.minecraft.server.MinecraftServer server, long id) {
                for (var level : server.getAllLevels()) KfxApi.stop(level, id);
                KfxScriptBridge.forget(id);
            }

            @Override public void attach(net.minecraft.server.level.ServerLevel level, long id,
                                         net.minecraft.world.entity.Entity entity,
                                         double ox, double oy, double oz,
                                         double ex, double ey, double ez, boolean endRelative) {
                KfxApi.attach(level, id, entity, ox, oy, oz, ex, ey, ez, endRelative);
            }

            @Override public long program(net.minecraft.server.level.ServerLevel level, String json,
                                          double sx, double sy, double sz, double ex, double ey, double ez) {
                var object = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                var definition = KfxDef.fromJson(object,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("koper_lib", "script_program"));
                long id = System.nanoTime() ^ json.hashCode();
                KfxApi.spawn(level, id, definition, sx, sy, sz, ex, ey, ez);
                return id;
            }

            @Override public void declareGraph(String graphJson) {
                KfxScriptBridge.declare(graphJson);
            }

            @Override public long playGraph(net.minecraft.server.MinecraftServer server, long id,
                                            String graphJson, String optionsJson) {
                return KfxScriptBridge.play(server, id, graphJson, optionsJson);
            }

            @Override public void setHandle(net.minecraft.server.MinecraftServer server, long id,
                                            String name, String valueJson) {
                KfxScriptBridge.set(id, name, valueJson);
            }

            @Override public void reanchorHandle(net.minecraft.server.MinecraftServer server, long id,
                                                 String startJson, String endJson) {
                KfxScriptBridge.reanchor(id, startJson, endJson);
            }

            @Override public void detachHandle(net.minecraft.server.MinecraftServer server, long id) {
                KfxScriptBridge.detach(id);
            }

            @Override public void signalHandle(net.minecraft.server.MinecraftServer server, long id,
                                               String name, String dataJson) {
                var envelope = new com.google.gson.JsonObject();
                envelope.addProperty("handle", id);
                envelope.addProperty("signal", name);
                envelope.add("data", com.google.gson.JsonParser.parseString(dataJson));
                com.koper.koper_lib.scripting.UniversalScriptEngine.fireKfxEvent(
                    "kfx:signal:" + name, envelope.toString());
            }
        });
    }

    private static void installImpactBridge() {
        if (!IMPACT_BRIDGE.compareAndSet(false, true)) return;
        KfxApi.addEventListener((level, impact) -> {
            var json = new com.google.gson.JsonObject();
            json.addProperty("handle", impact.handle());
            json.addProperty("node", impact.nodeId());
            json.addProperty("sequence", impact.sequence());
            json.addProperty("owner", impact.owner().toString());
            if (impact.entity() != null) json.addProperty("entity", impact.entity().toString());
            if (impact.block() != null) {
                var block = new com.google.gson.JsonObject();
                block.addProperty("x", impact.block().getX());
                block.addProperty("y", impact.block().getY());
                block.addProperty("z", impact.block().getZ());
                json.add("block", block);
            }
            json.add("position", vector(impact.position()));
            json.add("normal", vector(impact.normal()));
            json.add("incoming_velocity", vector(impact.incomingVelocity()));
            json.add("outgoing_velocity", vector(impact.outgoingVelocity()));
            json.addProperty("bounce", impact.bounce());
            json.addProperty("seed", impact.seed());
            var inputs = new com.google.gson.JsonObject();
            impact.inputs().forEach((name, value) -> inputs.add(name, switch (value.type()) {
                case NUMBER -> new com.google.gson.JsonPrimitive((double)value.value());
                case INTEGER, COLOR -> new com.google.gson.JsonPrimitive((int)value.value());
                case TEXT -> new com.google.gson.JsonPrimitive((String)value.value());
            }));
            json.add("inputs", inputs);
            com.koper.koper_lib.scripting.UniversalScriptEngine.fireKfxEvent("kfx:impact", json.toString());
        });
    }

    private static com.google.gson.JsonObject vector(net.minecraft.world.phys.Vec3 value) {
        var json = new com.google.gson.JsonObject();
        json.addProperty("x", value.x);
        json.addProperty("y", value.y);
        json.addProperty("z", value.z);
        return json;
    }
}
