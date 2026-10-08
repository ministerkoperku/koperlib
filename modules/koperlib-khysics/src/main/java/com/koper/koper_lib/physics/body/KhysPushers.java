package com.koper.koper_lib.physics.body;

import com.koper.koper_lib.coremod.KoperCore;
import com.koper.koper_lib.panama.KoperPhysBridge;
import com.koper.koper_lib.physics.KontraEntry;
import com.koper.koper_lib.physics.KoperPhys;
import com.koper.koper_lib.physics.KoperPhysicsEvents;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// who pushes which body. the engine calls runNative from its physics thread for every body marked
// pushed; engines that can't do that get the pusher run from the server tick instead (serverTick).
//
// pushers are keyed by kontra id and die with the body. a body restored from disk comes back under a
// new id (ON_RESTORE) — re-attach there, same as seats. a split-off child starts unpushed.
public final class KhysPushers {

    private KhysPushers() {}

    private static final Map<Long, KhysPusher> PUSHERS = new ConcurrentHashMap<>();
    // bodies whose engine said "can't do it on my thread" — run on the server tick instead
    private static final Set<Long> SERVER_SIDE = ConcurrentHashMap.newKeySet();
    // one log line per body that throws, not sixty a second
    private static final Set<Long> COMPLAINED = ConcurrentHashMap.newKeySet();

    private static volatile boolean wired;

    public static void attach(long kontraId, KhysPusher pusher) {
        if (pusher == null) { detach(kontraId); return; }
        wire();
        KontraEntry entry = KoperPhys.all().get(kontraId);
        if (entry == null) return;
        PUSHERS.put(kontraId, pusher);
        COMPLAINED.remove(kontraId);
        if (KoperPhysBridge.setPushed(entry.worldHandle(), kontraId, true)) SERVER_SIDE.remove(kontraId);
        else SERVER_SIDE.add(kontraId);
    }

    public static void detach(long kontraId) {
        if (PUSHERS.remove(kontraId) == null) return;
        KontraEntry entry = KoperPhys.all().get(kontraId);
        if (entry != null) {
            KoperPhysBridge.setPushed(entry.worldHandle(), kontraId, false);
            if (SERVER_SIDE.remove(kontraId))
                KoperPhysBridge.setHeldPush(entry.worldHandle(), kontraId, 0, 0, 0, 0, 0, 0);
        }
        SERVER_SIDE.remove(kontraId);
        COMPLAINED.remove(kontraId);
    }

    public static KhysPusher of(long kontraId) { return PUSHERS.get(kontraId); }

    // PHYSICS THREAD. out = force xyz, torque xyz. never throws
    public static void runNative(long kontraId, float[] state, float[] out) {
        KhysPusher pusher = PUSHERS.get(kontraId);
        if (pusher == null) return;
        KhysPush push = run(kontraId, pusher, new KhysBodyState(kontraId, state));
        if (push == null) return;
        out[0] = (float) push.force().x();  out[1] = (float) push.force().y();  out[2] = (float) push.force().z();
        out[3] = (float) push.torque().x(); out[4] = (float) push.torque().y(); out[5] = (float) push.torque().z();
    }

    // SERVER THREAD, once per tick, from KoperPhys.tickAll — the fallback for engines without a pusher
    // hook. result is held for the whole tick, which is the closest a 20Hz caller gets to 60Hz
    public static void serverTick() {
        if (SERVER_SIDE.isEmpty()) return;
        for (long id : SERVER_SIDE) {
            KhysPusher pusher = PUSHERS.get(id);
            KontraEntry entry = KoperPhys.all().get(id);
            if (pusher == null || entry == null) continue;
            KhysBody body = KhysBody.of(id);
            if (body == null) continue;
            KhysPush push = run(id, pusher, body.state());
            if (push == null) continue;
            KoperPhysBridge.setHeldPush(entry.worldHandle(), id,
                (float) push.force().x(), (float) push.force().y(), (float) push.force().z(),
                (float) push.torque().x(), (float) push.torque().y(), (float) push.torque().z());
        }
    }

    private static KhysPush run(long id, KhysPusher pusher, KhysBodyState state) {
        try {
            KhysPush push = new KhysPush(state);
            pusher.push(state, push);
            return push;
        } catch (Throwable error) {
            if (COMPLAINED.add(id))
                KoperCore.LOGGER.error("[Khysics] pusher on kontra {} threw — its push is skipped until it stops", id, error);
            return null;
        }
    }

    private static synchronized void wire() {
        if (wired) return;
        wired = true;
        KoperPhysicsEvents.ON_DESTROY.add(id -> {
            PUSHERS.remove(id);
            SERVER_SIDE.remove(id);
            COMPLAINED.remove(id);
        });
    }
}
