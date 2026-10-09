package com.koper.koper_lib.physics;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

// the official kontra seat — an invisible mount pinned to a kontraktion at LOCAL coords.
// rides the body through every rotation, the player never slides off. addons spawn one via
// KoperPhys.spawnSeat and startRiding it; input reading stays on the addon (getLastClientInput).
// not saved to disk — kontra ids change on restore, addons respawn seats in ON_SPAWN.
public class KontraSeat extends Entity {

    public static final EntityType<KontraSeat> TYPE = EntityType.Builder
        .<KontraSeat>of(KontraSeat::new, MobCategory.MISC)
        .sized(0.5f, 0.35f)
        .fireImmune()
        .build(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.ENTITY_TYPE,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("koperlib", "kontra_seat")));

    private static final EntityDataAccessor<Long>  KONTRA = SynchedEntityData.defineId(KontraSeat.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> LX = SynchedEntityData.defineId(KontraSeat.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> LY = SynchedEntityData.defineId(KontraSeat.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> LZ = SynchedEntityData.defineId(KontraSeat.class, EntityDataSerializers.FLOAT);

    public KontraSeat(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        setNoGravity(true);
    }

    public void bindTo(long kontraId, float lx, float ly, float lz) {
        entityData.set(KONTRA, kontraId);
        entityData.set(LX, lx);
        entityData.set(LY, ly);
        entityData.set(LZ, lz);
    }

    public long kontraId() { return entityData.get(KONTRA); }

    // 0 = vanilla, sneak drops you off right away. >0 = sneak is a control (down, brake, whatever the
    // vehicle wants) and only holding it this many ticks gets you off. server side only, not synced
    private int sneakOffTicks;
    private int sneakHeld;

    public KontraSeat sneakToLeave(int holdTicks) { sneakOffTicks = Math.max(0, holdTicks); return this; }
    public int sneakHoldTicks() { return sneakOffTicks; }

    // KontraSeatSneakMixin asks this instead of vanilla's "shift pressed = off"
    public boolean letsGoOf(net.minecraft.world.entity.player.Player player) {
        return sneakOffTicks == 0 ? player.isShiftKeyDown() : sneakHeld >= sneakOffTicks;
    }

    // the rider's keys, straight off their last input packet. null = empty seat or not a player
    public record PilotInput(float forward, float strafe, boolean jump, boolean sneak, boolean sprint) {
        // +1 up, -1 down, 0 neither or both
        public float lift() { return jump == sneak ? 0f : jump ? 1f : -1f; }
    }

    public PilotInput pilotInput() {
        if (!(getFirstPassenger() instanceof net.minecraft.server.level.ServerPlayer p)) return null;
        var in = p.getLastClientInput();
        if (in == null) return null;
        float forward = in.forward() == in.backward() ? 0f : in.forward() ? 1f : -1f;
        float strafe = in.left() == in.right() ? 0f : in.left() ? 1f : -1f;
        return new PilotInput(forward, strafe, in.jump(), in.shift(), in.sprint());
    }
    public float[] localPos() { return new float[]{ entityData.get(LX), entityData.get(LY), entityData.get(LZ) }; }

    @Override
    public void tick() {
        super.tick();
        long id = kontraId();
        if (id == 0L) return;

        float[] pos, rot;
        if (level().isClientSide()) {
            var peek = KontraGlue.CLIENT_POSE;
            float[] pose = peek != null ? peek.apply(id) : null;
            if (pose == null) return; // not synced yet — hold position, server will correct us
            pos = new float[]{ pose[0], pose[1], pose[2] };
            rot = new float[]{ pose[3], pose[4], pose[5], pose[6] };
        } else {
            sneakHeld = getFirstPassenger() instanceof net.minecraft.world.entity.player.Player rider
                && rider.isShiftKeyDown() ? sneakHeld + 1 : 0;
            pos = KoperPhys.getCachedPos(id);
            rot = KoperPhys.getCachedRot(id);
            if (pos == null || rot == null) { // kontra died under us — let the riders drop free
                ejectPassengers();
                discard();
                return;
            }
        }
        float[] w = rotate(entityData.get(LX), entityData.get(LY), entityData.get(LZ), rot);
        setPos(pos[0] + w[0], pos[1] + w[1], pos[2] + w[2]);
    }

    public void syncServerPose() {
        if(level().isClientSide()) return;
        float[] pos=KoperPhys.getCachedPos(kontraId()),rot=KoperPhys.getCachedRot(kontraId());
        if(pos==null || rot==null) return;
        float[] p=localPos(),w=rotate(p[0],p[1],p[2],rot);
        setPos(pos[0]+w[0],pos[1]+w[1],pos[2]+w[2]);
        for(var passenger:getPassengers()) positionRider(passenger);
    }

    // quat * v — tiny and local, the client has no KoperPhys math to borrow
    private static float[] rotate(float x, float y, float z, float[] q) {
        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        float tx = 2f * (qy * z - qz * y);
        float ty = 2f * (qz * x - qx * z);
        float tz = 2f * (qx * y - qy * x);
        return new float[]{
            x + qw * tx + qy * tz - qz * ty,
            y + qw * ty + qz * tx - qx * tz,
            z + qw * tz + qx * ty - qy * tx,
        };
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(KONTRA, 0L);
        b.define(LX, 0f);
        b.define(LY, 0f);
        b.define(LZ, 0f);
    }

    @Override protected boolean canAddPassenger(Entity e) { return getPassengers().isEmpty(); }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean hurtServer(ServerLevel level, DamageSource src, float amount) { return false; }
    @Override protected void readAdditionalSaveData(ValueInput in) {}
    @Override protected void addAdditionalSaveData(ValueOutput out) {}
}
