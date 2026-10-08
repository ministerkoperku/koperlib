package com.koper.koper_lib.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.FullPackLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

// bedrock's spawn_rules for addon mobs. java's spawner only knows mobs registered with biome spawn
// lists, which converted mobs are not, so without this an addon's creatures never show up on their own.
// once a second, a few tries per player 24..64 blocks out; every condition of every rule is checked
// right there: surface / underground / underwater, brightness, height, difficulty, block below,
// distance, biome filter, density limit. a passing rule is picked by weight, spawns its herd (with
// permute_type) and fires spawn_event. addon mobs despawn the java way when nobody is near
public final class BedrockRozsiewacz {

    private record Regula(String entity, String group, JsonObject c) {}

    private static final List<Regula> REGULY = new ArrayList<>();
    // per population group: how many addon mobs of that group may be around one player
    private static final Map<String, Integer> LIMIT = Map.of("monster", 25, "animal", 12, "water_animal", 6, "ambient", 8);
    private static final Map<String, List<String>> GRUPY = new HashMap<>();
    private static int zegar;

    private BedrockRozsiewacz() {}

    public static void reload() {
        REGULY.clear();
        GRUPY.clear();
        for (File pack : FullPackLoader.getEnabledPackDirs()) {
            Path dir = pack.toPath().resolve("bedrock_bp/spawn_rules");
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                    JsonObject sr = BedrockTlumacz.obj(BedrockTlumacz.czytajObj(f), "minecraft:spawn_rules");
                    JsonObject desc = BedrockTlumacz.obj(sr, "description");
                    String id = BedrockTlumacz.str(desc, "identifier", null);
                    // java spawns its own mobs; a pack retuning vanilla spawn rules is left to java
                    if (id == null || id.startsWith("minecraft:") || !sr.has("conditions")) continue;
                    String group = BedrockTlumacz.str(desc, "population_control", "animal");
                    for (JsonElement c : sr.getAsJsonArray("conditions")) if (c.isJsonObject()) REGULY.add(new Regula(id, group, c.getAsJsonObject()));
                    GRUPY.computeIfAbsent(group, g -> new ArrayList<>()).add(id);
                }
            } catch (Exception e) {
                KoperLib.LOGGER.warn("[Bedrock] spawn rules in {} unreadable: {}", pack.getName(), e.toString());
            }
        }
        if (!REGULY.isEmpty()) KoperLib.LOGGER.info("[Bedrock] {} natural spawn rules live", REGULY.size());
    }

    public static void tick(MinecraftServer server) {
        if (REGULY.isEmpty() || ++zegar % 20 != 0) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator()) continue;
            ServerLevel l = p.level();
            if (!mobyWlaczone(l)) continue;
            for (int i = 0; i < 3; i++) proba(l, p, l.getRandom());
        }
    }

    // the mob spawning gamerule, found by name: the gamerule api moved around in 26.x and this has to
    // compile whichever way it went. no such rule found = spawning on
    private static java.lang.reflect.Field REGULA_SPAWN;
    private static boolean szukane;

    private static boolean mobyWlaczone(ServerLevel l) {
        try {
            Object rules = l.getGameRules();
            if (!szukane) {
                szukane = true;
                for (String n : List.of("SPAWN_MOBS", "RULE_DOMOBSPAWNING", "DO_MOB_SPAWNING")) {
                    try { REGULA_SPAWN = net.minecraft.world.level.gamerules.GameRules.class.getField(n); break; } catch (NoSuchFieldException ignored) {}
                }
            }
            if (REGULA_SPAWN == null) return true;
            Object key = REGULA_SPAWN.get(null);
            for (var m : rules.getClass().getMethods()) {
                if ((m.getName().equals("get") || m.getName().equals("getBoolean")) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(key)) {
                    Object v = m.invoke(rules, key);
                    if (v instanceof Boolean b) return b;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return true;
    }

    private static void proba(ServerLevel l, ServerPlayer p, RandomSource r) {
        double ang = r.nextDouble() * Math.PI * 2, dist = 24 + r.nextDouble() * 40;
        int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist), z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
        if (!l.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) return;
        int top = l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // one surface spot and one cave spot per try, each checked against every rule
        BlockPos surface = new BlockPos(x, top, z);
        BlockPos cave = jaskinia(l, x, z, top, r);
        List<Regula> ok = new ArrayList<>();
        List<BlockPos> gdzie = new ArrayList<>();
        int total = 0;
        for (Regula reg : REGULY) {
            BlockPos at = null;
            JsonObject c = reg.c();
            boolean water = c.has("minecraft:spawns_underwater");
            if (water) at = woda(l, x, z, top);
            else {
                if (c.has("minecraft:spawns_on_surface") && pasuje(l, p, surface, reg, true)) at = surface;
                else if (c.has("minecraft:spawns_underground") && cave != null && pasuje(l, p, cave, reg, false)) at = cave;
            }
            if (water && (at == null || !pasuje(l, p, at, reg, top <= at.getY()))) at = null;
            if (at == null) continue;
            ok.add(reg);
            gdzie.add(at);
            total += waga(c);
        }
        if (ok.isEmpty() || total <= 0) return;
        int pick = r.nextInt(total);
        for (int i = 0; i < ok.size(); i++) {
            pick -= waga(ok.get(i).c());
            if (pick < 0) { stado(l, gdzie.get(i), ok.get(i), r); return; }
        }
    }

    private static int waga(JsonObject c) {
        JsonObject w = BedrockTlumacz.obj(c, "minecraft:weight");
        return w != null && w.has("default") ? Math.max(0, w.get("default").getAsInt()) : 100;
    }

    // air with a sturdy floor somewhere below the surface
    private static BlockPos jaskinia(ServerLevel l, int x, int z, int top, RandomSource r) {
        int lo = l.getMinY() + 1;
        if (top - 6 <= lo) return null;
        int y = lo + r.nextInt(top - 6 - lo);
        for (int k = 0; k < 24 && y > lo; k++, y--) {
            BlockPos at = new BlockPos(x, y, z);
            if (stoi(l, at)) return at;
        }
        return null;
    }

    private static BlockPos woda(ServerLevel l, int x, int z, int top) {
        for (int y = top - 1; y > top - 24 && y > l.getMinY(); y--) {
            BlockPos at = new BlockPos(x, y, z);
            if (l.getFluidState(at).is(FluidTags.WATER) && l.getFluidState(at.above()).is(FluidTags.WATER)) return at;
        }
        return null;
    }

    private static boolean stoi(ServerLevel l, BlockPos at) {
        BlockState below = l.getBlockState(at.below());
        return l.getBlockState(at).isAir() && l.getBlockState(at.above()).isAir() && l.getFluidState(at).isEmpty()
            && below.isFaceSturdy(l, at.below(), Direction.UP);
    }

    private static boolean pasuje(ServerLevel l, ServerPlayer p, BlockPos at, Regula reg, boolean onSurface) {
        JsonObject c = reg.c();
        boolean water = c.has("minecraft:spawns_underwater");
        if (!water && !stoi(l, at)) return false;
        JsonObject h = BedrockTlumacz.obj(c, "minecraft:height_filter");
        if (h != null && (at.getY() < num(h, "min", -9999) || at.getY() > num(h, "max", 9999))) return false;
        JsonObject br = BedrockTlumacz.obj(c, "minecraft:brightness_filter");
        if (br != null) {
            int light = l.getMaxLocalRawBrightness(at);
            if (br.has("adjust_for_weather") && br.get("adjust_for_weather").getAsBoolean() && l.isRaining() && onSurface) light = Math.max(0, light - 4);
            if (light < num(br, "min", 0) || light > num(br, "max", 15)) return false;
        }
        JsonObject df = BedrockTlumacz.obj(c, "minecraft:difficulty_filter");
        if (df != null) {
            int d = l.getDifficulty().getId();
            if (d < trudnosc(BedrockTlumacz.str(df, "min", "peaceful")) || d > trudnosc(BedrockTlumacz.str(df, "max", "hard"))) return false;
        } else if (reg.group().equals("monster") && l.getDifficulty() == Difficulty.PEACEFUL) return false;
        JsonObject dist = BedrockTlumacz.obj(c, "minecraft:distance_filter");
        if (dist != null) {
            double d = Math.sqrt(p.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
            if (d < num(dist, "min", 0) || d > num(dist, "max", 9999)) return false;
        }
        String under = BuiltInRegistries.BLOCK.getKey(l.getBlockState(at.below()).getBlock()).toString();
        JsonElement on = c.get("minecraft:spawns_on_block_filter");
        if (on != null && !bloki(on, under)) return false;
        JsonElement not = c.get("minecraft:spawns_on_block_prevented_filter");
        if (not != null && bloki(not, under)) return false;
        JsonElement bf = c.get("minecraft:biome_filter");
        if (bf != null && !biom(bf, l.getBiome(at))) return false;
        return tlok(l, p, reg);
    }

    private static int trudnosc(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) { case "peaceful" -> 0; case "easy" -> 1; case "normal" -> 2; default -> 3; };
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    // "minecraft:grass" or ["a", {"name": "b"}]; bedrock's grass is java's grass_block
    private static boolean bloki(JsonElement f, String under) {
        List<String> names = new ArrayList<>();
        if (f.isJsonPrimitive()) names.add(f.getAsString());
        else if (f.isJsonArray()) for (JsonElement x : f.getAsJsonArray()) names.add(x.isJsonObject() ? BedrockTlumacz.str(x.getAsJsonObject(), "name", "") : x.getAsString());
        else if (f.isJsonObject()) names.add(BedrockTlumacz.str(f.getAsJsonObject(), "name", ""));
        for (String n : names) {
            String id = n.contains(":") ? n : "minecraft:" + n;
            if (id.equals("minecraft:grass")) id = "minecraft:grass_block";
            if (id.equals(under)) return true;
        }
        return false;
    }

    // biome_filter: all_of / any_of / none_of trees of has_biome_tag / is_biome tests
    private static boolean biom(JsonElement f, Holder<Biome> b) {
        if (f == null || f.isJsonNull()) return true;
        if (f.isJsonArray()) {
            for (JsonElement x : f.getAsJsonArray()) if (!biom(x, b)) return false;
            return true;
        }
        if (!f.isJsonObject()) return true;
        JsonObject o = f.getAsJsonObject();
        if (o.has("all_of")) return biom(o.get("all_of"), b);
        if (o.has("any_of")) {
            JsonElement any = o.get("any_of");
            if (!any.isJsonArray()) return biom(any, b);
            for (JsonElement x : any.getAsJsonArray()) if (biom(x, b)) return true;
            return false;
        }
        if (o.has("none_of")) {
            JsonElement none = o.get("none_of");
            if (!none.isJsonArray()) return !biom(none, b);
            for (JsonElement x : none.getAsJsonArray()) if (biom(x, b)) return false;
            return true;
        }
        String test = BedrockTlumacz.str(o, "test", "has_biome_tag");
        String value = BedrockTlumacz.str(o, "value", "");
        boolean got = switch (test) {
            case "has_biome_tag", "is_biome" -> BedrockFiltr.biomeTag(b, value);
            default -> true;
        };
        String op = BedrockTlumacz.str(o, "operator", "==");
        return op.equals("!=") || op.equals("not") ? !got : got;
    }

    // population group cap and the rule's density_limit, counted around the player
    private static boolean tlok(ServerLevel l, ServerPlayer p, Regula reg) {
        AABB box = p.getBoundingBox().inflate(128, 64, 128);
        List<String> group = GRUPY.getOrDefault(reg.group(), List.of(reg.entity()));
        int same = 0, inGroup = 0;
        for (Mob m : l.getEntitiesOfClass(Mob.class, box, m -> true)) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
            if (id.equals(reg.entity())) same++;
            if (group.contains(id)) inGroup++;
        }
        if (inGroup >= LIMIT.getOrDefault(reg.group(), 8)) return false;
        JsonObject dl = BedrockTlumacz.obj(reg.c(), "minecraft:density_limit");
        if (dl != null) {
            int lim = (int) Math.max(num(dl, "surface", 9999), num(dl, "underground", 9999));
            if (dl.has("surface") && dl.has("underground")) lim = (int) Math.min(num(dl, "surface", 9999), num(dl, "underground", 9999));
            if (same >= lim) return false;
        }
        return true;
    }

    private static void stado(ServerLevel l, BlockPos at, Regula reg, RandomSource r) {
        JsonObject herd = BedrockTlumacz.obj(reg.c(), "minecraft:herd");
        int min = herd != null ? (int) num(herd, "min_size", 1) : 1, max = herd != null ? (int) num(herd, "max_size", min) : 1;
        int n = min + (max > min ? r.nextInt(max - min + 1) : 0);
        JsonObject se = BedrockTlumacz.obj(reg.c(), "minecraft:spawn_event");
        String event = se != null ? BedrockTlumacz.str(se, "event", "minecraft:entity_spawned") : null;
        for (int i = 0; i < Math.max(1, n); i++) {
            BlockPos pos = i == 0 ? at : at.offset(r.nextInt(5) - 2, 0, r.nextInt(5) - 2);
            if (i > 0 && !reg.c().has("minecraft:spawns_underwater") && !stoi(l, pos)) continue;
            String id = odmiana(reg.c(), reg.entity(), r);
            Identifier rl = Identifier.tryParse(com.koper.koper_lib.api.core.BedrockNazwy.doJavy(id));
            EntityType<?> type = rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
            if (type == null) continue;
            Entity e = type.create(l, EntitySpawnReason.NATURAL);
            if (e == null) continue;
            e.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, r.nextFloat() * 360f, 0f);
            if (!l.noCollision(e)) { e.discard(); continue; }
            boolean ours = BedrockZachowanie.ma(e);
            if (ours) BedrockZachowanie.overrideSpawnEvent(e, event);
            if (e instanceof Mob m) m.finalizeSpawn(l, l.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
            l.addFreshEntity(e);
            if (!ours && event != null && !event.equals("minecraft:entity_spawned")) BedrockZachowanie.event(e, event, null);
        }
    }

    // minecraft:permute_type: [{"weight": 91}, {"weight": 9, "entity_type": "x"}], no type = the rule's own
    private static String odmiana(JsonObject c, String own, RandomSource r) {
        JsonElement pt = c.get("minecraft:permute_type");
        if (pt == null || !pt.isJsonArray()) return own;
        JsonArray a = pt.getAsJsonArray();
        int total = 0;
        for (JsonElement x : a) total += x.isJsonObject() && x.getAsJsonObject().has("weight") ? x.getAsJsonObject().get("weight").getAsInt() : 0;
        if (total <= 0) return own;
        int pick = r.nextInt(total);
        for (JsonElement x : a) {
            JsonObject o = x.getAsJsonObject();
            pick -= o.has("weight") ? o.get("weight").getAsInt() : 0;
            if (pick < 0) return BedrockTlumacz.str(o, "entity_type", own);
        }
        return own;
    }
}
