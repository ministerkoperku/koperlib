package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.data.KoperBlockData;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;

public final class KoperBlockStates {
    private KoperBlockStates() {}

    public static List<Property<?>> properties(KoperBlockData data) {
        List<Property<?>> out = new ArrayList<>();
        if (data == null) return out;
        for (String raw : data.stateProperties) {
            Property<?> p = property(raw);
            if (p != null && !out.contains(p)) out.add(p);
        }
        if (data.connectGroup != null && !data.connectGroup.isBlank()) {
            addIfMissing(out, BlockStateProperties.NORTH);
            addIfMissing(out, BlockStateProperties.EAST);
            addIfMissing(out, BlockStateProperties.SOUTH);
            addIfMissing(out, BlockStateProperties.WEST);
            if (!Boolean.FALSE.equals(data.connectVertical)) {
                addIfMissing(out, BlockStateProperties.UP);
                addIfMissing(out, BlockStateProperties.DOWN);
            }
        }
        return out;
    }

    private static Property<?> declare(String s) {
        String[] bits = s.split(":", 4);
        try {
            return switch (bits[0]) {
                case "bool" -> BooleanProperty.create(bits[1]);
                case "int" -> IntegerProperty.create(bits[1], Integer.parseInt(bits[2]), Integer.parseInt(bits[3]));
                default -> new KoperNapisowaWlasciwosc(bits[1], java.util.Arrays.asList(bits[2].split("\\|")));
            };
        } catch (RuntimeException bad) {
            com.koper.koper_lib.KoperLib.LOGGER.warn("[KoperBlockStates] bad state declaration '{}': {}", s, bad.getMessage());
            return null;
        }
    }

    private static void addIfMissing(List<Property<?>> out, Property<?> property) {
        if (!out.contains(property)) out.add(property);
    }

    public static void addToDefinition(KoperBlockData data, StateDefinition.Builder<Block, BlockState> builder) {
        for (Property<?> p : properties(data)) builder.add(p);
    }

    public static BlockState applyDefaults(KoperBlockData data, BlockState state) {
        if (data == null || state == null) return state;
        for (var p : properties(data)) {
            String raw = data.defaultStates.get(p.getName());
            if (raw == null && p == BlockStateProperties.HORIZONTAL_FACING) raw = data.defaultStates.get("horizontal_facing");
            if (raw == null) raw = defaultValue(p);
            state = set(state, p.getName(), raw);
        }
        return state;
    }

    public static BlockState set(BlockState state, String name, String rawValue) {
        Property<?> p = find(state, name);
        if (p == null || rawValue == null) return state;
        return setKnown(state, p, rawValue);
    }

    public static BlockState toggle(BlockState state, String name) {
        Property<?> p = find(state, name);
        if (p instanceof BooleanProperty bp) return state.setValue(bp, !state.getValue(bp));
        if (p instanceof IntegerProperty ip) {
            int current = state.getValue(ip);
            int min = ip.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = ip.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(current);
            return state.setValue(ip, current >= max ? min : current + 1);
        }
        return state;
    }

    public static String propertyNameFromAction(JsonObject json) {
        if (json.has("set_state") && json.get("set_state").isJsonObject()) {
            JsonObject o = json.getAsJsonObject("set_state");
            return o.has("property") ? o.get("property").getAsString() : o.has("name") ? o.get("name").getAsString() : "";
        }
        if (json.has("toggle_state")) return json.get("toggle_state").getAsString();
        return "";
    }

    public static String propertyValueFromAction(JsonObject json) {
        if (json.has("set_state") && json.get("set_state").isJsonObject()) {
            JsonObject o = json.getAsJsonObject("set_state");
            if (o.has("value")) return o.get("value").getAsString();
        }
        return "";
    }

    // "bool:name", "int:name:min:max", "enum:name:a|b|c" declare a state of any name, bedrock style
    private static final java.util.Map<String, Property<?>> DECLARED = new java.util.concurrent.ConcurrentHashMap<>();

    public static Property<?> property(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase();
        if (s.startsWith("bool:") || s.startsWith("int:") || s.startsWith("enum:")) {
            // same declaration = same property object, java compares states by identity of these
            return DECLARED.computeIfAbsent(s, KoperBlockStates::declare);
        }
        return switch (s) {
            case "half" -> BlockStateProperties.HALF;
            case "facing" -> BlockStateProperties.FACING;
            case "horizontal_facing", "horizontal", "facing_horizontal" -> BlockStateProperties.HORIZONTAL_FACING;
            case "lit" -> BlockStateProperties.LIT;
            case "powered" -> BlockStateProperties.POWERED;
            case "open" -> BlockStateProperties.OPEN;
            case "north" -> BlockStateProperties.NORTH;
            case "east" -> BlockStateProperties.EAST;
            case "south" -> BlockStateProperties.SOUTH;
            case "west" -> BlockStateProperties.WEST;
            case "up" -> BlockStateProperties.UP;
            case "down" -> BlockStateProperties.DOWN;
            case "age", "age_7" -> BlockStateProperties.AGE_7;
            case "age_1" -> BlockStateProperties.AGE_1;
            case "age_2" -> BlockStateProperties.AGE_2;
            case "age_3" -> BlockStateProperties.AGE_3;
            case "age_4" -> BlockStateProperties.AGE_4;
            case "age_5" -> BlockStateProperties.AGE_5;
            case "age_15" -> BlockStateProperties.AGE_15;
            case "age_25" -> BlockStateProperties.AGE_25;
            default -> null;
        };
    }

    private static Property<?> find(BlockState state, String name) {
        if (state == null || name == null) return null;
        String n = name.equals("horizontal_facing") ? "facing" : name;
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(n)) return p;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setKnown(BlockState state, Property property, String rawValue) {
        String raw = rawValue.toLowerCase();
        if (property instanceof BooleanProperty bp) return state.setValue(bp, Boolean.parseBoolean(raw));
        if (property instanceof IntegerProperty ip) {
            try {
                int v = Integer.parseInt(raw);
                if (ip.getPossibleValues().contains(v)) return state.setValue(ip, v);
            } catch (NumberFormatException ignored) {
            }
            return state;
        }
        if (property == BlockStateProperties.FACING || property == BlockStateProperties.HORIZONTAL_FACING) {
            Direction d = Direction.byName(raw);
            if (d != null && property.getPossibleValues().contains(d)) return state.setValue(property, d);
        }
        for (Object value : property.getPossibleValues()) {
            if (value.toString().equalsIgnoreCase(raw)) return setRaw(state, property, value);
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setRaw(BlockState state, Property property, Object value) {
        return state.setValue(property, (Comparable) value);
    }

    private static String defaultValue(Property<?> p) {
        if (p == BlockStateProperties.HORIZONTAL_FACING || p == BlockStateProperties.FACING) return "north";
        if (p instanceof BooleanProperty) return "false";
        if (p instanceof IntegerProperty ip) return String.valueOf(ip.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElse(0));
        if (p == BlockStateProperties.HALF) return "bottom";
        if (!p.getPossibleValues().isEmpty()) return p.getName(cast(p.getPossibleValues().get(0)));
        return "";
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> T cast(Object o) {
        return (T) o;
    }
}
