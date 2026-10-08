package com.koper.koper_lib.kodel;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Which model an entity type wears, and what that means for combat: OBB picking from the
 * model's hitbox bones, and per-bone damage boxes.
 *
 * <p>OBB picking uses the rest pose, as it always did: a mob is hit where it stands, not where
 * its swinging arm is this frame.
 */
public final class KodelEntities {
    private KodelEntities() {}

    /** A damage box centred on a bone, in blocks, and what a hit on it multiplies. */
    public record Box(float width, float height, float depth, float damageMultiplier) {}

    /** What an entity type wears: the model, whether picking uses its hitbox bones, its damage boxes. */
    public record Binding(String model, boolean obb, Map<String, Box> boxes) {}

    private static final Binding NONE = new Binding(null, false, Map.of());
    private static final Map<EntityType<?>, Binding> TYPES = new ConcurrentHashMap<>();
    private static volatile Function<EntityType<?>, Binding> lookup = type -> null;

    /** Where bindings come from when nobody called {@link #bind}: fullpack entity json, for one. */
    public static void lookup(Function<EntityType<?>, Binding> source) {
        lookup = source == null ? type -> null : source;
        TYPES.clear();
    }

    public static void bind(EntityType<?> type, String model, boolean obb, Map<String, Box> boxes) {
        if (type == null || model == null) return;
        TYPES.put(type, new Binding(model, obb, boxes == null ? Map.of() : Map.copyOf(boxes)));
    }

    public static void clear() {
        TYPES.clear();
    }

    private static Binding of(EntityType<?> type) {
        if (type == null) return NONE;
        return TYPES.computeIfAbsent(type, t -> {
            Binding found = lookup.apply(t);
            return found == null || found.model() == null ? NONE : found;
        });
    }

    public static String model(EntityType<?> type) {
        return of(type).model();
    }

    public static boolean obb(EntityType<?> type) {
        return of(type).obb();
    }

    public static Map<String, Box> boxes(EntityType<?> type) {
        return of(type).boxes();
    }

    /** How far any OBB-picked model reaches past its entity, in blocks, to widen a pick search. */
    public static double maxReach() {
        double reach = 0;
        for (Binding b : TYPES.values()) {
            if (!b.obb()) continue;
            KodelBook.Entry entry = KodelBook.get(b.model());
            float[] box = entry == null ? null : entry.hitboxBounds();
            if (box == null) continue;
            for (float v : box) reach = Math.max(reach, Math.abs(v) / 16.0);
        }
        return reach;
    }

    /**
     * Distance along {@code from -> to} to the first hitbox bone the segment enters. NaN when the
     * entity has no model hitboxes (keep whatever vanilla decided), -1 when it misses them all.
     */
    public static double rayHit(Entity e, Vec3 from, Vec3 to) {
        String model = model(e.getType());
        if (model == null) return Double.NaN;
        KodelBook.Entry entry = KodelBook.get(model);
        List<KodelHitboxer.Obb> boxes = entry == null ? List.of() : entry.hitboxes();
        if (boxes.isEmpty()) return Double.NaN;

        float yaw = e instanceof LivingEntity living ? living.yBodyRot : e.getYRot();
        float back = (float) -Math.toRadians(180f - yaw);
        // into model pixels; the direction keeps the segment's length so t runs 0..1 along it
        Vector3f o = new Vector3f((float) (from.x - e.getX()), (float) (from.y - e.getY() - 0.01), (float) (from.z - e.getZ()))
            .rotateY(back).mul(16f);
        Vector3f d = new Vector3f((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z))
            .rotateY(back).mul(16f);
        float best = -1f;
        for (KodelHitboxer.Obb box : boxes) {
            float t = box.raycast(o.x, o.y, o.z, d.x, d.y, d.z);
            if (t >= 0f && t <= 1f && (best < 0f || t < best)) best = t;
        }
        return best < 0f ? -1 : best * from.distanceTo(to);
    }
}
