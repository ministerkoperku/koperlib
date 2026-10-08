package com.koper.koper_lib.physics;

import com.koper.koper_lib.config.KoperLibConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.function.LongFunction;
import java.util.function.Predicate;

// the whole "riding a kontra" thing in ONE place. every entity carries a little Mind:
// which deck it stands on + the last platform pose it already moved with + leftover momentum.
// inherited motion goes INTO Entity.move's own delta (see KontraCollideRedirectMixin), so
// vanilla's input-vs-output flag compare stays honest — friction, jumps, fall dmg all native.
// no more carry passes chasing the player from outside. hello people reading this o/
public final class KontraGlue {

    private KontraGlue() {}

    public static final class Mind {
        public long deckId;              // kontra we ride, 0 = nothing
        public int lostTicks;            // ticks since last real deck support
        public float[] basePos, baseRot; // platform pose we already moved with
        public double ghostX, ghostY, ghostZ; // platform momentum, m/tick, decays when off deck
        public double fedX, fedY, fedZ;  // inherited we injected THIS tick
        public boolean fedCarry;         // true = exact platform carry, false = ghost momentum
        public boolean fedSpent;         // the solve consumed it already (one move per feed)
        public int fedTick = Integer.MIN_VALUE;
        public int stampTick = Integer.MIN_VALUE;
        public long lastRideNanos;       // visuals grace
        public float[] deckRot;          // support frame from the solve, HUD/camera stuff
    }

    public interface MindHaver { Mind koper$rideMind(); }

    // flat pose peek: [px,py,pz, qx,qy,qz,qw, radius, aligned(1/0)] or null when gone.
    // client side fills this from KenderClientState (packet pose!), server reads KoperPhys.
    public static volatile LongFunction<float[]> CLIENT_POSE = null;
    // "is this THE local player" — inherited on the client is local-player-only,
    // everything else is server-synced anyway
    public static volatile Predicate<Entity> CLIENT_LOCAL = null;

    // how long tracking survives with no support (jump arc ≈ 12 ticks)
    private static final int LOST_GRACE_TICKS = 40;
    // 16 blocks/tick = 320 m/s. Koper gliders move fast; the old 8 was only a teleport guard
    // sized for walking-speed ships
    private static final double MAX_INHERITED_SQ = 256.0;

    public static Mind mind(Entity e) {
        return ((MindHaver) e).koper$rideMind();
    }

    public static boolean tracked(Entity e) {
        return e != null && mind(e).deckId != 0L;
    }

    // called from the move() head, once per tick per entity. returns delta + inherited.
    public static Vec3 feedInherited(Entity self, MoverType type, Vec3 delta) {
        boolean client = self.level().isClientSide();
        if (client) {
            Predicate<Entity> local = CLIENT_LOCAL;
            if (local == null || !local.test(self)) return delta;
        } else {
            // server players re-sim their CLIENT-claimed delta — inherited is already baked in
            if (type != MoverType.SELF || self instanceof Player) return delta;
        }
        Mind m = mind(self);
        if (self.tickCount == m.stampTick) return delta; // second move() this tick, already fed
        m.stampTick = self.tickCount;
        m.fedX = m.fedY = m.fedZ = 0.0;
        m.fedCarry = false;
        m.fedSpent = false;
        m.fedTick = self.tickCount;

        if (self.isSpectator()) {
            m.deckId = 0L;
            zeroGhost(m);
            return delta;
        }
        if (self instanceof Player p && p.getAbilities().flying) {
            if (m.deckId != 0L) release(self, m, "flying", true);
            return ghostFeed(self, m, delta);
        }

        if (m.deckId != 0L) {
            float[] pose = peekPose(self, m.deckId);
            if (pose == null) {
                release(self, m, "deck-gone", true);
                return ghostFeed(self, m, delta);
            }
            if (pose[8] > 0.5f) { // parked on grid = plain solid blocks now, nothing to inherit
                release(self, m, "parked", false);
                return delta;
            }
            if (m.basePos == null || m.baseRot == null) {
                m.basePos = new float[]{pose[0], pose[1], pose[2]};
                m.baseRot = new float[]{pose[3], pose[4], pose[5], pose[6]};
                return delta;
            }
            double px = self.getX(), py = self.getY(), pz = self.getZ();
            double[] local = invRot(px - m.basePos[0], py - m.basePos[1], pz - m.basePos[2], m.baseRot);
            double[] now = fwdRot(local[0], local[1], local[2], new float[]{pose[3], pose[4], pose[5], pose[6]});
            double ix = pose[0] + now[0] - px;
            double iy = pose[1] + now[1] - py;
            double iz = pose[2] + now[2] - pz;
            m.basePos[0] = pose[0]; m.basePos[1] = pose[1]; m.basePos[2] = pose[2];
            m.baseRot[0] = pose[3]; m.baseRot[1] = pose[4]; m.baseRot[2] = pose[5]; m.baseRot[3] = pose[6];
            double sq = ix * ix + iy * iy + iz * iz;
            if (sq > MAX_INHERITED_SQ) {
                release(self, m, "carry-far", false);
                zeroGhost(m);
                return delta;
            }
            m.ghostX = ix; m.ghostY = iy; m.ghostZ = iz; // stepping off keeps this as momentum
            m.fedX = ix; m.fedY = iy; m.fedZ = iz;
            m.fedCarry = true;
            return sq < 1.0e-10 ? delta : delta.add(ix, iy, iz);
        }
        return ghostFeed(self, m, delta);
    }

    // off-deck leftover momentum, bleeds out instead of vanishing — jump off a moving ship
    // and you fly WITH it, not like it never existed
    private static Vec3 ghostFeed(Entity self, Mind m, Vec3 delta) {
        double sq = m.ghostX * m.ghostX + m.ghostY * m.ghostY + m.ghostZ * m.ghostZ;
        if (sq < 1.0e-8) {
            zeroGhost(m);
            return delta;
        }
        double gx = m.ghostX, gy = m.ghostY, gz = m.ghostZ;
        dragGhost(self, m);
        m.fedX = gx; m.fedY = gy; m.fedZ = gz;
        return delta.add(gx, gy, gz);
    }

    private static void dragGhost(Entity self, Mind m) {
        if (self.onGround() || self.verticalCollision) {
            // vertical momentum DIES on landing — keeping it relaunched the player every
            // touchdown, bouncing them across the map like a rubber ball (koper report)
            m.ghostX *= 0.7; m.ghostZ *= 0.7; m.ghostY = 0.0;
        }
        if (self.horizontalCollision) {
            m.ghostX *= 0.8; m.ghostY *= 0.6; m.ghostZ *= 0.8;
        }
        if (self.isInWater()) {
            m.ghostX *= 0.9; m.ghostY *= 0.9; m.ghostZ *= 0.9;
        }
        m.ghostX *= 0.99; m.ghostY *= 0.99; m.ghostZ *= 0.99;
        if (Math.abs(m.ghostY) < 0.01) m.ghostY = 0.0;
    }

    private static void zeroGhost(Mind m) {
        m.ghostX = m.ghostY = m.ghostZ = 0.0;
    }

    // tracking transitions, right after the solve + vanilla collide ran
    public static void afterMove(Entity self, KontraRide.Ride r, Vec3 solved, Vec3 afterVanilla) {
        Mind m = mind(self);
        boolean grounded = r != null && r.onGround && r.kontraId != 0L;
        if (grounded) {
            if (m.deckId != r.kontraId) {
                dbg(self, "ride ON id=" + r.kontraId + " (was " + m.deckId + ")");
                m.deckId = r.kontraId;
                m.basePos = null;
                m.baseRot = null;
                float[] pose = peekPose(self, m.deckId);
                if (pose != null && pose[8] < 0.5f) {
                    m.basePos = new float[]{pose[0], pose[1], pose[2]};
                    m.baseRot = new float[]{pose[3], pose[4], pose[5], pose[6]};
                }
            }
            m.deckRot = r.obbRot != null ? r.obbRot.clone() : m.deckRot;
            m.lostTicks = 0;
            m.lastRideNanos = System.nanoTime();
            return;
        }
        if (m.deckId == 0L) return;

        // vanilla floor caught a fall the solve let through = we stand on the WORLD now
        if (solved != null && afterVanilla != null
                && solved.y < -1.0e-7 && afterVanilla.y - solved.y > 1.0e-7) {
            release(self, m, "world-ground", true);
            return;
        }
        float[] pose = peekPose(self, m.deckId);
        if (pose == null) {
            release(self, m, "deck-gone", true);
            return;
        }
        double dx = self.getX() - pose[0], dy = self.getY() - pose[1], dz = self.getZ() - pose[2];
        double reach = pose[7] + 2.0;
        if (dx * dx + dy * dy + dz * dz > reach * reach) {
            release(self, m, "walked-away", true);
            return;
        }
        // still near the hull (mid-jump etc.) — carry keeps flowing, but not forever
        if (++m.lostTicks > LOST_GRACE_TICKS) release(self, m, "lost-grace", true);
    }

    public static void release(Entity self, Mind m, String why, boolean keepGhost) {
        if (m.deckId != 0L) dbg(self, "ride OFF id=" + m.deckId + " why=" + why);
        m.deckId = 0L;
        m.basePos = null;
        m.baseRot = null;
        m.lostTicks = 0;
        m.deckRot = null;
        if (!keepGhost) zeroGhost(m);
    }

    // consume-once: the solve treats the platform carry as an exact rigid transform and must
    // NOT sweep it. only the move that fed it may take it — later same-tick probes get zero.
    // ghost momentum is not a rigid carry (it should collide), so it never comes out of here.
    public static Vec3 takeFedCarry(Entity self) {
        Mind m = mind(self);
        if (!m.fedCarry || m.fedSpent || m.fedTick != self.tickCount) return Vec3.ZERO;
        m.fedSpent = true;
        return new Vec3(m.fedX, m.fedY, m.fedZ);
    }

    private static float[] peekPose(Entity self, long id) {
        if (self.level().isClientSide()) {
            LongFunction<float[]> peek = CLIENT_POSE;
            return peek != null ? peek.apply(id) : null;
        }
        float[] p = KoperPhys.getCachedPos(id);
        float[] r = KoperPhys.getCachedRot(id);
        KontraEntry d = KoperPhys.all().get(id);
        if (p == null || r == null || d == null) return null;
        return new float[]{p[0], p[1], p[2], r[0], r[1], r[2], r[3], d.cachedRadius, d.aligned ? 1f : 0f};
    }

    // movement budget while tracked — fed carry vs own input vs what survived the solve and
    // vanilla, plus dm/shift/hand so a pilot-lock freeze is visible too. "frozen on a flying
    // kontra" class bugs are unfixable without these numbers
    public static void budgetDbg(Entity self, Vec3 delta, Vec3 solved, Vec3 after) {
        if (!KoperLibConfig.get().debugMode) return;
        Mind m = mind(self);
        if (m.deckId == 0L || self.tickCount % 10 != 0) return;
        double ownX = delta.x - m.fedX, ownY = delta.y - m.fedY, ownZ = delta.z - m.fedZ;
        Vec3 dm = self.getDeltaMovement();
        String hand = self instanceof Player p ? p.getMainHandItem().getItem().toString() : "-";
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info(
                "[RideDbg] ({}) {}#{} budget deck={} fed=({},{},{}) own=({},{},{}) solved=({},{},{}) after=({},{},{}) dm=({},{},{}) ground={} shift={} hand={}",
                self.level().isClientSide() ? "C" : "S", self.getType().toShortString(), self.getId(), m.deckId,
                f3(m.fedX), f3(m.fedY), f3(m.fedZ), f3(ownX), f3(ownY), f3(ownZ),
                f3(solved.x), f3(solved.y), f3(solved.z), f3(after.x), f3(after.y), f3(after.z),
                f3(dm.x), f3(dm.y), f3(dm.z),
                self.onGround(), self.isShiftKeyDown(), hand);
    }

    private static String f3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    static void dbg(Entity self, String msg) {
        if (!KoperLibConfig.get().debugMode) return;
        // who exactly — mob riding bugs are invisible without the entity name in the log
        com.koper.koper_lib.coremod.KoperCore.LOGGER.info("[RideDbg] ({}) {}#{} {}", self.level().isClientSide() ? "C" : "S",
                self.getType().toShortString(), self.getId(), msg);
    }

    private static double[] invRot(double x, double y, double z, float[] q) {
        return rot(x, y, z, -q[0], -q[1], -q[2], q[3]);
    }

    private static double[] fwdRot(double x, double y, double z, float[] q) {
        return rot(x, y, z, q[0], q[1], q[2], q[3]);
    }

    private static double[] rot(double x, double y, double z, double qx, double qy, double qz, double qw) {
        double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
        return new double[]{x + qw * tx + qy * tz - qz * ty, y + qw * ty + qz * tx - qx * tz,
                z + qw * tz + qx * ty - qy * tx};
    }
}
