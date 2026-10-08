package com.koper.koper_lib.physics;

/** Fullpack content/reload integration owned by Khysics. */
public final class KhysicsFullpackAddon implements Runnable {
    @Override public void run() {
        KhysicsFullpackCompat.registerContentTypes();
        com.koper.koper_lib.api.FullpackAddons.physics("khysics",
            new com.koper.koper_lib.api.FullpackAddons.Physics() {
                @Override public void force(long id, float x, float y, float z) {
                    var data = KoperPhys.all().get(id);
                    if (data != null) com.koper.koper_lib.panama.KoperPhysBridge.applyForce(
                        data.worldHandle(), id, x, y, z);
                }

                @Override public void impulse(long id, float x, float y, float z) {
                    var data = KoperPhys.all().get(id);
                    if (data != null) com.koper.koper_lib.panama.KoperPhysBridge.applyImpulse(
                        data.worldHandle(), id, x, y, z);
                }

                @Override public void selfRight(long id) {
                    var data = KoperPhys.all().get(id);
                    if (data != null) com.koper.koper_lib.panama.KoperPhysBridge.selfRight(data.worldHandle(), id);
                }

                @Override public void destroy(net.minecraft.server.MinecraftServer server, long id) {
                    KoperPhys.destroyKontraktion(server, id);
                }

                @Override public void restore(net.minecraft.server.MinecraftServer server, long id) {
                    KoperPhys.restoreToWorld(server, id);
                }
            });
        com.koper.koper_lib.api.FullpackAddons.reloadHook("khysics", () -> {
            com.koper.koper_lib.physics.weight.KhysWeightBook.loadAll();
            com.koper.koper_lib.physics.dim.KhysDimensions.loadAll();
        });
    }
}
