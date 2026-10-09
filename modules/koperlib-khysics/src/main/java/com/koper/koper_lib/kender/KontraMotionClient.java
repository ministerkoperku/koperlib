package com.koper.koper_lib.kender;

import com.koper.koper_lib.network.*;
import com.koper.koper_lib.physics.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

public final class KontraMotionClient {
    private KontraMotionClient() {}
    private static KontraMotionFramePayload session;
    private static long sequence;
    private static boolean initialized;
    private static KontraMotionFramePayload pending;
    public static void receive(KontraMotionFramePayload p) {
        var mc=Minecraft.getInstance();
        if (mc.player==null || !mc.player.level().dimension().identifier().toString().equals(p.dimension())) return;
        if (session!=null && p.epoch()<session.epoch()) return;
        boolean changed=session==null || session.epoch()!=p.epoch();
        session=p;
        if (changed) { sequence=p.sequence(); initialized=false; }
        if (p.body()==0) {
            pending=null;
            var mind=KontraGlue.mind(mc.player);
            KontraGlue.release(mc.player,mind,"server-frame-left",false);
            mind.ghostX=p.linearVelocity().x/20;mind.ghostY=p.linearVelocity().y/20;mind.ghostZ=p.linearVelocity().z/20;
            return;
        }
        if (changed || p.correction() || !initialized) pending=p;
        applyPending();
    }
    public static void applyPending() {
        var mc=Minecraft.getInstance();
        var p=pending;
        if (p==null || mc.player==null || !mc.player.level().dimension().identifier().toString().equals(p.dimension())) return;
        var frame=frame(p.body());
        if (frame==null) return; // geometry can arrive in fragments; wait rather than move into an absent hull
        {
            mc.player.setPos(frame.toWorld(p.local()));
            var mind=KontraGlue.mind(mc.player);
            mind.deckId=p.body(); mind.basePos=null; mind.baseRot=null;
            boolean first=!initialized;
            initialized=true; pending=null;
            if(first && ClientPlayNetworking.canSend(KontraMotionPayload.TYPE))
                ClientPlayNetworking.send(new KontraMotionPayload(p.body(),p.dimension(),p.epoch(),++sequence,p.local(),mc.player.getYRot(),mc.player.getXRot()));
        }
    }
    public static boolean managed(LocalPlayer p) {
        return initialized && session!=null && session.body()!=0 && session.dimension().equals(p.level().dimension().identifier().toString())
            && session.body()==KontraGlue.mind(p).deckId && !p.isPassenger() && !p.getAbilities().flying
            && frame(session.body())!=null && ClientPlayNetworking.canSend(KontraMotionPayload.TYPE);
    }
    public static boolean fast(net.minecraft.world.entity.Entity e) {
        return e instanceof LocalPlayer p && managed(p);
    }
    public static Vec3 departure(net.minecraft.world.entity.Entity e,Vec3 at) {
        if(!(e instanceof LocalPlayer) || session==null || session.body()==0) return null;
        var pose=frame(session.body());return pose==null?null:pose.velocityAt(at).scale(1.0/20);
    }
    public static boolean vacuum(net.minecraft.world.entity.Entity e) {
        return session!=null && session.vacuum() && e instanceof LocalPlayer;
    }
    public static boolean send(LocalPlayer p) {
        if (!managed(p)) return false;
        var f=frame(session.body());
        ClientPlayNetworking.send(new KontraMotionPayload(session.body(),session.dimension(),session.epoch(),++sequence,
            f.toLocal(p.position()),p.getYRot(),p.getXRot()));
        return true;
    }
    private static KontraFrame frame(long id) {
        var k=KenderClientState.getById(id);
        if (k==null || k.currPos==null || k.currRot==null) return null;
        var p=k.currPos; var q=k.currRot;
        var origin=new Vec3(p[0],p[1],p[2]);var rotation=new Quaterniond(q[0],q[1],q[2],q[3]);
        Vec3 v=Vec3.ZERO,w=Vec3.ZERO,com=origin;
        if(session!=null && session.body()==id) {
            v=session.linearVelocity();w=session.angularVelocity();
            var local=session.localCenterOfMass();var arm=rotation.transform(new org.joml.Vector3d(local.x,local.y,local.z));
            com=origin.add(arm.x,arm.y,arm.z);
        }
        return new KontraFrame(origin,rotation,v,w,com,k.currServerTick);
    }
    public static void clear() { session=null; pending=null; initialized=false; sequence=0; }
}
