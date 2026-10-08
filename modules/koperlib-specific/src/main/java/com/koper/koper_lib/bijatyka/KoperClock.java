package com.koper.koper_lib.bijatyka;

import java.util.ArrayList;

// time-gated phases. like KoperPhaser but counts ticks not hp. add marks at tick offsets.
// good for bosses whose phases flip on a timer, not on damage
public final class KoperClock {

    private record Mark(int tick, Runnable onEnter) {}

    private final ArrayList<Mark> marks = new ArrayList<>();
    private int t = 0;
    private int current = -1;

    public KoperClock at(int tick, Runnable onEnter) {
        marks.add(new Mark(tick, onEnter));
        marks.sort((a, b) -> Integer.compare(a.tick, b.tick));
        return this;
    }

    public void tick() {
        t++;
        while (current + 1 < marks.size() && t >= marks.get(current + 1).tick) {
            current++;
            Runnable r = marks.get(current).onEnter;
            if (r != null) r.run();
        }
    }

    public int phase()  { return current; }
    public int ticks()  { return t; }
    public void reset() { t = 0; current = -1; }
}
