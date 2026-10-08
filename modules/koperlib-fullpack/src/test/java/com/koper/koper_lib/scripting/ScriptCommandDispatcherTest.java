package com.koper.koper_lib.scripting;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptCommandDispatcherTest {
    @Test
    void parsesLengthCheckedKfxFieldsWithoutDelimiterAmbiguity() {
        String data = "{\"text\":\"a:b zażółć\"}";
        String encoded = command("kfx_handle_signal", "41", "charge:phase", data);

        assertEquals(
            new ScriptCommand.KfxHandleSignal(41L, "charge:phase", data),
            ScriptCommandDispatcher.parse(encoded)
        );
    }

    @Test
    void rejectsAFieldWhoseDeclaredUtf8LengthDoesNotMatch() {
        String encoded = command("kfx_graph_declare", "{\"id\":\"test:x\"}");
        encoded = encoded.replaceFirst(":15:", ":14:");

        assertNull(ScriptCommandDispatcher.parse(encoded));
    }

    @Test
    void staticGraphDeclarationsCanRunBeforeAWorldExists() {
        assertFalse(ScriptCommandDispatcher.requiresServer(
            new ScriptCommand.KfxGraphDeclare("{\"version\":2}")));
        assertTrue(ScriptCommandDispatcher.requiresServer(new ScriptCommand.KfxStop(7L)));
    }

    private static String command(String opcode, String... fields) {
        StringBuilder out = new StringBuilder(opcode);
        for (String field : fields) {
            byte[] utf8 = field.getBytes(StandardCharsets.UTF_8);
            out.append(':').append(utf8.length).append(':')
                .append(Base64.getEncoder().encodeToString(utf8));
        }
        return out.toString();
    }
}
