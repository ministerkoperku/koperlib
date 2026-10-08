package com.koper.koper_lib.kfx.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KfxControllerBookTest {
    @Test
    void tickReturnsCommittedImpactsAndDropsStoppedControllers() {
        UUID owner = UUID.fromString("11111111-1111-1111-1111-111111111111");
        KfxController controller = KfxController.projectile(22, owner, Vec3.ZERO, new Vec3(4, 0, 0))
            .response(KfxController.Response.STOP).build();
        KfxControllerBook book = new KfxControllerBook();
        book.add(controller);
        KfxSensor wall = (from, to, radius, ignored) -> KfxSensor.Contact.block(
            new BlockPos(2, 0, 0), new Vec3(2, 0, 0), new Vec3(-1, 0, 0));

        KfxControllerBook.Frame frame = book.tick(ignored -> wall);

        assertEquals(1, frame.impacts().size());
        assertEquals(1, frame.impacts().getFirst().sequence());
        assertEquals(KfxController.State.STOPPED, frame.motions().getFirst().state());
        assertTrue(book.isEmpty());
    }

    @Test
    void bookRejectsControllersPastTheHardPerformanceCap() {
        UUID owner = UUID.fromString("11111111-1111-1111-1111-111111111111");
        KfxControllerBook book = new KfxControllerBook();
        for (int i = 1; i <= KfxControllerBook.MAX_CONTROLLERS; i++) {
            book.add(KfxController.projectile(i, owner, Vec3.ZERO, Vec3.ZERO).build());
        }

        assertThrows(IllegalStateException.class, () -> book.add(
            KfxController.projectile(KfxControllerBook.MAX_CONTROLLERS + 1L, owner, Vec3.ZERO, Vec3.ZERO).build()));
    }
}
