package com.koper.koper_lib.kfx;

public interface KfxBackend {
    String name();
    boolean available();
    boolean submit(KfxInstance fx);

    KfxBackend MC_PATH = new KfxBackend() {
        @Override public String name() { return "minecraft"; }
        @Override public boolean available() { return true; }
        @Override public boolean submit(KfxInstance fx) { KfxClient.spawn(fx); return true; }
    };

    KfxBackend KENDER_NATIVE = new KfxBackend() {
        @Override public String name() { return "kender_native"; }
        @Override public boolean available() {
            return com.koper.koper_lib.kender.KenderBridge.isOk();
        }
        @Override public boolean submit(KfxInstance fx) {
            try {
                var graph = com.koper.koper_lib.kfx.render.KfxNativeProgram.from(fx);
                long uploaded = com.koper.koper_lib.kender.KenderBridge.kfxGraphUpload(graph.encode());
                if (uploaded == graph.graphHash()
                    && com.koper.koper_lib.kender.KenderBridge.kfxGraphSpawn(
                        fx.id, graph.graphHash(),
                        com.koper.koper_lib.kfx.render.KfxNativeProgram.castSeed(fx),
                        com.koper.koper_lib.kfx.render.KfxQuality.configured().particleBudget(graph.maxParticles()),
                        fx.sx, fx.sy, fx.sz, fx.ex, fx.ey, fx.ez, KfxClient.bornTicks(fx))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Legacy programs keep their existing float[24] native path during migration.
            }
            float[] ops = KfxGpuCompiler.compile(fx);
            if (ops.length == 0) return false;
            return com.koper.koper_lib.kender.KenderBridge.kfxUploadProgram(fx.id, ops,
                fx.sx, fx.sy, fx.sz, fx.ex, fx.ey, fx.ez, KfxClient.bornTicks(fx)) == 0;
        }
    };
}
