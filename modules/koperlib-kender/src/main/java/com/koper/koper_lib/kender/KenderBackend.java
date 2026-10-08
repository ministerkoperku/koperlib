package com.koper.koper_lib.kender;

// which path kender (koperlib's fork renderer) uses to draw geo geometry — surfaced in F3.
// MC_PIPELINE today (COLLECT_SUBMITS into MC's graph); flips to VULKAN once the direct VkBuffer/SSBO
// instancing path lands, or GL_FALLBACK when our Vulkan caps are missing.
public enum KenderBackend {
    MC_PIPELINE("MC pipeline"),
    VULKAN("Vulkan (direct)"),
    GL_FALLBACK("GL fallback");

    public final String label;
    KenderBackend(String label) { this.label = label; }

    private static volatile KenderBackend current = MC_PIPELINE;

    public static KenderBackend current() { return current; }
    public static void set(KenderBackend b) { if (b != null) current = b; }

    // does the GPU/driver expose Vulkan at all (decides if we can ever go direct-Vulkan vs GL fallback)
    public static boolean vulkanAvailable() {
        return com.koper.koper_lib.kender.KenderBridge.vulkanSupported();
    }

    public static String describe() {
        // /koperlib debug also runs on a dedicated server: there is no renderer to describe
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT)
            return "kender render: none (dedicated server)";
        KenderVk.tryInit();                      // hand MC's device to Rust once (logs handles + result)
        boolean mcVk = KenderVk.onVulkan();      // is MC actually running its Vulkan backend?
        String shared = mcVk ? " | shared: " + (KenderVk.deviceShared() ? "ok" : "—") : "";
        return "kender render: " + current.label + " | MC: " + (mcVk ? "Vulkan" : "OpenGL") + shared
            + (mcVk ? "" : " (GPU vk: " + (vulkanAvailable() ? "yes" : "no") + ")");
    }
}
