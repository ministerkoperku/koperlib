package com.koper.koper_lib.loader;

import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.nio.file.Path;

public class KoperLibDirectories {
    public static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("koperlib");
    public static final Path JSONS = ROOT.resolve("jsons");
    public static final Path FULLPACKS = ROOT.resolve("fullpacks");

    public static void init() {
        createDir(ROOT.toFile());
        createDir(JSONS.toFile());
        createDir(FULLPACKS.toFile());
    }

    private static void createDir(File file) {
        if (!file.exists()) {
            file.mkdirs();
        }
    }
}
