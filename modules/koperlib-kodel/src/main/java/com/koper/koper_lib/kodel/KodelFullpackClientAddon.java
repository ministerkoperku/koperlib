package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.FullpackClientAddons;
import com.koper.koper_lib.api.core.KodelEntityBridge;
import com.koper.koper_lib.data.KoperEntityData;
import com.koper.koper_lib.data.KoperItemData;
import com.koper.koper_lib.loader.ContentRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Fullpack entities and armour drawn by Kodel. */
public final class KodelFullpackClientAddon implements Runnable {
    @Override
    public void run() {
        FullpackClientAddons.entityRenderer("kodel", KodelFullpackClientAddon::registerEntity);
        KodelZbrojaBook.source(KodelFullpackClientAddon::armour);
    }

    private static boolean registerEntity(EntityType<PathfinderMob> type, KoperEntityData data, Identifier texture) {
        if (data == null || data.model == null || data.model.isBlank()) return false;
        String model = KodelFullpackAddon.bare(data.model);
        var clips = new KodelEntityBridge.Clips(
            clip(data.idleAnimation, "idle", data),
            clip(data.runAnimation, "walk", data),
            clip(data.attackAnimation, "attack", data),
            clip(data.deathAnimation, "death", data));
        float shadow = data.width != null ? Math.min(data.width * 0.5f, 2f) : 0.5f;
        List<KodelClipRules.Rule> rules = rules(data);
        int tint = data.tint;
        EntityRendererRegistry.register(type, ctx -> new KodelMobek<>(ctx, model, texture, clips, shadow, rules, tint));
        return true;
    }

    private static List<KodelClipRules.Rule> rules(KoperEntityData data) {
        List<KodelClipRules.Rule> out = new ArrayList<>();
        if (data.spawnAnimation != null && !data.spawnAnimation.isBlank())
            out.add(new KodelClipRules.Rule("just_spawned", 40, data.spawnAnimation));
        if (data.animationConditions != null) for (var condition : data.animationConditions) {
            if (condition == null || condition.play == null || condition.play.isBlank()) continue;
            out.add(new KodelClipRules.Rule(
                condition.when == null ? "always" : condition.when.toLowerCase(java.util.Locale.ROOT),
                condition.value, condition.play));
        }
        return List.copyOf(out);
    }

    private static String clip(String explicit, String preset, KoperEntityData data) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return Boolean.TRUE.equals(data.presetAnimations) ? preset : null;
    }

    private static List<KodelZbrojaBook.Zbroja> armour() {
        List<KodelZbrojaBook.Zbroja> out = new ArrayList<>();
        for (Map.Entry<Identifier, KoperItemData> entry : ContentRegistry.getAllItemData().entrySet()) {
            KoperItemData data = entry.getValue();
            if (data.model == null || data.model.isBlank()) continue;
            String model = KodelFullpackAddon.bare(data.model);
            String texture = data.armorTexture != null && !data.armorTexture.isBlank() ? data.armorTexture : model;
            out.add(new KodelZbrojaBook.Zbroja(entry.getKey().toString(), null, model,
                KodelZbrojaBook.texture(entry.getKey().getNamespace(), texture),
                data.armorScale,
                data.armorOffset != null ? data.armorOffset : KodelZbrojaBook.DEFAULT_OFFSET.clone(),
                data.armorRotate != null ? data.armorRotate : new float[] {0f, 0f, 0f},
                data.armorBones, data.renderBones != null ? new HashSet<>(data.renderBones) : null,
                data.bonePlacement, data.tint, data.dyeable, data.armorAnimation,
                data.itemScale != null ? data.itemScale : 0.9f,
                data.itemOffset != null ? data.itemOffset : new float[] {0.5f, 0f, 0.5f}));
        }
        return out;
    }
}
