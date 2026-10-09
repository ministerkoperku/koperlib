package com.koper.koper_lib.physics;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KontraTransferTest {
    @Test void failedOccupantMoveRollsBackBeforeSourceDestruction() {
        var cargo=new ArrayList<>(List.of("source:inventory"));
        boolean committed=KontraTransferTransaction.run(Set.of(7L), new KontraTransferTransaction.Stage() {
            public boolean prepare() {cargo.add("staged:inventory");return true;}
            public boolean moveOccupants() {cargo.add("moved:first");return false;}
            public void rollback() {cargo.remove("staged:inventory");cargo.remove("moved:first");}
            public void commit() {cargo.remove("source:inventory");}
        });
        assertFalse(committed); assertEquals(List.of("source:inventory"),cargo);
    }
    @Test void overlappingAssemblyTransferIsRejectedWhileLocked() {
        boolean[] nested={true};
        assertTrue(KontraTransferTransaction.run(Set.of(7L,8L),new KontraTransferTransaction.Stage() {
            public boolean prepare() {
                nested[0]=KontraTransferTransaction.run(Set.of(8L),this);return true;
            }
            public boolean moveOccupants() {return true;}
            public void rollback() {fail("successful transfer rolled back");}
            public void commit() {}
        }));
        assertFalse(nested[0]);
    }
    @Test void failedReturnKeepsAssemblyLockedUntilRecoveryFinishes() {
        var stage=new KontraTransferTransaction.Stage() {
            public boolean prepare() {return true;}
            public boolean moveOccupants() {return false;}
            public void rollback() {}
            public void commit() {fail("failed movement committed");}
            public boolean recoveryPending() {return true;}
        };
        assertFalse(KontraTransferTransaction.run(Set.of(99L),stage));
        try {assertFalse(KontraTransferTransaction.run(Set.of(99L),stage));}
        finally {KontraTransferTransaction.release(Set.of(99L));}
        assertTrue(KontraTransferTransaction.run(Set.of(99L),new KontraTransferTransaction.Stage() {
            public boolean prepare() {return true;}
            public boolean moveOccupants() {return true;}
            public void rollback() {fail("recovered transaction rolled back");}
            public void commit() {}
        }));
    }
}
