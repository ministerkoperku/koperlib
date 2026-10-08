package com.koper.koper_lib.kfx;

// one program stage. builtins ship a native opcode, addon ops can be java-only.
// just implement draw() and hand it to KfxOps.register / KfxApi.registerOp
@FunctionalInterface
public interface KfxOp {
    void draw(KfxDrawCtx ctx, KfxProgram.Op op);
}
