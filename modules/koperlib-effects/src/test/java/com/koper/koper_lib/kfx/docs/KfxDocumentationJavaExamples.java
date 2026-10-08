package com.koper.koper_lib.kfx.docs;

import com.koper.koper_lib.kfx.KfxApi;
import com.koper.koper_lib.kfx.KfxDef;
import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxGraph;
import com.koper.koper_lib.kfx.graph.KfxGraphJson;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.graph.KfxResolvedValue;
import com.koper.koper_lib.kfx.graph.KfxRuntime;
import com.koper.koper_lib.kfx.graph.KfxSocket;
import com.koper.koper_lib.kfx.runtime.KfxController;
import com.koper.koper_lib.kfx.runtime.KfxHandle;
import com.koper.koper_lib.kfx.runtime.KfxPlayRequest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/** Compile-only copies of the public Java snippets in KFX_PARTICLE_ENGINE.md. */
final class KfxDocumentationJavaExamples {
    static void parseAndSpawn(ServerLevel level, String json, long castSeed, Vec3 start, Vec3 end) {
        KfxGraph graph = KfxGraphJson.parse(json, "data/aq/kfx/void_bloom.json");
        KfxRuntime.Spawn spawn = KfxRuntime.spawn(
            level,
            graph,
            castSeed,
            Map.of("accent", KfxResolvedValue.color(0xEEB05CFF)),
            start,
            end
        );
    }

    static void composeAndDeclare(ServerLevel level, long castSeed, Vec3 start, Vec3 end) {
        KfxGraph graph = KfxApi.graph("aq:void_spell_java")
            .include("lua", "aq:void_spell_lua")
            .node("shape", "koper_lib:source/ring")
                .text("mode", "burst").number("radius_to", 4.0).end()
            .node("fx", "koper_lib:render/particles")
                .link("source", "shape").integer("count", 80).end()
            .node("root", "koper_lib:group").links("children", "fx").end()
            .output("root").budget(300, 50).build();

        KfxApi.declareGraph(graph);
        KfxApi.spawnGraph(level, "aq:void_spell_java", castSeed, Map.of(), start, end);
    }

    static void playAndControl(ServerLevel level, ServerPlayer player, long castSeed,
            KfxAnchor otherStart, KfxAnchor otherEnd) {
        KfxAnchor hand = new KfxAnchor.Entity(
            player.getId(), KfxSocket.MAIN_HAND, Vec3.ZERO, KfxMissingPolicy.FADE
        );
        KfxAnchor target = new KfxAnchor.Entity(
            player.getId(), KfxSocket.EYES, new Vec3(0, 0, 8), KfxMissingPolicy.FADE
        );

        KfxHandle spell = KfxApi.play(level, new KfxPlayRequest(
            "aq:void_spell_java", Map.of(), hand, target, castSeed
        ));

        spell.set("accent", KfxResolvedValue.color(0xFF66DDFF));
        spell.reanchor(otherStart, otherEnd);
        spell.detach();
        spell.stop();
    }

    static void launchBouncingController(ServerLevel level, ServerPlayer owner, KfxDef beamDef,
            Vec3 start, Vec3 velocity) {
        long handle = KfxRuntime.nextInstanceId();
        KfxApi.spawnControlled(level, beamDef,
            KfxController.projectile(handle, owner.getUUID(), start, velocity)
                .radius(0.16)
                .response(KfxController.Response.BOUNCE)
                .restitution(0.72)
                .surfaceFriction(0.12)
                .maxBounces(3)
                .lifetime(120)
                .build());
    }

    private KfxDocumentationJavaExamples() {}
}
