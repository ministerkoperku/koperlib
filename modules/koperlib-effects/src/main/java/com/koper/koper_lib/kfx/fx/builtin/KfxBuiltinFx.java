package com.koper.koper_lib.kfx.fx.builtin;

/** Registers the effects KoperLib ships, on the client. */
public final class KfxBuiltinFx {
    private static boolean done;

    private KfxBuiltinFx() {}

    public static synchronized void register() {
        if (done) return;
        done = true;
        KfxLasers.register();
        KfxObjects.register();
    }
}
