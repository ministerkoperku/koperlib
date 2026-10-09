package com.koper.koper_lib.network;

import com.koper.koper_lib.physics.*;
import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.dim.KhysDimensions;
import com.koper.koper_lib.api.core.KoperNetwork;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import java.util.*;

public final class KontraMotionServer {
    private KontraMotionServer() {}
    private static final Map<UUID,KontraMotionState> PEERS = new HashMap<>();
    private static long nextEpoch;
    private static final Set<UUID> READY=new HashSet<>();
    private static final Map<UUID,net.minecraft.world.entity.Entity> RIDERS=new HashMap<>();
    // Short lived, nonpersistent tickets: moving crew must survive vanilla visibility changes.
    private static final net.minecraft.server.level.TicketType CREW_TICKET=new net.minecraft.server.level.TicketType(20,
        net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION);
    public static void track(net.minecraft.world.entity.Entity entity) {
        if(!entity.level().isClientSide() && KhysDimensions.getFor(entity.level().dimension().identifier().toString()).flight().fastFlight())
            RIDERS.put(entity.getUUID(),entity);
    }
    public static Collection<net.minecraft.world.entity.Entity> occupants(ServerLevel level) {
        var entities=new LinkedHashMap<UUID,net.minecraft.world.entity.Entity>();
        for(var entity:level.getAllEntities()) if(entity!=null) entities.put(entity.getUUID(),entity);
        for(var entity:RIDERS.values()) if(entity.level()==level && !entity.isRemoved()) entities.put(entity.getUUID(),entity);
        return List.copyOf(entities.values());
    }
    public static net.minecraft.world.entity.Entity tracked(UUID id) {
        var entity=RIDERS.get(id);return entity!=null && !entity.isRemoved()?entity:null;
    }
    private static void keepChunk(ServerLevel level,Vec3 at) {
        var chunk=new net.minecraft.world.level.ChunkPos(((int)Math.floor(at.x))>>4,((int)Math.floor(at.z))>>4);
        level.getChunkSource().addTicketWithRadius(CREW_TICKET,chunk,2);
        level.getChunk(chunk.x(),chunk.z());
    }
    public static KontraFrame frame(long id) {
        float[] p=KoperPhys.getCachedPos(id), q=KoperPhys.getCachedRot(id);
        if (p==null || q==null) return null;
        return new KontraFrame(new Vec3(p[0],p[1],p[2]),new Quaterniond(q[0],q[1],q[2],q[3]),
            Vec3.ZERO,Vec3.ZERO,new Vec3(p[0],p[1],p[2]),0);
    }
    public static Vec3 anchor(ServerPlayer p,long body) {
        var s=PEERS.get(p.getUUID()); return s!=null && s.body==body && s.dimension.equals(dimension(p))?s.anchor():null;
    }
    public static Vec3 interactionEye(ServerPlayer player) {
        var state=PEERS.get(player.getUUID());
        if(state!=null && ready(player,state.body) && state.dimension.equals(dimension(player)) && bodyFor(player)==state.body) {
            var pose=frame(state.body);
            if(pose!=null) return pose.toWorld(state.anchor()).add(0,player.getEyeHeight(),0);
        }
        return player.getEyePosition();
    }
    private static boolean teleportPending(ServerPlayer p) {
        return ((com.koper.koper_lib.mixin.ServerGamePacketAccessor)p.connection).koper$pendingTeleport()!=null;
    }
    private static long bodyFor(ServerPlayer p) {return p.getVehicle() instanceof KontraSeat seat?seat.kontraId():KontraGlue.mind(p).deckId;}
    public static boolean ready(ServerPlayer p,long body) {var state=PEERS.get(p.getUUID());return state!=null && state.body==body && READY.contains(p.getUUID()) && !teleportPending(p);}
    public static boolean teleportReady(ServerPlayer p) {return !teleportPending(p);}
    public static boolean managed(ServerPlayer p) {
        var state=PEERS.get(p.getUUID());
        return !p.isPassenger() && READY.contains(p.getUUID()) && !teleportPending(p) && state!=null && state.dimension.equals(dimension(p)) && bodyFor(p)==state.body;
    }
    private static String dimension(ServerPlayer p) { return p.level().dimension().identifier().toString(); }
    private static void send(ServerPlayer p, KontraMotionState s, boolean correction) {
        var flight=KhysDimensions.getFor(dimension(p)).flight();
        var entry=KoperPhys.all().get(s.body);
        var raw=entry==null?null:KoperPhysBridge.bodyState(entry.worldHandle(),s.body);
        var state=raw==null?null:new com.koper.koper_lib.physics.body.KhysBodyState(s.body,raw);
        var v=state==null?new org.joml.Vector3d():state.velocity();
        var w=state==null?new org.joml.Vector3d():state.omega();
        var com=state==null?new org.joml.Vector3d():state.localCenterOfMass();
        KoperNetwork.send(p,new KontraMotionFramePayload(s.body,s.dimension,s.epoch,s.sequence(),s.anchor(),
            new Vec3(v.x,v.y,v.z),new Vec3(w.x,w.y,w.z),new Vec3(com.x,com.y,com.z),correction,flight.vacuumMomentum()));
    }
    public static Vec3 pointVelocity(long body,Vec3 at) {
        var e=KoperPhys.all().get(body);
        if(e==null) return Vec3.ZERO;
        var raw=com.koper.koper_lib.panama.KoperPhysBridge.bodyState(e.worldHandle(),body);
        if(raw==null) return Vec3.ZERO;
        var state=new com.koper.koper_lib.physics.body.KhysBodyState(body,raw);
        var pose=frame(body);if(pose==null)return Vec3.ZERO;
        var localCom=state.localCenterOfMass();var v=state.velocity();var w=state.omega();
        // Velocities are native; the lever arm belongs to the SAME cached frame as the rider.
        var coherent=new KontraFrame(pose.origin(),pose.rotation(),new Vec3(v.x,v.y,v.z),new Vec3(w.x,w.y,w.z),
            pose.toWorld(new Vec3(localCom.x,localCom.y,localCom.z)),pose.epoch());
        return coherent.velocityAt(at).scale(1.0/20);
    }
    public static void tick(MinecraftServer server) {
        for(var level:server.getAllLevels()) {
            if(!KhysDimensions.getFor(level.dimension().identifier().toString()).flight().fastFlight()) continue;
            for(var entity:occupants(level)) {
                if(entity==null) continue;
                if(entity instanceof KontraSeat seat) {
                    var pose=frame(seat.kontraId());
                    var entry=KoperPhys.all().get(seat.kontraId());
                    if(pose==null || entry==null || !entry.levelKey().equals(KoperPhys.levelKey(level))) {
                        seat.ejectPassengers();seat.discard();continue;
                    }
                    track(seat);
                    if(!seat.getPassengers().isEmpty()) {
                        var local=seat.localPos();keepChunk(level,pose.toWorld(new Vec3(local[0],local[1],local[2])));
                    }
                    for(var passenger:seat.getPassengers()) track(passenger);
                    seat.syncServerPose();continue;
                }
                if(entity instanceof net.minecraft.world.entity.player.Player || entity.isPassenger()) continue;
                var mind=KontraGlue.mind(entity);var pose=frame(mind.deckId);var entry=KoperPhys.all().get(mind.deckId);
                if(mind.deckId!=0 && (pose==null || entry==null || !entry.levelKey().equals(KoperPhys.levelKey(level)))) {
                    KontraGlue.release(entity,mind,"body-unavailable",false);continue;
                }
                if(pose==null || entry==null || entry.aligned) continue;
                track(entity);
                if(mind.basePos!=null && mind.baseRot!=null) {
                    var p=mind.basePos;var q=mind.baseRot;
                    var before=new KontraFrame(new Vec3(p[0],p[1],p[2]),new Quaterniond(q[0],q[1],q[2],q[3]),Vec3.ZERO,Vec3.ZERO,new Vec3(p[0],p[1],p[2]),0);
                    var at=pose.toWorld(before.toLocal(entity.position()));
                    keepChunk(level,at);
                    entity.setPos(at);
                }
                var p=pose.origin();var q=pose.rotation();
                mind.basePos=new float[]{(float)p.x,(float)p.y,(float)p.z};
                mind.baseRot=new float[]{(float)q.x,(float)q.y,(float)q.z,(float)q.w};
            }
        }
        RIDERS.values().removeIf(entity -> entity.isRemoved() || (!(entity instanceof KontraSeat) && !entity.isPassenger() && KontraGlue.mind(entity).deckId==0)
            || !KhysDimensions.getFor(entity.level().dimension().identifier().toString()).flight().fastFlight());
        for (ServerPlayer p:server.getPlayerList().getPlayers()) {
            if (teleportPending(p)) continue;
            var mind=KontraGlue.mind(p);
            long body=bodyFor(p);
            var entry=KoperPhys.all().get(body);
            var pose=frame(body);
            boolean eligible=body!=0 && entry!=null && pose!=null && !entry.aligned
                && (p.isPassenger() || (!p.isSpectator() && !p.getAbilities().flying))
                && entry.levelKey().equals(KoperPhys.levelKey((ServerLevel)p.level()))
                && KhysDimensions.getFor(dimension(p)).flight().fastFlight()
                && ServerPlayNetworking.canSend(p,KontraMotionFramePayload.TYPE);
            var s=PEERS.get(p.getUUID());
            if (!eligible) { leave(p); continue; }
            if (s==null || s.body!=body || !s.dimension.equals(dimension(p))) {
                s=new KontraMotionState(body,dimension(p),++nextEpoch,pose.toLocal(p.position()));
                PEERS.put(p.getUUID(),s); READY.remove(p.getUUID()); send(p,s,true);
            } else if(!READY.contains(p.getUUID())) { send(p,s,true); continue; } else if(p.isPassenger()) {
                s.solved(pose.toLocal(p.position()));
            } else {
                // Hull CCD owns travel collision. Resolve the player's small own movement at the new anchor.
                p.setPos(pose.toWorld(s.anchor()));
                p.connection.resetPosition();
        ((ServerLevel)p.level()).getChunkSource().move(p);
                p.move(MoverType.SELF,Vec3.ZERO);
                s.solved(pose.toLocal(p.position()));
                if (mind.deckId!=body) { leave(p); continue; }
            }
            send(p,s,false);
        }
    }
    public static void handle(ServerPlayer p,KontraMotionPayload packet) {
        var s=PEERS.get(p.getUUID());
        if (s==null || teleportPending(p) || !s.dimension.equals(dimension(p)) || bodyFor(p)!=s.body) return;
        var pose=frame(s.body);
        if (pose==null || !Float.isFinite(packet.yaw()) || !Float.isFinite(packet.pitch())
            || !s.accept(packet.body(),packet.dimension(),packet.epoch(),packet.sequence(),p.level().getGameTime(),packet.local())) {
            send(p,s,true); return;
        }
        READY.add(p.getUUID());
        if(p.isPassenger()) {send(p,s,false);return;}
        p.setPos(pose.toWorld(s.anchor()));
        Vec3 own=pose.directionToWorld(packet.local().subtract(s.anchor()));
        p.move(MoverType.SELF,own);
        p.connection.resetPosition();
        ((ServerLevel)p.level()).getChunkSource().move(p);
        p.setYRot(packet.yaw()); p.setXRot(Math.max(-90,Math.min(90,packet.pitch())));
        s.solved(pose.toLocal(p.position()));
        // Correct only actual blocked steps. ACKs preserve outstanding client prediction.
        send(p,s,s.anchor().distanceToSqr(packet.local())>.0025);
    }
    public static void leave(ServerPlayer p) { leave(p,true); }
    public static void leave(ServerPlayer p,boolean momentum) {
        READY.remove(p.getUUID());
        var old=PEERS.remove(p.getUUID());
        if (old!=null && ServerPlayNetworking.canSend(p,KontraMotionFramePayload.TYPE))
            KoperNetwork.send(p,new KontraMotionFramePayload(0,dimension(p),++nextEpoch,0,Vec3.ZERO,
                momentum && old.dimension.equals(dimension(p))?pointVelocity(old.body,p.position()).scale(20):Vec3.ZERO,
                Vec3.ZERO,Vec3.ZERO,true,KhysDimensions.getFor(dimension(p)).flight().vacuumMomentum()));
    }
    public static void forget(UUID player) { PEERS.remove(player); READY.remove(player); }
    public static void clear() { PEERS.clear(); READY.clear(); RIDERS.clear(); }
}
