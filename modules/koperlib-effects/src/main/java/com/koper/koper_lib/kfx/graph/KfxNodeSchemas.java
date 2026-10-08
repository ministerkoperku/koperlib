package com.koper.koper_lib.kfx.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class KfxNodeSchemas {
    static final String SOURCE_RING = "koper_lib:source/ring";
    static final String SOURCE_SPIRAL = "koper_lib:source/spiral";
    static final String SOURCE_SIGIL = "koper_lib:source/sigil";
    static final String SOURCE_STREAM = "koper_lib:source/stream";
    static final String SOURCE_ORB = "koper_lib:source/orb";
    static final String RENDER_PARTICLES = "koper_lib:render/particles";
    static final String RENDER_BEAM = "koper_lib:render/beam";
    static final String RENDER_RIBBON = "koper_lib:render/ribbon";
    static final String RENDER_TRAIL = "koper_lib:render/trail";
    static final String RENDER_MESH = "koper_lib:render/mesh";
    static final String RENDER_DECAL = "koper_lib:render/decal";
    static final String RENDER_LIGHT = "koper_lib:render/light";
    static final String GROUP = "koper_lib:group";

    private static final Set<String> RENDERS = Set.of(RENDER_PARTICLES, RENDER_BEAM, RENDER_RIBBON,
        RENDER_TRAIL, RENDER_MESH, RENDER_DECAL, RENDER_LIGHT);

    private static final Map<String, Schema> SCHEMAS = Map.ofEntries(
        Map.entry(SOURCE_RING, new Schema(
            properties(
                optional("radius", KfxResolvedValue.number(1.0)),
                optional("radius_to", KfxResolvedValue.number(1.0)),
                optional("spin", KfxResolvedValue.number(0.2)),
                optional("wobble", KfxResolvedValue.number(0.0)),
                optional("depth", KfxResolvedValue.number(0.0)),
                optional("speed", KfxResolvedValue.number(1.0)),
                fixed("mode", KfxResolvedValue.text("ring"))
            ), Map.of()
        )),
        Map.entry(SOURCE_SPIRAL, new Schema(
            properties(
                optional("radius", KfxResolvedValue.number(1.0)),
                optional("spin", KfxResolvedValue.number(2.0)),
                optional("wobble", KfxResolvedValue.number(0.0)),
                optional("depth", KfxResolvedValue.number(1.5)),
                optional("speed", KfxResolvedValue.number(3.0))
            ), Map.of()
        )),
        Map.entry(SOURCE_SIGIL, new Schema(
            properties(
                optional("radius", KfxResolvedValue.number(2.0)),
                optional("radius_to", KfxResolvedValue.number(2.0)),
                optional("spin", KfxResolvedValue.number(0.15)),
                optional("wobble", KfxResolvedValue.number(0.0)),
                optional("depth", KfxResolvedValue.number(0.0)),
                optional("speed", KfxResolvedValue.number(0.0)),
                optional("points", KfxResolvedValue.integer(5)),
                optional("skip", KfxResolvedValue.integer(2))
            ), Map.of()
        )),
        Map.entry(SOURCE_STREAM, new Schema(
            properties(
                optional("radius", KfxResolvedValue.number(0.0)),
                optional("radius_to", KfxResolvedValue.number(0.0)),
                optional("spin", KfxResolvedValue.number(0.0)),
                optional("wobble", KfxResolvedValue.number(0.18)),
                optional("depth", KfxResolvedValue.number(0.0)),
                optional("speed", KfxResolvedValue.number(1.0))
            ), Map.of()
        )),
        Map.entry(SOURCE_ORB, new Schema(
            properties(
                optional("radius", KfxResolvedValue.number(0.0)),
                optional("radius_to", KfxResolvedValue.number(0.0)),
                optional("spin", KfxResolvedValue.number(0.0)),
                optional("wobble", KfxResolvedValue.number(0.0)),
                optional("depth", KfxResolvedValue.number(0.0)),
                optional("speed", KfxResolvedValue.number(0.0))
            ), Map.of()
        )),
        Map.entry(RENDER_PARTICLES, new Schema(
            properties(
                optional("from", KfxResolvedValue.number(0.0)),
                optional("to", KfxResolvedValue.number(20.0)),
                required("count", KfxValueType.INTEGER, false, true),
                optional("thickness", KfxResolvedValue.number(0.12)),
                optional("size", KfxResolvedValue.number(0.08)),
                optional("alpha", KfxResolvedValue.number(1.0)),
                optional("style", KfxResolvedValue.text("orb3d")),
                optional("color", KfxResolvedValue.color(0xFFFFFFFF)),
                optional("ease", KfxResolvedValue.text("smooth")),
                optional("build", KfxResolvedValue.text("all")),
                optional("dim", KfxResolvedValue.text("auto")),
                optional("priority", KfxResolvedValue.text("core"))
            ), Map.of("source", new Link(false, true,
                Set.of(SOURCE_RING, SOURCE_SPIRAL, SOURCE_SIGIL, SOURCE_STREAM, SOURCE_ORB)))
        )),
        Map.entry(RENDER_BEAM, primitive(
            optional("thickness", KfxResolvedValue.number(0.12)),
            optional("alpha", KfxResolvedValue.number(1.0)), optional("color", KfxResolvedValue.color(0xFFFFFFFF)),
            optional("material", KfxResolvedValue.text("koper_lib:additive")),
            optional("style", KfxResolvedValue.text("tube")), optional("priority", KfxResolvedValue.text("core"))
        )),
        Map.entry(RENDER_RIBBON, primitive(
            optional("thickness", KfxResolvedValue.number(0.16)), optional("points", KfxResolvedValue.integer(12)),
            optional("alpha", KfxResolvedValue.number(1.0)), optional("color", KfxResolvedValue.color(0xFFFFFFFF)),
            optional("material", KfxResolvedValue.text("koper_lib:additive")), optional("priority", KfxResolvedValue.text("core"))
        )),
        Map.entry(RENDER_TRAIL, primitive(
            optional("thickness", KfxResolvedValue.number(0.12)), optional("points", KfxResolvedValue.integer(16)),
            optional("alpha", KfxResolvedValue.number(1.0)), optional("color", KfxResolvedValue.color(0xFFFFFFFF)),
            optional("material", KfxResolvedValue.text("koper_lib:additive")), optional("priority", KfxResolvedValue.text("core"))
        )),
        Map.entry(RENDER_MESH, primitive(
            required("mesh", KfxValueType.TEXT, true, false), optional("scale", KfxResolvedValue.number(1.0)),
            optional("alpha", KfxResolvedValue.number(1.0)), optional("color", KfxResolvedValue.color(0xFFFFFFFF)),
            optional("material", KfxResolvedValue.text("koper_lib:translucent")), optional("priority", KfxResolvedValue.text("core"))
        )),
        Map.entry(RENDER_DECAL, primitive(
            required("texture", KfxValueType.TEXT, true, false), optional("size", KfxResolvedValue.number(1.0)),
            optional("alpha", KfxResolvedValue.number(1.0)), optional("color", KfxResolvedValue.color(0xFFFFFFFF)),
            optional("material", KfxResolvedValue.text("koper_lib:decal")), optional("priority", KfxResolvedValue.text("decorative"))
        )),
        Map.entry(RENDER_LIGHT, primitive(
            optional("radius", KfxResolvedValue.number(4.0)), optional("intensity", KfxResolvedValue.number(1.0)),
            optional("color", KfxResolvedValue.color(0xFFFFFFFF)), optional("priority", KfxResolvedValue.text("decorative"))
        )),
        Map.entry(GROUP, new Schema(Map.of(), Map.of("children", new Link(true, true,
            Set.of(RENDER_PARTICLES, RENDER_BEAM, RENDER_RIBBON, RENDER_TRAIL, RENDER_MESH,
                RENDER_DECAL, RENDER_LIGHT, GROUP)))))
    );

    private KfxNodeSchemas() {}

    static Schema get(String type, String path) {
        Schema schema = SCHEMAS.get(type);
        if (schema == null) throw new KfxGraphException(path, "node type '" + type + "' is not available in Part 1");
        return schema;
    }

    static boolean isRender(String type) { return RENDERS.contains(type); }

    static String primitiveName(String type) {
        return isRender(type) ? type.substring("koper_lib:render/".length()) : "";
    }

    private static Schema primitive(Property... properties) {
        return new Schema(properties(properties), Map.of());
    }

    private static Map<String, Property> properties(Property... properties) {
        Map<String, Property> map = new LinkedHashMap<>();
        for (Property property : properties) map.put(property.name(), property);
        return java.util.Collections.unmodifiableMap(map);
    }

    private static Property optional(String name, KfxResolvedValue value) {
        return new Property(name, value.type(), false, value, true, true);
    }

    private static Property fixed(String name, KfxResolvedValue value) {
        return new Property(name, value.type(), false, value, false, false);
    }

    private static Property required(String name, KfxValueType type, boolean input, boolean random) {
        return new Property(name, type, true, null, input, random);
    }

    record Schema(Map<String, Property> properties, Map<String, Link> links) {}

    record Property(
        String name,
        KfxValueType type,
        boolean required,
        KfxResolvedValue defaultValue,
        boolean allowInput,
        boolean allowRandom
    ) {}

    record Link(boolean many, boolean required, Set<String> targetTypes) {}
}
