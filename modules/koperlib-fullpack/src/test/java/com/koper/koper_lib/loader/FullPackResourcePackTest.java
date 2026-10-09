package com.koper.koper_lib.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FullPackResourcePackTest {

    @TempDir Path pack;

    @Test
    void listingOnlyTheMatchingBranchFindsWhatAFullWalkFinds() throws IOException {
        for (String file : List.of("sounds.json", "sounds/a.ogg", "tags/block/a.json", "tags/blocks/b.json",
                "tags/item/c.json", "function/x.mcfunction", "function/sub/y.mcfunction", "recipe/r.json",
                "textures/block/stone.png", "textures/item/gem.png", "textures/entity/mob.png", "models/item/m.json")) {
            Path path = pack.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "x");
        }
        Path textures = pack.resolve("textures");
        for (String prefix : List.of("", "sounds", "so", "tags/block", "tags/blocks", "tags/", "tags/item",
                "function", "function/sub", "recipe", "textures", "textures/", "textures/block", "textures/blo",
                "tex", "models", "missing", "missing/deeper")) {
            assertEquals(fullWalk(pack, pack, prefix), relative(pack, FullPackResourcePack.matchingFiles(pack, pack, prefix)),
                "prefix '" + prefix + "'");
            assertEquals(fullWalk(pack, textures, prefix), relative(pack, FullPackResourcePack.matchingFiles(pack, textures, prefix)),
                "prefix '" + prefix + "' inside textures/");
        }
    }

    private static Set<String> fullWalk(Path base, Path scan, String prefix) throws IOException {
        try (Stream<Path> walk = Files.walk(scan)) {
            return walk.filter(Files::isRegularFile).map(p -> base.relativize(p).toString().replace('\\', '/'))
                .filter(r -> r.startsWith(prefix)).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> relative(Path base, List<Path> files) {
        return files.stream().map(p -> base.relativize(p).toString().replace('\\', '/'))
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
