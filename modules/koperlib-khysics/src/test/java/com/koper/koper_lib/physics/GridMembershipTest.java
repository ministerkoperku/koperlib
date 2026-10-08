package com.koper.koper_lib.physics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

// the ghost block: mining next to a kontraption left the block gone on the server and still
// there on the client. gridAtLogical answers for the six NEIGHBOURS too, so a plain world
// block touching a machine got routed into the kontra grid and the client was never told.
class GridMembershipTest {

    @Test
    void thereIsAnExactLookupAndTheRoutingMixinsUseIt() throws Exception {
        Method exact = KoperPhys.class.getMethod("gridAtLogicalExact",
            net.minecraft.server.level.ServerLevel.class, net.minecraft.core.BlockPos.class);
        assertNotNull(exact, "gridAtLogicalExact is gone");

        // whatever decides "this block belongs to a kontra" and cancels vanilla bookkeeping has
        // to use the exact one, or the ghost comes straight back
        for (String cls : new String[]{
                "com/koper/koper_lib/mixin/KontraLevelQueryMixin.class",
                "com/koper/koper_lib/mixin/KontraBeSendDataMixin.class"}) {
            byte[] bytes;
            try (var in = GridMembershipTest.class.getClassLoader().getResourceAsStream(cls)) {
                assertNotNull(in, "cannot read " + cls);
                bytes = in.readAllBytes();
            }
            String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            // can only prove the exact lookup is referenced: the constant pool holds each name
            // once, so call sites are not countable from here. KontraLevelQueryMixin legitimately
            // still uses the neighbour version for isLoaded, which only answers "that area exists"
            assertTrue(pool.contains("gridAtLogicalExact"),
                cls + " should look up the exact cell for membership, or ghost blocks are back");
        }
    }
}
