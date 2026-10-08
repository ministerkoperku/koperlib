package com.koper.koper_lib.kui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// renders a live mob model inside a gui box (bestiary, altar boss, mob picker). entity id lives in e.text.
// follows the mouse like the inventory player; faces front when there's no mouse (hud).
@Environment(EnvType.CLIENT)
public final class KuiPreview {
    private static final Map<String, LivingEntity> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static int fakeId = -10_000; // negative so a preview entity never clashes with real world ids

    private KuiPreview() {}

    public static void clear() { CACHE.clear(); FAILED.clear(); }

    public static void render(GuiGraphicsExtractor g, KuiElement e, int mouseX, int mouseY) {
        if (FAILED.contains(e.text)) return;
        LivingEntity ent = get(e.text);
        if (ent == null) return;

        int x0 = e.absX, y0 = e.absY, x1 = e.absX + e.absW, y1 = e.absY + e.absH;
        float cx = (x0 + x1) / 2.0f, cy = (y0 + y1) / 2.0f;
        float mx = mouseX < 0 ? cx : mouseX;
        float my = mouseY < 0 ? cy - 20 : mouseY;

        float xAngle = (float) Math.atan((cx - mx) / 40.0f);
        float yAngle = (float) Math.atan((cy - my) / 40.0f);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf xRotation = new Quaternionf().rotateX(yAngle * 20.0f * (float) (Math.PI / 180.0));
        rotation.mul(xRotation);

        EntityRenderState rs = extract(ent);
        if (rs == null) { FAILED.add(e.text); return; } // give up on this entity instead of spamming
        if (rs instanceof LivingEntityRenderState lrs) {
            lrs.bodyRot = 180.0f + xAngle * 20.0f;
            lrs.yRot = xAngle * 20.0f;
            lrs.xRot = -yAngle * 20.0f;
            lrs.boundingBoxWidth = lrs.boundingBoxWidth / lrs.scale;
            lrs.boundingBoxHeight = lrs.boundingBoxHeight / lrs.scale;
            lrs.scale = 1.0f;
        }

		float entityWidth = Math.max(.35f, rs.boundingBoxWidth);
		float entityHeight = Math.max(.35f, rs.boundingBoxHeight);
		float fitted = Math.min(e.absW * .72f / entityWidth, e.absH * .72f / entityHeight);
		float size = Math.max(3f, Math.min(48f, fitted) * Math.max(.05f, e.previewZoom));
        Vector3f translation = new Vector3f(0.0f, rs.boundingBoxHeight / 2.0f, 0.0f);
        g.entity(rs, size, translation, rotation, xRotation, x0, y0, x1, y1);
    }

    private static EntityRenderState extract(LivingEntity ent) {
        try {
            EntityRenderDispatcher d = Minecraft.getInstance().getEntityRenderDispatcher();
            EntityRenderer<? super LivingEntity, ?> renderer = d.getRenderer(ent);
            EntityRenderState rs = renderer.createRenderState(ent, 1.0f);
            rs.shadowPieces.clear();
            rs.outlineColor = 0;
            return rs;
        } catch (Throwable ex) {
            com.koper.koper_lib.KoperLib.LOGGER.warn("[Kui] entity render state failed for {}: {}",
                ent.getType(), ex.toString());
            return null;
        }
    }

    private static LivingEntity get(String id) {
        if (CACHE.containsKey(id)) return CACHE.get(id);
        if (FAILED.contains(id)) return null;
        LivingEntity le = null;
        try {
            Identifier rid = Identifier.tryParse(id);
            var level = Minecraft.getInstance().level;
            if (rid != null && level != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rid)) {
                var ent = BuiltInRegistries.ENTITY_TYPE.getValue(rid).create(level, EntitySpawnReason.EVENT);
                if (ent instanceof LivingEntity l) {
                    l.setId(fakeId--); // assign an id so createRenderState doesn't throw on a never-added entity
                    le = l;
                }
            }
        } catch (Throwable ex) {
            com.koper.koper_lib.KoperLib.LOGGER.warn("[Kui] entity preview create failed for {}: {}", id, ex.toString());
        }
        if (le != null) CACHE.put(id, le);
        else { FAILED.add(id); com.koper.koper_lib.KoperLib.LOGGER.warn("[Kui] entity preview '{}' = null (not created)", id); }
        return le;
    }
}
