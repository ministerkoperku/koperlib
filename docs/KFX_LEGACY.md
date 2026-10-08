# Legacy KFX compatibility

Existing definition and program APIs remain in the runtime for current consumers. Use [graph v2](KFX_PARTICLE_ENGINE.md) for new compositions. Developer commands require Koperstuff. Render behavior and shader participation depend on the backend.

## Definition and program APIs

The sections below document the renderer/program implementation that graph v2 currently lowers into.
They remain useful when debugging Kender and the CPU fallback, but old JSON/Lua authoring examples will
be removed as `aq` migrates.

Effects previously lived in fullpacks under `koperlib/kfx/*.json`, or could be emitted inline from Lua.
Java owns MC/Fabric wiring, networking, hot reload and fallback rendering. Kender owns the native
program store and is the target for the GPU particle path.

The debug commands below are only a test harness. Real content should be fullpack JSON or Lua.

## Developer preview commands

```mcfunction
/koperlib kfx chargebeam
/koperlib kfx orbit
/koperlib kfx implode
/koperlib kfx swirl
/koperlib kfx sparkline
/koperlib kfx ringspray
/koperlib kfx cast 20 koper_examples:mahou_circle
```

The short commands are intentionally basic demos/debug. Bigger authoring examples live in fullpacks, e.g. `koper_examples:mahou_circle`.

## Lua compatibility API

```lua
function on_use(player, pos)
    koper.kfx.cast(player, "koper_examples:charge_beam", 18.0)
end

-- or explicit endpoints
koper.kfx.spawn("koper_examples:charge_beam", sx, sy, sz, ex, ey, ez)

-- inline custom effect, no Java edit and no JSON file required
koper.kfx.cast_json(player, [[
{
  "shape": "ritual_beam",
  "color": "#55ccff",
  "color2": "#ffffff",
  "radius": 1.2,
  "thickness": 0.18,
  "lifetime": 90,
  "program": {
    "stages": [
      {"op":"ring_particles","from":0,"to":30,"count":120,"radius":3.4,"style":"orb3d","build":"center_out"},
      {"op":"pentagram_particles","from":15,"to":48,"count":90,"radius":2.35,"style":"star"},
      {"op":"beam","from":48,"to":90,"thickness":0.3,"ease":"out"}
    ]
  }
}
]], 18.0)

-- nicer Lua DSL: a Lua table becomes the same KFX JSON program
koper.kfx.cast_program(player, {
  color = "#b86cff",
  color2 = "#ffffff",
  lifetime = 120,
  light = { radius = 13.0, intensity = 1.0, color = "#b86cff" },
  program = {
    stages = {
      { op = "burst_ring", from = 0, to = 24, count = 96, radius = 0.2, radius_to = 3.8, style = "spark" },
      { op = "ring_particles", from = 10, to = 42, count = 160, radius = 3.8, style = "orb3d", build = "center_out" },
      { op = "pentagram", from = 28, to = 64, count = 110, radius = 2.55, style = "star" },
      { op = "spiral", from = 42, to = 82, count = 140, radius = 3.2, depth = 1.5, speed = 3.0 },
      { op = "beam", from = 78, to = 120, thickness = 0.38 }
    }
  }
}, 20.0)
```

Lua entrypoints:

- `koper.kfx.spawn(id, sx, sy, sz, ex, ey, ez)`
- `koper.kfx.cast(player, id, range)`
- `koper.kfx.spawn_json(json, sx, sy, sz, ex, ey, ez)`
- `koper.kfx.cast_json(player, json, range)`
- `koper.kfx.spawn_program(table, sx, sy, sz, ex, ey, ez)`
- `koper.kfx.cast_program(player, table, range)`

## Definition fields

```json
{
  "shape": "emitter",
  "color": "#55ccff",
  "color2": "#ffffff",
  "radius": 0.24,
  "size_end": 0.035,
  "lifetime": 150,
  "emitter": {
    "shape": "sphere",
    "particle_style": "orb3d",
    "motion": "swirl",
    "burst": 120,
    "rate": 54.0,
    "particle_lifetime": 38,
    "spread": 0.72,
    "speed": 0.16,
    "gravity": -0.0045,
    "drag": 0.965,
    "max_particles": 850,
    "turbulence": 0.018
  }
}
```

Particle styles: `sprite`, `spark`, `star`, `ring`, `shard`, `cube`, `tetra`, `orb3d`, `gem`. Shapes and lighting are described in [the KFX guide](KFX_PARTICLE_ENGINE.md#particle-styles-and-look).

Emitter shapes: `point`, `ring`, `beam`, `cone`, `sphere`.

Motion modes: `free`, `orbit`, `inward`, `swirl`.

## Program operations

`program.stages[]` is the open particle API. Java does not know the full effect, it only interprets ops.

Supported ops:

- `ring_particles`
- `pentagram_particles`
- `ring_band`
- `orb`
- `beam`
- `burst_ring`
- `stream`
- `spiral`

Aliases exist for nicer authoring: `pentagram`, `sigil`, `laser`, `ray`, `shockwave`, `line_particles`, `helix`.

Common op fields:

- `from`, `to`, `duration`
- `count`, `radius`, `size`, `thickness`
- `radius_to`, `speed`, `depth`, `wobble`, `spin`
- `style`
- `color`, `alpha`
- `ease`: `linear`, `smooth`, `in`, `out`
- `build`: `all`, `center_out`

## Native/GPU Contract

When an effect has `program.stages[]`, Java compiles it into a flat op buffer and uploads it to Kender:

```text
float[24] per op:
0 opcode
1 from_tick
2 to_tick
3 count
4 radius
5 size
6 thickness
7 alpha
8 style_code
9 ease_code
10 build_code
11 spin
12 wobble
13 depth
14 color_r
15 color_g
16 color_b
17 color_a
18 seed
19 radius_to
20 speed
21 x
22 y
23 z
```

Opcode mapping:

- `1` ring particles
- `2` pentagram/star polygon particles
- `3` ring band
- `4` orb/point
- `5` beam/laser
- `6` burst ring
- `7` stream/line particles
- `8` spiral/helix

Right now MC path still draws the fallback geometry for compatibility. The important bit is that Lua/fullpack effects already produce a native Kender program, so the Vulkan/OpenGL implementation can consume the same API without changing pack code.

Kender can also evaluate particle-producing ops into an instance batch:

```text
float[10] per particle:
0 x
1 y
2 z
3 size
4 color_r
5 color_g
6 color_b
7 color_a
8 style_code
9 seed
```

The MC fallback renderer reads this batch zero-copy when Kender is active. The future GL/Vulkan draw path should bind the same batch as an instance/storage buffer and draw particle meshes/billboards from it.

Ritual/timeline effects can use `shape: "ritual_beam"` plus `program`:

```json
{
  "shape": "ritual_beam",
  "radius": 1.35,
  "thickness": 0.18,
  "emitter": {
    "burst": 150,
    "particle_style": "orb3d"
  },
  "timeline": {
    "warmup": 52.0,
    "beam_time": 10.0,
    "ring_scale": 2.8,
    "sigil_scale": 0.72
  }
}
```

## Open Java API: addon ops and styles

Ops and styles are a string-keyed registry, not a fixed enum. Builtins register themselves the same way an addon does, so nothing is special-cased. Add new effect primitives from Java without touching the engine, no `cargo`, hot-reload friendly.

```java
// a new program op: full java draw on the MC (Vulkan) fallback path
KfxApi.registerOp("koper_shuriken", (ctx, op) -> {
    int blades = op.count > 0 ? op.count : 4;
    for (int i = 0; i < blades; i++) {
        float a = i / (float) blades * 6.2831853f + ctx.spin * op.spin;
        ctx.particle(op.style, (float) Math.cos(a) * op.radius, 0, (float) Math.sin(a) * op.radius,
            op.size, ctx.color(), ctx.fx.id + i * 17f);
    }
});

// op WITH a native kender opcode (nativeBatch = kender already emits its particles)
KfxApi.registerOp("koper_ribbon", 9, true, (ctx, op) -> { /* java fallback draw */ });

// a new particle look. coords are effect-local, draw with KfxDraw.*
KfxApi.registerStyle("koper_glyph", (pose, c, x, y, z, s, color, seed) ->
    KfxDraw.quad(pose, c, x-s,y,z-s, x+s,y,z-s, x+s,y,z+s, x-s,y,z+s, color));
```

Then JSON/Lua just reference them by name: `"op": "koper_shuriken"`, `"style": "koper_glyph"`. The parser resolves through the registry, no recompile of the parser/compiler.

Surfaces:
- `KfxApi.registerOp(name, handler)`: java-only op
- `KfxApi.registerOp(name, opcode, nativeBatch, handler, aliases...)`: op with a native path
- `KfxApi.registerStyle(name, drawer)`: new look, returns its style code
- `KfxDrawCtx` (ops): `particle()`, `beam()`, `eased()`, `color()`, `fx`, `basis`, `alpha/ease/smooth`
- `KfxDraw` (styles): `quad`, `tri`, `sprite`, `alpha`, `rgba`, `mix`

An op with `opcode = -1` is java-only: kender ignores it, the MC path always draws it. An op with a real opcode must also have a matching arm in `engine/koperlib-kender/src/kfx.rs` to produce native particles.

## Real 3D meshes + 2D/3D `dim`

Shapes are actual 3D geometry (CPU-generated triangles submitted through MC's Vulkan pipeline via `COLLECT_SUBMITS`, zero raw GL), not crossed planes:

- **sphere / particle kinds** → solid UV sphere, per-face shaded
- **orb3d style** → smooth sphere; the faceted octahedron it used to be is now the `gem` style
- **cube / tetra / shard / star / ring styles** → real solid box, tetrahedron, crystal, spiked star and torus
- **beam / laser** → laser with core, sheath, glow and end flares; `"style"` picks `tube`, `square` (also `box`/`cube`/`rect`/`prism`), `lightning`, `helix` or `pulse`. Width = `thickness`, length = start→end endpoints. All options are in [the KFX guide](KFX_PARTICLE_ENGINE.md#lasers).

Every mesh style also has a cheap 2D form (flat billboard / flat star). Pick per op or effect with `dim`:

```json
[
  { "op": "ring_particles", "style": "orb3d", "dim": "3d" },
  { "op": "burst_ring", "style": "orb3d", "dim": "2d" }
]
```

`3d` selects the solid mesh (and is the automatic default); `2d` selects the cheaper flat billboard.

`dim`: `auto` (default, = 3d) · `3d`/`mesh`/`solid` · `2d`/`flat`/`billboard`/`sprite`.

Style aliases for the 3D ball: `orb3d`, `sphere3d`, `ball`, `sphere`, `orb`, `mini_sphere`.

## Rendering backends

Kender can render supported native particle batches through GPU instancing when the active Minecraft backend exposes the required Vulkan device and command buffer. The Effects setting is `kenderVulkanParticles` in `config/koperlib/effects.json`. Unsupported paths retain CPU rendering.

GPU and CPU paths can differ in style, material and fade support. A successful codec or graph test does not establish visual parity on a graphics backend. Validate the intended effect on the current Minecraft version and shader configuration.

For the current graph authoring API, anchors and live effect handles, see [KFX graph v2](KFX_PARTICLE_ENGINE.md).

*Claude AI used for documentation.*
