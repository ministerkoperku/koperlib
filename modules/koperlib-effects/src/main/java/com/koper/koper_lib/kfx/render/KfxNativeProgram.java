package com.koper.koper_lib.kfx.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koper.koper_lib.kfx.graph.KfxCompiledGraph;
import com.koper.koper_lib.kfx.KfxInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Versioned byte IR shared exactly with engine/koperlib-kender/src/kfx.rs. */
public final class KfxNativeProgram {
    public static final int MAJOR = 2;
    public static final int HEADER_BYTES = 64;
    public static final int NODE_BYTES = 32;
    public static final int HARD_MAX_NODES = 1024;
    public static final int HARD_MAX_CONSTANTS = 32_768;
    public static final int HARD_MAX_PARTICLES = 4096;

    public enum Overflow { SCALE_RATE, DROP_OLDEST, SKIP_DECORATIVE, REJECT }

    public record Node(int stableId, int opcode, boolean decorative, int cost, float[] properties) {
        public Node {
            if (stableId == 0) throw new IllegalArgumentException("KFX native node id cannot be zero");
            if (opcode < 0 || opcode > 0xffff) throw new IllegalArgumentException("KFX native opcode is out of range");
            if (cost < 0) throw new IllegalArgumentException("KFX native node cost cannot be negative");
            properties = properties == null ? new float[0] : properties.clone();
            for (float value : properties) if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("KFX native constants must be finite");
            }
        }

        @Override public float[] properties() { return properties.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Node node && stableId == node.stableId && opcode == node.opcode
                && decorative == node.decorative && cost == node.cost && Arrays.equals(properties, node.properties);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(stableId, opcode, decorative, cost) + Arrays.hashCode(properties);
        }
    }

    private final long graphHash;
    private final List<Node> nodes;
    private final int maxParticles;
    private final int maxLifetime;
    private final Overflow overflow;

    public KfxNativeProgram(long graphHash, List<Node> nodes, int maxParticles, int maxLifetime, Overflow overflow) {
        if (graphHash == 0L) throw new IllegalArgumentException("KFX native graph hash cannot be zero");
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (this.nodes.size() > HARD_MAX_NODES) throw new IllegalArgumentException("KFX native node hard cap exceeded");
        int constants = this.nodes.stream().mapToInt(node -> node.properties.length).sum();
        if (constants > HARD_MAX_CONSTANTS) throw new IllegalArgumentException("KFX native constant hard cap exceeded");
        if (maxParticles < 1 || maxParticles > HARD_MAX_PARTICLES) {
            throw new IllegalArgumentException("KFX native particle budget must be 1.." + HARD_MAX_PARTICLES);
        }
        if (maxLifetime < 1) throw new IllegalArgumentException("KFX native lifetime must be positive");
        this.overflow = Objects.requireNonNull(overflow, "overflow");
        long cost = this.nodes.stream().mapToLong(Node::cost).sum();
        if (overflow == Overflow.REJECT && cost > maxParticles) {
            throw new IllegalArgumentException("KFX native REJECT cost exceeds particle budget");
        }
        this.graphHash = graphHash;
        this.maxParticles = maxParticles;
        this.maxLifetime = maxLifetime;
    }

    public long graphHash() { return graphHash; }
    public List<Node> nodes() { return nodes; }
    public int maxParticles() { return maxParticles; }
    public int maxLifetime() { return maxLifetime; }
    public Overflow overflow() { return overflow; }

    public byte[] encode() {
        int constants = nodes.stream().mapToInt(node -> node.properties.length).sum();
        int nodesOffset = HEADER_BYTES;
        int constantsOffset = Math.addExact(nodesOffset, Math.multiplyExact(nodes.size(), NODE_BYTES));
        int totalBytes = Math.addExact(constantsOffset, Math.multiplyExact(constants, Float.BYTES));
        ByteBuffer out = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put("KFX2".getBytes(StandardCharsets.US_ASCII));
        out.putShort((short)MAJOR).putShort((short)0);
        out.putLong(graphHash);
        out.putInt(nodes.size()).putInt(constants).putInt(0).putInt(1);
        out.putInt(maxParticles).putInt(maxLifetime);
        out.put((byte)overflow.ordinal()).put(new byte[3]);
        out.putInt(nodesOffset).putInt(constantsOffset).putInt(totalBytes);
        out.position(HEADER_BYTES);
        int propertyOffset = 0;
        for (Node node : nodes) {
            out.putInt(node.stableId).putShort((short)node.opcode)
                .putShort((short)(node.decorative ? 1 : 0));
            out.putInt(0).putInt(0).putInt(propertyOffset).putInt(0).putInt(node.cost).putInt(0);
            propertyOffset += node.properties.length;
        }
        for (Node node : nodes) for (float property : node.properties) out.putFloat(property);
        return out.array();
    }

    public static KfxNativeProgram decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length < HEADER_BYTES) throw new IllegalArgumentException("truncated KFX2 header");
            ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[4]; in.get(magic);
            if (!Arrays.equals(magic, "KFX2".getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("bad KFX2 magic");
            }
            int major = Short.toUnsignedInt(in.getShort()); in.getShort();
            if (major != MAJOR) throw new IllegalArgumentException("unsupported KFX native major " + major);
            long graphHash = in.getLong();
            int nodeCount = in.getInt(), constantCount = in.getInt();
            in.getInt(); in.getInt();
            int maxParticles = in.getInt(), maxLifetime = in.getInt();
            int overflowOrdinal = Byte.toUnsignedInt(in.get()); in.position(in.position() + 3);
            int nodesOffset = in.getInt(), constantsOffset = in.getInt(), totalBytes = in.getInt();
            if (nodeCount < 0 || nodeCount > HARD_MAX_NODES || constantCount < 0 || constantCount > HARD_MAX_CONSTANTS
                || totalBytes != bytes.length || nodesOffset != HEADER_BYTES
                || constantsOffset != nodesOffset + nodeCount * NODE_BYTES
                || constantsOffset + constantCount * Float.BYTES != totalBytes
                || overflowOrdinal >= Overflow.values().length) {
                throw new IllegalArgumentException("invalid KFX2 header or table offsets");
            }
            int[] propertyOffsets = new int[nodeCount];
            int[] ids = new int[nodeCount], opcodes = new int[nodeCount], costs = new int[nodeCount];
            boolean[] decorative = new boolean[nodeCount];
            in.position(nodesOffset);
            for (int i = 0; i < nodeCount; i++) {
                ids[i] = in.getInt(); opcodes[i] = Short.toUnsignedInt(in.getShort());
                decorative[i] = (Short.toUnsignedInt(in.getShort()) & 1) != 0;
                in.getInt(); in.getInt(); propertyOffsets[i] = in.getInt(); in.getInt(); costs[i] = in.getInt(); in.getInt();
                if (propertyOffsets[i] < 0 || propertyOffsets[i] > constantCount
                    || i > 0 && propertyOffsets[i] < propertyOffsets[i - 1]) {
                    throw new IllegalArgumentException("invalid KFX2 property offset");
                }
            }
            float[] constants = new float[constantCount];
            in.position(constantsOffset);
            for (int i = 0; i < constantCount; i++) {
                constants[i] = in.getFloat();
                if (!Float.isFinite(constants[i])) throw new IllegalArgumentException("non-finite KFX2 constant");
            }
            List<Node> nodes = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                int end = i + 1 < nodeCount ? propertyOffsets[i + 1] : constantCount;
                nodes.add(new Node(ids[i], opcodes[i], decorative[i], costs[i],
                    Arrays.copyOfRange(constants, propertyOffsets[i], end)));
            }
            return new KfxNativeProgram(graphHash, nodes, maxParticles, maxLifetime, Overflow.values()[overflowOrdinal]);
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException error) {
            throw new IllegalArgumentException("truncated KFX2 program", error);
        }
    }

    public static KfxNativeProgram from(KfxCompiledGraph graph) {
        JsonObject root = JsonParser.parseString(graph.programJson()).getAsJsonObject();
        List<Node> nodes = new ArrayList<>();
        root.getAsJsonArray("stages").forEach(raw -> {
            JsonObject stage = raw.getAsJsonObject();
            String id = stage.has("node") ? stage.get("node").getAsString() : "stage_" + nodes.size();
            String op = stage.get("op").getAsString();
            int count = stage.has("count") ? stage.get("count").getAsInt() : primitiveCost(stage);
            boolean decorative = KfxQualityPlan.decorative(stage);
            int argb = color(stage);
            float[] properties = {
                number(stage, "from", 0), number(stage, "to", graph.lifetime()),
                number(stage, "radius", 1), number(stage, "radius_to", 1),
                number(stage, "thickness", 0.1f), number(stage, "size", 0.05f),
                number(stage, "alpha", 1), number(stage, "spin", 0),
                number(stage, "wobble", 0), number(stage, "depth", 0),
                number(stage, "speed", 0), number(stage, "style", 0),
                ((argb >> 16) & 255) / 255.0f, ((argb >> 8) & 255) / 255.0f,
                (argb & 255) / 255.0f, ((argb >>> 24) & 255) / 255.0f,
                number(stage, "points", 5), number(stage, "skip", 2)
            };
            nodes.add(new Node(fnv32(id), opcode(op), decorative, count, properties));
        });
        long hash = fnv64(graph.id() + '\0' + graph.programJson());
        if (hash == 0L) hash = 1L;
        return new KfxNativeProgram(hash, nodes, requiredNativeBudget(nodes, graph.maxParticles()),
            graph.lifetime(), Overflow.SKIP_DECORATIVE);
    }

    public static KfxNativeProgram from(KfxInstance fx) {
        if (fx == null || fx.programJson == null || fx.programJson.isBlank()) {
            throw new IllegalArgumentException("KFX native graph instance has no program");
        }
        JsonObject root = JsonParser.parseString(fx.programJson).getAsJsonObject();
        List<Node> nodes = new ArrayList<>();
        root.getAsJsonArray("stages").forEach(raw -> {
            JsonObject stage = raw.getAsJsonObject();
            String id = stage.has("node") ? stage.get("node").getAsString() : "stage_" + nodes.size();
            String op = stage.get("op").getAsString();
            int count = stage.has("count") ? stage.get("count").getAsInt() : primitiveCost(stage);
            boolean decorative = KfxQualityPlan.decorative(stage);
            float[] properties = {
                number(stage, "from", 0), number(stage, "to", fx.lifetime),
                number(stage, "radius", fx.radius), number(stage, "radius_to", fx.radius),
                number(stage, "thickness", fx.thickness), number(stage, "size", 0.05f),
                number(stage, "alpha", 1), number(stage, "spin", 0),
                number(stage, "wobble", 0), number(stage, "depth", 0),
                number(stage, "speed", fx.speed), 0,
                ((fx.color >> 16) & 255) / 255.0f, ((fx.color >> 8) & 255) / 255.0f,
                (fx.color & 255) / 255.0f, ((fx.color >>> 24) & 255) / 255.0f,
                number(stage, "points", 5), number(stage, "skip", 2)
            };
            nodes.add(new Node(fnv32(id), opcode(op), decorative, count, properties));
        });
        JsonObject identity = root.deepCopy();
        identity.addProperty("cast_seed", 0L);
        long hash = fnv64(identity.toString());
        if (hash == 0L) hash = 1L;
        return new KfxNativeProgram(hash, nodes, requiredNativeBudget(nodes, fx.maxParticles),
            Math.max(1, fx.lifetime), Overflow.SKIP_DECORATIVE);
    }

    public static long castSeed(KfxInstance fx) {
        try {
            JsonObject root = JsonParser.parseString(fx.programJson).getAsJsonObject();
            return root.has("cast_seed") ? root.get("cast_seed").getAsLong() : fx.id;
        } catch (RuntimeException ignored) {
            return fx.id;
        }
    }

    private static int color(JsonObject stage) {
        if (!stage.has("color")) return 0xFFFFFFFF;
        String raw = stage.get("color").getAsString().trim();
        if (raw.startsWith("#")) raw = raw.substring(1);
        try { return (int)Long.parseLong(raw, 16); }
        catch (NumberFormatException ignored) { return 0xFFFFFFFF; }
    }

    private static float number(JsonObject object, String name, float fallback) {
        try { return object.has(name) ? object.get(name).getAsFloat() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
    private static int opcode(String op) {
        return switch (op) {
            case "ring_particles" -> 1; case "beam" -> 5; case "burst_ring" -> 6; case "spiral" -> 8;
            case "ribbon" -> 9; case "trail" -> 10; case "mesh" -> 11; case "decal" -> 12;
            case "light" -> 13; case "group" -> 14; default -> 4;
        };
    }
    private static int primitiveCost(JsonObject stage) {
        String primitive = stage.has("primitive") ? stage.get("primitive").getAsString() : "particles";
        return switch (primitive) {
            case "beam" -> 32;
            case "ribbon" -> stage.has("points") ? stage.get("points").getAsInt() : 24;
            case "trail" -> stage.has("points") ? stage.get("points").getAsInt() : 20;
            case "mesh" -> 1;
            case "decal" -> 16;
            case "light", "group" -> 0;
            default -> 1;
        };
    }
    private static int requiredNativeBudget(List<Node> nodes, int declared) {
        int core = nodes.stream().filter(node -> !node.decorative()).mapToInt(Node::cost).sum();
        return Math.clamp(Math.max(declared, core), 1, HARD_MAX_PARTICLES);
    }
    private static int fnv32(String value) {
        int hash = 0x811c9dc5;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) { hash ^= b & 0xff; hash *= 0x01000193; }
        return hash == 0 ? 1 : hash;
    }
    private static long fnv64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) { hash ^= b & 0xffL; hash *= 0x100000001b3L; }
        return hash;
    }

    @Override public boolean equals(Object other) {
        return other instanceof KfxNativeProgram program && graphHash == program.graphHash
            && maxParticles == program.maxParticles && maxLifetime == program.maxLifetime
            && overflow == program.overflow && nodes.equals(program.nodes);
    }
    @Override public int hashCode() { return Objects.hash(graphHash, nodes, maxParticles, maxLifetime, overflow); }
}
