package com.koper.koper_lib.kfx;

public final class KfxApi {
    private KfxApi() {}

    public static com.koper.koper_lib.kfx.graph.KfxGraphBuilder graph(String id) {
        return com.koper.koper_lib.kfx.graph.KfxGraphBuilder.graph(id);
    }

    public static void declareGraph(com.koper.koper_lib.kfx.graph.KfxGraph graph) {
        com.koper.koper_lib.kfx.graph.KfxGraphs.declare(
            com.koper.koper_lib.kfx.graph.KfxOrigin.JAVA, graph
        );
    }

    public static com.koper.koper_lib.kfx.graph.KfxLinkedGraph linkedGraph(String id) {
        return com.koper.koper_lib.kfx.graph.KfxGraphs.linked(id);
    }

    public static void launchController(net.minecraft.server.level.ServerLevel level,
                                        com.koper.koper_lib.kfx.runtime.KfxController controller) {
        if (level == null || controller == null) throw new IllegalArgumentException("KFX controller needs a level");
        com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.launch(level, controller);
    }

    public static long spawnControlled(net.minecraft.server.level.ServerLevel level, KfxDef def,
                                       com.koper.koper_lib.kfx.runtime.KfxController controller) {
        if (level == null || def == null || controller == null) {
            throw new IllegalArgumentException("controlled KFX needs a level, visual and controller");
        }
        com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.launch(level, controller);
        try {
            var start = controller.position();
            var end = start.add(controller.velocity());
            spawn(level, controller.handle(), def, start.x, start.y, start.z, end.x, end.y, end.z);
            return controller.handle();
        } catch (RuntimeException error) {
            com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.stop(level, controller.handle());
            com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
                new com.koper.koper_lib.network.KfxStopPayload(controller.handle()));
            throw error;
        }
    }

    // controller + graph v2 visual on one handle. no anchors, the controller's motion drives it.
    // rolls the controller back if the spawn throws, otherwise a rejected cap leaves an orphan effect
    public static long spawnControlledGraph(
        net.minecraft.server.level.ServerLevel level,
        String graphId,
        long castSeed,
        java.util.Map<String, com.koper.koper_lib.kfx.graph.KfxResolvedValue> arguments,
        com.koper.koper_lib.kfx.runtime.KfxController controller
    ) {
        if (level == null || graphId == null || controller == null) {
            throw new IllegalArgumentException("controlled KFX graph needs a level, graph id and controller");
        }
        var linked = linkedGraph(graphId);
        if (linked == null) throw new IllegalArgumentException("unknown KFX graph " + graphId);
        com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.launch(level, controller);
        try {
            var start = controller.position();
            var end = start.add(controller.velocity());
            com.koper.koper_lib.kfx.graph.KfxRuntime.spawn(level, controller.handle(), linked.graph(),
                castSeed, arguments, start, end);
            return controller.handle();
        } catch (RuntimeException error) {
            com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.stop(level, controller.handle());
            com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
                new com.koper.koper_lib.network.KfxStopPayload(controller.handle()));
            throw error;
        }
    }

    // running controller behind a handle, or null if it already finished
    public static com.koper.koper_lib.kfx.runtime.KfxController controller(
            net.minecraft.server.level.ServerLevel level, long handle) {
        return com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.find(level, handle);
    }

    // new velocity for a live controller. false = that handle isn't running in this level
    public static boolean steerController(net.minecraft.server.level.ServerLevel level, long handle,
                                          net.minecraft.world.phys.Vec3 velocity) {
        return com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.steer(level, handle, velocity);
    }

    public static void addEventListener(com.koper.koper_lib.kfx.runtime.KfxEventListener listener) {
        com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.addEventListener(listener);
    }

    public static void removeEventListener(com.koper.koper_lib.kfx.runtime.KfxEventListener listener) {
        com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.removeEventListener(listener);
    }

    public static com.koper.koper_lib.kfx.graph.KfxRuntime.Spawn spawnGraph(
        net.minecraft.server.level.ServerLevel level,
        String graphId,
        long castSeed,
        java.util.Map<String, com.koper.koper_lib.kfx.graph.KfxResolvedValue> arguments,
        net.minecraft.world.phys.Vec3 start,
        net.minecraft.world.phys.Vec3 end
    ) {
        var linked = linkedGraph(graphId);
        if (linked == null) throw new IllegalArgumentException("unknown KFX graph " + graphId);
        return com.koper.koper_lib.kfx.graph.KfxRuntime.spawn(
            level, linked.graph(), castSeed, arguments, start, end
        );
    }

    public static com.koper.koper_lib.kfx.runtime.KfxHandle play(
        net.minecraft.server.level.ServerLevel level,
        com.koper.koper_lib.kfx.runtime.KfxPlayRequest request
    ) {
        return play(level, com.koper.koper_lib.kfx.graph.KfxRuntime.nextInstanceId(), request);
    }

    public static com.koper.koper_lib.kfx.runtime.KfxHandle play(
        net.minecraft.server.level.ServerLevel level,
        long instanceId,
        com.koper.koper_lib.kfx.runtime.KfxPlayRequest request
    ) {
        if (level == null || request == null) throw new IllegalArgumentException("KFX play needs a level and request");
        if (instanceId == 0L) throw new IllegalArgumentException("KFX play handle cannot be zero");
        var linked = linkedGraph(request.graph());
        if (linked == null) throw new IllegalArgumentException("unknown KFX graph " + request.graph());
        var poses = new com.koper.koper_lib.kfx.runtime.KfxAnchorResolver.PoseSource() {
            @Override public com.koper.koper_lib.kfx.runtime.KfxAnchorResolver.EntityPose entity(int id) {
                var entity = level.getEntity(id);
                if (entity == null) return null;
                float yaw = entity.getYRot();
                boolean right = true;
                if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
                    yaw = living.yBodyRot;
                    right = living.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
                }
                return new com.koper.koper_lib.kfx.runtime.KfxAnchorResolver.EntityPose(
                    entity.position(), entity.position(), yaw, yaw, entity.getYRot(), entity.getYRot(),
                    entity.getXRot(), entity.getXRot(), entity.getBbHeight(), entity.getEyeHeight(), right
                );
            }

            @Override public java.util.Optional<com.koper.koper_lib.kfx.runtime.KfxTransform> bone(
                    int id, String bone, float partialTick) {
                return java.util.Optional.empty();
            }
        };
        var resolver = new com.koper.koper_lib.kfx.runtime.KfxAnchorResolver();
        var start = resolver.resolve(request.start(), poses, 1.0f, null);
        var end = resolver.resolve(request.end(), poses, 1.0f, null);
        if (start.state() != com.koper.koper_lib.kfx.runtime.KfxAnchorState.ACTIVE
            || end.state() != com.koper.koper_lib.kfx.runtime.KfxAnchorState.ACTIVE) {
            throw new IllegalArgumentException("KFX play anchor is not present in this level");
        }
        var spawn = com.koper.koper_lib.kfx.graph.KfxRuntime.spawn(level, instanceId, linked.graph(), request.seed(),
            request.parameters(), start.transform().position(), end.transform().position());
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            new com.koper.koper_lib.network.KfxAnchorPayload(spawn.instanceId(), request.start(), request.end()));
        return com.koper.koper_lib.kfx.runtime.KfxHandle.server(level, spawn.instanceId(), request);
    }

    // ---- addon surface: bolt new ops/styles onto KFX from java, no enum, no engine edit ----

    // java-only op (drawn on the MC fallback path). give it a name + draw lambda
    public static void registerOp(String name, KfxOp handler) {
        KfxOps.register(name, handler);
    }

    // op with a native kender opcode too. nativeBatch = kender already emits its particles
    public static void registerOp(String name, int nativeOpcode, boolean nativeBatch, KfxOp handler, String... aliases) {
        KfxOps.register(name, nativeOpcode, nativeBatch, handler, aliases);
    }

    // new particle look, returns its style code
    public static int registerStyle(String name, KfxStyle drawer) {
        return KfxStyles.register(name, drawer);
    }

    public static void registerPrimitive(com.koper.koper_lib.kfx.render.KfxPrimitive primitive) {
        com.koper.koper_lib.kfx.render.KfxPrimitiveRegistry.builtin().register(primitive);
    }

    public static void registerMaterial(com.koper.koper_lib.kfx.render.KfxMaterialBook.Material material) {
        com.koper.koper_lib.kfx.render.KfxMaterialBook.builtin().register(material);
    }

    public static KfxInstance instance(long id, KfxDef def, float sx, float sy, float sz, float ex, float ey, float ez) {
        return new KfxInstance(id, def.kind(), def.color(), def.color2(), sx, sy, sz, ex, ey, ez,
            def.radius(), def.thickness(), def.lifetime(), def.loop(), def.spinY(),
            def.pulseSpeed(), def.pulseAmount(), def.fadeIn(), def.fadeOut(),
            def.emitterRate(), def.emitterBurst(), def.particleLifetime(), def.spread(),
            def.speed(), def.gravity(), def.drag(), def.sizeEnd(),
            def.emitterShape(), def.particleStyle(), def.particleMotion(),
            def.timelineWarmup(), def.timelineBeamTime(), def.ringScale(), def.sigilScale(),
            def.programJson(), def.maxParticles(), def.turbulence(), def.collisionResponse(), def.collisionRadius(),
            def.collisionFluids(), def.collisionRestitution(), def.collisionFriction(), def.light());
    }

    public static long spawn(net.minecraft.server.level.ServerLevel level, String effectId,
                             double sx, double sy, double sz, double ex, double ey, double ez) {
        if (level == null) return 0L;
        var graph = com.koper.koper_lib.kfx.graph.KfxGraphs.runtimeLinked(effectId);
        if (graph != null) {
            long seed = System.nanoTime() ^ effectId.hashCode();
            return com.koper.koper_lib.kfx.graph.KfxRuntime.spawn(
                level, graph.graph(), seed, java.util.Map.of(),
                new net.minecraft.world.phys.Vec3(sx, sy, sz),
                new net.minecraft.world.phys.Vec3(ex, ey, ez)
            ).instanceId();
        }
        var def = KfxBook.get(effectId);
        if (def == null) return 0L;
        long id = System.nanoTime() ^ effectId.hashCode();
        spawn(level, id, def, sx, sy, sz, ex, ey, ez);
        return id;
    }

    public static void spawn(net.minecraft.server.level.ServerLevel level, long id, KfxDef def,
                             double sx, double sy, double sz, double ex, double ey, double ez) {
        if (level == null || def == null) return;
        var fx = instance(id, def, (float)sx, (float)sy, (float)sz, (float)ex, (float)ey, (float)ez);
        KfxDiagnostics.legacy(fx);
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            com.koper.koper_lib.network.KfxSpawnPayload.of(fx));
    }

    public static void update(net.minecraft.server.level.ServerLevel level, long id,
                              double sx, double sy, double sz, double ex, double ey, double ez) {
        if (level == null || id == 0L) return;
        KfxDiagnostics.anchors(id, new net.minecraft.world.phys.Vec3(sx, sy, sz),
            new net.minecraft.world.phys.Vec3(ex, ey, ez));
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            new com.koper.koper_lib.network.KfxUpdatePayload(id,
                (float)sx, (float)sy, (float)sz, (float)ex, (float)ey, (float)ez));
    }

    public static void updateProperties(net.minecraft.server.level.ServerLevel level, long id,
                                        int color, int color2, float radius, float thickness) {
        if (level == null || id == 0L) return;
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            new com.koper.koper_lib.network.KfxPropertiesPayload(id, color, color2, radius, thickness));
    }

    public static void setScale(net.minecraft.server.level.ServerLevel level, long id,
                                int color, int color2, float radius, float thickness) {
        updateProperties(level, id, color, color2, radius, thickness);
    }

    public static void stop(net.minecraft.server.level.ServerLevel level, long id) {
        if (level == null || id == 0L) return;
        com.koper.koper_lib.kfx.runtime.KfxControllerRuntime.stop(level, id);
        KfxDiagnostics.forget(id);
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            new com.koper.koper_lib.network.KfxStopPayload(id));
    }

    public static void attach(net.minecraft.server.level.ServerLevel level, long id, net.minecraft.world.entity.Entity entity,
                              double ox, double oy, double oz, double ex, double ey, double ez, boolean endRelative) {
        if (level == null || entity == null || id == 0L) return;
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            new com.koper.koper_lib.network.KfxAttachPayload(id, entity.getId(),
                (float)ox, (float)oy, (float)oz, (float)ex, (float)ey, (float)ez, endRelative));
    }

    public static void attachBetween(net.minecraft.server.level.ServerLevel level, long id,
                                     net.minecraft.world.entity.Entity startEntity,
                                     double startOx, double startOy, double startOz,
                                     net.minecraft.world.entity.Entity endEntity,
                                     double endOx, double endOy, double endOz) {
        if (level == null || startEntity == null || endEntity == null || id == 0L) return;
        com.koper.koper_lib.network.KoperNetworking.broadcastToLevel(level,
            new com.koper.koper_lib.network.KfxAttachBetweenPayload(id,
                startEntity.getId(), (float)startOx, (float)startOy, (float)startOz,
                endEntity.getId(), (float)endOx, (float)endOy, (float)endOz));
    }
}
