package com.koper.koper_lib.physics;

import java.util.function.Supplier;

// Level calls made by a BE/block tick need to land back in its kontra, not in the abandoned world cells.
public final class KontraGridContext {
    private static final ThreadLocal<KontraGrid> ACTIVE = new ThreadLocal<>();

    private KontraGridContext() {}

    public static KontraGrid active() { return ACTIVE.get(); }

    public static void run(KontraGrid grid, Runnable action) {
        KontraGrid old = ACTIVE.get();
        ACTIVE.set(grid);
        try { action.run(); }
        finally {
            if (old == null) ACTIVE.remove();
            else ACTIVE.set(old);
        }
    }

    public static <T> T call(KontraGrid grid, Supplier<T> action) {
        KontraGrid old = ACTIVE.get();
        ACTIVE.set(grid);
        try { return action.get(); }
        finally {
            if (old == null) ACTIVE.remove();
            else ACTIVE.set(old);
        }
    }

    public static <T> T outside(Supplier<T> action) {
        KontraGrid old = ACTIVE.get();
        ACTIVE.remove();
        try { return action.get(); }
        finally { if (old != null) ACTIVE.set(old); }
    }
}
