package com.koper.koper_lib.bedrock;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

// point KOPER_BEDROCK_PACK at any .mcpack/.mcaddon you own and this converts it for real.
// nothing from the pack ends up in the repo, the output goes to a temp dir
class BedrockPrawdziwyTest {

    @Test
    void convertWhateverIsPointedAt() throws Exception {
        String env = System.getenv("KOPER_BEDROCK_PACK");
        Assumptions.assumeTrue(env != null && Files.isRegularFile(Path.of(env)));
        Path fullpacks = Files.createTempDirectory("koper_bedrock_real");
        Path in = Files.createTempDirectory("koper_bedrock_in");
        Files.copy(Path.of(env), in.resolve(Path.of(env).getFileName()));
        long start = System.currentTimeMillis();
        BedrockPaczkomat.przerob(fullpacks, List.of(in));
        List<Path> made;
        try (Stream<Path> s = Files.list(fullpacks)) {
            made = s.filter(p -> p.getFileName().toString().endsWith(BedrockPaczkomat.SUFFIX)).toList();
        }
        assertTrue(!made.isEmpty(), "nothing converted");
        for (Path p : made) {
            long files;
            try (Stream<Path> s = Files.walk(p)) { files = s.filter(Files::isRegularFile).count(); }
            System.out.println("converted " + p.getFileName() + ": " + files + " files in " + (System.currentTimeMillis() - start) + " ms");
            System.out.println(Files.readString(p.resolve("bedrock.koper.json")).lines().limit(40).reduce("", (a, b) -> a + "\n" + b));
        }
        System.out.println("OUT " + fullpacks);
    }
}
