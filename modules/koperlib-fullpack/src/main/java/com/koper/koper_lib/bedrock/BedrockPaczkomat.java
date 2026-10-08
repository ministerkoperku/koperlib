package com.koper.koper_lib.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.KoperLibDirectories;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// picks up .mcaddon / .mcpack / bedrock zips / loose bedrock folders from fullpacks/ and mods/,
// unpacks them, pairs behavior with resource packs and hands each pair to BedrockTlumacz.
// output is fullpacks/<name>_bedrock, a normal pack from then on
public final class BedrockPaczkomat {

    public static final String SUFFIX = "_bedrock";
    private static final String STAMP = ".bedrock_stamp";
    // bump when BedrockTlumacz output changes, every addon gets converted again on next start
    private static final int WERSJA = 15; // 6: 26.3 data formats (loot modifier/condition, cooking times). 7: structures copied. 8: data java refuses left out. 9: ~LINEBREAK~ in lang, packs nested in packs skipped. 10: no eggs for is_spawnable false. 11: hud title codes. 12: no .png.png textures. 13: function titles as hud codes. 14: stopsound, pack sounds in functions. 15: no client entity = invisible

    private BedrockPaczkomat() {}

    private record Kawalek(Path dir, String name, String uuid, boolean behavior, boolean resources, Set<String> deps, String source) {}

    // called by FullPackLoader before it lists anything, so the converted folders are there to be found
    public static void przerob() {
        // the game: ask the item registry. the 2 arg one below runs in tests, no registry there
        BedrockTlumacz.javaItem = id -> {
            var rl = net.minecraft.resources.Identifier.tryParse(id);
            return rl != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl);
        };
        przerob(KoperLibDirectories.FULLPACKS, List.of(KoperLibDirectories.FULLPACKS,
            FabricLoader.getInstance().getGameDir().resolve("mods")));
    }

    // fullpacks = where <name>_bedrock folders go, roots = where addons are looked for
    static void przerob(Path fullpacks, List<Path> roots) {
        Path srcCache = fullpacks.resolve(".cache").resolve("bedrock_src");
        BedrockTaczka.posprzataj(smietnik(fullpacks));
        List<Kawalek> found = new ArrayList<>();
        Map<String, String> stamps = new LinkedHashMap<>();
        try {
            Files.createDirectories(srcCache);
            for (Path root : roots) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> s = Files.list(root)) {
                    for (Path p : s.sorted().toList()) {
                        String n = p.getFileName().toString();
                        if (n.startsWith(".") || n.endsWith(SUFFIX)) continue;
                        if (Files.isDirectory(p)) {
                            if (!isBedrockDir(p)) continue;
                            String stamp = stampOfDir(p);
                            collect(p, n, found, stamp, srcCache);
                            stamps.put(n, stamp);
                        } else if (isBedrockArchive(p)) {
                            String base = n.substring(0, n.lastIndexOf('.'));
                            String stamp = Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis();
                            Path unpacked = srcCache.resolve(safe(base));
                            if (!stamp.equals(readStamp(unpacked))) {
                                wipe(unpacked, fullpacks);
                                rozpakuj(p, unpacked);
                                Files.writeString(unpacked.resolve(STAMP), stamp);
                            }
                            collect(unpacked, base, found, stamp, srcCache);
                            stamps.put(base, stamp);
                        }
                    }
                }
            }
        } catch (IOException e) {
            KoperLib.LOGGER.error("[Bedrock] scanning for addons blew up", e);
        }

        Set<String> alive = new HashSet<>();
        Set<Kawalek> usedRp = new HashSet<>();
        for (Kawalek bp : found) {
            if (!bp.behavior()) continue;
            Kawalek rp = null;
            for (Kawalek k : found) if (k.resources() && bp.deps().contains(k.uuid())) rp = k;
            // no uuid link but both came out of the same .mcaddon: that is a pair too
            if (rp == null) for (Kawalek k : found) if (k.resources() && k.source().equals(bp.source()) && !usedRp.contains(k)) rp = k;
            if (rp != null) usedRp.add(rp);
            alive.add(zrob(fullpacks, bp.name(), bp.dir(), rp == null ? null : rp.dir(), stamps.get(bp.source()) + "|" + (rp == null ? "" : stamps.get(rp.source()))));
        }
        for (Kawalek rp : found) {
            if (!rp.resources() || usedRp.contains(rp)) continue;
            alive.add(zrob(fullpacks, rp.name(), null, rp.dir(), stamps.get(rp.source())));
        }
        alive.remove(null);

        // converted folders whose addon is gone. only ours: they carry bedrock.koper.json
        try (Stream<Path> s = Files.list(fullpacks)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(SUFFIX) || alive.contains(n) || !Files.isRegularFile(p.resolve("bedrock.koper.json"))) continue;
                KoperLib.LOGGER.info("[Bedrock] addon for {} is gone, removing the converted pack", n);
                wipe(p, fullpacks);
            }
        } catch (IOException ignored) {}
    }

    // outside a real game (unit tests) the version isn't set yet, detect it from the jar
    private static String mcVersion() {
        try { return net.minecraft.SharedConstants.getCurrentVersion().id(); }
        catch (IllegalStateException notYet) {
            net.minecraft.SharedConstants.tryDetectVersion();
            return net.minecraft.SharedConstants.getCurrentVersion().id();
        }
    }

    private static String zrob(Path fullpacks, String name, Path bp, Path rp, String stamp) {
        String outName = safe(name) + SUFFIX;
        Path out = fullpacks.resolve(outName);
        // minecraft version in the stamp: a pack converted for one version is never reused by another
        String full = stamp + "|v" + WERSJA + "|mc=" + mcVersion()
            + "|kodel=" + com.koper.koper_lib.api.core.KodelBedrockBridge.installed();
        try {
            if (full.equals(readStamp(out)) && Files.isRegularFile(out.resolve("pack.kopermeta"))) return outName;
            wipe(out, fullpacks);
            BedrockTlumacz.translate(new BedrockTlumacz.Paczka(name, bp, rp), out);
            Files.writeString(out.resolve(STAMP), full);
            return outName;
        } catch (Exception e) {
            KoperLib.LOGGER.error("[Bedrock] converting {} failed, the addon is skipped", name, e);
            return null;
        }
    }

    // a folder is bedrock when it (or a folder right inside it) has a bedrock manifest and no koper meta
    public static boolean isBedrockDir(Path dir) {
        if (Files.exists(dir.resolve("pack.kopermeta")) || Files.exists(dir.resolve("fullpack.json"))) return false;
        if (isBedrockManifest(dir.resolve("manifest.json"))) return true;
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> Files.isDirectory(p) && isBedrockManifest(p.resolve("manifest.json")));
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isBedrockArchive(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        // .mctemplate / .mcworld: a world with its resource_packs/ and behavior_packs/ inside. the world
        // itself stays bedrock's, the packs come out like from an mcaddon (RLCraft ships like this)
        if (n.endsWith(".mcaddon") || n.endsWith(".mcpack") || n.endsWith(".mctemplate") || n.endsWith(".mcworld")) return true;
        if (!n.endsWith(".zip")) return false;
        try (ZipFile z = BedrockTaczka.otworz(file)) {
            if (z.getEntry("pack.kopermeta") != null || z.getEntry("fullpack.json") != null) return false;
            var it = z.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                String en = e.getName();
                if (en.endsWith(".mcpack")) return true;
                if (!en.equals("manifest.json") && !en.matches("[^/]+/manifest.json")) continue;
                try (InputStream in = z.getInputStream(e)) {
                    if (manifestLooksBedrock(new String(in.readAllBytes(), StandardCharsets.UTF_8))) return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    private static boolean isBedrockManifest(Path f) {
        if (!Files.isRegularFile(f)) return false;
        try {
            return manifestLooksBedrock(Files.readString(f));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean manifestLooksBedrock(String raw) {
        return raw.contains("\"header\"") && raw.contains("\"modules\"") && raw.contains("\"uuid\"");
    }

    // every manifest in the tree, nested .mcpack files unpacked next to themselves first
    private static void collect(Path root, String sourceName, List<Kawalek> into, String stamp, Path cache) throws IOException {
        List<Path> nested;
        try (Stream<Path> s = Files.walk(root, 3)) {
            nested = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mcpack") && Files.isRegularFile(p)).toList();
        }
        for (Path mc : nested) {
            Path dst = mc.resolveSibling(mc.getFileName().toString().replaceAll("(?i)\\.mcpack$", "") + "_mcpack");
            if (!Files.isDirectory(dst)) rozpakuj(mc, dst);
        }
        List<Path> manifests;
        try (Stream<Path> s = Files.walk(root, 4)) {
            // shallow first, the outer pack has to be known before anything nested in it
            manifests = s.filter(p -> p.getFileName().toString().equals("manifest.json"))
                .sorted(java.util.Comparator.comparingInt(Path::getNameCount).thenComparing(java.util.Comparator.naturalOrder())).toList();
        }
        List<Path> packDirs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path mf : manifests) {
            // a pack folder sitting inside another pack is just files the author forgot there, bedrock
            // only imports the outer one (mowzie's bp carries a whole other bp like that)
            Path in = null;
            for (Path p : packDirs) if (mf.getParent().startsWith(p)) { in = p; break; }
            if (in != null) {
                KoperLib.LOGGER.warn("[Bedrock] {}: {} is a pack inside the pack {}, bedrock does not load that, skipping it",
                    sourceName, root.relativize(mf.getParent()), root.relativize(in));
                continue;
            }
            JsonObject m = BedrockTlumacz.czytajObj(mf);
            JsonObject header = BedrockTlumacz.obj(m, "header");
            if (header == null || !m.has("modules")) continue;
            boolean bp = false, rp = false;
            for (JsonElement mod : m.getAsJsonArray("modules")) {
                String t = BedrockTlumacz.str(mod.getAsJsonObject(), "type", "");
                if (t.equals("data") || t.equals("script") || t.equals("javascript")) bp = true;
                if (t.equals("resources")) rp = true;
            }
            if (!bp && !rp) continue; // skin packs, world templates, nothing for us
            Set<String> deps = new HashSet<>();
            if (m.has("dependencies")) for (JsonElement d : m.getAsJsonArray("dependencies")) {
                String u = BedrockTlumacz.str(d.getAsJsonObject(), "uuid", null);
                if (u != null) deps.add(u.toLowerCase(Locale.ROOT));
            }
            Path dir = mf.getParent();
            packDirs.add(dir);
            String name = dir.equals(root) ? sourceName : sourceName + "_" + dir.getFileName();
            Path origin = dir;
            // subpacks and __brarchive get flattened into a plain folder first, once per change
            if (BedrockSkladacz.potrzebny(dir)) {
                String wish = subpackWish(sourceName, BedrockTlumacz.str(header, "uuid", ""), BedrockTlumacz.str(header, "name", ""));
                Path merged = cache.resolve("merged").resolve(safe(sourceName) + "__" + safe(root.relativize(dir).toString().replace('/', '_').replace('\\', '_')));
                String want = stamp + "|" + wish;
                if (!want.equals(readStamp(merged))) {
                    BedrockSkladacz.scal(dir, merged, wish);
                    Files.writeString(merged.resolve(STAMP), want);
                }
                dir = merged;
            }
            if (!origin.equals(dir)) KoperLib.LOGGER.debug("[Bedrock] {} flattened into {}", origin, dir);
            // an mcaddon with bp + rp inside: name the result after the addon, not "addon_BP"
            if (!dir.equals(root) && bp) name = sourceName;
            // two bps in one addon would write into the same folder and the second eats the first
            for (int n = 2; !names.add(name); n++) name = sourceName + "_" + n;
            into.add(new Kawalek(dir, name, BedrockTlumacz.str(header, "uuid", "").toLowerCase(Locale.ROOT), bp, rp && !bp, deps, sourceName));
        }
    }

    // koperlib/bedrock_subpacks.json: {"addon file name, pack name or uuid": "subpack folder or its display name"}
    private static String subpackWish(String source, String uuid, String packName) {
        JsonObject o;
        try {
            o = BedrockTlumacz.czytajObj(KoperLibDirectories.ROOT.resolve("bedrock_subpacks.json"));
        } catch (Throwable noGameDir) {
            return null;
        }
        if (o == null) return null;
        for (String k : new String[] {source, uuid, packName}) {
            if (k != null && o.has(k) && o.get(k).isJsonPrimitive()) return o.get(k).getAsString();
        }
        return null;
    }

    // zip slip checked inside, a pack from the internet does not get to write outside its own folder
    private static void rozpakuj(Path zip, Path target) throws IOException {
        BedrockTaczka.rozpakuj(zip, target);
    }

    private static String stampOfDir(Path dir) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                md.update(dir.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                md.update(Long.toString(Files.size(p)).getBytes(StandardCharsets.UTF_8));
                md.update(Long.toString(Files.getLastModifiedTime(p).toMillis()).getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(md.digest(), 0, 12);
    }

    private static String readStamp(Path dir) {
        try {
            Path f = dir.resolve(STAMP);
            return Files.isRegularFile(f) ? Files.readString(f).strip() : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String safe(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_").replaceAll("_+", "_");
        return s.isEmpty() ? "addon" : s;
    }

    // one rename, the actual deleting happens on a daemon thread. trash = fullpacks/.cache/trash
    private static void wipe(Path dir, Path fullpacks) throws IOException {
        BedrockTaczka.wywal(dir, smietnik(fullpacks));
    }

    static Path smietnik(Path fullpacks) {
        return fullpacks.resolve(".cache").resolve("trash");
    }
}
