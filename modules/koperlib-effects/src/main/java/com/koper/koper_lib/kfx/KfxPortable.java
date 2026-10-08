package com.koper.koper_lib.kfx;

// Explored: draw KFX particles directly via a custom RenderPass + RenderPipeline (Rust-built vertex buffer, one draw).
// It compiles and runs with no errors, but the pass never composites onto the visible framebuffer — a hardcoded
// NDC test quad with an identity transform never showed. So the custom-pass injection at LevelRenderer TAIL is a
// dead end (wrong target / timing in MC 26.2's deferred render graph).
//
// KFX particles instead render through MC's OWN pipeline (KfxRenderer COLLECT_SUBMITS) as cheap billboards — that
// path is GPU-accelerated by MC and portable across Vulkan AND OpenGL, and performs well. The Rust gather/emitter
// sim + the kender_kfx_build_vertices FFI are left in place for a future salvage attempt on the direct GPU path.
public final class KfxPortable {
    private KfxPortable() {}
}
