package com.koper.koper_lib.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// bedrock command syntax -> java's. everything a script runs, every line of a converted function,
// queue_command and bp animation commands come through here. no minecraft classes, so it runs in plain
// tests; the running addon's namespace comes in through the two suppliers BedrockKomendy sets
public final class BedrockSkladnia {

    // namespace of the addon whose script is running now (function names), and its sound namespace
    static volatile Supplier<String> addonNs = () -> BedrockSkladnia.PACK_NAMESPACE.get();
    static volatile Supplier<String> soundNs = () -> null;
    // commands of a behavior pack entity (queue_command, bp animations) run with no script on the stack: its pack's ns
    static final ThreadLocal<String> PACK_NAMESPACE = new ThreadLocal<>();
    // somewhere loud to say what got lost in translation, BedrockKomendy plugs the logger in
    static volatile java.util.function.Consumer<String> warn = s -> {};
    // does java have this sound event ("minecraft:block.wool.place")? the registry answers at runtime, tests say no
    static volatile java.util.function.Predicate<String> javaDzwiek = id -> false;

    private BedrockSkladnia() {}


    private static final Pattern QUOTED_STATE = Pattern.compile("\"([a-z0-9_:]+)\"\\s*[=:]\\s*(\"[^\"]*\"|[^,\\]]+)");

    // for .mcfunction files at convert time, there is no running addon to borrow a namespace from
    static String przetlumaczDla(String ns, String raw) {
        // the pack's namespace for everything inside: functions, loot tables and its own sounds. sounds went to
        // minecraft: before, rlcraft's tutorial voice lines never played
        String was = PACK_NAMESPACE.get();
        PACK_NAMESPACE.set(ns);
        String out;
        try { out = przetlumacz(raw); } finally { if (was == null) PACK_NAMESPACE.remove(); else PACK_NAMESPACE.set(was); }
        if (out.startsWith("function ") && out.startsWith("function minecraft:") && !raw.contains("minecraft:"))
            out = "function " + ns + ":" + out.substring("function minecraft:".length());
        return out;
    }

    public static String przetlumacz(String raw) {
        if (raw == null) return "";
        String cmd = raw.strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        cmd = selectors(cmd);
        cmd = blockStates(cmd);
        List<String> w = words(cmd);
        if (w.isEmpty()) return cmd;
        // bedrock takes "~~1~" and "~-2~~-2" glued together, java wants three words
        if (splitGluedCoords(w)) cmd = String.join(" ", w);
        String head = w.get(0).toLowerCase(Locale.ROOT);
        try {
            switch (head) {
                case "effect": {
                    if (w.size() >= 3 && !w.get(1).equals("give") && !w.get(1).equals("clear")) {
                        if (w.get(2).equals("clear")) return "effect clear " + w.get(1) + (w.size() >= 4 ? " " + ns(w.get(3)) : "");
                        // a duration of 0 takes the effect off on bedrock, java won't take a 0
                        if (w.size() >= 4 && w.get(3).equals("0")) return "effect clear " + w.get(1) + " " + ns(w.get(2));
                        List<String> out = new ArrayList<>(List.of("effect", "give", w.get(1), ns(w.get(2))));
                        out.addAll(w.subList(3, w.size()));
                        return String.join(" ", out);
                    }
                    break;
                }
                case "give": {
                    // give <who> <item> [amount] [data] [components]: java has no data values, the data picks the item
                    // instead (dye 4 is lapis) and bedrock's own names become java's (muttonRaw, record_cat)
                    if (w.size() < 3) break;
                    boolean dane = w.size() >= 5 && w.get(4).matches("\\d+");
                    String item = com.koper.koper_lib.api.core.BedrockNazwy.przedmiotDoJavy(w.get(2) + (dane ? ":" + w.get(4) : ""));
                    List<String> out = new ArrayList<>(List.of("give", w.get(1), item));
                    if (w.size() >= 4) out.add(w.get(3));
                    return String.join(" ", out);
                }
                case "clear": {
                    // clear [who] [item] [data] [maxCount]
                    if (w.size() < 3) break;
                    boolean dane = w.size() >= 4 && w.get(3).matches("-?\\d+");
                    int d = dane ? Integer.parseInt(w.get(3)) : -1;
                    String item = com.koper.koper_lib.api.core.BedrockNazwy.przedmiotDoJavy(w.get(2) + (d >= 0 ? ":" + d : ""));
                    List<String> out = new ArrayList<>(List.of("clear", w.get(1), item));
                    if (w.size() >= 5) out.add(w.get(4));
                    return String.join(" ", out);
                }
                case "gamemode": {
                    if (w.size() >= 2) {
                        String gm = switch (w.get(1).toLowerCase(Locale.ROOT)) {
                            case "0", "s" -> "survival";
                            case "1", "c" -> "creative";
                            case "2", "a" -> "adventure";
                            case "6", "spectator" -> "spectator";
                            case "d", "5", "default" -> "survival";
                            default -> w.get(1);
                        };
                        return "gamemode " + gm + (w.size() > 2 ? " " + String.join(" ", w.subList(2, w.size())) : "");
                    }
                    break;
                }
                case "titleraw": {
                    // titleraw <who> <title|subtitle|actionbar|...> {"rawtext": [...]}
                    if (w.size() >= 4) {
                        String json = String.join(" ", w.subList(3, w.size()));
                        return "title " + w.get(1) + " " + w.get(2) + " " + rawtext(json);
                    }
                    return "title" + cmd.substring(head.length());
                }
                case "tellraw": {
                    if (w.size() >= 3) return "tellraw " + w.get(1) + " " + rawtext(String.join(" ", w.subList(2, w.size())));
                    break;
                }
                case "scoreboard": {
                    // players random <who> <obj> <min> <max>, players test <who> <obj> <min> [max]: bedrock only
                    if (w.size() >= 7 && w.get(1).equals("players") && w.get(2).equals("random"))
                        return "execute store result score " + w.get(3) + " " + w.get(4) + " run random value " + w.get(5) + ".." + w.get(6);
                    if (w.size() >= 6 && w.get(1).equals("players") && w.get(2).equals("test")) {
                        String lo = w.get(5).equals("*") ? "" : w.get(5), hi = w.size() > 6 && !w.get(6).equals("*") ? w.get(6) : "";
                        return "execute if score " + w.get(3) + " " + w.get(4) + " matches " + lo + ".." + hi;
                    }
                    // setdisplay sidebar <obj> ascending|descending: java has no sort order there
                    if (w.size() == 6 && w.get(1).equals("objectives") && w.get(2).equals("setdisplay") && (w.get(5).equals("ascending") || w.get(5).equals("descending")))
                        return String.join(" ", w.subList(0, 5));
                    break;
                }
                case "testforblock": {
                    if (w.size() >= 5) return "execute if block " + w.get(1) + " " + w.get(2) + " " + w.get(3) + " " + ns(w.get(4));
                    break;
                }
                case "testforblocks": {
                    if (w.size() >= 10) return "execute if blocks " + String.join(" ", w.subList(1, 10)) + " " + (w.size() > 10 && w.get(10).equals("masked") ? "masked" : "all");
                    break;
                }
                case "tp", "teleport": {
                    // bedrock's trailing checkForBlocks
                    String last = w.get(w.size() - 1);
                    if (w.size() >= 3 && (last.equals("true") || last.equals("false"))) return String.join(" ", w.subList(0, w.size() - 1));
                    break;
                }
                case "setblock": {
                    // setblock x y z block [data value] [mode]
                    if (w.size() >= 6 && w.get(5).matches("-?\\d+")) {
                        List<String> out = new ArrayList<>(w.subList(0, 5));
                        out.addAll(w.subList(6, w.size()));
                        return String.join(" ", out);
                    }
                    break;
                }
                case "fill": {
                    // fill x1 y1 z1 x2 y2 z2 block [data] [mode] [replace block] [data]
                    if (w.size() >= 9) {
                        List<String> out = new ArrayList<>();
                        for (int i = 0; i < w.size(); i++) if (!((i == 8 || i == 11) && w.get(i).matches("-?\\d+"))) out.add(w.get(i));
                        return String.join(" ", out);
                    }
                    break;
                }
                case "particle": {
                    // bedrock effects (packs' own and bedrock's names) go to kodel's particle engine
                    if (w.size() >= 2) return "bparticle " + String.join(" ", w.subList(1, w.size()));
                    break;
                }
                case "ride": {
                    if (w.size() >= 4 && w.get(2).equals("start_riding")) return "ride " + w.get(1) + " mount " + w.get(3);
                    if (w.size() >= 3 && w.get(2).equals("stop_riding")) return "ride " + w.get(1) + " dismount";
                    break;
                }
                case "damage": {
                    // damage <who> <amount> [cause] [entity <damager>]
                    if (w.size() >= 4) {
                        String out = "damage " + w.get(1) + " " + w.get(2) + " " + przyczyna(w.get(3));
                        if (w.size() >= 6 && w.get(4).equals("entity")) out += " by " + w.get(5);
                        return out;
                    }
                    break;
                }
                case "loot": {
                    // loot <target...> loot "animals/deer" [tool]: a path under the pack's loot_tables, java wants ns:path
                    for (int i = 2; i + 1 < w.size(); i++) {
                        if (!w.get(i).equals("loot")) continue;
                        String t = w.get(i + 1).replace("\"", "");
                        if (t.startsWith("loot_tables/")) t = t.substring("loot_tables/".length());
                        if (t.endsWith(".json")) t = t.substring(0, t.length() - 5);
                        if (!t.contains(":")) {
                            String ans = addonNs.get();
                            t = (ans != null ? ans : "minecraft") + ":" + t.toLowerCase(Locale.ROOT);
                        }
                        // java's loot source takes no tool after the table
                        List<String> out = new ArrayList<>(w.subList(0, i + 1));
                        out.add(t);
                        return String.join(" ", out);
                    }
                    break;
                }
                case "title": {
                    // title <who> title|subtitle|actionbar <plain text>: bedrock takes the rest of the line as the text,
                    // java wants a component. json text and titleraw-style input stay as they are
                    if (w.size() >= 4 && (w.get(2).equals("title") || w.get(2).equals("subtitle") || w.get(2).equals("actionbar"))) {
                        String rest = String.join(" ", w.subList(3, w.size()));
                        if (rest.startsWith("{") || rest.startsWith("[") || rest.startsWith("\"")) break;
                        return "title " + w.get(1) + " " + w.get(2) + " \"" + rest.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    }
                    break;
                }
                case "gamerule": {
                    if (w.size() < 2) break;
                    String j = BedrockGameRules.javaName(w.get(1));
                    // doFireTick is a radius now: true = java's default 128, false = 0
                    if (j.equals("fire_spread_radius_around_player") && w.size() >= 3 && w.get(2).matches("true|false"))
                        return "gamerule " + j + " " + (w.get(2).equals("true") ? "128" : "0");
                    return "gamerule " + j + (w.size() >= 3 ? " " + w.get(2) : "");
                }
                case "tag": {
                    // java's tag names stop at ':' and the like, bedrock's don't (hfrlc:spin_attack_cooldown)
                    if (w.size() >= 4 && (w.get(2).equals("add") || w.get(2).equals("remove")) && !w.get(3).matches("[A-Za-z0-9_.+-]+"))
                        return "btag " + w.get(1) + " " + w.get(2) + " " + String.join(" ", w.subList(3, w.size()));
                    break;
                }
                case "testfor":
                    return w.size() >= 2 ? "execute if entity " + w.get(1) : cmd;
                case "stopsound": {
                    // bedrock: stopsound <who> [sound]. java wants a source in between
                    if (w.size() == 3 && !isSource(w.get(2))) return "stopsound " + w.get(1) + " * " + sound(w.get(2));
                    break;
                }
                case "playsound": {
                    if (w.size() >= 2 && !isSource(w.size() > 2 ? w.get(2) : "")) {
                        List<String> out = new ArrayList<>(List.of("playsound", sound(w.get(1)), "master", w.size() > 2 ? w.get(2) : "@s"));
                        if (w.size() > 3) out.addAll(w.subList(3, w.size()));
                        return String.join(" ", out);
                    }
                    break;
                }
                case "replaceitem": {
                    // replaceitem entity <who> slot.x <n> <item> [amount]
                    if (w.size() >= 6 && w.get(1).equals("entity")) {
                        String slot = javaSlot(w.get(3), w.get(4));
                        String out = "item replace entity " + w.get(2) + " " + slot + " with " + w.get(5);
                        return w.size() > 6 ? out + " " + w.get(6) : out;
                    }
                    if (w.size() >= 8 && w.get(1).equals("block")) {
                        return "item replace block " + w.get(2) + " " + w.get(3) + " " + w.get(4) + " container." + w.get(6) + " with " + w.get(7)
                            + (w.size() > 8 ? " " + w.get(8) : "");
                    }
                    break;
                }
                case "xp": {
                    if (w.size() >= 2 && !w.get(1).equals("add") && !w.get(1).equals("set") && !w.get(1).equals("query")) {
                        String amt = w.get(1);
                        boolean levels = amt.toUpperCase(Locale.ROOT).endsWith("L");
                        if (levels) amt = amt.substring(0, amt.length() - 1);
                        return "xp add " + (w.size() > 2 ? w.get(2) : "@s") + " " + amt + (levels ? " levels" : " points");
                    }
                    break;
                }
                case "summon": {
                    // bedrock: summon <type> [pos] [yRot xRot] [spawnEvent] [name]. a spawn event needs our bsummon
                    if (w.size() >= 2) w.set(1, typ(w.get(1)));
                    if (w.size() >= 6 && !w.get(5).startsWith("{")) {
                        int ev = 5;
                        if (w.size() >= 8 && pozycja(w.get(5)) && pozycja(w.get(6))) ev = 7;
                        if (ev < w.size() && w.get(ev).contains(":")) {
                            String out = "bsummon " + w.get(1) + " " + w.get(2) + " " + w.get(3) + " " + w.get(4) + " " + w.get(ev);
                            return ev + 1 < w.size() ? out + " " + String.join(" ", w.subList(ev + 1, w.size())) : out;
                        }
                    }
                    if (w.size() >= 6 && !w.get(5).startsWith("{")) return String.join(" ", w.subList(0, 5));
                    if (w.size() >= 2) return String.join(" ", w);
                    break;
                }
                case "difficulty": {
                    if (w.size() == 2) {
                        String dd = switch (w.get(1).toLowerCase(Locale.ROOT)) {
                            case "p", "0" -> "peaceful"; case "e", "1" -> "easy";
                            case "n", "2" -> "normal"; case "h", "3" -> "hard";
                            default -> w.get(1);
                        };
                        return "difficulty " + dd;
                    }
                    break;
                }
                case "function": {
                    if (w.size() >= 2 && !w.get(1).contains(":")) {
                        String ans = addonNs.get();
                        return "function " + (ans != null ? ans : "minecraft") + ":" + w.get(1).toLowerCase(Locale.ROOT);
                    }
                    break;
                }
                case "execute": {
                    // old style: execute <who> <pos> <command>
                    if (w.size() >= 6 && w.get(1).startsWith("@")) {
                        String rest = String.join(" ", w.subList(5, w.size()));
                        // execute <who> <pos> detect <pos> <block> <data> <command>
                        if (rest.startsWith("detect ")) {
                            List<String> d = w.subList(5, w.size());
                            if (d.size() < 7) break;
                            String block = blockType(d.get(4));
                            return "execute as " + w.get(1) + " at @s positioned " + w.get(2) + " " + w.get(3) + " " + w.get(4)
                                + " if block " + d.get(1) + " " + d.get(2) + " " + d.get(3) + " " + block
                                + " run " + przetlumacz(String.join(" ", d.subList(6, d.size())));
                        }
                        return "execute as " + w.get(1) + " at @s positioned " + w.get(2) + " " + w.get(3) + " " + w.get(4) + " run " + przetlumacz(rest);
                    }
                    int run = w.indexOf("run");
                    if (run > 0 && run < w.size() - 1) {
                        return String.join(" ", w.subList(0, run + 1)) + " " + przetlumacz(String.join(" ", w.subList(run + 1, w.size())));
                    }
                    break;
                }
                default: break;
            }
        } catch (RuntimeException ignored) {
            // half parsed garbage in, the untouched command goes out and java complains about it itself
        }
        return cmd;
    }

    // bedrock's name for a mob -> java's, written the way it came (with or without minecraft:)
    static String typ(String id) {
        String j = com.koper.koper_lib.api.core.BedrockNazwy.doJavy(id);
        return !id.contains(":") && j.startsWith("minecraft:") ? j.substring(10) : j;
    }

    private static boolean pozycja(String s) {
        return s.startsWith("~") || s.startsWith("^") || s.matches("-?\\d+(\\.\\d+)?");
    }

    // bedrock damage causes -> java damage types
    static String przyczyna(String cause) {
        return switch (cause.toLowerCase(Locale.ROOT)) {
            case "entity_attack" -> "minecraft:mob_attack";
            case "fire" -> "minecraft:in_fire";
            case "fire_tick" -> "minecraft:on_fire";
            case "drowning" -> "minecraft:drown";
            case "void" -> "minecraft:out_of_world";
            case "lightning" -> "minecraft:lightning_bolt";
            case "suffocation" -> "minecraft:in_wall";
            case "projectile" -> "minecraft:arrow";
            case "freezing" -> "minecraft:freeze";
            case "block_explosion", "entity_explosion" -> "minecraft:explosion";
            case "anvil" -> "minecraft:falling_anvil";
            case "contact" -> "minecraft:cactus";
            case "fly_into_wall" -> "minecraft:fly_into_wall";
            case "falling_block" -> "minecraft:falling_block";
            case "sonic_boom" -> "minecraft:sonic_boom";
            case "none", "override" -> "minecraft:generic";
            default -> cause.contains(":") ? cause : "minecraft:" + cause;
        };
    }

    // {"rawtext": [{"text"}, {"translate", "with"}, {"selector"}, {"score"}]} -> a java text component.
    // bedrock's with may itself be {"rawtext": [...]}. not rawtext at all: left as it came
    static String rawtext(String json) {
        try {
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(json);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("rawtext")) return json;
            return skladnik(e).toString();
        } catch (RuntimeException notJson) {
            return json;
        }
    }

    private static com.google.gson.JsonElement skladnik(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return new com.google.gson.JsonPrimitive("");
        if (e.isJsonPrimitive()) return e;
        if (e.isJsonArray()) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            a.add("");
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) a.add(skladnik(x));
            return a;
        }
        com.google.gson.JsonObject o = e.getAsJsonObject();
        if (o.has("rawtext")) return skladnik(o.get("rawtext"));
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        if (o.has("text")) out.add("text", o.get("text"));
        if (o.has("selector")) out.add("selector", o.get("selector"));
        if (o.has("score")) out.add("score", o.get("score"));
        if (o.has("translate")) {
            out.add("translate", o.get("translate"));
            com.google.gson.JsonElement with = o.get("with");
            if (with != null) {
                com.google.gson.JsonArray wa = new com.google.gson.JsonArray();
                if (with.isJsonArray()) for (com.google.gson.JsonElement x : with.getAsJsonArray()) wa.add(x.isJsonPrimitive() ? x : skladnik(x));
                else if (with.isJsonObject() && with.getAsJsonObject().has("rawtext"))
                    for (com.google.gson.JsonElement x : with.getAsJsonObject().getAsJsonArray("rawtext")) wa.add(skladnik(x));
                out.add("with", wa);
            }
        }
        if (out.isEmpty()) out.addProperty("text", "");
        return out;
    }

    private static String ns(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static boolean isSource(String s) {
        return switch (s) {
            case "master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice", "ui" -> true;
            default -> false;
        };
    }

    // bedrock's vanilla names first (random.explode is entity.generic.explode), then a name java has as is
    // (block.wool.place), anything else is the pack's own sound
    static String sound(String id) {
        String bare = id.startsWith("minecraft:") ? id.substring(10) : id;
        String j = com.koper.koper_lib.api.core.BedrockNazwy.dzwiekDoJavy(bare);
        if (j != null) return "minecraft:" + j;
        if (id.startsWith("minecraft:") || javaDzwiek.test("minecraft:" + bare)) return "minecraft:" + bare;
        String ns = PACK_NAMESPACE.get() != null ? PACK_NAMESPACE.get() : soundNs.get();
        if (ns == null) return id.contains(":") ? id : "minecraft:" + id;
        // a bedrock sound name is just a string, ':' included (villager news calls them oreville_vn:acuqxm).
        // the converter files it under the pack with every odd character made '_', so the same here
        return ns + ":" + soundKey(id);
    }

    // what BedrockTlumacz names a pack sound event
    public static String soundKey(String bedrockName) {
        return bedrockName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    private static String javaSlot(String bedrock, String n) {
        return switch (bedrock) {
            case "slot.weapon.mainhand" -> "weapon.mainhand";
            case "slot.weapon.offhand" -> "weapon.offhand";
            case "slot.armor.head" -> "armor.head";
            case "slot.armor.chest" -> "armor.chest";
            case "slot.armor.legs" -> "armor.legs";
            case "slot.armor.feet" -> "armor.feet";
            case "slot.hotbar" -> "hotbar." + n;
            case "slot.inventory" -> "inventory." + n;
            case "slot.enderchest" -> "enderchest." + n;
            default -> bedrock.replace("slot.", "");
        };
    }

    // bedrock selector args -> java ones. r/rm/c/m/l/lm/rx/ry, the rest is the same or dropped
    private static String selectors(String cmd) {
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("@[aeprsn]\\[([^\\]]*)]").matcher(cmd);
        int last = 0;
        while (m.find()) {
            out.append(cmd, last, m.start(1));
            out.append(selectorArgs(m.group(1)));
            last = m.end(1);
        }
        out.append(cmd.substring(last));
        return out.toString().replace("@initiator", "@s");
    }

    private static String selectorArgs(String args) {
        // min/max pairs: bedrock splits a range over two keys, java writes it as one "min..max"
        Map<String, String[]> ranges = new java.util.LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : (args + ",").toCharArray()) {
            if (ch == '{') depth++;
            if (ch == '}') depth--;
            if (ch != ',' || depth != 0) { cur.append(ch); continue; }
            String part = cur.toString().strip();
            cur.setLength(0);
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) { parts.add(part); continue; }
            String k = part.substring(0, eq).strip();
            String v = part.substring(eq + 1).strip();
            switch (k) {
                case "r" -> ranges.computeIfAbsent("distance", x -> new String[2])[1] = v;
                case "rm" -> ranges.computeIfAbsent("distance", x -> new String[2])[0] = v;
                case "l" -> ranges.computeIfAbsent("level", x -> new String[2])[1] = v;
                case "lm" -> ranges.computeIfAbsent("level", x -> new String[2])[0] = v;
                case "rx" -> ranges.computeIfAbsent("x_rotation", x -> new String[2])[1] = v;
                case "rxm" -> ranges.computeIfAbsent("x_rotation", x -> new String[2])[0] = v;
                case "ry" -> ranges.computeIfAbsent("y_rotation", x -> new String[2])[1] = v;
                case "rym" -> ranges.computeIfAbsent("y_rotation", x -> new String[2])[0] = v;
                case "c" -> {
                    int n = Integer.parseInt(v);
                    parts.add("limit=" + Math.abs(n));
                    parts.add("sort=" + (n < 0 ? "furthest" : "nearest"));
                }
                case "m" -> {
                    boolean not = v.startsWith("!");
                    String g = switch (not ? v.substring(1) : v) {
                        case "0", "s", "survival" -> "survival";
                        case "1", "c", "creative" -> "creative";
                        case "2", "a", "adventure" -> "adventure";
                        case "spectator" -> "spectator";
                        default -> null;
                    };
                    if (g != null) parts.add("gamemode=" + (not ? "!" : "") + g);
                }
                // family and has_property java parses itself since BedrockSelectorMixin
                case "hasitem", "haspermission" -> warn.accept("selector option " + k + " has no java twin yet, dropped, the selector matches more than the pack meant: " + part);
                // type=villager_v2 is type=minecraft:villager here, a leading ! stays in front
                case "type" -> {
                    boolean not = v.startsWith("!");
                    String t = not ? v.substring(1).strip() : v;
                    parts.add("type=" + (not ? "!" : "") + (t.startsWith("#") ? t : typ(t)));
                }
                default -> parts.add(part);
            }
        }
        ranges.forEach((k, mm) -> parts.add(k + "=" + (mm[0] == null ? "" : mm[0]) + ".." + (mm[1] == null ? "" : mm[1])));
        return String.join(",", parts);
    }

    // bedrock setblock/fill states: ["facing_direction"=2,"open_bit"=true] -> [facing_direction=2,open_bit=true]
    private static String blockStates(String cmd) {
        if (!cmd.contains("\"=") && !cmd.contains("\":") && !cmd.contains("\" =")) return cmd;
        Matcher m = QUOTED_STATE.matcher(cmd);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = m.group(2).strip();
            if (v.startsWith("\"")) v = v.substring(1, v.length() - 1);
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1).replace("minecraft:", "") + "=" + v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern COORD = Pattern.compile("[~^][-+]?(?:\\d+\\.?\\d*|\\.\\d+)?");
    private static final Pattern GLUED = Pattern.compile("(?:[~^][-+]?(?:\\d+\\.?\\d*|\\.\\d+)?){2,3}");

    static boolean splitGluedCoords(List<String> w) {
        boolean changed = false;
        for (int i = 0; i < w.size(); i++) {
            if (!GLUED.matcher(w.get(i)).matches()) continue;
            Matcher m = COORD.matcher(w.get(i));
            List<String> parts = new ArrayList<>();
            while (m.find()) parts.add(m.group());
            w.remove(i);
            w.addAll(i, parts);
            i += parts.size() - 1;
            changed = true;
        }
        return changed;
    }

    private static String blockType(String b) {
        return b.contains(":") ? b : "minecraft:" + b;
    }

    private static List<String> words(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean quote = false;
        for (char ch : cmd.toCharArray()) {
            if (ch == '"') quote = !quote;
            if (!quote && (ch == '[' || ch == '{')) depth++;
            if (!quote && (ch == ']' || ch == '}')) depth--;
            if (ch == ' ' && depth == 0 && !quote) {
                if (!cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(ch);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}
