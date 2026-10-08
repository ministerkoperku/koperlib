package com.koper.koper_lib.kfx.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class KfxDocumentationExamplesTest {
    @Test
    void everyJsonFenceIsValidAndEveryV2GraphUsesTheRealParser() throws IOException {
        Path guide = repositoryRoot().resolve("docs/KFX_PARTICLE_ENGINE.md");
        List<String> examples = fences(Files.readString(guide), "json");
        assertFalse(examples.isEmpty(), "KFX guide needs JSON examples");

        for (int index = 0; index < examples.size(); index++) {
            String source = guide + " json fence " + (index + 1);
            String example = examples.get(index);
            JsonElement json = assertDoesNotThrow(() -> JsonParser.parseString(example), source);
            if (json.isJsonObject() && json.getAsJsonObject().has("version")) {
                assertDoesNotThrow(() -> KfxGraphJson.parse(example, source), source);
            }
        }
    }

    private static List<String> fences(String markdown, String language) {
        List<String> result = new ArrayList<>();
        String marker = "```" + language;
        int cursor = 0;
        while ((cursor = markdown.indexOf(marker, cursor)) >= 0) {
            int bodyStart = markdown.indexOf('\n', cursor + marker.length());
            int bodyEnd = bodyStart < 0 ? -1 : markdown.indexOf("```", bodyStart + 1);
            if (bodyStart < 0 || bodyEnd < 0) break;
            result.add(markdown.substring(bodyStart + 1, bodyEnd).strip());
            cursor = bodyEnd + 3;
        }
        return result;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("docs/KFX_PARTICLE_ENGINE.md"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("could not find KoperLib repository root");
    }
}
