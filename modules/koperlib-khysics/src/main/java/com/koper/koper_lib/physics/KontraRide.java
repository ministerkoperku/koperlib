package com.koper.koper_lib.physics;

import com.koper.koper_lib.physics.shape.KhysShapeCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

// Kontra collision that plugs into Entity.move. Vanilla terrain still runs after this clamp.
public final class KontraRide {

    private KontraRide() {}

    public static final class Ride {
        public Vec3 delta;
        public boolean onGround;
        public boolean ceiling;
        // axes the sweep REALLY clamped (intent after slope projection vs achieved).
        // the raw delta-vs-result compare can't tell a wall from the projection
        // legitimately redirecting the walk along a tilted deck
        public boolean hitX, hitZ;
        public long kontraId;
        public float[] obbRot;
    }

    public interface ClientClamp { Ride clamp(Entity entity, Vec3 worldDelta); }

    public static volatile ClientClamp CLIENT_HOOK = null;
    public static final ThreadLocal<Long> IGNORE_KONTRA = ThreadLocal.withInitial(() -> 0L);

    // who is actually riding right now — snap-to-deck must only fire for riders; keying it off
    // entity.onGround() pulled players standing NEXT TO a kontra down into its floor (slow-sink jitter)
    private static boolean ridingFresh(Entity e) {
        return KontraGlue.tracked(e);
    }

    private static final double SWEEP_EPS = 0.004;
    private static final double CONTACT_SLOP = 0.0015;
    private static final double DEPEN_CAP = 0.35;
    private static final double DEFAULT_SIDE_DEPEN_CAP = 0.18;
    private static final double MAX_SURFACE_WALK_SCALE = 3.0;
    private static final double STABLE_FLOOR_MIN_UP = 0.766044443118978; // cos(40deg)
    private static final double CONTACT_FRAME_MIN_UP = 0.5; // loose contact only; stable floor still decides ride
    private static final double FLOOR_SNAP = 0.18;
    private static final double MERGE_EPS = 1.0e-6;
    private static final int MOVE_ITERS = 5;
    private static final int DEPEN_ITERS = 6;
    private static final ThreadLocal<Double> SIDE_DEPEN_CAP = ThreadLocal.withInitial(() -> DEFAULT_SIDE_DEPEN_CAP);
    private static final ThreadLocal<Boolean> FLOOR_DEPEN_ACTIVE = ThreadLocal.withInitial(() -> true);
    // the deck the entity currently RIDES — its floor gets a deep embed window (crouch-jump spam
    // on a climbing kontra buried players past the half-block point where min-MTV flips sideways
    // or DOWN → depen popped them out under their own floor. never down through your own deck.)
    private static final ThreadLocal<Long> RIDDEN_DECK = ThreadLocal.withInitial(() -> 0L);
    private static final double RIDDEN_FLOOR_WINDOW = 1.25;
    // strong passes (velocityMatch cleanup) may pop deeper than the movement clamp — a half-buried
    // item stuck under the 0.35 cap just stayed buried forever
    private static final ThreadLocal<Double> FLOOR_DEPEN_CAP = ThreadLocal.withInitial(() -> DEPEN_CAP);
    private static final double[][] WORLD_BASIS = new double[][]{
            {1, 0, 0}, {0, 1, 0}, {0, 0, 1}
    };

    public static final class KItem {
        public final long id;
        public final float[] pos, rot;
        public final float[] prevPos, prevRot;
        public final List<VoxelShape> shapes;

        public KItem(long id, float[] pos, float[] rot, List<VoxelShape> shapes) {
            this(id, pos, rot, null, null, shapes);
        }

        public KItem(long id, float[] pos, float[] rot, float[] prevPos, float[] prevRot, List<VoxelShape> shapes) {
            this.id = id;
            this.pos = pos;
            this.rot = rot;
            this.prevPos = prevPos;
            this.prevRot = prevRot;
            this.shapes = shapes;
        }
    }

    private static final class Solid {
        final long kontraId;
        final double cx, cy, cz;
        final float qx, qy, qz, qw;
        final float hx, hy, hz;
        final double prevCx, prevCy, prevCz;
        final float prevQx, prevQy, prevQz, prevQw;
        final double moveX, moveY, moveZ;

        Solid(long kontraId, double cx, double cy, double cz, float[] rot, float hx, float hy, float hz,
              double prevCx, double prevCy, double prevCz, float[] prevRot) {
            this.kontraId = kontraId;
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.qx = rot[0]; this.qy = rot[1]; this.qz = rot[2]; this.qw = rot[3];
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.prevCx = prevCx; this.prevCy = prevCy; this.prevCz = prevCz;
            this.prevQx = prevRot[0]; this.prevQy = prevRot[1]; this.prevQz = prevRot[2]; this.prevQw = prevRot[3];
            this.moveX = cx - prevCx; this.moveY = cy - prevCy; this.moveZ = cz - prevCz;
        }

    }

    private static final class EntityBox {
        final double hx, hy, hz;
        final double[][] basis;
        final boolean rotated;
        final boolean locked;

        EntityBox(double hx, double hy, double hz, double[][] basis, boolean rotated, boolean locked) {
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.basis = basis;
            this.rotated = rotated;
            this.locked = locked;
        }
    }

    private static final class SupportPoint {
        final double[] local;
        final double nx, ny, nz;
        final boolean stable;
        final Solid solid;

        SupportPoint(double[] local, double nx, double ny, double nz, boolean stable, Solid solid) {
            this.local = local;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.stable = stable;
            this.solid = solid;
        }
    }

    public static Ride serverClamp(Entity entity, Vec3 worldDelta) {
        return serverClamp(entity, worldDelta, DEFAULT_SIDE_DEPEN_CAP, true);
    }

    private static Ride serverClamp(Entity entity, Vec3 worldDelta, double sideCap, boolean floorDepen) {
        if (entity.isSpectator()) return null;
        if (!(entity.level() instanceof ServerLevel sl)) return null;
        if (KoperPhys.all().isEmpty()) return null;
        String levelKey = KoperPhys.levelKey(sl);
        AABB box = entity.getBoundingBox();
        double ex = mid(box.minX, box.maxX), ey = mid(box.minY, box.maxY), ez = mid(box.minZ, box.maxZ);
        double ehx = half(box.minX, box.maxX), ehy = half(box.minY, box.maxY), ehz = half(box.minZ, box.maxZ);
        var carryMind=KontraGlue.mind(entity);
        Vec3 carry=carryMind.fedCarry && KontraGlue.fastCarry(entity)?new Vec3(carryMind.fedX,carryMind.fedY,carryMind.fedZ):Vec3.ZERO;
        ex+=carry.x; ey+=carry.y; ez+=carry.z;
        double ownLength=worldDelta.subtract(carry).length();
        double cullR = Math.max(ehx, Math.max(ehy, ehz)) + 1.5 + ownLength;
        double cullSq = (cullR + 1.0) * (cullR + 1.0);

        List<KItem> ks = new ArrayList<>();
        long ignore = IGNORE_KONTRA.get();
        for (var e : KoperPhys.all().entrySet()) {
            if (ignore != 0L && e.getKey() == ignore) continue;
            KontraEntry d = e.getValue();
            if (d.aligned) continue; // parked on-grid = real solid blocks via BlockCollisions, SAT off
            if (!d.levelKey().equals(levelKey)) continue;
            float[] pos = KoperPhys.getCachedPos(e.getKey());
            float[] rot = KoperPhys.getCachedRot(e.getKey());
            if (pos == null || rot == null) continue;
            double dx = ex - pos[0], dy = ey - pos[1], dz = ez - pos[2];
            double reach = d.cachedRadius + 2.0 + ownLength;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;

            double[] le = invRot(ex - pos[0], ey - pos[1], ez - pos[2], rot);
            List<VoxelShape> shapes = new ArrayList<>();
            for (var be : d.blockOffsets.entrySet()) {
                float[] o = be.getValue();
                if (o == null) continue;
                double ddx = o[0] - le[0], ddy = o[1] - le[1], ddz = o[2] - le[2];
                if (ddx * ddx + ddy * ddy + ddz * ddz > cullSq) continue;
                var subs = d.shapeAt(be.getKey());
                for (AABB s : subs) shapes.add(Shapes.create(s.move(o[0] - 0.5, o[1] - 0.5, o[2] - 0.5)));
            }
            if (!shapes.isEmpty()) ks.add(new KItem(e.getKey(), pos, rot, shapes));
        }
        return ks.isEmpty() ? null : resolveWithDepen(entity, box, worldDelta, ks, sideCap, floorDepen);
    }

    public static Ride resolveWithSideDepen(Entity entity, AABB worldBox, Vec3 worldDelta,
                                            List<KItem> ks, double sideCap) {
        return resolveWithDepen(entity, worldBox, worldDelta, ks, sideCap, true);
    }

    private static Ride resolveWithDepen(Entity entity, AABB worldBox, Vec3 worldDelta,
                                         List<KItem> ks, double sideCap, boolean floorDepen) {
        // no floor lift for creative flyers — the depen elevator dragged them slowly upward
        // whenever they hovered against a deck. sweep still stops them entering the hull.
        if (entity instanceof Player fp && fp.getAbilities().flying) floorDepen = false;
        double prevSide = SIDE_DEPEN_CAP.get();
        boolean prevFloor = FLOOR_DEPEN_ACTIVE.get();
        double prevFloorCap = FLOOR_DEPEN_CAP.get();
        SIDE_DEPEN_CAP.set(Math.max(DEFAULT_SIDE_DEPEN_CAP, sideCap));
        FLOOR_DEPEN_ACTIVE.set(floorDepen);
        FLOOR_DEPEN_CAP.set(floorDepen ? Math.max(DEPEN_CAP, sideCap) : DEPEN_CAP);
        try {
            return resolve(entity, worldBox, worldDelta, ks, floorDepen);
        } finally {
            SIDE_DEPEN_CAP.set(prevSide);
            FLOOR_DEPEN_ACTIVE.set(prevFloor);
            FLOOR_DEPEN_CAP.set(prevFloorCap);
        }
    }

    public static Ride resolve(Entity entity, AABB worldBox, Vec3 worldDelta, List<KItem> ks) {
        return resolve(entity, worldBox, worldDelta, ks, true);
    }

    private static Ride resolve(Entity entity, AABB worldBox, Vec3 worldDelta, List<KItem> ks, boolean allowFloorSnap) {
        // the platform carry hiding in the delta is an EXACT rigid transform — start from the
        // carried spot and only collide the entity's OWN motion. sweeping the carry too meant
        // pre-depen fired on the pre-carry position vs the already-rotated hull and the seams
        // nibbled the tangential part → riders slowly slid off spinning kontras. fuck that.
        Vec3 carry = KontraGlue.takeFedCarry(entity);
        Vec3 ownDelta = worldDelta.subtract(carry);
        EntityBox eb = entityBoxFor(entity, worldBox, ks, allowFloorSnap);
        double sx = mid(worldBox.minX, worldBox.maxX) + carry.x;
        double sy = mid(worldBox.minY, worldBox.maxY) + carry.y;
        double sz = mid(worldBox.minZ, worldBox.maxZ) + carry.z;
        List<Solid> solids = solidsFor(sx, sy, sz, eb.hx, eb.hy, eb.hz, ownDelta.length() + 0.5, ks);
        if (solids.isEmpty()) {
            if (carry.lengthSqr() < 1.0e-12) return null;
            Ride pass = new Ride();
            pass.delta = worldDelta;
            return pass;
        }
        RIDDEN_DECK.set(KontraGlue.mind(entity).deckId);
        try {
            return resolveInner(entity, worldDelta, ownDelta, carry, eb, solids, sx, sy, sz, allowFloorSnap);
        } finally {
            RIDDEN_DECK.set(0L);
        }
    }

    private static Ride resolveInner(Entity entity, Vec3 worldDelta, Vec3 ownDelta, Vec3 carry,
                                     EntityBox eb, List<Solid> solids,
                                     double sx, double sy, double sz, boolean allowFloorSnap) {
        Depen pre = depenetrate(sx, sy, sz, eb, solids);
        double px = sx + pre.x, py = sy + pre.y, pz = sz + pre.z;
        Vec3 walkDelta = adaptWalkDelta(entity, ownDelta, eb, px, py, pz, solids, pre.groundId != 0);
        Depen move = sweepSlide(px, py, pz, eb, walkDelta.x, walkDelta.y, walkDelta.z, solids);
        boolean hitX = Math.abs(move.x - walkDelta.x) > 0.01;
        boolean hitZ = Math.abs(move.z - walkDelta.z) > 0.01;
        px += move.x; py += move.y; pz += move.z;
        Depen post = depenetrate(px, py, pz, eb, solids);
        px += post.x; py += post.y; pz += post.z;

        Depen snap = new Depen();
        boolean flying = entity instanceof Player p && p.getAbilities().flying;
        boolean wantsSnap = allowFloorSnap && !flying && ownDelta.y <= 0.05
                && (ridingFresh(entity) || pre.groundId != 0 || move.groundId != 0 || post.groundId != 0);
        if (wantsSnap && pre.groundId == 0 && move.groundId == 0 && post.groundId == 0) {
            snap = sweepSlide(px, py, pz, eb, 0.0, -FLOOR_SNAP, 0.0, solids);
            if (snap.groundId == 0 || snap.y >= -1.0e-8) snap = new Depen();
        }

        double rx = carry.x + pre.x + move.x + post.x + snap.x;
        double ry = carry.y + pre.y + move.y + post.y + snap.y;
        double rz = carry.z + pre.z + move.z + post.z + snap.z;

        Ride r = new Ride();
        r.delta = new Vec3(rx, ry, rz);
        r.hitX = hitX;
        r.hitZ = hitZ;
        r.onGround = !flying && (pre.groundId != 0 || move.groundId != 0 || post.groundId != 0 || snap.groundId != 0);
        r.ceiling = pre.ceiling || move.ceiling || post.ceiling || snap.ceiling;
        r.kontraId = pre.groundId != 0 ? pre.groundId
                : (move.groundId != 0 ? move.groundId
                : (post.groundId != 0 ? post.groundId
                : (snap.groundId != 0 ? snap.groundId
                : (pre.pushId != 0 ? pre.pushId : (move.pushId != 0 ? move.pushId : (post.pushId != 0 ? post.pushId : snap.pushId))))));
        r.obbRot = pre.groundRot != null ? pre.groundRot
                : (move.groundRot != null ? move.groundRot : (post.groundRot != null ? post.groundRot : snap.groundRot));
        // grounded is a STATE, not a motion side effect. walking parallel to a tilted deck makes
        // zero floor hits (the walk delta is projected onto the plane), so motion-based ground
        // stayed false → vanilla used AIR acceleration (5x slower, the snail walk) and refused
        // jumps. if a stable surface carries the feet right now, we are standing on it. period.
        if (!r.onGround && !flying && ownDelta.y <= 0.05) {
            SupportPoint sp = bestSupport(px, py, pz, eb, solids, true);
            if (sp != null && sp.stable) {
                r.onGround = true;
                if (r.kontraId == 0) r.kontraId = sp.solid.kontraId;
                if (r.obbRot == null) r.obbRot = frameForNormal(sp.solid, sp.nx, sp.ny, sp.nz);
            }
        }
        return r;
    }

    // support normal per entity survives a few dropped probes — the probe flickering off for one
    // tick mid-slope randomly ate walk speed (walk delta went unprojected → clipped by the ramp)
    private static final java.util.Map<java.util.UUID, double[]> LAST_SUPPORT = new java.util.concurrent.ConcurrentHashMap<>();

    private static Vec3 adaptWalkDelta(Entity entity, Vec3 delta, EntityBox eb,
                                       double ex, double ey, double ez,
                                       List<Solid> solids, boolean alreadyGrounded) {
        if (!(entity instanceof Player p) || p.getAbilities().flying) return delta;
        double horizSq = delta.x * delta.x + delta.z * delta.z;
        if (horizSq < 1.0e-8 || Math.abs(delta.y) > 0.12) return delta;
        SupportPoint support = bestSupport(ex, ey, ez, eb, solids, true);
        if (support == null) {
            if (!alreadyGrounded && !entity.onGround()) return delta;
            support = bestSupport(ex, ey, ez, eb, solids, false);
        }

        double nx, ny, nz;
        if (support != null && support.stable) {
            double len = Math.sqrt(sqr(support.nx, support.ny, support.nz));
            if (len < 1.0e-8) return delta;
            nx = support.nx / len;
            ny = support.ny / len;
            nz = support.nz / len;
            if (LAST_SUPPORT.size() > 512) LAST_SUPPORT.clear(); // lazy leak guard, whatever
            LAST_SUPPORT.put(entity.getUUID(), new double[]{nx, ny, nz, System.nanoTime()});
        } else {
            double[] mem = LAST_SUPPORT.get(entity.getUUID());
            if (mem == null || System.nanoTime() - (long) mem[3] > 250_000_000L) return delta;
            nx = mem[0]; ny = mem[1]; nz = mem[2];
        }
        if (ny < STABLE_FLOOR_MIN_UP) return delta;

        double alongNormal = delta.x * nx + delta.y * ny + delta.z * nz;
        double tx = delta.x - nx * alongNormal;
        double ty = delta.y - ny * alongNormal;
        double tz = delta.z - nz * alongNormal;
        double tanSq = sqr(tx, ty, tz);
        if (tanSq < 1.0e-10) return delta;

        double tanHoriz = Math.sqrt(tx * tx + tz * tz);
        if (tanHoriz < 1.0e-5) return new Vec3(tx, ty, tz);
        double scale = Math.min(MAX_SURFACE_WALK_SCALE, Math.sqrt(horizSq) / tanHoriz);
        return new Vec3(tx * scale, ty * scale, tz * scale);
    }

    private static SupportPoint bestSupport(double ex, double ey, double ez, EntityBox eb,
                                            List<Solid> solids, boolean stableOnly) {
        SupportPoint best = null;
        double bestGap = Double.MAX_VALUE;
        double[] up = eb.rotated ? eb.basis[1] : WORLD_BASIS[1];
        for (Solid s : solids) {
            SupportPoint support = supportLocal(ex, ey, ez, eb, s, 0.24, stableOnly);
            if (support == null) continue;
            double[] top = solidPoint(s, support.local);
            double feet = ex * up[0] + ey * up[1] + ez * up[2] - entityRadiusOn(eb, up[0], up[1], up[2]);
            double topAlong = top[0] * up[0] + top[1] * up[1] + top[2] * up[2];
            double gap = Math.abs(feet - topAlong);
            if (gap < bestGap) {
                best = support;
                bestGap = gap;
            }
        }
        return best;
    }

    private static SupportPoint supportLocal(double ex, double ey, double ez, EntityBox eb, Solid s,
                                             double maxGap, boolean stableOnly) {
        double[] up = eb.rotated ? eb.basis[1] : WORLD_BASIS[1];
        double[][] b = solidBasis(s);
        double rx = ex - s.cx, ry = ey - s.cy, rz = ez - s.cz;
        double[] local = new double[]{
                rx * b[0][0] + ry * b[0][1] + rz * b[0][2],
                rx * b[1][0] + ry * b[1][1] + rz * b[1][2],
                rx * b[2][0] + ry * b[2][1] + rz * b[2][2]
        };
        double[] half = new double[]{s.hx, s.hy, s.hz};
        int topAxis = 0;
        double dot0 = b[0][0] * up[0] + b[0][1] * up[1] + b[0][2] * up[2];
        double topSign = dot0 >= 0 ? 1.0 : -1.0;
        double topAlign = Math.abs(dot0);
        for (int i = 1; i < 3; i++) {
            double dot = b[i][0] * up[0] + b[i][1] * up[1] + b[i][2] * up[2];
            double align = Math.abs(dot);
            if (align > topAlign) {
                topAxis = i;
                topSign = dot >= 0 ? 1.0 : -1.0;
                topAlign = align;
            }
        }
        if (topAlign < 0.28) return null;
        double nx = b[topAxis][0] * topSign;
        double ny = b[topAxis][1] * topSign;
        double nz = b[topAxis][2] * topSign;
        boolean stable = stableWorldFloor(eb, nx, ny, nz);
        double nLen = Math.sqrt(sqr(nx, ny, nz));
        double worldUp = nLen > 1.0e-8 ? ny / nLen : 0.0;
        if (stableOnly ? !stable : worldUp < CONTACT_FRAME_MIN_UP) return null;
        for (int i = 0; i < 3; i++) {
            if (i == topAxis) continue;
            double radius = entityRadiusOn(eb, b[i][0], b[i][1], b[i][2]);
            if (stable && !hasUsefulFootOverlap(local[i], radius, half[i])) return null;
            double pad = radius + 0.04;
            if (local[i] < -half[i] - pad || local[i] > half[i] + pad) return null;
            local[i] = Math.max(-half[i], Math.min(half[i], local[i]));
        }
        local[topAxis] = topSign * half[topAxis];
        double[] top = solidPoint(s, local);
        double feet = ex * up[0] + ey * up[1] + ez * up[2] - entityRadiusOn(eb, up[0], up[1], up[2]);
        double topAlong = top[0] * up[0] + top[1] * up[1] + top[2] * up[2];
        double gap = feet - topAlong;
        // embedded feet in the ridden deck still count as supported — grounded must survive
        // while the up-heal is working, or the ride drops mid-repair
        double sink = s.kontraId == RIDDEN_DECK.get() && s.kontraId != 0 ? RIDDEN_FLOOR_WINDOW : 0.045;
        if (gap < -sink || gap > maxGap) return null;
        return new SupportPoint(local, nx, ny, nz, stable, s);
    }

    private static double[] solidPoint(Solid s, double[] local) {
        double[][] b = solidBasis(s);
        return new double[]{
                s.cx + b[0][0] * local[0] + b[1][0] * local[1] + b[2][0] * local[2],
                s.cy + b[0][1] * local[0] + b[1][1] * local[1] + b[2][1] * local[2],
                s.cz + b[0][2] * local[0] + b[1][2] * local[1] + b[2][2] * local[2]
        };
    }

    private static EntityBox entityBoxFor(Entity entity, AABB worldBox, List<KItem> ks, boolean allowContactObb) {
        double hx = half(worldBox.minX, worldBox.maxX);
        double hy = half(worldBox.minY, worldBox.maxY);
        double hz = half(worldBox.minZ, worldBox.maxZ);
        // sneak no longer means "welded to the deck" — that's the pilot's job
        return new EntityBox(hx, hy, hz, WORLD_BASIS, false, false);
    }

    private static float[] frameForNormal(Solid s, double nx, double ny, double nz) {
        return frameFromBasisAndNormal(solidBasis(s), nx, ny, nz);
    }

    private static float[] frameFromBasisAndNormal(double[][] b, double nx, double ny, double nz) {
        double nLen = Math.sqrt(sqr(nx, ny, nz));
        if (nLen < 1.0e-8) return null;
        double[] up = {nx / nLen, ny / nLen, nz / nLen};

        double[] forward = projectToPlane(b[2], up);
        if (sqr(forward[0], forward[1], forward[2]) < 1.0e-8) forward = projectToPlane(b[0], up);
        if (sqr(forward[0], forward[1], forward[2]) < 1.0e-8) forward = projectToPlane(b[1], up);
        normalizeInPlace(forward);

        double[] right = cross(up, forward);
        normalizeInPlace(right);
        forward = cross(right, up);
        normalizeInPlace(forward);
        return quatFromBasis(right, up, forward);
    }

    private static double[] projectToPlane(double[] v, double[] normal) {
        double d = v[0] * normal[0] + v[1] * normal[1] + v[2] * normal[2];
        return new double[]{v[0] - normal[0] * d, v[1] - normal[1] * d, v[2] - normal[2] * d};
    }

    private static void normalizeInPlace(double[] v) {
        double len = Math.sqrt(sqr(v[0], v[1], v[2]));
        if (len < 1.0e-8) return;
        v[0] /= len;
        v[1] /= len;
        v[2] /= len;
    }

    private static float[] quatFromBasis(double[] right, double[] up, double[] forward) {
        double m00 = right[0],   m01 = up[0],   m02 = forward[0];
        double m10 = right[1],   m11 = up[1],   m12 = forward[1];
        double m20 = right[2],   m21 = up[2],   m22 = forward[2];
        double x, y, z, w;
        double tr = m00 + m11 + m22;
        if (tr > 0.0) {
            double s = Math.sqrt(tr + 1.0) * 2.0;
            w = 0.25 * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
            w = (m21 - m12) / s;
            x = 0.25 * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25 * s;
            z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25 * s;
        }
        double len = Math.sqrt(x * x + y * y + z * z + w * w);
        return len > 1.0e-8
                ? new float[]{(float)(x / len), (float)(y / len), (float)(z / len), (float)(w / len)}
                : new float[]{0f, 0f, 0f, 1f};
    }

    private static List<Solid> solidsFor(double ex, double ey, double ez, double hx, double hy, double hz,
                                         double moveLen, List<KItem> ks) {
        List<Solid> out = new ArrayList<>();
        double reachPad = hx + hy + hz + moveLen + 2.0;
        for (KItem k : ks) {
            if (k.pos == null || k.rot == null) continue;
            float[] prevPos = k.prevPos != null ? k.prevPos : k.pos;
            float[] prevRot = k.prevRot != null ? k.prevRot : k.rot;
            for (AABB s : mergedLocalBoxes(k.shapes)) {
                double lx = mid(s.minX, s.maxX), ly = mid(s.minY, s.maxY), lz = mid(s.minZ, s.maxZ);
                double[] wc = fwdRot(lx, ly, lz, k.rot);
                double cx = k.pos[0] + wc[0], cy = k.pos[1] + wc[1], cz = k.pos[2] + wc[2];
                double[] pc = fwdRot(lx, ly, lz, prevRot);
                double pcx = prevPos[0] + pc[0], pcy = prevPos[1] + pc[1], pcz = prevPos[2] + pc[2];
                double dx = cx - ex, dy = cy - ey, dz = cz - ez;
                double pdx = pcx - ex, pdy = pcy - ey, pdz = pcz - ez;
                double reach = reachPad + Math.sqrt(sqr(cx - pcx, cy - pcy, cz - pcz));
                if (Math.min(sqr(dx, dy, dz), sqr(pdx, pdy, pdz)) > reach * reach + 6.0) continue;
                out.add(new Solid(k.id, cx, cy, cz, k.rot,
                        (float)half(s.minX, s.maxX), (float)half(s.minY, s.maxY), (float)half(s.minZ, s.maxZ),
                        pcx, pcy, pcz, prevRot));
            }
        }
        return out;
    }

    private static List<AABB> mergedLocalBoxes(List<VoxelShape> shapes) {
        List<AABB> boxes = new ArrayList<>();
        for (VoxelShape vs : shapes) boxes.addAll(vs.toAabbs());
        if (boxes.size() < 2) return boxes;

        boolean changed;
        do {
            changed = false;
            outer:
            for (int i = 0; i < boxes.size(); i++) {
                AABB a = boxes.get(i);
                for (int j = i + 1; j < boxes.size(); j++) {
                    AABB merged = tryMerge(a, boxes.get(j));
                    if (merged == null) continue;
                    boxes.set(i, merged);
                    boxes.remove(j);
                    changed = true;
                    break outer;
                }
            }
        } while (changed);
        return boxes;
    }

    private static AABB tryMerge(AABB a, AABB b) {
        if (same(a.minY, b.minY) && same(a.maxY, b.maxY) && same(a.minZ, b.minZ) && same(a.maxZ, b.maxZ)
                && touches(a.minX, a.maxX, b.minX, b.maxX)) {
            return new AABB(Math.min(a.minX, b.minX), a.minY, a.minZ,
                    Math.max(a.maxX, b.maxX), a.maxY, a.maxZ);
        }
        if (same(a.minX, b.minX) && same(a.maxX, b.maxX) && same(a.minZ, b.minZ) && same(a.maxZ, b.maxZ)
                && touches(a.minY, a.maxY, b.minY, b.maxY)) {
            return new AABB(a.minX, Math.min(a.minY, b.minY), a.minZ,
                    a.maxX, Math.max(a.maxY, b.maxY), a.maxZ);
        }
        if (same(a.minX, b.minX) && same(a.maxX, b.maxX) && same(a.minY, b.minY) && same(a.maxY, b.maxY)
                && touches(a.minZ, a.maxZ, b.minZ, b.maxZ)) {
            return new AABB(a.minX, a.minY, Math.min(a.minZ, b.minZ),
                    a.maxX, a.maxY, Math.max(a.maxZ, b.maxZ));
        }
        return null;
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) <= MERGE_EPS;
    }

    private static boolean touches(double aMin, double aMax, double bMin, double bMax) {
        return aMax + MERGE_EPS >= bMin && bMax + MERGE_EPS >= aMin;
    }

    private static final class Depen {
        double x, y, z;
        long groundId;
        long pushId;
        float[] groundRot;
        boolean ceiling;
    }

    private static final class SweepHit {
        final double t;
        final double nx, ny, nz;
        final long kontraId;
        final Solid solid;

        SweepHit(double t, double nx, double ny, double nz, Solid solid) {
            this.t = t;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.solid = solid;
            this.kontraId = solid.kontraId;
        }
    }

    private static Depen sweepSlide(double ex, double ey, double ez, EntityBox eb,
                                    double dx, double dy, double dz, List<Solid> solids) {
        Depen out = new Depen();
        double rx = dx, ry = dy, rz = dz;
        double px = ex, py = ey, pz = ez;
        for (int iter = 0; iter < MOVE_ITERS; iter++) {
            double len = Math.sqrt(sqr(rx, ry, rz));
            if (len < 1.0e-8) break;

            SweepHit best = null;
            long tangentGroundId = 0;
            float[] tangentGroundRot = null;
            for (Solid s : solids) {
                SweepHit h = sweepHitDetailed(px, py, pz, eb, rx, ry, rz, s);
                if (h == null) continue;
                double into = rx * h.nx + ry * h.ny + rz * h.nz;
                if (into >= -1.0e-9) {
                    // moving along or AWAY from this face = not a collision, for ANY face. these
                    // phantom t≈0 touches used to clamp the whole move without deflecting anything
                    // (deflection only fires when into<0) → 5 iterations of nothing → player frozen
                    // and jump eaten while hugging a single rotated block
                    double hx = px + rx * h.t;
                    double hy = py + ry * h.t;
                    double hz = pz + rz * h.t;
                    if (tangentGroundId == 0 && isFloorContact(h.nx, h.ny, h.nz, hx, hy, hz, eb, h.solid)) {
                        tangentGroundId = h.kontraId;
                        tangentGroundRot = frameForNormal(h.solid, h.nx, h.ny, h.nz);
                    }
                    continue;
                }
                if (best == null || h.t < best.t) best = h;
            }
            if (best == null) {
                out.x += rx; out.y += ry; out.z += rz;
                if (out.groundId == 0 && tangentGroundId != 0) {
                    out.groundId = tangentGroundId;
                    out.groundRot = tangentGroundRot;
                }
                break;
            }

            double safeT = Math.max(0.0, best.t - SWEEP_EPS / Math.max(len, 1.0e-6));
            double stepX = rx * safeT, stepY = ry * safeT, stepZ = rz * safeT;
            px += stepX; py += stepY; pz += stepZ;
            out.x += stepX; out.y += stepY; out.z += stepZ;

            if (isFloorContact(best.nx, best.ny, best.nz, px, py, pz, eb, best.solid)) {
                out.groundId = best.kontraId;
                out.groundRot = frameForNormal(best.solid, best.nx, best.ny, best.nz);
            } else if (normalDotUp(best.nx, best.ny, best.nz, eb) < -0.35) {
                out.ceiling = true;
            }
            if (best.kontraId != 0) out.pushId = best.kontraId;

            double rest = Math.max(0.0, 1.0 - safeT);
            rx *= rest; ry *= rest; rz *= rest;
            double into = rx * best.nx + ry * best.ny + rz * best.nz;
            if (into < 0.0) {
                rx -= best.nx * into;
                ry -= best.ny * into;
                rz -= best.nz * into;
            }

            // sit just outside the plane so the next SAT pass sees a clean separation.
            px += best.nx * CONTACT_SLOP;
            py += best.ny * CONTACT_SLOP;
            pz += best.nz * CONTACT_SLOP;
            out.x += best.nx * CONTACT_SLOP;
            out.y += best.ny * CONTACT_SLOP;
            out.z += best.nz * CONTACT_SLOP;
        }
        return out;
    }

    private static Depen depenetrate(double ex, double ey, double ez, EntityBox eb, List<Solid> solids) {
        Depen out = new Depen();
        for (int i = 0; i < DEPEN_ITERS; i++) {
            double[] best = null;
            long bestId = 0;
            boolean bestFloor = false;
            boolean bestCeiling = false;
            double bestMag = Double.MAX_VALUE;
            Solid bestSolid = null;
            for (Solid s : solids) {
                double[] m = mtv(ex, ey, ez, eb, s);
                if (m == null) continue;
                // deep in the deck we RIDE, the shortest way out may point sideways or DOWN —
                // taking it dumps the rider under their own floor. climb out the top instead.
                long ridden = RIDDEN_DECK.get();
                if (ridden != 0 && s.kontraId == ridden) {
                    double mLen = Math.sqrt(sqr(m[0], m[1], m[2]));
                    if (mLen > 1.0e-8 && normalDotUp(m[0], m[1], m[2], eb) / mLen < 0.5) {
                        double[] up = eb.rotated ? eb.basis[1] : WORLD_BASIS[1];
                        double solidTop = s.cx * up[0] + s.cy * up[1] + s.cz * up[2]
                                + solidRadiusOn(s, up[0], up[1], up[2]);
                        double feet = ex * up[0] + ey * up[1] + ez * up[2]
                                - entityRadiusOn(eb, up[0], up[1], up[2]);
                        double need = solidTop - feet;
                        if (need > 0 && need < 2.0) {
                            double[] cand = {up[0] * need, up[1] * need, up[2] * need};
                            if (isFloorMtv(cand, ex, ey, ez, eb, s)) m = cand;
                        }
                    }
                }
                boolean floor = isFloorMtv(m, ex, ey, ez, eb, s);
                double mag = sqr(m[0], m[1], m[2]);
                double len = Math.sqrt(mag);
                double floorCap = FLOOR_DEPEN_CAP.get();
                double[] c = null;
                if (floor && FLOOR_DEPEN_ACTIVE.get()) {
                    if (mag <= floorCap * floorCap) {
                        c = m;
                    } else {
                        // deep embed used to be REJECTED here → waist-deep in the deck forever.
                        // now it always heals, just gradually (0.3/pass) so there's no launch
                        double sc = 0.30 / len;
                        c = new double[]{ m[0] * sc, m[1] * sc, m[2] * sc };
                    }
                } else if (!floor) {
                    double dotUp = normalDotUp(m[0], m[1], m[2], eb) / Math.max(len, 1.0e-8);
                    boolean ceiling = dotUp < -0.35;
                    double cap = SIDE_DEPEN_CAP.get();
                    // ceilings push harder — walking under a tilted hull left half your head inside.
                    // EXCEPT the ceiling of the deck we ride: inside a 2-high cabin the boosted
                    // shove sandwiched the rider between ceiling-slam and floor-heal every tick.
                    // own-deck ceilings heal gently instead, never a slam
                    boolean ownDeck = s.kontraId == RIDDEN_DECK.get() && s.kontraId != 0;
                    double boost = ceiling && !ownDeck ? 0.35 : 0.0;
                    if (mag > 1.0e-8 && len <= cap + boost) c = m;
                    else if (ceiling && ownDeck && len > 1.0e-8) {
                        double sc = cap / len;
                        c = new double[]{ m[0] * sc, m[1] * sc, m[2] * sc };
                    }
                }
                if (c == null) continue;
                double cm = sqr(c[0], c[1], c[2]);
                boolean ceiling = !floor && normalDotUp(c[0], c[1], c[2], eb) < -0.35 * Math.sqrt(Math.max(cm, 1.0e-8));
                if (best == null || (floor && !bestFloor) || (floor == bestFloor && cm < bestMag)) {
                    best = c;
                    bestId = s.kontraId;
                    bestSolid = s;
                    bestFloor = floor;
                    bestCeiling = ceiling;
                    bestMag = cm;
                }
            }
            if (best == null) break;
            ex += best[0]; ey += best[1]; ez += best[2];
            out.x += best[0]; out.y += best[1]; out.z += best[2];
            if (bestId != 0) out.pushId = bestId;
            if (bestFloor) {
                out.groundId = bestId;
                if (bestSolid != null) out.groundRot = frameForNormal(bestSolid, best[0], best[1], best[2]);
            }
            if (bestCeiling) out.ceiling = true;
        }
        return out;
    }

    private static boolean isFloorMtv(double[] m, double ex, double ey, double ez, EntityBox eb, Solid s) {
        if (m == null) return false;
        if (!stableWorldFloor(eb, m[0], m[1], m[2])) return false;
        double[] up = eb.rotated ? eb.basis[1] : WORLD_BASIS[1];
        double upPush = m[0] * up[0] + m[1] * up[1] + m[2] * up[2];
        double sidePushSq = Math.max(0.0, sqr(m[0], m[1], m[2]) - upPush * upPush);
        if (upPush <= 1.0e-6 || upPush * upPush < sidePushSq * 0.55) return false;
        double feet = ex * up[0] + ey * up[1] + ez * up[2] - entityRadiusOn(eb, up[0], up[1], up[2]);
        double[][] b = solidBasis(s);
        double rx = ex - s.cx, ry = ey - s.cy, rz = ez - s.cz;
        double[] local = new double[]{
                rx * b[0][0] + ry * b[0][1] + rz * b[0][2],
                rx * b[1][0] + ry * b[1][1] + rz * b[1][2],
                rx * b[2][0] + ry * b[2][1] + rz * b[2][2]
        };
        double[] half = new double[]{s.hx, s.hy, s.hz};
        int topAxis = 0;
        double dot0 = b[0][0] * up[0] + b[0][1] * up[1] + b[0][2] * up[2];
        double topSign = dot0 >= 0 ? 1.0 : -1.0;
        double topAlign = Math.abs(dot0);
        for (int i = 1; i < 3; i++) {
            double dot = b[i][0] * up[0] + b[i][1] * up[1] + b[i][2] * up[2];
            double align = Math.abs(dot);
            if (align > topAlign) {
                topAxis = i;
                topSign = dot >= 0 ? 1.0 : -1.0;
                topAlign = align;
            }
        }
        if (topAlign < 0.28) return false;
        double nx = b[topAxis][0] * topSign;
        double ny = b[topAxis][1] * topSign;
        double nz = b[topAxis][2] * topSign;
        if (!stableWorldFloor(eb, nx, ny, nz)) return false;
        for (int i = 0; i < 3; i++) {
            if (i == topAxis) {
                local[i] = topSign * half[i];
            } else {
                double radius = entityRadiusOn(eb, b[i][0], b[i][1], b[i][2]);
                if (!hasUsefulFootOverlap(local[i], radius, half[i])) return false;
                local[i] = Math.max(-half[i], Math.min(half[i], local[i]));
            }
        }
        double topX = s.cx + b[0][0] * local[0] + b[1][0] * local[1] + b[2][0] * local[2];
        double topY = s.cy + b[0][1] * local[0] + b[1][1] * local[1] + b[2][1] * local[2];
        double topZ = s.cz + b[0][2] * local[0] + b[1][2] * local[1] + b[2][2] * local[2];
        double top = topX * up[0] + topY * up[1] + topZ * up[2];
        double below = s.kontraId == RIDDEN_DECK.get() && s.kontraId != 0 ? RIDDEN_FLOOR_WINDOW : 0.14;
        return feet >= top - below && feet <= top + FLOOR_DEPEN_CAP.get() + 0.14;
    }

    private static boolean isFloorContact(double nx, double ny, double nz, double ex, double ey, double ez,
                                          EntityBox eb, Solid s) {
        return isFloorMtv(new double[]{nx, ny, nz}, ex, ey, ez, eb, s);
    }

    private static double normalDotUp(double nx, double ny, double nz, EntityBox eb) {
        double[] up = eb.rotated ? eb.basis[1] : WORLD_BASIS[1];
        return nx * up[0] + ny * up[1] + nz * up[2];
    }

    private static boolean stableWorldFloor(EntityBox eb, double nx, double ny, double nz) {
        double len = Math.sqrt(sqr(nx, ny, nz));
        return len > 1.0e-8 && ny / len >= STABLE_FLOOR_MIN_UP;
    }

    private static boolean hasUsefulFootOverlap(double centerLocal, double radius, double half) {
        double overlap = Math.min(half, centerLocal + radius) - Math.max(-half, centerLocal - radius);
        double min = Math.min(0.24, Math.max(0.12, radius * 0.55));
        return overlap >= min;
    }

    private static SweepHit sweepHitDetailed(double ex, double ey, double ez, EntityBox eb,
                                             double dx, double dy, double dz, Solid s) {
        double enter = 0.0;
        double exit = 1.0;
        double bestAx = 0, bestAy = 1, bestAz = 0;
        double bestDist = 0, bestSpeed = 0;
        boolean hasAxis = false;
        for (double[] raw : axesFor(eb, s)) {
            double ax = raw[0], ay = raw[1], az = raw[2];
            double ls = sqr(ax, ay, az);
            if (ls < 1.0e-10) continue;
            if (Math.abs(ls - 1.0) > 1.0e-4) {
                double inv = 1.0 / Math.sqrt(ls);
                ax *= inv; ay *= inv; az *= inv;
            }
            double radius = entityRadiusOn(eb, ax, ay, az) + solidRadiusOn(s, ax, ay, az);
            double dist = ax * (s.cx - ex) + ay * (s.cy - ey) + az * (s.cz - ez);
            double speed = ax * dx + ay * dy + az * dz;
            if (Math.abs(speed) < 1.0e-10) {
                if (Math.abs(dist) > radius) return null;
                continue;
            }
            double a = (dist - radius) / speed;
            double b = (dist + radius) / speed;
            double axisEnter = Math.min(a, b);
            double axisExit = Math.max(a, b);
            if (axisEnter > enter || (!hasAxis && axisEnter >= -1.0e-8)) {
                enter = Math.max(0.0, axisEnter);
                bestAx = ax; bestAy = ay; bestAz = az;
                bestDist = dist;
                bestSpeed = speed;
                hasAxis = true;
            }
            exit = Math.min(exit, axisExit);
            if (enter > exit || exit < 0.0 || enter > 1.0) return null;
        }
        if (!hasAxis) return null;
        double sep = bestDist - bestSpeed * enter;
        double sign = sep > 0.0 ? -1.0 : 1.0;
        return new SweepHit(Math.max(0.0, enter), bestAx * sign, bestAy * sign, bestAz * sign, s);
    }

    private static double[] mtv(double px, double py, double pz, EntityBox eb, Solid s) {
        double[][] b = solidBasis(s);
        double dx = s.cx - px, dy = s.cy - py, dz = s.cz - pz;
        double minO = Double.MAX_VALUE, mx = 0, my = 0, mz = 0;
        for (double[] raw : axesFor(eb, s)) {
            double ax = raw[0], ay = raw[1], az = raw[2];
            double ls = sqr(ax, ay, az);
            if (ls < 1.0e-10) continue;
            if (Math.abs(ls - 1.0) > 1.0e-4) {
                double inv = 1.0 / Math.sqrt(ls);
                ax *= inv; ay *= inv; az *= inv;
            }
            double rA = entityRadiusOn(eb, ax, ay, az);
            double rB = Math.abs(ax * b[0][0] + ay * b[0][1] + az * b[0][2]) * s.hx
                    + Math.abs(ax * b[1][0] + ay * b[1][1] + az * b[1][2]) * s.hy
                    + Math.abs(ax * b[2][0] + ay * b[2][1] + az * b[2][2]) * s.hz;
            double d = ax * dx + ay * dy + az * dz;
            double o = rA + rB - Math.abs(d);
            if (o <= 0) return null;
            if (o < minO) {
                minO = o;
                double sign = d < 0 ? 1.0 : -1.0;
                mx = ax * o * sign; my = ay * o * sign; mz = az * o * sign;
            }
        }
        return new double[]{mx, my, mz};
    }

    private static double[][] axesFor(EntityBox eb, Solid s) {
        double[][] a = eb.rotated ? eb.basis : WORLD_BASIS;
        double[][] b = solidBasis(s);
        return new double[][]{
                a[0], a[1], a[2],
                b[0], b[1], b[2],
                cross(a[0], b[0]), cross(a[0], b[1]), cross(a[0], b[2]),
                cross(a[1], b[0]), cross(a[1], b[1]), cross(a[1], b[2]),
                cross(a[2], b[0]), cross(a[2], b[1]), cross(a[2], b[2])
        };
    }

    private static double[][] solidBasis(Solid s) {
        return basis(s.qx, s.qy, s.qz, s.qw);
    }

    private static double[][] basis(double qx, double qy, double qz, double qw) {
        double x2 = qx * 2, y2 = qy * 2, z2 = qz * 2;
        double xx = qx * x2, yy = qy * y2, zz = qz * z2;
        double xy = qx * y2, xz = qx * z2, yz = qy * z2;
        double wx = qw * x2, wy = qw * y2, wz = qw * z2;
        return new double[][]{
                {1 - yy - zz, xy + wz, xz - wy},
                {xy - wz, 1 - xx - zz, yz + wx},
                {xz + wy, yz - wx, 1 - xx - yy}
        };
    }

    private static double solidRadiusOn(Solid s, double ax, double ay, double az) {
        double[][] b = solidBasis(s);
        return Math.abs(ax * b[0][0] + ay * b[0][1] + az * b[0][2]) * s.hx
                + Math.abs(ax * b[1][0] + ay * b[1][1] + az * b[1][2]) * s.hy
                + Math.abs(ax * b[2][0] + ay * b[2][1] + az * b[2][2]) * s.hz;
    }

    private static double entityRadiusOn(EntityBox eb, double ax, double ay, double az) {
        if (!eb.rotated) return Math.abs(ax) * eb.hx + Math.abs(ay) * eb.hy + Math.abs(az) * eb.hz;
        double[][] b = eb.basis;
        return Math.abs(ax * b[0][0] + ay * b[0][1] + az * b[0][2]) * eb.hx
                + Math.abs(ax * b[1][0] + ay * b[1][1] + az * b[1][2]) * eb.hy
                + Math.abs(ax * b[2][0] + ay * b[2][1] + az * b[2][2]) * eb.hz;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double[] invRot(double x, double y, double z, float[] q) {
        return rot(x, y, z, -q[0], -q[1], -q[2], q[3]);
    }

    private static double[] fwdRot(double x, double y, double z, float[] q) {
        return rot(x, y, z, q[0], q[1], q[2], q[3]);
    }

    private static double[] rot(double x, double y, double z, double qx, double qy, double qz, double qw) {
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return new double[]{
                x + qw * tx + qy * tz - qz * ty,
                y + qw * ty + qz * tx - qx * tz,
                z + qw * tz + qx * ty - qy * tx
        };
    }

    private static double mid(double a, double b) { return (a + b) * 0.5; }
    private static double half(double a, double b) { return (b - a) * 0.5; }
    private static double sqr(double x, double y, double z) { return x * x + y * y + z * z; }
}
