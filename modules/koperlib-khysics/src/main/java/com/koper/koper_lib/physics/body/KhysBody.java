package com.koper.koper_lib.physics.body;

import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.KontraEntry;
import com.koper.koper_lib.physics.KontraGrid;
import com.koper.koper_lib.physics.KontraGridContext;
import com.koper.koper_lib.physics.KoperPhys;
import com.koper.koper_lib.physics.dim.KhysDimensions;
import com.koper.koper_lib.physics.weight.KhysWeightBook;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.joml.Vector3dc;

// the koper handle for one kontraption. everything an addon (or a ship mod ported from another engine) does to a
// body goes through here instead of KoperPhys statics + a world handle + float[] soup:
//
//   KhysBody ship = KhysBody.at(level, helmPos).body();
//   ship.pusher((state, push) -> push.cancelGravity().localForce(thrust));   // 60Hz, fresh state
//   ship.stash().putString("mymod:name", "Koper's Boat");                     // saved with the hull
//
// a handle is cheap and holds nothing but the id — grab a new one whenever, compare by id().
// all server thread, except what a KhysPusher does (that's the physics thread, read its doc).
public final class KhysBody {

    private final long id;

    private KhysBody(long id) { this.id = id; }

    // null if no such body is alive
    public static KhysBody of(long kontraId) {
        return KoperPhys.all().containsKey(kontraId) ? new KhysBody(kontraId) : null;
    }

    // which body owns this position, and where on it. understands all three places a "ship block
    // position" can be: the block entity grid (a BE on a hull ticks at a private grid pos), the world
    // cell a hull block currently projects into, and the world pos a block was assembled FROM.
    // null = plain world block
    public static Spot at(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return null;
        KontraGrid active = KontraGridContext.active();
        if (active != null && active.ownsGridPos(pos)) {
            KhysBody body = of(active.kontraId());
            if (body != null) return new Spot(body, active.toLocal(pos));
        }
        KontraGrid grid = KoperPhys.gridAtLogicalExact(server, pos);
        if (grid != null) {
            KhysBody body = of(grid.kontraId());
            if (body != null) return new Spot(body, grid.toLocal(pos));
        }
        var endpoint = KoperPhys.resolveMovingEndpoint(server, pos);
        if (endpoint == null) return null;
        KhysBody body = of(endpoint.kontraId());
        return body != null ? new Spot(body, endpoint.local()) : null;
    }

    // a body plus a block on it, in assembly-local coordinates (the key of entry().blocks)
    public record Spot(KhysBody body, BlockPos local) {
        public BlockState state() { return body.entry().blocks.get(local); }
    }

    public long id() { return id; }

    public boolean alive() { return KoperPhys.all().containsKey(id); }

    // live entry, null once the body is gone
    public KontraEntry entry() { return KoperPhys.all().get(id); }

    public ServerLevel level() {
        KontraEntry e = entry();
        return e != null ? KoperPhys.levelFor(e) : null;
    }

    private long world() {
        KontraEntry e = entry();
        return e != null ? e.worldHandle() : 0L;
    }

    // ── reading ──────────────────────────────────────────────────────────────

    // last published state, server thread. up to one physics step old — a pusher gets the fresh one
    public KhysBodyState state() {
        float[] raw = KoperPhysBridge.bodyState(world(), id);
        return new KhysBodyState(id, raw != null ? raw : estimate());
    }

    // gravity of this body's dimension, blocks/s² (negative = down). per body scale not included
    public Vector3d dimensionGravity() {
        ServerLevel level = level();
        float[] g = level != null
            ? KhysDimensions.getFor(level.dimension().identifier().toString()).gravity()
            : KhysDimensions.DimPhysics.DEFAULT.gravity();
        return new Vector3d(g[0], g[1], g[2]);
    }

    // ── pushing ──────────────────────────────────────────────────────────────

    // the "every physics step" hook. one pusher per body, a new one replaces the old
    public KhysBody pusher(KhysPusher pusher) { KhysPushers.attach(id, pusher); return this; }

    public KhysBody clearPusher() { KhysPushers.detach(id); return this; }

    // force + torque (world frame, through the COM) kept on every physics step until changed.
    // for a steady push decided at 20Hz: a propeller at a fixed throttle, a winch, a wind vane
    public KhysBody hold(Vector3dc force, Vector3dc torque) {
        KoperPhysBridge.setHeldPush(world(), id,
            (float) force.x(), (float) force.y(), (float) force.z(),
            (float) torque.x(), (float) torque.y(), (float) torque.z());
        return this;
    }

    public KhysBody letGo() {
        KoperPhysBridge.setHeldPush(world(), id, 0, 0, 0, 0, 0, 0);
        return this;
    }

    // instant velocity change of impulse/mass, through the COM
    public KhysBody kick(Vector3dc impulse) {
        KoperPhysBridge.applyImpulse(world(), id, (float) impulse.x(), (float) impulse.y(), (float) impulse.z());
        return this;
    }

    public KhysBody kickAt(Vector3dc impulse, Vector3dc worldPoint) {
        KoperPhysBridge.applyImpulseAtPoint(world(), id,
            (float) impulse.x(), (float) impulse.y(), (float) impulse.z(),
            (float) worldPoint.x(), (float) worldPoint.y(), (float) worldPoint.z());
        return this;
    }

    // angular impulse, world frame
    public KhysBody spin(Vector3dc angularImpulse) {
        KoperPhysBridge.applyAngularImpulse(world(), id,
            (float) angularImpulse.x(), (float) angularImpulse.y(), (float) angularImpulse.z());
        return this;
    }

    public KhysBody velocity(Vector3dc v) {
        KoperPhysBridge.setVelocity(world(), id, (float) v.x(), (float) v.y(), (float) v.z());
        return this;
    }

    // ── knobs ────────────────────────────────────────────────────────────────

    // 1 = normal, 0 = weightless, negative falls up
    public KhysBody gravityScale(double scale) {
        KoperPhysBridge.setGravityScale(world(), id, (float) scale);
        return this;
    }

    // multiplies what water pushes up. a floater block, a leaky hull, a submarine's ballast tanks
    public KhysBody buoyancyScale(double scale) {
        KoperPhysBridge.setBuoyancyScale(world(), id, (float) scale);
        return this;
    }

    public KhysBody damping(double linear, double angular) {
        KoperPhys.setBodyDamping(id, (float) linear, (float) angular);
        return this;
    }

    // frozen in place, immovable — an anchor. unparking wakes it with zero velocity
    public KhysBody parked(boolean parked) {
        KoperPhys.setBodyParked(id, parked);
        return this;
    }

    // one block's weight, in place, without touching the block. weigher-backed blocks
    // (KhysWeightBook.weigher) get this automatically when their state changes
    public KhysBody blockMass(BlockPos local, double mass) {
        KontraEntry e = entry();
        if (e == null) return this;
        float[] off = e.blockOffsets.get(local);
        if (off == null) return this;
        KoperPhysBridge.setBlockMass(e.worldHandle(), id, off[0], off[1], off[2], (float) mass);
        return this;
    }

    // ── data that rides with the hull ────────────────────────────────────────

    // a tag saved and restored with this body (kontras.bin), for whatever your mod wants to remember
    // about it: a ship name, a brain's settings, an owner. namespace your keys ("mymod:thing").
    // a body restored from disk gets it back under its new id before ON_RESTORE fires.
    // a split-off child starts with an empty one — copy what it should inherit in ON_SPLIT
    public CompoundTag stash() {
        KontraEntry e = entry();
        return e != null ? e.stash : new CompoundTag();
    }

    // ── frames ───────────────────────────────────────────────────────────────

    // block-offset space → world, at the last published pose
    public Vector3d toWorld(Vector3dc local) { return state().toWorld(local); }

    public Vector3d toWorld(BlockPos local) {
        KontraEntry e = entry();
        float[] off = e != null ? e.blockOffsets.get(local) : null;
        Vector3d p = off != null ? new Vector3d(off[0], off[1], off[2])
            : new Vector3d(local.getX(), local.getY(), local.getZ());
        return toWorld(p);
    }

    public Vector3d toLocal(Vector3dc world) { return state().toLocal(world); }

    @Override public boolean equals(Object o) { return o instanceof KhysBody b && b.id == id; }
    @Override public int hashCode() { return Long.hashCode(id); }
    @Override public String toString() { return "KhysBody#" + id; }

    // ── engines with no state readback (elpe) ────────────────────────────────

    // built from what java has anyway: cached pose, last tick's movement, weight-book masses.
    // no spin (elpe bodies don't turn) and no wetness
    private float[] estimate() {
        float[] s = new float[KhysBodyState.LEN];
        s[6] = 1f;
        KontraEntry e = entry();
        float[] pos = KoperPhys.getCachedPos(id), rot = KoperPhys.getCachedRot(id);
        if (pos != null) { s[0] = pos[0]; s[1] = pos[1]; s[2] = pos[2]; }
        if (rot != null) { s[3] = rot[0]; s[4] = rot[1]; s[5] = rot[2]; s[6] = rot[3]; }
        float[] d = KoperPhys.getKontraVelocity(id);
        if (d != null) { s[7] = d[0] * 20f; s[8] = d[1] * 20f; s[9] = d[2] * 20f; }
        double mass = 0, cx = 0, cy = 0, cz = 0;
        if (e != null) {
            for (var b : e.blocks.entrySet()) {
                float[] off = e.blockOffsets.get(b.getKey());
                if (off == null) continue;
                double m = KhysWeightBook.get(b.getValue()).mass();
                mass += m; cx += off[0] * m; cy += off[1] * m; cz += off[2] * m;
            }
        }
        if (mass > 0) { cx /= mass; cy /= mass; cz /= mass; }
        s[16] = (float) cx; s[17] = (float) cy; s[18] = (float) cz;
        Vector3d com = new KhysBodyState(id, s.clone()).toWorld(new Vector3d(cx, cy, cz));
        s[13] = (float) com.x; s[14] = (float) com.y; s[15] = (float) com.z;
        s[19] = (float) Math.max(mass, 0.01);
        // a solid-ish lump: good enough for "torque = I·α" on a body that can't rotate anyway
        float lump = (float) (Math.max(mass, 0.01) / 6.0);
        s[20] = lump; s[24] = lump; s[28] = lump;
        Vector3d g = dimensionGravity();
        s[29] = (float) g.x; s[30] = (float) g.y; s[31] = (float) g.z;
        s[38] = 1f; s[39] = 1f;
        if (e != null && e.aligned) s[34] = 2f;
        return s;
    }
}
