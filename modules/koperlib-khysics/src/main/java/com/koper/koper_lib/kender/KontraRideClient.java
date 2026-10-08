package com.koper.koper_lib.kender;

import com.koper.koper_lib.config.KoperLibConfig;
import com.koper.koper_lib.physics.KhysicsWand;
import com.koper.koper_lib.physics.KontraGlue;
import com.koper.koper_lib.physics.KontraRide;
import com.koper.koper_lib.physics.shape.KhysShapeCache;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// client half of riding: builds the kontra collision set for the unified move solve
// (KontraGlue + KontraCollideRedirectMixin do the actual thinking) + camera/HUD fluff.
// the old carry/push/probe circus lived here — gone, one solve owns everything now.
@Environment(EnvType.CLIENT)
public final class KontraRideClient {

    private KontraRideClient() {
    }

    public enum CameraMode {
        VANILLA("vanilla", "vanilla"),
        LOCKED("locked", "locked"),
        KONTRA_ROT("kontra_rot", "kontra rotation");

        private final String id;
        private final String label;

        CameraMode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        CameraMode next() {
            CameraMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        static CameraMode byId(String id) {
            if (id == null)
                return VANILLA;
            for (CameraMode mode : values()) {
                if (mode.id.equals(id) || mode.label.equals(id))
                    return mode;
            }
            return VANILLA;
        }
    }

    private static float[] smoothRideRot;
    private static long smoothRideRotId;
    private static long smoothRideRotNanos;
    private static long lastDebugHudNanos;
    private static final long VISUAL_GRACE_NANOS = 650_000_000L;
    private static final long ROT_SMOOTH_NANOS = 120_000_000L;
    private static final KeyMapping.Category KOPER_KEYS = KeyMapping.Category
            .register(Identifier.fromNamespaceAndPath("koper_lib", "koperlib"));
    private static final KeyMapping CAMERA_MODE_KEY = new KeyMapping(
            "key.koper_lib.kontra_camera_mode",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_K,
            KOPER_KEYS);
    private static final TagKey<Item> HELM = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("koperlib", "helm"));
    private static CameraMode cameraMode = CameraMode.VANILLA;

    public static void register() {
        cameraMode = CameraMode.byId(com.koper.koper_lib.physics.KhysicsConfig.get().kontraCameraMode);
        KontraRide.CLIENT_HOOK = KontraRideClient::clamp;
        // the glue needs to know who's local and where the kontras are (PACKET pose,
        // same one the solve collides against — render lerp lies mid-tick)
        KontraGlue.CLIENT_LOCAL = ent -> ent instanceof LocalPlayer lp && lp == Minecraft.getInstance().player;
        KontraGlue.CLIENT_POSE = id -> {
            KenderClientState.KontraRenderData k = KenderClientState.getById(id);
            if (k == null || k.currPos == null || k.currRot == null)
                return null;
            float radius = (float) Math.sqrt(k.obbHalfX * k.obbHalfX + k.obbHalfY * k.obbHalfY
                    + k.obbHalfZ * k.obbHalfZ);
            return new float[] { k.currPos[0], k.currPos[1], k.currPos[2],
                    k.currRot[0], k.currRot[1], k.currRot[2], k.currRot[3],
                    radius, k.aligned ? 1f : 0f };
        };
        // parked kontras answer vanilla collision queries as real blocks (see
        // KontraSolidBlocksMixin)
        com.koper.koper_lib.physics.KontraSolidBook.CLIENT =
            new com.koper.koper_lib.physics.KontraSolidBook.ClientLookup() {
                @Override public net.minecraft.world.level.block.state.BlockState at(net.minecraft.core.BlockPos pos) {
                    return KenderClientState.alignedSolidAt(pos);
                }
                @Override public java.util.List<net.minecraft.world.phys.AABB> shapeAt(net.minecraft.core.BlockPos pos) {
                    return KenderClientState.alignedShapeAt(pos);
                }
            };
        // packet poses go live at tick start, in phase with the player's own lerp
        ClientTickEvents.START_CLIENT_TICK.register(mc -> KenderClientState.latchTick());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tickCameraKey(mc);
            tickDebugHud(mc);
        });
    }

    public static void onTransformUpdate(long id, long updateNanos) {
        // render smoothing only, riding doesn't care — the glue reads poses itself
    }

    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            KontraGlue.release(mc.player, KontraGlue.mind(mc.player), "clear-all", false);
    }

    public static void onKontraRemoved(long kontraId) {
        if (kontraId == 0L)
            return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null)
            return;
        KontraGlue.Mind m = KontraGlue.mind(p);
        // keep the ghost momentum — deck exploding under you shouldn't freeze you mid-air
        if (m.deckId == kontraId)
            KontraGlue.release(p, m, "kontra-removed", true);
    }

    public static float[] visualRideRot(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (!(entity instanceof LocalPlayer player) || player != mc.player || player.getAbilities().flying)
            return null;
        long nowNs = System.nanoTime();
        KenderClientState.KontraRenderData k = visualKontraFor(player, nowNs);
        if (k == null)
            return null;
        float[] frameRot = KenderClientState.renderRot(k, nowNs);
        return frameRot != null ? smoothRideRot(k.id, frameRot, nowNs) : null;
    }

    public static float[] collisionRideRot(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (!(entity instanceof LocalPlayer player) || player != mc.player || player.getAbilities().flying)
            return null;
        KontraGlue.Mind m = KontraGlue.mind(player);
        if (m.deckId == 0L)
            return null;
        long nowNs = System.nanoTime();
        if (!isRideLocked(player) && nowNs - m.lastRideNanos > VISUAL_GRACE_NANOS)
            return null;
        return new float[] { 0f, 0f, 0f, 1f };
    }

    public static Vec3 renderRideOffset(Entity entity) {
        return Vec3.ZERO;
    }

    // walking on a deck: nothing, the model is drawn where the hitbox is (the old visual glue made the
    // two drift apart). sitting on a seat: the seat follows the ship's PACKET pose once a tick and
    // vanilla lerps the rider on its own clock, while kender draws the ship on another one. at 12
    // blocks/s that put the camera up to a block off the seat and made it jump when a packet came in
    // out of step (koper: "the player flies off the chair for under a second"). so the rider is
    // drawn where kender draws the seat THIS frame
    public static Vec3 renderRideOffset(Entity entity, float tickDelta) {
        if (!(entity.getVehicle() instanceof com.koper.koper_lib.physics.KontraSeat seat)) return Vec3.ZERO;
        KenderClientState.KontraRenderData k = KenderClientState.getById(seat.kontraId());
        if (k == null) return Vec3.ZERO;
        long nowNs = System.nanoTime();
        float[] p = KenderClientState.renderPos(k, nowNs);
        float[] r = KenderClientState.renderRot(k, nowNs);
        if (p == null || r == null) return Vec3.ZERO;
        float[] l = seat.localPos();
        org.joml.Vector3f w = new org.joml.Quaternionf(r[0], r[1], r[2], r[3]).transform(new org.joml.Vector3f(l[0], l[1], l[2]));
        // where the rider belongs: kender's seat this frame plus the seat's fixed riding point. the rider's
        // own x-minus-seat's x was tried first and is one tick off whenever the seat moved and the rider
        // was not yet put back on it, which pushed the camera a tick's travel off the seat (omni's ride csv)
        Vec3 attach = seat.getPassengerRidingPosition(entity).subtract(seat.position());
        double riderX = net.minecraft.util.Mth.lerp(tickDelta, entity.xo, entity.getX());
        double riderY = net.minecraft.util.Mth.lerp(tickDelta, entity.yo, entity.getY());
        double riderZ = net.minecraft.util.Mth.lerp(tickDelta, entity.zo, entity.getZ());
        Vec3 off = new Vec3(p[0] + w.x + attach.x - riderX, p[1] + w.y + attach.y - riderY, p[2] + w.z + attach.z - riderZ);
        // a pose from another world or a teleport is not a lag to hide
        return off.lengthSqr() > 16.0 ? Vec3.ZERO : off;
    }

    public static double renderRideCameraDistance(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (!(entity instanceof LocalPlayer player) || player != mc.player || !isRideLocked(player))
            return 0.0;
        KenderClientState.KontraRenderData k = visualKontraFor(player, System.nanoTime());
        if (k == null)
            return 0.0;
        double radius = Math.sqrt(k.obbHalfX * k.obbHalfX + k.obbHalfY * k.obbHalfY + k.obbHalfZ * k.obbHalfZ);
        return Math.max(4.0, Math.min(24.0, radius * 1.25 + 2.0));
    }

    private static KenderClientState.KontraRenderData visualKontraFor(LocalPlayer player, long nowNs) {
        KontraGlue.Mind m = KontraGlue.mind(player);
        if (m.deckId != 0L && (isRideLocked(player) || nowNs - m.lastRideNanos <= VISUAL_GRACE_NANOS)) {
            KenderClientState.KontraRenderData k = KenderClientState.getById(m.deckId);
            if (k != null)
                return k;
        }
        return null;
    }

    public static CameraMode cameraMode() {
        return cameraMode;
    }

    public static KeyMapping cameraModeKey() {
        return CAMERA_MODE_KEY;
    }

    private static void tickCameraKey(Minecraft mc) {
        while (CAMERA_MODE_KEY.consumeClick()) {
            cameraMode = cameraMode.next();
            com.koper.koper_lib.physics.KhysicsConfig.get().kontraCameraMode = cameraMode.id();
            com.koper.koper_lib.physics.KhysicsConfig.save();
            if (mc.player != null) {
                mc.gui.hud.setOverlayMessage(Component.literal("Kontra camera: " + cameraMode.label()), false);
            }
        }
    }

    private static void tickDebugHud(Minecraft mc) {
        if (!KoperLibConfig.get().debugMode || mc.player == null)
            return;
        long now = System.nanoTime();
        if (now - lastDebugHudNanos < 250_000_000L)
            return;
        lastDebugHudNanos = now;

        LocalPlayer p = mc.player;
        KontraGlue.Mind m = KontraGlue.mind(p);
        Vec3 vel = p.getDeltaMovement();
        long ageMs = m.lastRideNanos == 0L ? -1L : Math.max(0L, (now - m.lastRideNanos) / 1_000_000L);
        double[] up = m.deckRot != null ? fwdRot(0.0, 1.0, 0.0, m.deckRot) : null;
        String text = String.format(Locale.ROOT,
                "Kontra ride=%d lost=%d up=%s inh=%.3f %.3f %.3f cam=%s ground=%s age=%dms vel=%.3f %.3f %.3f",
                m.deckId, m.lostTicks, formatVec(up), m.fedX, m.fedY, m.fedZ,
                cameraMode.label(), p.onGround() ? "Y" : "N", ageMs, vel.x, vel.y, vel.z);
        mc.gui.hud.setOverlayMessage(Component.literal(text), false);
    }

    private static String formatVec(double[] v) {
        if (v == null || v.length < 3)
            return "-";
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v[0], v[1], v[2]);
    }

    // gathers nearby non-parked kontras as OBB solids and runs the SAT solve.
    // called from inside Entity.move via the collide redirect — delta already
    // carries the inherited platform motion, this only clamps it.
    public static KontraRide.Ride clamp(Entity entity, Vec3 worldDelta) {
        if (!(entity instanceof LocalPlayer player))
            return null;
        if (KenderClientState.isEmpty())
            return null;
        KontraGlue.Mind mind = KontraGlue.mind(player);
        boolean stickFlight = isStickFlying(player);
        if ((isPilotLocked(player) || stickFlight) && mind.deckId != 0L
                && KontraRide.IGNORE_KONTRA.get() != mind.deckId) {
            // pilot steers, legs stay put: drop the pilot's own HORIZONTAL walk and run the
            // normal solve with carry + gravity. the old frozen-position hack (Ride=ZERO)
            // skipped grounding entirely — un-crouching left you with zero velocity and a
            // stale state, and off the deck you went.
            // stick flight drops own Y too — space/shift belong to the SHIP, jumping and
            // crouch-hopping on the deck mid-flight was peak clownery
            double fx = 0.0, fy = worldDelta.y, fz = 0.0;
            if (mind.fedTick == player.tickCount && !mind.fedSpent) {
                fx = mind.fedX;
                fz = mind.fedZ;
                if (stickFlight) fy = mind.fedY;
            }
            worldDelta = new Vec3(fx, fy, fz);
        }
        AABB box = entity.getBoundingBox();
        double ex = (box.minX + box.maxX) * 0.5, ey = (box.minY + box.maxY) * 0.5, ez = (box.minZ + box.maxZ) * 0.5;
        double ehx = (box.maxX - box.minX) * 0.5, ehy = (box.maxY - box.minY) * 0.5, ehz = (box.maxZ - box.minZ) * 0.5;
        double cullR = Math.max(ehx, Math.max(ehy, ehz)) + 1.5 + worldDelta.length();
        double cullSq = (cullR + 1.0) * (cullR + 1.0);

        List<KontraRide.KItem> ks = new ArrayList<>();
        long ignore = KontraRide.IGNORE_KONTRA.get();
        for (var k : KenderClientState.all()) {
            if (ignore != 0L && k.id == ignore)
                continue;
            if (k.aligned)
                continue; // real solid blocks while parked — vanilla collide handles it
            // PACKET pose — the same one the glue computed inherited from. mixing in the
            // render-interpolated pose = two kontra positions inside one tick = dropped rides
            float[] pos = k.currPos;
            float[] rot = k.currRot;
            if (pos == null || rot == null)
                continue;
            double dx = ex - pos[0], dy = ey - pos[1], dz = ez - pos[2];
            double kr = Math.sqrt(k.obbHalfX * k.obbHalfX + k.obbHalfY * k.obbHalfY + k.obbHalfZ * k.obbHalfZ);
            double reach = kr + 2.0 + worldDelta.length();
            if (dx * dx + dy * dy + dz * dz > reach * reach)
                continue;
            double[] le = invRot(ex - pos[0], ey - pos[1], ez - pos[2], rot);

            List<VoxelShape> shapes = new ArrayList<>();
            int n = k.offsets.length / 3;
            for (int i = 0; i < n; i++) {
                float ox = k.offsets[i * 3], oy = k.offsets[i * 3 + 1], oz = k.offsets[i * 3 + 2];
                double ddx = ox - le[0], ddy = oy - le[1], ddz = oz - le[2];
                if (ddx * ddx + ddy * ddy + ddz * ddz > cullSq)
                    continue;
                BlockState bs = k.states != null && i < k.states.length ? k.states[i] : null;
                var localPos = new net.minecraft.core.BlockPos(
                    Math.round(ox), Math.round(oy), Math.round(oz));
                List<AABB> subs = bs != null
                    ? KhysShapeCache.get(bs, k.localData.get(localPos)) : KhysShapeCache.FULL_CUBE;
                for (AABB s : subs)
                    shapes.add(Shapes.create(s.move(ox - 0.5, oy - 0.5, oz - 0.5)));
            }
            if (!shapes.isEmpty())
                ks.add(new KontraRide.KItem(k.id, pos, rot, shapes));
        }
        if (ks.isEmpty())
            return null;
        return KontraRide.resolveWithSideDepen(entity, box, worldDelta, ks, 0.18);
    }

    private static boolean isRideLocked(LocalPlayer player) {
        return player != null && player.isShiftKeyDown() && KontraGlue.mind(player).deckId != 0L
                && holdsPilotItem(player);
    }

    private static boolean isPilotLocked(LocalPlayer player) {
        return isRideLocked(player);
    }

    private static boolean holdsPilotItem(LocalPlayer player) {
        if (player == null)
            return false;
        var held = player.getMainHandItem();
        return held.getItem() instanceof KhysicsWand || held.is(HELM);
    }

    // flight stick welds you the moment you hold it on deck — WASD belongs to the ship then.
    // swap the hotbar slot to walk around, no crouch ceremony
    private static boolean isStickFlying(LocalPlayer player) {
        return player != null
                && player.getMainHandItem().getItem() instanceof com.koper.koper_lib.physics.KhysFlightStick;
    }

    private static float[] smoothRideRot(long id, float[] target, long nowNs) {
        if (target == null || target.length < 4)
            return null;
        if (smoothRideRot == null || smoothRideRotId != id) {
            smoothRideRot = new float[] { 0f, 0f, 0f, 1f };
            smoothRideRotId = id;
            smoothRideRotNanos = nowNs - 50_000_000L;
        }
        double dt = smoothRideRotNanos == 0L ? 0.05 : Math.max(0.0, (nowNs - smoothRideRotNanos) / 1.0e9);
        smoothRideRotNanos = nowNs;
        float alpha = (float) Math.max(0.0, Math.min(1.0, 1.0 - Math.exp(-(dt * 1.0e9) / ROT_SMOOTH_NANOS)));
        smoothRideRot = slerp(smoothRideRot, target, alpha);
        return smoothRideRot.clone();
    }

    private static float[] slerp(float[] from, float[] toRaw, float alpha) {
        float[] to = normalize(toRaw.clone());
        float dot = from[0] * to[0] + from[1] * to[1] + from[2] * to[2] + from[3] * to[3];
        if (dot < 0.0f) {
            dot = -dot;
            to[0] = -to[0];
            to[1] = -to[1];
            to[2] = -to[2];
            to[3] = -to[3];
        }
        float s0;
        float s1;
        if (dot > 0.9995f) {
            s0 = 1.0f - alpha;
            s1 = alpha;
        } else {
            dot = Math.max(0.0f, Math.min(1.0f, dot));
            double theta0 = Math.acos(dot);
            double theta = theta0 * alpha;
            double sinTheta = Math.sin(theta);
            double sinTheta0 = Math.sin(theta0);
            s0 = (float) (Math.cos(theta) - dot * sinTheta / sinTheta0);
            s1 = (float) (sinTheta / sinTheta0);
        }
        return normalize(new float[] {
                from[0] * s0 + to[0] * s1,
                from[1] * s0 + to[1] * s1,
                from[2] * s0 + to[2] * s1,
                from[3] * s0 + to[3] * s1
        });
    }

    private static float[] normalize(float[] q) {
        float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (len < 1.0e-6f)
            return new float[] { 0f, 0f, 0f, 1f };
        q[0] /= len;
        q[1] /= len;
        q[2] /= len;
        q[3] /= len;
        return q;
    }

    private static double[] invRot(double x, double y, double z, float[] q) {
        return rot(x, y, z, -q[0], -q[1], -q[2], q[3]);
    }

    private static double[] fwdRot(double x, double y, double z, float[] q) {
        return rot(x, y, z, q[0], q[1], q[2], q[3]);
    }

    private static double[] rot(double x, double y, double z, double qx, double qy, double qz, double qw) {
        double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
        return new double[] { x + qw * tx + qy * tz - qz * ty, y + qw * ty + qz * tx - qx * tz,
                z + qw * tz + qx * ty - qy * tx };
    }

}
