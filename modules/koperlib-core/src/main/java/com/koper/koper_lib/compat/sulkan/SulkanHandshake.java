package com.koper.koper_lib.compat.sulkan;

import com.koper.koper_lib.coremod.KoperCore;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// Sulkan is someone else's mod and it's GPLv3 — we do NOT link it, we do NOT copy from it. Everything
// here is reflection against its public surface, and every single call fails soft to "not there".
// If Sulkan is absent, renamed, or refactored, KoperLib renders exactly like it always did.
//
// What we actually need from it: is it on, is it drawing its shadow map right now, and which cascade
// matrix to draw our kontraktions with. That's it.
public final class SulkanHandshake {

    private static final String RUNTIME = "com.sulkan.shaders.runtime.ShaderRuntime";
    private static final String SHADOWS = "com.sulkan.shaders.render.shadow.DirectionalShadowRenderer";

    private static boolean probed;
    private static boolean present;

    private static MethodHandle reloading;      // ShaderRuntime.resourceReloading()
    private static MethodHandle pipelineState;  // ShaderRuntime.pipelineState()
    private static MethodHandle shadersEnabled; // ShaderPipelineState.shadersEnabled()
    private static MethodHandle shadowsEnabled; // ShaderPipelineState.shadowsEnabled()
    private static MethodHandle renderingShadow;// DirectionalShadowRenderer.isRenderingShadowMap()
    private static MethodHandle activeCascade;  // DirectionalShadowRenderer.activeCascade()
    private static MethodHandle shadowGet;      // DirectionalShadowRenderer.get()
    private static MethodHandle cascadeMatrix;  // DirectionalShadowRenderer#cascadeMatrix(int)

    private SulkanHandshake() {}

    public static boolean present() {
        probe();
        return present;
    }

    private static synchronized void probe() {
        if (probed) return;
        probed = true;
        try {
            var cl = SulkanHandshake.class.getClassLoader();
            Class<?> runtime = Class.forName(RUNTIME, false, cl);
            Class<?> shadow = Class.forName(SHADOWS, false, cl);
            var lookup = MethodHandles.lookup();

            reloading = lookup.findStatic(runtime, "resourceReloading", MethodType.methodType(boolean.class));
            pipelineState = lookup.unreflect(runtime.getMethod("pipelineState"));
            Class<?> state = pipelineState.type().returnType();
            shadersEnabled = lookup.unreflect(state.getMethod("shadersEnabled"));
            shadowsEnabled = lookup.unreflect(state.getMethod("shadowsEnabled"));

            renderingShadow = lookup.findStatic(shadow, "isRenderingShadowMap", MethodType.methodType(boolean.class));
            activeCascade = lookup.findStatic(shadow, "activeCascade", MethodType.methodType(int.class));
            shadowGet = lookup.unreflect(shadow.getMethod("get"));
            cascadeMatrix = lookup.unreflect(shadow.getMethod("cascadeMatrix", int.class));

            present = true;
            KoperCore.LOGGER.info("[Sulkan] detected — kontraktion shadow compat armed");
        } catch (ClassNotFoundException none) {
            // not installed, which is the normal case
        } catch (Throwable t) {
            KoperCore.LOGGER.warn("[Sulkan] found but its API doesn't match what we expect — compat off ({})", t.toString());
        }
    }

    // shaders actually doing something this frame
    public static boolean shadersOn() {
        if (!present()) return false;
        try {
            if ((boolean) reloading.invoke()) return false;
            Object st = pipelineState.invoke();
            return st != null && (boolean) shadersEnabled.invoke(st);
        } catch (Throwable t) { return false; }
    }

    public static boolean shadowsOn() {
        if (!present()) return false;
        try {
            if ((boolean) reloading.invoke()) return false;
            Object st = pipelineState.invoke();
            return st != null && (boolean) shadowsEnabled.invoke(st);
        } catch (Throwable t) { return false; }
    }

    // true while Sulkan is filling its shadow map — the pass our geo draw has to join
    public static boolean inShadowMap() {
        if (!present()) return false;
        try { return (boolean) renderingShadow.invoke(); } catch (Throwable t) { return false; }
    }

    public static int cascade() {
        if (!present()) return -1;
        try { return (int) activeCascade.invoke(); } catch (Throwable t) { return -1; }
    }

    // light-space view-projection for the cascade being drawn. null = don't draw.
    public static float[] cascadeViewProj(float[] out) {
        if (!present() || out == null || out.length < 16) return null;
        try {
            int c = (int) activeCascade.invoke();
            if (c < 0) return null;
            Object renderer = shadowGet.invoke();
            if (renderer == null) return null;
            Object m = cascadeMatrix.invoke(renderer, c);
            if (!(m instanceof org.joml.Matrix4fc mat)) return null;
            mat.get(out);
            return out;
        } catch (Throwable t) { return null; }
    }
}
