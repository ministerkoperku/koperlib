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

                @Override public java.util.List<Assembly> assemblies(net.minecraft.server.level.ServerLevel level) {
                    var out = new java.util.ArrayList<Assembly>();
                    for (long root : KoperPhys.freeAssemblyRoots(level)) {
                        float[] p = KoperPhys.getCachedPos(root);
                        if (p == null) continue;
                        float[] d = KoperPhys.getKontraVelocity(root);
                        var velocity = d == null ? net.minecraft.world.phys.Vec3.ZERO
                            : new net.minecraft.world.phys.Vec3(d[0] * 20.0, d[1] * 20.0, d[2] * 20.0);
                        out.add(new Assembly(root, new net.minecraft.world.phys.Vec3(p[0], p[1], p[2]), velocity));
                    }
                    return out;
                }

                @Override public boolean carries(net.minecraft.world.entity.Entity entity) {
                    return entity instanceof KontraSeat || entity.getVehicle() instanceof KontraSeat || KontraGlue.tracked(entity);
                }

                @Override public long transfer(long root, net.minecraft.server.level.ServerLevel target,
                                               net.minecraft.world.phys.Vec3 where) {
                    var moved = KoperPhys.transferAssembly(root, target,
                        new float[]{(float) where.x, (float) where.y, (float) where.z}, 0);
                    return moved == null ? 0L : moved.root();
                }
            });
        com.koper.koper_lib.api.FullpackAddons.reloadHook("khysics", () -> {
            com.koper.koper_lib.physics.weight.KhysWeightBook.loadAll();
            com.koper.koper_lib.physics.dim.KhysDimensions.loadAll();
        });
    }
}
