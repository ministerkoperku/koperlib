package com.koper.koper_lib.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.List;

// create swaps block models for wrappers that decide their geometry from world context, and a
// cogwheel's wrapper answers "no parts" in the real world because the flywheel visual draws the
// whole thing. the plain collectParts() call skips that decision and hands back the full model,
// which is the static cog we were drawing on top of the spinning one.
//
// so ask the context aware method when the model has one. plain models do not, and fall through.
public final class KenderModelContext {

    private KenderModelContext() {}

    private static final java.util.Map<Class<?>, Method> LOOKUP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Method NONE;
    static {
        Method marker = null;
        try { marker = KenderModelContext.class.getDeclaredMethod("noSuchMethod"); } catch (Throwable ignored) {}
        NONE = marker;
    }
    @SuppressWarnings("unused")
    private static void noSuchMethod() {}

    /** true = the model answered with context and `out` is authoritative, even when empty */
    public static boolean collectWithContext(Object model, BlockAndTintGetter world, BlockPos pos,
                                             BlockState state, RandomSource random, List<?> out) {
        if (model == null || world == null) return false;
        Method m = LOOKUP.computeIfAbsent(model.getClass(), KenderModelContext::find);
        if (m == NONE) return false;
        try {
            m.invoke(model, world, pos, state, random, out);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method find(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("addPartsWithInfo")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 5) continue;
                if (!p[0].isAssignableFrom(BlockAndTintGetter.class) && !BlockAndTintGetter.class.isAssignableFrom(p[0])) continue;
                if (p[1] != BlockPos.class || p[2] != BlockState.class) continue;
                m.setAccessible(true);
                return m;
            }
        }
        return NONE;
    }
}
