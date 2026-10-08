package com.koper.koper_lib.kodel;

import com.koper.koper_lib.api.core.KodelEntityBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

// any json mob (fullpacks, converted bedrock addons) drawn straight from a .kodel. a slimmer
// renderer for json mobs: idle/walk base clip, attack laid over the top, death
public final class KodelMobek<T extends Mob> extends EntityRenderer<T, KodelMobek.Stan> {

    private static final double FADE = 0.2;
    private static final Map<Integer, Zegarek> ZEGARKI = new ConcurrentHashMap<>();

    private final String model;
    private final Identifier texture;
    private final KodelEntityBridge.Clips clips;
    private final List<KodelClipRules.Rule> rules;
    private final int tint;

    public KodelMobek(EntityRendererProvider.Context ctx, String model, Identifier texture,
                      KodelEntityBridge.Clips clips, float shadow) {
        this(ctx, model, texture, clips, shadow, List.of(), 0xFFFFFFFF);
    }

    /// rules are the entity json's animation_conditions, checked before the built in states;
    /// tint multiplies the whole model (white leaves it alone)
    public KodelMobek(EntityRendererProvider.Context ctx, String model, Identifier texture,
                      KodelEntityBridge.Clips clips, float shadow, List<KodelClipRules.Rule> rules, int tint) {
        super(ctx);
        this.model = model;
        this.texture = texture;
        this.clips = clips;
        this.shadowRadius = shadow;
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.tint = tint;
    }

    public static final class Stan extends EntityRenderState {
        float bodyYaw;
        float limbSwing;
        String clip;
        double clipTime;
        boolean gait;
        String prevClip;
        double prevClipTime;
        boolean prevGait;
        float mix = 1f;
        String overlay;
        double overlayTime;
        float scale = 1f;
        boolean hurt;
        int entityId = -1;
        java.util.Set<String> trackedBones;
    }

    private static final class Zegarek {
        String clip;
        double started;
        String prev;
        double prevStarted;
        double swingStarted = -1;
    }

    @Override
    public Stan createRenderState() {
        return new Stan();
    }

    @Override
    protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(T entity, float partialTicks) {
        net.minecraft.world.phys.AABB box = super.getBoundingBoxForCulling(entity, partialTicks);
        float[] reach = KodelEntityRender.cullingReach(model);
        if (reach[0] <= 0 && reach[1] <= 0 && reach[2] <= 0) return box;
        return box.inflate(reach[0], 0, reach[0]).expandTowards(0, reach[1], 0).expandTowards(0, -reach[2], 0);
    }

    @Override
    public void extractRenderState(T mob, Stan s, float partialTick) {
        super.extractRenderState(mob, s, partialTick);
        s.bodyYaw = Mth.lerp(partialTick, mob.yBodyRotO, mob.yBodyRot);
        s.limbSwing = mob.walkAnimation.position(partialTick);
        s.scale = mob.isBaby() ? 0.5f : 1f;
        s.hurt = mob.hurtTime > 0 || mob.deathTime > 0;

        s.entityId = mob.getId();
        var boxes = KodelEntities.boxes(mob.getType());
        s.trackedBones = boxes.isEmpty() ? null : boxes.keySet();

        // a script's clip first, then the json's rules, then the built in states
        boolean moving = mob.walkAnimation.speed(partialTick) > 0.05f;
        String forced = KodelForcedClips.get(mob.getId(), mob.level().getGameTime());
        String ruled = forced != null ? null : KodelClipRules.pick(rules, mob);
        String want;
        if (forced != null) want = forced;
        else if (ruled != null) want = ruled;
        else if (mob.deathTime > 0 && clips.death() != null) want = clips.death();
        else if (moving && clips.walk() != null) want = clips.walk();
        else want = clips.idle();

        double now = s.ageInTicks / 20.0;
        Zegarek z = ZEGARKI.computeIfAbsent(mob.getId(), i -> new Zegarek());
        if (!Objects.equals(z.clip, want)) {
            z.prev = z.clip;
            z.prevStarted = z.started;
            z.clip = want;
            z.started = now;
        }
        s.clip = want;
        s.clipTime = Math.max(0, now - z.started);
        s.gait = want != null && want.equals(clips.walk()) || KodelEntityRender.looksLikeGait(want);
        double fade = (now - z.started) / FADE;
        if (fade >= 1 || z.prev == null) {
            z.prev = null;
            s.prevClip = null;
            s.mix = 1f;
        } else {
            s.prevClip = z.prev;
            s.prevClipTime = Math.max(0, now - z.prevStarted);
            s.prevGait = KodelEntityRender.looksLikeGait(z.prev);
            s.mix = (float) Math.max(0, fade);
        }

        // swing kicks the attack clip off and it plays to the end even if the swing stopped.
        // a forced or ruled clip owns the whole body, attack included
        if (clips.attack() != null && forced == null && ruled == null) {
            float len = KodelEntityRender.clipLength(model, clips.attack());
            if (mob.isSwinging() && (z.swingStarted < 0 || now - z.swingStarted > len)) z.swingStarted = now;
            boolean playing = z.swingStarted >= 0 && now - z.swingStarted <= len;
            s.overlay = playing ? clips.attack() : null;
            s.overlayTime = playing ? now - z.swingStarted : 0;
        }
        if (mob.isRemoved()) {
            ZEGARKI.remove(mob.getId());
            com.koper.koper_lib.api.core.KoperBonePositions.clearEntity(mob.getId());
        }
    }

    @Override
    public void submit(Stan s, PoseStack pose, SubmitNodeCollector tasks, CameraRenderState camera) {
        KodelEntityRender.Poza p = new KodelEntityRender.Poza();
        p.model = model;
        p.texture = texture;
        p.clip = s.clip;
        p.clipTime = s.clipTime;
        p.gait = s.gait;
        p.prevClip = s.prevClip;
        p.prevClipTime = s.prevClipTime;
        p.prevGait = s.prevGait;
        p.clipMix = s.mix;
        p.overlay = s.overlay;
        p.overlayTime = s.overlayTime;
        p.overlayWeight = s.overlay != null ? 1f : 0f;
        p.limbSwing = s.limbSwing;
        p.bodyYaw = s.bodyYaw;
        p.scale = s.scale;
        p.light = s.lightCoords;
        // no overlay texture on this path, so getting hit just blushes the whole model
        p.tint = s.hurt ? multiply(tint, 0xFFFF7F7F) : tint;
        p.entityId = s.entityId;
        p.x = s.x;
        p.y = s.y;
        p.z = s.z;
        p.trackedBones = s.trackedBones;
        KodelEntityRender.submit(p, pose, tasks, camera);
        super.submit(s, pose, tasks, camera);
    }

    /// clears per-entity clocks; entity ids are reused in the next world
    public static void clearClocks() {
        ZEGARKI.clear();
        KodelForcedClips.clear();
    }

    private static int multiply(int a, int b) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8)
            out |= ((((a >>> shift) & 0xFF) * ((b >>> shift) & 0xFF) / 255) & 0xFF) << shift;
        return out;
    }
}
