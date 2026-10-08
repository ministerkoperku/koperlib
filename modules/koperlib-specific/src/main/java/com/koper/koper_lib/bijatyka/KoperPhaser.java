package com.koper.koper_lib.bijatyka;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;

// fires a callback when boss hp drops past a gate. add gates in any order, 1.0 = start phase
// hello whoever reads this, help a silly little koperdev make bosses not suck xD
public final class KoperPhaser {

    private record Gate(float frac, Runnable onEnter) {}

    private final ArrayList<Gate> gates = new ArrayList<>();
    private int current = -1;

    public KoperPhaser add(float hpFrac, Runnable onEnter) {
        gates.add(new Gate(hpFrac, onEnter));
        gates.sort((a, b) -> Float.compare(b.frac, a.frac));
        return this;
    }

    public void tick(LivingEntity boss) {
        float max = boss.getMaxHealth();
        if (max <= 0) return;   // dead/uninit, dont divide by that
        float frac = boss.getHealth() / max;

        while (current + 1 < gates.size() && frac <= gates.get(current + 1).frac) {
            current++;
            Runnable r = gates.get(current).onEnter;
            if (r != null) r.run();
        }
    }

    public int phase() { return current; }
}
