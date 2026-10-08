package com.koper.koper_lib.bijatyka;

// runs one swing at a time: windup -> active -> recovery
// boss ticks this each server tick, asks inActiveWindow() to know when the hit actually bites
public final class KoperSwingTimer {

    private KoperSwing swing;
    private int t;          // ticks since start
    private int phase = 0;  // 0 idle, 1 windup, 2 active, 3 recovery

    public boolean busy() { return phase != 0; }

    public void start(KoperSwing s) {
        if (s == null) return;
        swing = s;
        t = 0;
        phase = 1;
        if (s.onWindup != null) s.onWindup.run();
    }

    public void tick() {
        if (phase == 0) return;
        t++;
        int w = swing.windup;
        int a = w + swing.active;
        int r = a + swing.recovery;

        if (phase == 1 && t >= w) {
            phase = 2;
            if (swing.onActive != null) swing.onActive.run();
        } else if (phase == 2 && t >= a) {
            phase = 3;
            if (swing.onRecover != null) swing.onRecover.run();
        } else if (phase == 3 && t >= r) {
            phase = 0;
            swing = null;
        }
    }

    // active frames only -> swing the bone hitbox here
    public boolean inActiveWindow() { return phase == 2; }

    public boolean inWindup()   { return phase == 1; }
    public boolean inRecovery() { return phase == 3; }   // the laggy bit after the hit, good for punish/rest windows

    public KoperSwing current() { return swing; }
}
