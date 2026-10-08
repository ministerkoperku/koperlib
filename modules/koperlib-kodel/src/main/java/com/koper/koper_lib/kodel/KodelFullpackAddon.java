package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.FullpackAddons;
import com.koper.koper_lib.data.KoperEntityData;
import com.koper.koper_lib.factory.EntityFactory;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Kodel reads out of fullpacks: geo and animation files, the model blocks' json, which model
 * a fullpack entity wears, and the clips scripts ask entities to play.
 */
public final class KodelFullpackAddon implements Runnable {
    /** Clients further than this from an entity are not told about its script clips. */
    private static final double CLIP_RANGE_SQ = 128.0 * 128.0;

    @Override
    public void run() {
        FullpackAddons.modelFiles("kodel", KodelGeoSources::model);
        FullpackAddons.animationFiles("kodel", KodelGeoSources::animation);
        FullpackAddons.blockVisuals("kodel", (id, definition) -> {
            KodelBlockBook.bindFromJson(id, definition);
            var binding = KodelBlockBook.binding(id);
            return binding == null ? null : binding.texture();
        });
        FullpackAddons.reloadHook("kodel", () -> {
            KodelGeoSources.clear();
            KodelBook.clear();
            KodelPhysicsShapes.clear();
            KodelBlockBook.clear();
            KodelBlockPose.clear();
            KodelTriggerBox.clear();
            KodelEntities.clear();
            KodelZbrojaBook.clear();
        });
        KodelEntities.lookup(type -> binding(EntityFactory.dataFor(type)));
        FullpackAddons.geo("kodel", new FullpackAddons.Geo() {
            @Override
            public java.util.Collection<String> entityBones(net.minecraft.world.entity.Entity entity) {
                KodelBook.Entry entry = KodelBook.get(KodelEntities.model(entity.getType()));
                if (entry == null) return List.of();
                return entry.model().bones.stream().map(bone -> bone.name).toList();
            }

            @Override
            public void playAnimation(net.minecraft.world.entity.Entity entity, String clip, int holdTicks) {
                if (!(entity.level() instanceof ServerLevel level)) return;
                var packet = new KodelClipPayload(entity.getId(), clip, holdTicks);
                for (var viewer : level.players())
                    if (viewer.distanceToSqr(entity) < CLIP_RANGE_SQ)
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(viewer, packet);
            }
        });
    }

    static KodelEntities.Binding binding(KoperEntityData data) {
        if (data == null || data.model == null || data.model.isBlank()) return null;
        Map<String, KodelEntities.Box> boxes = new LinkedHashMap<>();
        data.hitboxBones.forEach((bone, box) -> boxes.put(bone,
            new KodelEntities.Box(box.width, box.height, box.depth, box.damageMultiplier)));
        return new KodelEntities.Binding(bare(data.model), Boolean.TRUE.equals(data.obbHitboxes), Map.copyOf(boxes));
    }

    static String bare(String name) {
        return name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
    }
}
