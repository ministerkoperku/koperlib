package com.koper.koper_lib.kfx;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxMissingPolicy;
import com.koper.koper_lib.kfx.graph.KfxSocket;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class KfxScriptBridgeTest {
    @Test
    void parsesWorldVectorShorthandUsedByLuaPlay() {
        assertEquals(
            new KfxAnchor.World(new Vec3(1, 2, 3), new Vec3(0, 1, 0), KfxMissingPolicy.FREEZE),
            KfxScriptBridge.parseAnchor("[1,2,3]")
        );
    }

    @Test
    void parsesFollowingEntityAnchorWithSocketOffsetAndMissingPolicy() {
        assertEquals(
            new KfxAnchor.Entity(17, KfxSocket.MAIN_HAND, new Vec3(0.1, 0.2, 0.3), KfxMissingPolicy.DETACH),
            KfxScriptBridge.parseAnchor("""
                {"type":"entity","entity":17,"socket":"main_hand",
                 "offset":[0.1,0.2,0.3],"missing":"detach"}
                """)
        );
    }
}
