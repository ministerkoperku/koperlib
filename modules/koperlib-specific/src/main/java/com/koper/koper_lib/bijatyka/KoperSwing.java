package com.koper.koper_lib.bijatyka;

// one attack move. timings in ticks. anim = geo clip fired on windup.
public final class KoperSwing {

    public final int windup;
    public final int active;
    public final int recovery;

    public String anim;
    public Runnable onWindup;
    public Runnable onActive;
    public Runnable onRecover;

    public KoperSwing(int windup, int active, int recovery) {
        this.windup = windup;
        this.active = active;
        this.recovery = recovery;
    }

    public KoperSwing anim(String a) { this.anim = a; return this; }
    public KoperSwing onWindup(Runnable r) { this.onWindup = r; return this; }
    public KoperSwing onActive(Runnable r) { this.onActive = r; return this; }
    public KoperSwing onRecover(Runnable r) { this.onRecover = r; return this; }
}
