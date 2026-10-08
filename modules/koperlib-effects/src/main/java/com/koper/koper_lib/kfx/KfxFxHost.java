package com.koper.koper_lib.kfx;

import com.koper.koper_lib.kfx.fx.KfxFx;
import com.koper.koper_lib.kfx.fx.KfxFxBook;
import com.koper.koper_lib.kfx.fx.KfxFxFrame;
import com.koper.koper_lib.kfx.fx.KfxFxSpec;
import com.koper.koper_lib.kfx.render.KfxQuality;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// runs the "fx" program op: one live KfxFx per effect and node, updated once a frame in the main pass
final class KfxFxHost {
    private static final Map<Long, Map<String, State>> LIVE = new HashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final float MAX_STEP = 3.0f;

    private KfxFxHost() {}

    private static final class State {
        final KfxFx fx;
        final double ox, oy, oz;
        final KfxFxFrame frame = new KfxFxFrame();
        float lastAge = Float.NaN;

        State(KfxFx fx, KfxInstance at) {
            this.fx = fx;
            ox = at.sx; oy = at.sy; oz = at.sz;
        }
    }

    static void draw(KfxDrawCtx ctx, KfxProgram.Op op) {
        KfxInstance inst = ctx.fx;
        String key = op.fx + "#" + (long)op.seed;
        Map<String, State> nodes = LIVE.computeIfAbsent(inst.id, id -> new HashMap<>());
        State state = nodes.get(key);
        if (state == null) {
            KfxFx.Factory factory = KfxFxBook.get(op.fx);
            if (factory == null) {
                if (WARNED.add(op.fx)) com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                    "[KFX] fx '{}' is not registered, that part of the effect does not draw. registered: {}",
                    op.fx, KfxFxBook.ids());
                return;
            }
            KfxFxSpec spec = new KfxFxSpec(op.fx, op.color != 0 ? op.color : inst.color, op.coreColor,
                op.size > 0 ? op.size : 0.12f, Math.max(1, op.count), op.intensity, op.speed, op.variant,
                op.glow, op.noise, ((long)op.seed << 20) ^ inst.id);
            try {
                state = new State(factory.create(spec), inst);
            } catch (RuntimeException e) {
                if (WARNED.add(op.fx + "!create")) com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                    "[KFX] fx '{}' does not work: it failed to start", op.fx, e);
                return;
            }
            nodes.put(key, state);
        }

        KfxFxFrame f = state.frame;
        fill(f, state, ctx, op);
        boolean glow = KfxRenderer.glowPass;
        try {
            if (!glow) {
                f.dt = Float.isNaN(state.lastAge) ? 0.0f : Math.clamp(ctx.age - state.lastAge, 0.0f, MAX_STEP);
                state.lastAge = ctx.age;
                state.fx.update(f);
            }
            state.fx.draw(f, new KfxPaint(ctx.pose, ctx.consumer, f, glow, KfxRenderer.glowActive));
        } catch (RuntimeException e) {
            nodes.remove(key);
            if (WARNED.add(op.fx + "!run")) com.koper.koper_lib.coremod.KoperCore.LOGGER.error(
                "[KFX] fx '{}' does not work: it threw while drawing and was removed", op.fx, e);
        }
    }

    private static void fill(KfxFxFrame f, State s, KfxDrawCtx ctx, KfxProgram.Op op) {
        KfxInstance fx = ctx.fx;
        f.age = ctx.age;
        f.fade = Math.clamp(ctx.fade * op.alpha, 0.0f, 1.0f);
        f.sx = (float)(fx.sx - s.ox); f.sy = (float)(fx.sy - s.oy); f.sz = (float)(fx.sz - s.oz);
        f.ex = (float)(fx.ex - s.ox); f.ey = (float)(fx.ey - s.oy); f.ez = (float)(fx.ez - s.oz);
        f.camX = KfxRenderer.camX + f.sx; f.camY = KfxRenderer.camY + f.sy; f.camZ = KfxRenderer.camZ + f.sz;
        float dx = f.ex - f.sx, dy = f.ey - f.sy, dz = f.ez - f.sz;
        float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        f.length = len;
        if (len > 1.0e-4f) {
            f.dx = dx / len; f.dy = dy / len; f.dz = dz / len;
        } else {
            var forward = ctx.basis.forward();
            f.dx = forward.x; f.dy = forward.y; f.dz = forward.z;
        }
        float ux = -f.dz, uy = 0, uz = f.dx;
        float ul = (float)Math.sqrt(ux * ux + uz * uz);
        if (ul < 1.0e-4f) { ux = 1; uz = 0; ul = 1; }
        f.ux = ux / ul; f.uy = uy; f.uz = uz / ul;
        f.vx = f.dy * f.uz - f.dz * f.uy; f.vy = f.dz * f.ux - f.dx * f.uz; f.vz = f.dx * f.uy - f.dy * f.ux;
        f.quality = switch (KfxQuality.configured()) { case LOW -> 0; case MEDIUM -> 1; case HIGH -> 2; };
    }

    static void drop(long id) {
        LIVE.remove(id);
    }

    static void clear() {
        LIVE.clear();
    }
}
