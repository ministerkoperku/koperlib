package com.koper.koper_lib.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;

// create's multiblocks (fluid tank, boiler, vault, item silo) do NOT put the merge into the
// blockstate — the BE holds a pointer to whichever part is the controller, and the model reads it.
// so any cache keyed on "what are my neighbours" is lying until the controller is part of the key.
// no create at runtime = every call is one map hit on a class we already gave up on. fine.
public final class KontraMultiSniffer {

    private KontraMultiSniffer() {}

    private static final java.util.Map<Class<?>, Method> LOOKUP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Method NONE;
    static {
        Method marker = null;
        try { marker = KontraMultiSniffer.class.getDeclaredMethod("noSuchMethod"); } catch (Throwable ignored) {}
        NONE = marker;
    }
    @SuppressWarnings("unused")
    private static void noSuchMethod() {}

    /** controller position RELATIVE to the block entity itself, hashed. 0 = not a multiblock part */
    public static long controllerHash(BlockEntity be) {
        if (be == null) return 0L;
        Method m = LOOKUP.computeIfAbsent(be.getClass(), KontraMultiSniffer::find);
        if (m == NONE) return 0L;
        try {
            if (!(m.invoke(be) instanceof BlockPos controller)) return 0L;
            BlockPos self = be.getBlockPos();
            // relative, so two tanks standing in the same spot of two identical multiblocks still
            // share one baked mesh instead of each burning its own
            long h = (controller.getX() - self.getX()) * 73856093L
                   ^ (controller.getY() - self.getY()) * 19349663L
                   ^ (controller.getZ() - self.getZ()) * 83492791L;
            return h == 0 ? 1L : h;  // 0 means "no multiblock", a controller sitting on us is not that
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Method find(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Method hit = scan(c);
            if (hit != null) return hit;
            for (Class<?> i : c.getInterfaces()) {
                hit = scan(i);
                if (hit != null) return hit;
            }
        }
        return NONE;
    }

    private static Method scan(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("getController")) continue;
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != BlockPos.class) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }
}
