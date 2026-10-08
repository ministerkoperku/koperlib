package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.KoperLibDirectories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// titles a pack's json ui eats ("thirstbar20;", "hide_sphere;"): bedrock never shows them, its hud reads them.
// java has no json ui, so the hud mixin drops them instead of printing them huge in the middle of the screen
public final class BedrockHudCodes {

    private static volatile List<String> codes;

    private BedrockHudCodes() {}

    public static boolean swallowed(String text) {
        if (text == null || text.isEmpty()) return false;
        List<String> c = codes;
        if (c == null) c = load();
        // the ui compares a prefix ('%.12s'), the rest of the title is data riding along
        for (String code : c) if (text.startsWith(code)) return true;
        return false;
    }

    // after a reload the converted packs may have changed
    public static void forget() {
        codes = null;
    }

    private static synchronized List<String> load() {
        if (codes != null) return codes;
        List<String> out = new ArrayList<>();
        Path dir = KoperLibDirectories.FULLPACKS;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    JsonObject side = BedrockTlumacz.czytajObj(p.resolve("bedrock.koper.json"));
                    if (side == null || !side.has("hud_title_codes")) continue;
                    for (JsonElement e : side.getAsJsonArray("hud_title_codes")) out.add(e.getAsString());
                }
            } catch (Exception e) {
                KoperLib.LOGGER.error("[Bedrock] can't read hud title codes from {}, pack hud titles will show as text: {}", dir, e.toString());
            }
        }
        codes = List.copyOf(out);
        return codes;
    }
}
