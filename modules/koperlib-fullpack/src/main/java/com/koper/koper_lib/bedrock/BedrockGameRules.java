package com.koper.koper_lib.bedrock;

import com.koper.koper_lib.KoperLib;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// bedrock game rule names (doDayLightCycle) -> 26.x ones (advance_time). scripts' world.gameRules and the
// is_game_rule filter both come here. 26.x renamed half of them, a snake_case guess only gets the rest
final class BedrockGameRules {

    private static final Map<String, String> RENAMED = Map.ofEntries(
        Map.entry("commandblocksenabled", "command_blocks_work"),
        Map.entry("dodaylightcycle", "advance_time"),
        Map.entry("doweathercycle", "advance_weather"),
        Map.entry("doentitydrops", "entity_drops"),
        Map.entry("domobloot", "mob_drops"),
        Map.entry("dotiledrops", "block_drops"),
        Map.entry("domobspawning", "spawn_mobs"),
        Map.entry("doinsomnia", "spawn_phantoms"),
        Map.entry("doimmediaterespawn", "immediate_respawn"),
        Map.entry("dolimitedcrafting", "limited_crafting"),
        Map.entry("naturalregeneration", "natural_health_regeneration"),
        Map.entry("functioncommandlimit", "max_command_sequence_length"),
        Map.entry("maxcommandchainlength", "max_command_sequence_length"),
        Map.entry("spawnradius", "respawn_radius"));

    // bedrock only, the client draws them (coordinates, days played...): nothing on java to read or set
    private static final Set<String> BEDROCK_ONLY = Set.of("showcoordinates", "showdaysplayed", "showtags",
        "showbordereffect", "showrecipemessages", "recipesunlock", "respawnblocksexplode", "locatorbar");

    private static final Set<String> SAID = new HashSet<>();

    private BedrockGameRules() {}

    private static String key(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    // no minecraft classes in here, BedrockSkladnia's /gamerule uses it too
    static String javaName(String name) {
        String k = key(name);
        if (k.equals("dofiretick")) return "fire_spread_radius_around_player";
        String j = RENAMED.get(k);
        if (j != null) return j;
        return name.contains("_") ? name.toLowerCase(Locale.ROOT) : name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    static GameRule<?> java(String name) {
        String j = javaName(name);
        Identifier id = Identifier.tryParse(j.contains(":") ? j : "minecraft:" + j);
        return id == null ? null : BuiltInRegistries.GAME_RULE.getValue(id);
    }

    // Boolean or Integer, null when java has no such rule (said once, loud)
    static Object read(MinecraftServer s, String name) {
        GameRule<?> r = java(name);
        if (r == null) {
            complain(name);
            return null;
        }
        Object v = s.getGameRules().get(r);
        // doFireTick is a radius on 26.x: 0 = no fire spread
        if (r == GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER && key(name).equals("dofiretick")) return ((Integer) v) != 0;
        return v;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean set(MinecraftServer s, String name, String value) {
        GameRule r = java(name);
        if (r == null) {
            complain(name);
            return false;
        }
        if (r == GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER && key(name).equals("dofiretick")) {
            s.getGameRules().set(r, Boolean.parseBoolean(value) ? GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.defaultValue() : 0, s);
            return true;
        }
        var parsed = r.deserialize(value);
        if (parsed.result().isEmpty()) {
            KoperLib.LOGGER.error("[Bedrock] game rule {} can't be set to '{}'", name, value);
            return false;
        }
        s.getGameRules().set(r, parsed.result().get(), s);
        return true;
    }

    private static void complain(String name) {
        if (!SAID.add(key(name))) return;
        if (BEDROCK_ONLY.contains(key(name)))
            KoperLib.LOGGER.warn("[Bedrock] game rule {} only exists on bedrock (its client draws it), java has nothing to read or set", name);
        else KoperLib.LOGGER.error("[Bedrock] game rule {} has no java twin, a pack reading it gets nothing", name);
    }
}
