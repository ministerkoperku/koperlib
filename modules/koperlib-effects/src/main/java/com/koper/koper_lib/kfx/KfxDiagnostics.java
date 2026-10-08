package com.koper.koper_lib.kfx;

import com.koper.koper_lib.kfx.graph.KfxCompiledGraph;
import com.koper.koper_lib.kfx.graph.KfxLinkedGraph;
import com.koper.koper_lib.kfx.render.KfxNativeProgram;
import com.koper.koper_lib.kfx.render.KfxPrimitiveRegistry;
import com.koper.koper_lib.kfx.render.KfxQuality;
import com.koper.koper_lib.kfx.render.KfxRenderCompiler;
import com.koper.koper_lib.kfx.runtime.KfxController;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Live, bounded metadata for developer inspection. Rendering remains client-local. */
public final class KfxDiagnostics {
    private static final int HARD_MAX_SNAPSHOTS = 4096;
    private static final Map<Long, Snapshot> LIVE = new ConcurrentHashMap<>();

    private KfxDiagnostics() {}

    public static void graph(long handle, KfxLinkedGraph linked, KfxCompiledGraph compiled, Vec3 start, Vec3 end) {
        if (handle == 0L || linked == null || compiled == null) return;
        String sources = linked.sourceChain().isEmpty() ? linked.graph().source()
            : linked.sourceChain().stream().map(ref -> ref.origin().name().toLowerCase() + ":" + ref.source())
                .distinct().reduce((left, right) -> left + " -> " + right).orElse(linked.graph().source());
        try {
            var plan = new KfxRenderCompiler(KfxPrimitiveRegistry.builtin()).lower(compiled, KfxQuality.configured());
            var nativeProgram = KfxNativeProgram.from(compiled);
            put(handle, new Snapshot(handle, compiled.id(), Long.toUnsignedString(nativeProgram.graphHash(), 16),
                sources, "auto (client chooses native/portable)", KfxQuality.configured().name().toLowerCase(),
                anchors(start, end), "none", 0, plan.cost().particles(), plan.cost().geometryUnits(),
                0, plan.cost().batches(), compiled.maxParticles(), "none"));
        } catch (RuntimeException problem) {
            put(handle, new Snapshot(handle, compiled.id(), "unavailable", sources,
                "auto (client chooses native/portable)", KfxQuality.configured().name().toLowerCase(),
                anchors(start, end), "none", 0, compiled.totalParticles(), 0, 0, 0,
                compiled.maxParticles(), problem.getClass().getSimpleName() + ": " + problem.getMessage()));
        }
    }

    public static void legacy(KfxInstance fx) {
        if (fx == null || fx.id == 0L) return;
        int particles = fx.kind == KfxDef.Kind.EMITTER ? Math.max(0, fx.maxParticles) : 0;
        put(fx.id, new Snapshot(fx.id, fx.kind.name().toLowerCase(), "legacy", "legacy/json definition",
            "auto (client chooses native/portable)", KfxQuality.configured().name().toLowerCase(),
            anchors(new Vec3(fx.sx, fx.sy, fx.sz), new Vec3(fx.ex, fx.ey, fx.ez)),
            "none", 0, particles, fx.kind == KfxDef.Kind.EMITTER ? 0 : 1,
            0, 1, Math.max(0, fx.maxParticles), "none"));
    }

    public static void controller(KfxController controller) {
        if (controller == null) return;
        LIVE.computeIfPresent(controller.handle(), (ignored, old) -> old.withController(
            controller.state().name().toLowerCase() + " age=" + controller.age()
                + " pos=" + compact(controller.position()) + " velocity=" + compact(controller.velocity())));
    }

    public static void collision(long handle, int cells, String response, long fingerprint) {
        LIVE.computeIfPresent(handle, (ignored, old) -> old.withCollision(cells,
            response + " field=" + Long.toUnsignedString(fingerprint, 16)));
    }

    public static void anchors(long handle, Vec3 start, Vec3 end) {
        LIVE.computeIfPresent(handle, (ignored, old) -> old.withAnchors(anchors(start, end)));
    }

    public static Snapshot snapshot(long handle) { return LIVE.get(handle); }
    public static void forget(long handle) { LIVE.remove(handle); }
    public static void clear() { LIVE.clear(); }

    public static List<String> describe(long handle) {
        Snapshot it = LIVE.get(handle);
        if (it == null) return List.of("[KFX] Unknown or expired handle " + handle + ".");
        return List.of(
            "[KFX] handle=" + it.handle + " graph=" + it.graphId + " hash=" + it.graphHash,
            " source=" + it.sourceChain,
            " backend=" + it.backend + " quality=" + it.quality + " anchors=" + it.anchors,
            " controller=" + it.controller + " sensors=" + it.sensors,
            " particles=" + it.particles + "/" + it.particleBudget + " geometry=" + it.geometry
                + " batches=" + it.batches + " collision_cells=" + it.collisionCells,
            " collision=" + it.collision + " last_problem=" + it.lastProblem
        );
    }

    private static void put(long handle, Snapshot snapshot) {
        if (LIVE.size() >= HARD_MAX_SNAPSHOTS && !LIVE.containsKey(handle)) {
            Long oldest = LIVE.keySet().stream().min(Long::compare).orElse(null);
            if (oldest != null) LIVE.remove(oldest);
        }
        LIVE.put(handle, snapshot);
    }

    private static String anchors(Vec3 start, Vec3 end) { return compact(start) + " -> " + compact(end); }
    private static String compact(Vec3 point) {
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", point.x, point.y, point.z);
    }

    public record Snapshot(long handle, String graphId, String graphHash, String sourceChain,
                           String backend, String quality, String anchors, String controller, int sensors,
                           int particles, int geometry, int collisionCells, int batches, int particleBudget,
                           String lastProblem, String collision) {
        private Snapshot(long handle, String graphId, String graphHash, String sourceChain,
                         String backend, String quality, String anchors, String controller, int sensors,
                         int particles, int geometry, int collisionCells, int batches, int particleBudget,
                         String lastProblem) {
            this(handle, graphId, graphHash, sourceChain, backend, quality, anchors, controller, sensors,
                particles, geometry, collisionCells, batches, particleBudget, lastProblem, "none");
        }
        Snapshot withController(String value) {
            return new Snapshot(handle, graphId, graphHash, sourceChain, backend, quality, anchors, value, sensors,
                particles, geometry, collisionCells, batches, particleBudget, lastProblem, collision);
        }
        Snapshot withCollision(int cells, String value) {
            return new Snapshot(handle, graphId, graphHash, sourceChain, backend, quality, anchors, controller, sensors,
                particles, geometry, cells, batches, particleBudget, lastProblem, value);
        }
        Snapshot withAnchors(String value) {
            return new Snapshot(handle, graphId, graphHash, sourceChain, backend, quality, value, controller, sensors,
                particles, geometry, collisionCells, batches, particleBudget, lastProblem, collision);
        }
    }
}
