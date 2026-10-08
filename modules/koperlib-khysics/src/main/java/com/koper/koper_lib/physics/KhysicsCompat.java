package com.koper.koper_lib.physics;

import net.fabricmc.loader.api.FabricLoader;

// compat is built into khysics, this gate keeps optional mod classes asleep when they are absent
public final class KhysicsCompat {
    private KhysicsCompat() {}

    public static void init() {
        if (FabricLoader.getInstance().isModLoaded("create"))
            com.koper.koper_lib.compat.create.KoperCreateContraptions.init();
    }
}
