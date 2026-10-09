package com.koper.koper_lib.physics;

import java.util.*;

public final class KontraTransferTransaction {
    private KontraTransferTransaction() {}
    public interface Stage {
        boolean prepare(); boolean moveOccupants(); void rollback(); void commit();
        default boolean recoveryPending() { return false; }
    }
    private static final Set<Long> LOCKED=new HashSet<>();
    static void release(Set<Long> bodies) { synchronized(LOCKED) { LOCKED.removeAll(bodies); } }
    public static boolean run(Set<Long> bodies,Stage stage) {
        synchronized(LOCKED) {
            if (bodies.isEmpty() || bodies.stream().anyMatch(LOCKED::contains)) return false;
            LOCKED.addAll(bodies);
        }
        boolean committed=false;
        try {
            if (!stage.prepare() || !stage.moveOccupants()) return false;
            committed=true;
            stage.commit();
            return true;
        } finally {
            try { if (!committed) stage.rollback(); }
            finally { synchronized(LOCKED) { if(!stage.recoveryPending()) LOCKED.removeAll(bodies); } }
        }
    }
}
