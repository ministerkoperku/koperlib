package com.koper.koper_lib.kfx.runtime;

import com.koper.koper_lib.kfx.graph.KfxAnchor;
import com.koper.koper_lib.kfx.graph.KfxResolvedValue;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KfxHandleTest {
    @Test
    void playRequestOwnsAnImmutableParameterSnapshot() {
        Map<String, KfxResolvedValue> parameters = new HashMap<>();
        parameters.put("power", KfxResolvedValue.number(2));
        var request = new KfxPlayRequest("test:spell", parameters,
            KfxAnchor.world(Vec3.ZERO), KfxAnchor.world(new Vec3(1, 0, 0)), 123);

        parameters.clear();

        assertEquals(2.0, request.parameters().get("power").asNumber("power"));
        assertThrows(UnsupportedOperationException.class, () -> request.parameters().clear());
        assertThrows(IllegalArgumentException.class, () -> new KfxPlayRequest(" ", Map.of(),
            KfxAnchor.world(Vec3.ZERO), KfxAnchor.world(Vec3.ZERO), 1));
    }

    @Test
    void handleRoutesLifecycleCommandsToItsOwnId() {
        var commands = new RecordingCommands();
        var handle = new KfxHandle(72, commands);
        var start = KfxAnchor.world(new Vec3(1, 2, 3));
        var end = KfxAnchor.world(new Vec3(4, 5, 6));

        handle.reanchor(start, end);
        assertEquals("anchor:72", commands.last);
        handle.detach();
        assertEquals("detach:72", commands.last);
        handle.set("power", KfxResolvedValue.number(4));
        assertEquals("set:72:power=4.0", commands.last);
        handle.setAll(Map.of(
            "power", KfxResolvedValue.number(5),
            "size", KfxResolvedValue.number(2)));
        assertEquals("setAll:72:2", commands.last);
        assertThrows(NullPointerException.class, () -> handle.setAll(null));
        handle.stop();
        assertEquals("stop:72", commands.last);
    }

    private static final class RecordingCommands implements KfxHandle.Commands {
        String last;

        @Override public void stop(long id) { last = "stop:" + id; }
        @Override public void reanchor(long id, KfxAnchor start, KfxAnchor end) { last = "anchor:" + id; }
        @Override public void detach(long id) { last = "detach:" + id; }
        @Override public void set(long id, String name, KfxResolvedValue value) {
            last = "set:" + id + ":" + name + "=" + value.asNumber(name);
        }
        @Override public void setAll(long id, Map<String, KfxResolvedValue> values) {
            last = "setAll:" + id + ":" + values.size();
        }
    }
}
