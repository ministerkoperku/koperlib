package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.KfxApi;
import com.koper.koper_lib.kfx.KfxDef;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Compiles one immutable graph instance at cast time, then sends only the resolved program to clients. */
public final class KfxRuntime {
    private static final AtomicLong HANDLES = new AtomicLong(Math.max(1L, System.nanoTime() & Long.MAX_VALUE));

    private KfxRuntime() {}

    public static Spawn spawn(
        ServerLevel level,
        KfxGraph graph,
        long castSeed,
        Map<String, KfxResolvedValue> arguments,
        Vec3 start,
        Vec3 end
    ) {
        if (level == null || graph == null || start == null || end == null) {
            throw new IllegalArgumentException("KFX spawn needs a level, graph, start, and end");
        }
        return spawn(level, nextInstanceId(), graph, castSeed, arguments, start, end);
    }

    public static Spawn spawn(
        ServerLevel level,
        long instanceId,
        KfxGraph graph,
        long castSeed,
        Map<String, KfxResolvedValue> arguments,
        Vec3 start,
        Vec3 end
    ) {
        if (instanceId == 0L) throw new IllegalArgumentException("KFX instance id cannot be zero");
        if (level == null || graph == null || start == null || end == null) {
            throw new IllegalArgumentException("KFX spawn needs a level, graph, start, and end");
        }
        KfxCompiledGraph compiled = KfxGraphCompiler.compile(graph, castSeed, arguments);
        KfxDef backendDefinition = backendDefinition(compiled);
        KfxApi.spawn(level, instanceId, backendDefinition, start.x, start.y, start.z, end.x, end.y, end.z);
        KfxLinkedGraph linked = KfxGraphs.runtimeLinked(graph.id());
        if (linked == null) linked = new KfxLinkedGraph(graph, java.util.List.of());
        com.koper.koper_lib.kfx.KfxDiagnostics.graph(instanceId, linked, compiled, start, end);
        return new Spawn(instanceId, compiled);
    }

    public static long nextInstanceId() {
        long id = HANDLES.getAndIncrement();
        if (id != 0) return id;
        return HANDLES.getAndIncrement();
    }

    private static KfxDef backendDefinition(KfxCompiledGraph compiled) {
        int color = firstColor(compiled);
        double radius = firstNumber(compiled, ".radius", 1.0);

        JsonObject backend = new JsonObject();
        backend.addProperty("id", compiled.id());
        backend.addProperty("shape", "particle");
        backend.addProperty("color", colorString(color));
        backend.addProperty("color2", "#ffffffff");
        backend.addProperty("radius", radius);
        backend.addProperty("thickness", 0.14);
        backend.addProperty("spin_y", 1.0);
        backend.addProperty("lifetime", compiled.lifetime());
        backend.addProperty("fade_in", 2);
        backend.addProperty("fade_out", 8);
        JsonObject emitter = new JsonObject();
        emitter.addProperty("max_particles", compiled.maxParticles());
        backend.add("emitter", emitter);
        backend.add("program", JsonParser.parseString(compiled.programJson()).getAsJsonObject());
        return KfxDef.fromJson(backend, Identifier.parse(compiled.id()));
    }

    private static int firstColor(KfxCompiledGraph compiled) {
        for (Map.Entry<String, KfxResolvedValue> entry : compiled.sampledValues().entrySet()) {
            if (entry.getKey().endsWith(".color") && entry.getValue().type() == KfxValueType.COLOR) {
                return entry.getValue().asColor(entry.getKey());
            }
        }
        return 0xFF55CCFF;
    }

    private static double firstNumber(KfxCompiledGraph compiled, String suffix, double fallback) {
        for (Map.Entry<String, KfxResolvedValue> entry : compiled.sampledValues().entrySet()) {
            if (entry.getKey().endsWith(suffix)
                && (entry.getValue().type() == KfxValueType.NUMBER || entry.getValue().type() == KfxValueType.INTEGER)) {
                return entry.getValue().asNumber(entry.getKey());
            }
        }
        return fallback;
    }

    private static String colorString(int argb) {
        return String.format(java.util.Locale.ROOT, "#%08x", argb);
    }

    public record Spawn(long instanceId, KfxCompiledGraph compiled) {}
}
