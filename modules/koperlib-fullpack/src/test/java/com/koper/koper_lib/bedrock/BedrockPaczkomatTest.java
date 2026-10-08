package com.koper.koper_lib.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

// the shapes addons actually get shared in: one .mcaddon with two folders, one .mcaddon with two
// .mcpack files inside, two loose .mcpack files that only find each other by uuid
class BedrockPaczkomatTest {

    static Path sample() throws Exception {
        return Path.of(BedrockPaczkomatTest.class.getResource("/bedrock/koper_bedrock_test").toURI());
    }

    static void zipDir(Path dir, OutputStream out, String prefix) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(out); Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                z.putNextEntry(new ZipEntry(prefix + dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
    }

    static byte[] zipOf(Path dir) throws IOException {
        var buf = new java.io.ByteArrayOutputStream();
        zipDir(dir, buf, "");
        return buf.toByteArray();
    }

    static void assertConverted(Path fullpacks, String name) {
        Path out = fullpacks.resolve(name + BedrockPaczkomat.SUFFIX);
        assertTrue(Files.isRegularFile(out.resolve("pack.kopermeta")), "no pack for " + name);
        assertTrue(Files.isRegularFile(out.resolve("items/zap_wand.json")), "bp half missing");
        assertTrue(Files.isRegularFile(out.resolve("assets/kbt/textures/item/zap_wand.png")), "rp half missing, pairing broke");
        assertTrue(Files.isRegularFile(out.resolve("bedrock_scripts/main.js")));
    }

    @Test
    void mcaddonWithFolders(@TempDir Path fullpacks) throws Exception {
        try (OutputStream o = Files.newOutputStream(fullpacks.resolve("Koper Test.mcaddon"))) {
            try (ZipOutputStream z = new ZipOutputStream(o); Stream<Path> s = Files.walk(sample())) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    z.putNextEntry(new ZipEntry(sample().relativize(p).toString().replace('\\', '/')));
                    Files.copy(p, z);
                    z.closeEntry();
                }
            }
        }
        BedrockPaczkomat.przerob(fullpacks, List.of(fullpacks));
        assertConverted(fullpacks, "koper_test");
        // second run with nothing changed must not convert again
        long before = Files.getLastModifiedTime(fullpacks.resolve("koper_test_bedrock/pack.kopermeta")).toMillis();
        Thread.sleep(20);
        BedrockPaczkomat.przerob(fullpacks, List.of(fullpacks));
        assertEquals(before, Files.getLastModifiedTime(fullpacks.resolve("koper_test_bedrock/pack.kopermeta")).toMillis());
        // addon gone -> converted pack gone
        Files.delete(fullpacks.resolve("Koper Test.mcaddon"));
        BedrockPaczkomat.przerob(fullpacks, List.of(fullpacks));
        assertFalse(Files.exists(fullpacks.resolve("koper_test_bedrock")));
    }

    @Test
    void mcaddonWithNestedMcpacks(@TempDir Path fullpacks) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(fullpacks.resolve("nested.mcaddon")))) {
            z.putNextEntry(new ZipEntry("kbt_BP.mcpack"));
            z.write(zipOf(sample().resolve("BP")));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("kbt_RP.mcpack"));
            z.write(zipOf(sample().resolve("RP")));
            z.closeEntry();
        }
        BedrockPaczkomat.przerob(fullpacks, List.of(fullpacks));
        assertConverted(fullpacks, "nested");
    }

    @Test
    void looseMcpacksFindEachOtherByUuid(@TempDir Path fullpacks, @TempDir Path mods) throws Exception {
        Files.write(fullpacks.resolve("bugs_behavior.mcpack"), zipOf(sample().resolve("BP")));
        Files.write(mods.resolve("bugs_resources.mcpack"), zipOf(sample().resolve("RP")));
        BedrockPaczkomat.przerob(fullpacks, List.of(fullpacks, mods));
        assertConverted(fullpacks, "bugs_behavior");
        // the rp was used by the bp, it does not also become its own pack
        assertFalse(Files.exists(fullpacks.resolve("bugs_resources_bedrock")));
    }

    @Test
    void zipSlipIsIgnored(@TempDir Path fullpacks) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(fullpacks.resolve("evil.mcpack")))) {
            z.putNextEntry(new ZipEntry("../../outside.txt"));
            z.write("nope".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(Files.readAllBytes(sample().resolve("RP/manifest.json")));
            z.closeEntry();
        }
        BedrockPaczkomat.przerob(fullpacks, List.of(fullpacks));
        assertFalse(Files.exists(fullpacks.getParent().resolve("outside.txt")));
        assertFalse(Files.exists(fullpacks.resolve(".cache/outside.txt")));
    }

    @Test
    void detection() throws Exception {
        assertTrue(BedrockPaczkomat.isBedrockDir(sample()));
        assertTrue(BedrockPaczkomat.isBedrockDir(sample().resolve("BP")));
    }
}
