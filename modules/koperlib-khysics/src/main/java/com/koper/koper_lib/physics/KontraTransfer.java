package com.koper.koper_lib.physics;

import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.network.KontraMotionServer;
import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.body.KhysBodyState;
import com.koper.koper_lib.physics.dim.KhysDimensions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.*;
import org.joml.Quaterniond;
import java.util.*;

public final class KontraTransfer {
    private KontraTransfer() {}
    private static final List<Moving> ACTIVATIONS=new ArrayList<>();
    private static final List<Moving> RECOVERIES=new ArrayList<>();
    private static final Map<UUID,Boolean> recoveryGravity=new HashMap<>();
    static boolean recoveringBody(long id) {
        return RECOVERIES.stream().anyMatch(stage -> stage.oldIds.contains(id));
    }
    public static void recover(net.minecraft.server.MinecraftServer server) {
        for(var stage:List.copyOf(ACTIVATIONS)) if(stage.source.getServer()==server && stage.activate()) ACTIVATIONS.remove(stage);
        for(var stage:List.copyOf(RECOVERIES)) if(stage.source.getServer()==server) {
            stage.rollback();
            if(!stage.recoveryPending) { RECOVERIES.remove(stage); KontraTransferTransaction.release(Set.copyOf(stage.oldIds)); }
        }
    }
    public static void clear(net.minecraft.server.MinecraftServer server) {
        for(var stage:List.copyOf(RECOVERIES)) if(stage.source.getServer()==server) {
            KontraTransferTransaction.release(Set.copyOf(stage.oldIds));RECOVERIES.remove(stage);
            for(var o:stage.occupants) {var gravity=recoveryGravity.remove(o.current.getUUID());if(gravity!=null)o.current.setNoGravity(gravity);}
        }
        ACTIVATIONS.removeIf(stage -> stage.source.getServer()==server);
        LAST_ATTEMPT.clear();
    }
    private static final ThreadLocal<List<KontraSeat>> STAGED_SEATS=new ThreadLocal<>();
    static KontraSeat reusableSeat(ServerLevel level,long id,float x,float y,float z) {
        var seats=STAGED_SEATS.get();
        if (seats==null) return null;
        for (var seat:seats) {
            var p=seat.localPos();
            if (seat.level()==level && seat.kontraId()==id && Math.abs(p[0]-x)<1e-4
                && Math.abs(p[1]-y)<1e-4 && Math.abs(p[2]-z)<1e-4) return seat;
        }
        return null;
    }
    private static final Map<Long,Integer> LAST_ATTEMPT=new HashMap<>();
    public static KoperPhys.AssemblyTransfer move(long root,ServerLevel target,float[] where,int yawQuarters) {
        var entry=KoperPhys.all().get(root); var source=KoperPhys.levelFor(entry);
        if (source==null || target==null || source.getServer()!=target.getServer() || !source.getServer().isSameThread()) return null;
        if (where!=null && (where.length<3 || !Float.isFinite(where[0]) || !Float.isFinite(where[1]) || !Float.isFinite(where[2]))) return null;
        int tick=source.getServer().getTickCount();
        LAST_ATTEMPT.entrySet().removeIf(attempt -> attempt.getValue()<tick-1);
        var stage=new Moving(root,source,target,where,yawQuarters);
        if(stage.oldIds.stream().anyMatch(id -> Objects.equals(LAST_ATTEMPT.get(id),tick) || ACTIVATIONS.stream().anyMatch(active -> active.remap.containsValue(id)))) return null;
        for(long id:stage.oldIds) LAST_ATTEMPT.put(id,tick);
        try { return KontraTransferTransaction.run(Set.copyOf(stage.oldIds),stage)
            ? new KoperPhys.AssemblyTransfer(Collections.unmodifiableMap(stage.remap),stage.remap.get(root)) : null;
        } catch (RuntimeException failure) {
            KoperCore.LOGGER.error("[Khysics] occupied assembly transfer failed for {}",root,failure);return null;
        }
    }
    private static final class Occupant {
        Entity current;
        final Vec3 before,local,velocity;
        final float yaw,pitch;
        final long oldBody;
        final KontraSeat oldSeat;
        KontraSeat newSeat;
        boolean moved;
        Occupant(Entity e,long id,KontraFrame pose,KontraSeat seat) {
            current=e; before=e.position();
            Vec3 authoritative=e instanceof ServerPlayer player?KontraMotionServer.anchor(player,id):null;
            local=authoritative!=null?authoritative:pose.toLocal(before); velocity=e.getDeltaMovement();
            yaw=e.getYRot();pitch=e.getXRot();oldBody=id;oldSeat=seat;
        }
    }
    private static final class Moving implements KontraTransferTransaction.Stage {
        final long root;
        final ServerLevel source,target;
        final List<Long> oldIds;
        final Map<Long,Long> remap=new LinkedHashMap<>();
        final Map<Long,KhysBodyState> motion=new LinkedHashMap<>();
        final List<Occupant> occupants=new ArrayList<>();
        final List<KontraSeat> seats=new ArrayList<>();
        final Quaterniond yaw;
        final int quarters;
        float[] destination;
        boolean sourceParked;
        boolean recoveryPending;
        @Override public boolean recoveryPending() { return recoveryPending; }
        Moving(long root,ServerLevel source,ServerLevel target,float[] where,int quarters) {
            this.root=root;this.source=source;this.target=target;oldIds=KoperPhys.assemblyOf(root);
            destination=where==null?null:where.clone();this.quarters=Math.floorMod(quarters,4);yaw=new Quaterniond().rotateY(Math.floorMod(quarters,4)*Math.PI/2);
        }
        @Override public boolean prepare() {
            for (Entity e:KontraMotionServer.occupants(source)) {
                if(e==null) continue;
                if (e instanceof KontraSeat) continue;
                var seat=e.getVehicle() instanceof KontraSeat s?s:null;
                long id=seat!=null?seat.kontraId():KontraGlue.mind(e).deckId;
                if (!oldIds.contains(id) || (seat==null && (e.isSpectator() || e instanceof ServerPlayer player && player.getAbilities().flying))) continue;
                var pose=KontraMotionServer.frame(id);
                if (pose!=null) occupants.add(new Occupant(e,id,pose,seat));
            }
            long sourceWorld=KoperPhys.all().get(root).worldHandle();
            if (!KoperPhysBridge.syncCommands(sourceWorld)) return false;
            for (long id:oldIds) {
                var entry=KoperPhys.all().get(id);
                if (entry==null || KoperPhys.levelFor(entry)!=source) return false;
                float[] raw=KoperPhysBridge.bodyState(sourceWorld,id);
                if (raw==null) return false;
                motion.put(id,new KhysBodyState(id,raw));
            }
            for (long id:oldIds) KoperPhys.setBodyParked(id,true);
            sourceParked=true;
            if (!KoperPhysBridge.syncCommands(sourceWorld)) return false;
            for (long id:oldIds) {
                float[] raw=KoperPhysBridge.bodyState(sourceWorld,id);
                if (raw==null) return false;
                KoperPhys.installFrozenPose(id,raw);
            }
            var snap=KoperPhys.captureAssembly(root);
            if (snap==null) return false;
            if (destination==null) destination=KoperPhys.getCachedPos(root).clone();
            var flight=KhysDimensions.getFor(target.dimension().identifier().toString()).flight();
            if (!flight.allows(new Vec3(destination[0],destination[1],destination[2]),Vec3.ZERO)) return false;
            // Fail closed at unloaded destinations. The caller prepares its portal/landing area first.
            var rootRotation=new Quaterniond(snap.rootRot()[0],snap.rootRot()[1],snap.rootRot()[2],snap.rootRot()[3]);
            var base=new Quaterniond(yaw).mul(rootRotation);
            int bodyIndex=0;
            for (var body:snap.bodies()) {
                var state=motion.get(oldIds.get(bodyIndex++));var velocity=yaw.transform(state.velocity());
                var center=base.transform(new org.joml.Vector3d(body.pos()[0],body.pos()[1],body.pos()[2]));
                if(!flight.allows(new Vec3(center.x+destination[0],center.y+destination[1],center.z+destination[2]),new Vec3(velocity.x,velocity.y,velocity.z))) return false;
                var rotation=new Quaterniond(base).mul(new Quaterniond(body.rot()[0],body.rot()[1],body.rot()[2],body.rot()[3]));
                for (int i=0;i<body.offsets().length;i+=3) {
                    var point=rotation.transform(new org.joml.Vector3d(body.offsets()[i],body.offsets()[i+1],body.offsets()[i+2])).add(center)
                        .add(destination[0],destination[1],destination[2]);
                    if (!target.hasChunk(((int)Math.floor(point.x))>>4,((int)Math.floor(point.z))>>4)
                        || !target.noCollision(null,new AABB(point.x-.866,point.y-.866,point.z-.866,point.x+.866,point.y+.866,point.z+.866))) return false;
                }
            }
            long[] ids;
            KoperPhys.beginTransferStaging();
            try { ids=KoperPhys.spawnAssembly(target,snap,destination,quarters,true); }
            finally { KoperPhys.endTransferStaging(); }
            if (ids==null || ids.length!=oldIds.size()) return false;
            for (int i=0;i<ids.length;i++) {
                long old=oldIds.get(i), fresh=ids[i];
                remap.put(old,fresh);
                var from=KoperPhys.all().get(old); var to=KoperPhys.all().get(fresh);
                to.stash.merge(from.stash.copy());
                to.assembledFrom.putAll(from.assembledFrom);
                from.localData.forEach((key,value)->to.localData.put(key,value.copy()));
                to.grid(fresh).restoreTicks(from.grid(old).savedTicks());
                to.setAeroMode(from.aeroOverride());
                KoperPhys.setAeroMode(fresh,from.aeroOverride());
            }
            long world=KoperPhys.all().get(ids[0]).worldHandle();
            if (!KoperPhysBridge.syncCommands(world)) return false;
            for (long id:ids) {
                float[] raw=KoperPhysBridge.bodyState(world,id);
                if (raw==null) return false;
                KoperPhys.installFrozenPose(id,raw);
            }
            return true;
        }
        @Override public boolean moveOccupants() {
            for (long id:remap.values()) KoperPhys.publishTransferBody(id);
            for (var o:occupants) {
                var frame=KontraMotionServer.frame(remap.get(o.oldBody));
                if (frame==null) return false;
                Vec3 at=frame.toWorld(o.local);
                o.current.stopRiding();
                Entity moved=o.current.teleport(new TeleportTransition(target,at,o.velocity,o.yaw,o.pitch,TeleportTransition.DO_NOTHING));
                if (moved==null) return false;
                o.current=moved;o.moved=true;
                if (moved instanceof ServerPlayer p) {KontraMotionServer.leave(p,false);KoperPhys.sendAllToPlayer(p);}
                if (o.oldSeat!=null) {
                    float[] p=o.oldSeat.localPos();
                    o.newSeat=KoperPhys.spawnSeat(target,remap.get(o.oldBody),p[0],p[1],p[2]);
                    if (o.newSeat==null) return false;
                    seats.add(o.newSeat);o.newSeat.sneakToLeave(o.oldSeat.sneakHoldTicks());
                    if (!moved.startRiding(o.newSeat,true,false)) return false;
                } else {
                    var m=KontraGlue.mind(moved);m.deckId=remap.get(o.oldBody);m.basePos=null;m.baseRot=null;m.lostTicks=0;
                }
                KontraMotionServer.track(moved);
            }
            return true;
        }
        @Override public void rollback() {
            boolean recovered=true;
            for (int i=occupants.size()-1;i>=0;i--) {
                var o=occupants.get(i);
                if (o.moved) {
                    if(o.current instanceof ServerPlayer) {
                        var online=source.getServer().getPlayerList().getPlayer(o.current.getUUID());
                        if(online==null) {recovered=false;continue;}
                        o.current=online;
                    } else if(o.current.isRemoved()) {o.moved=false;continue;}
                    var sourceFrame=KontraMotionServer.frame(o.oldBody);
                    if(sourceFrame==null) {recovered=false;continue;}
                    o.current.stopRiding();
                    Entity restored=o.current.teleport(new TeleportTransition(source,sourceFrame.toWorld(o.local),o.velocity,o.yaw,o.pitch,TeleportTransition.DO_NOTHING));
                    if (restored==null) {recovered=false;continue;}
                    o.moved=false;
                    o.current=restored;
                }
                var sourceFrame=KontraMotionServer.frame(o.oldBody);
                if(sourceFrame!=null) o.current.setPos(sourceFrame.toWorld(o.local));
                if (o.oldSeat!=null && !o.oldSeat.isRemoved()) o.current.startRiding(o.oldSeat,true,false);
                var mind=KontraGlue.mind(o.current);mind.deckId=o.oldBody;mind.basePos=null;mind.baseRot=null;
            }
            for (var seat:seats) seat.discard();
            for (long id:remap.values()) KoperPhys.destroyKontraktion(source.getServer(),id);
            remap.clear(); seats.clear();
            if (!recovered) {
                boolean firstRecovery=!recoveryPending;
                recoveryPending=true;
                if (!RECOVERIES.contains(this)) RECOVERIES.add(this);
                for(var o:occupants) if(o.moved) {
                    if(!recoveryGravity.containsKey(o.current.getUUID())) recoveryGravity.put(o.current.getUUID(),o.current.isNoGravity());
                    o.current.setNoGravity(true); o.current.setDeltaMovement(Vec3.ZERO);
                }
                if(firstRecovery) KoperCore.LOGGER.error("[Khysics] source assembly {} held for occupant recovery; destination copies removed",oldIds);
            } else {
                recoveryPending=false;
                for(var o:occupants) { var gravity=recoveryGravity.remove(o.current.getUUID()); if(gravity!=null) o.current.setNoGravity(gravity); }
                if (sourceParked) for (long id:oldIds) restoreMotion(id,motion.get(id),false);
            }
        }
        @Override public void commit() {
            STAGED_SEATS.set(seats);
            try {KoperPhysicsEvents.fireTransfer(Collections.unmodifiableMap(remap),source,target);}
            finally {STAGED_SEATS.remove();}
            for (var o:occupants) if (o.oldSeat!=null && !o.oldSeat.isRemoved()) o.oldSeat.discard();
            ACTIVATIONS.add(this);
            for (long old:oldIds) KoperPhys.destroyKontraktion(source.getServer(),old);
        }
        private boolean activate() {
            boolean ready=true;
            for(var o:occupants) {
                if(o.current instanceof ServerPlayer) {
                    var online=target.getServer().getPlayerList().getPlayer(o.current.getUUID());
                    if(online==null) continue;
                    o.current=online;
                } else if(o.current.isRemoved()) continue;
                var present=target.getEntity(o.current.getUUID());
                if(present!=o.current) {ready=false;continue;}
                var frame=KontraMotionServer.frame(remap.get(o.oldBody));
                if(frame==null) return true; // explicitly destroyed destination
                if(o.oldSeat==null) {
                    o.current.setPos(frame.toWorld(o.local));
                    var m=KontraGlue.mind(o.current);m.deckId=remap.get(o.oldBody);m.basePos=null;m.baseRot=null;
                }
                if(o.current instanceof ServerPlayer p) {
                    boolean fast=KhysDimensions.getFor(target.dimension().identifier().toString()).flight().fastFlight();
                    var stream=KenderSyncServer.stats(p.getUUID());
                    if(!KontraMotionServer.teleportReady(p) || (fast && !KontraMotionServer.ready(p,remap.get(o.oldBody)))
                        || stream!=null && (stream.queuedBodies()>0 || stream.inFlightBytes()>0)) ready=false;
                }
            }
            if(!ready) return false;
            for(long old:oldIds) restoreMotion(remap.get(old),motion.get(old),true);
            return true;
        }
        private void restoreMotion(long id,KhysBodyState state,boolean rotate) {
            var entry=KoperPhys.all().get(id);
            if (entry==null || state==null) return;
            KoperPhys.setBodyParked(id,state.parked());
            if (state.parked()) return;
            var v=state.velocity();var w=state.omega();
            if (rotate) {yaw.transform(v);yaw.transform(w);}
            KoperPhysBridge.setVelocity(entry.worldHandle(),id,(float)v.x,(float)v.y,(float)v.z);
            if (!KoperPhysBridge.setAngularVelocity(entry.worldHandle(),id,(float)w.x,(float)w.y,(float)w.z))
                KoperCore.LOGGER.error("[Khysics] native could not restore spin for {}",id);
        }
    }
}
