# KFX: composable effect graphs

KFX is an effect-building API, not a list of spell presets. Authors assemble reusable nodes, random
value sources and inputs into a graph. JSON is the simplest authoring level; Lua graph builders and
Java extension nodes are the next layers. All three target the same compiled graph and can be mixed.

JSON, Lua and Java declarations share the same graph registry and compiler. Existing definition and
program APIs are documented separately in [Legacy KFX](KFX_LEGACY.md). This guide covers the current
authoring and runtime surface; known limits are listed below.

## Graph authoring and runtime

Graphs support typed inputs, includes, deterministic compilation, live handles and client-resolved
anchors. Server controllers perform contact sweeps and publish impact events. Materials and primitives
lower into render batches; cosmetic collision does not apply gameplay damage.

### Fluent Lua graph and live handles

Lua can assemble the same graph schema without hand-writing a nested JSON object:

```lua
local fx = koper.kfx.graph("aq:void_cast")
  :input("accent", "color", "#55ccff")
  :include("sigil", "aq:void_sigil_json", { accent = "#b967ff" })
  :node("shape", "koper_lib:source/spiral", { radius = 1.6 })
  :node("trail", "koper_lib:render/particles", {
    source = "shape", count = 72, color = { input = "accent" }
  })
  :output("trail")
  :budget(96, 45)

local spell = fx:play({
  start = { type="entity", entity=player:get_entity_id(), socket="main_hand",
            missing="fade" },
  finish = { type="entity", entity=target:get_entity_id(), socket="center",
             missing="detach" },
  parameters = { accent="#ee66ff" },
  seed = cast_seed
})
```

Builder calls return the graph, so they can be chained or split across ordinary Lua control flow.
`register()` declares without spawning. `play()` declares, links and compiles once per call and returns
a positive 63-bit handle. `handle:set(input, value)` recompiles the immutable graph snapshot under the
same instance id; `reanchor(start,end)` changes references, `detach()` freezes the resolved visual,
`signal(name,data)` emits `kfx:signal:<name>`, and `stop()` ends it. Java handles additionally expose
`setAll(Map)` so related inputs such as color and scale produce one replacement instead of several.

World vectors may be `{x,y,z}` arrays or `{x=,y=,z=}` objects. Explicit anchors are `world`, `entity`,
`bone`, and `between`; they use the same sockets and missing policies as Java anchors. The Lua-to-Java
command boundary uses byte-length-checked Base64 fields, not delimiter escaping, so nested JSON,
namespaced ids, unicode and signal names containing `:` are lossless. Controller impacts are broadcast
to Lua listeners as `kfx:impact` structured events after authoritative state has committed.

### JSON graph

A graph v2 has named inputs, named primitive nodes, explicit outputs and a hard budget:

```json
{
  "version": 2,
  "id": "aq:void_bloom",
  "inputs": {
    "accent": { "type": "color", "default": "#55ccff" }
  },
  "nodes": {
    "shape": {
      "type": "koper_lib:source/ring",
      "radius": {
        "random": { "distribution": "uniform", "min": 1.5, "max": 3.0 }
      },
      "spin": {
        "random": { "distribution": "uniform", "min": -42.0, "max": 42.0 }
      }
    },
    "particles": {
      "type": "koper_lib:render/particles",
      "source": "shape",
      "from": 0,
      "to": 36,
      "count": {
        "random": { "distribution": "integer", "min": 80, "max": 120 }
      },
      "color": { "input": "accent" },
      "style": "orb3d"
    },
    "root": {
      "type": "koper_lib:group",
      "children": ["particles"]
    }
  },
  "outputs": ["root"],
  "budget": { "max_particles": 120, "lifetime": 40 }
}
```

Graph validation and compilation:

- Supported value types are `number`, `integer`, `color`, and `text`.
- A visual is already a linked graph rather than a renamed backend stage list. `render/particles.source`
  links one source node, and `group.children` composes render/group nodes. Missing links, incompatible
  links and cycles are compile errors.
- Constant properties, `{ "input": "name" }`, and cast-scoped `uniform`, `integer`, and `palette`
  random distributions can be mixed where the node schema permits them. Property types and names are
  checked; unknown properties do not silently reach the renderer.
- A cast has one `long` seed. The sample for a property is derived from `(cast seed, node.property)`,
  so replaying the same cast gives byte-identical output and changing one property does not reshuffle
  unrelated properties.
- The compiler samples randomness once per cast. It does not evaluate authoring code every frame.
- `budget.max_particles` is checked against every reachable render node's declared worst case before
  spawn and checked again after cast-time resolution. Compilation forbids using an unbounded
  graph input for `count`. The engine hard ceiling is 4096 particles per graph even if old backend JSON
  requests more; both CPU and Rust receive the capped program.
- Validation failures include the source and precise JSON path, for example
  `data/aq/kfx/spell.json $.nodes.shape.radius.random.max`.
- The basic nodes used in this example are `koper_lib:source/ring`, `koper_lib:source/spiral`,
  `koper_lib:render/particles`, and `koper_lib:group`. A ring source can set `mode` to `ring` or `burst`.
  The primitive reference below lists additional node families.
- The compiler writes every backend field explicitly and normalizes rotation timing, avoiding different
  implicit defaults between the portable and native evaluators.
- Colors accept `#RRGGBB` (opaque) or `#AARRGGBB`.

Java can parse, compile and spawn the graph without selecting a preset:

```java
KfxGraph graph = KfxGraphJson.parse(json, "data/aq/kfx/void_bloom.json");
KfxRuntime.Spawn spawn = KfxRuntime.spawn(
    level,
    graph,
    castSeed,
    Map.of("accent", KfxResolvedValue.color(0xEEB05CFF)),
    start,
    end
);
```

`KfxCompiledGraph.sampledValues()` exposes the resolved cast values for debugging. Runtime sends the
resolved program to clients; a client does not run JSON or random sampling every frame.

### Mixing JSON, Lua and Java

A graph may include reusable fragments by id. `as` is required and becomes a private node prefix:

```json
{
  "version": 2,
  "id": "aq:void_spell_lua",
  "include": [
    {
      "graph": "aq:void_ring_json",
      "as": "ring",
      "bind": {
        "accent": { "type": "color", "value": "#b967ff" }
      }
    }
  ],
  "nodes": {
    "shape": { "type": "koper_lib:source/spiral" },
    "fx": { "type": "koper_lib:render/particles", "source": "shape", "count": 90 },
    "root": { "type": "koper_lib:group", "children": ["fx"] }
  },
  "outputs": ["root"],
  "budget": { "max_particles": 200, "lifetime": 50 }
}
```

Reusable source/transform fragments expose only deliberate node contracts:

```json
{
  "version": 2,
  "id": "aq:void_ring_json",
  "nodes": {
    "shape": { "type": "koper_lib:source/ring", "radius": 2.4 }
  },
  "exports": { "shape": "shape" },
  "outputs": [],
  "budget": { "max_particles": 1, "lifetime": 50 }
}
```

The wrapper refers to that public node as `ring/shape`, for example
`"source": "ring/shape"`. Non-exported child nodes cannot be linked. All included nodes are still
privately prefixed with `ring/` internally, so they cannot collide with wrapper nodes. Renderable child
outputs automatically join the wrapper outputs; an outputless fragment is valid when it exports at
least one node. Every unbound child input uses its declared default.

A binding is either a typed constant, `{ "type": "color", "value": "#b967ff" }`, or one of the
including graph's own inputs, `{ "input": "color" }`. The second form is what keeps a spell coherent: a
shared fragment takes the caster's accent instead of hard-coding a palette of its own, and the binding
is rejected at link time when the including graph declares no such input or declares it with a
different type. In Lua the same two forms are `tint = "#b967ff"` and `tint = { input = "color" }`.

Lua declares the same graph object through the Fullpack scripting module:

```lua
koper.kfx.graph("aq:void_spell_lua")
  :include("ring", "aq:void_ring_json")
  :node("fx", "koper_lib:render/particles", {
    source = "ring/shape", count = 90
  })
  :node("root", "koper_lib:group", { children = {"fx"} })
  :output("root")
  :budget(200, 50)
  :register()
```

`declare` also accepts a JSON string. It converts the table at the Lua/Java boundary, validates it with
the same parser as a JSON file, and stores it as a Lua-owned declaration. Lua is not called per frame.

Java can build a top-level fragment without manually producing JSON:

```java
KfxGraph graph = KfxApi.graph("aq:void_spell_java")
    .include("lua", "aq:void_spell_lua")
    .node("shape", "koper_lib:source/ring")
        .text("mode", "burst").number("radius_to", 4.0).end()
    .node("fx", "koper_lib:render/particles")
        .link("source", "shape").integer("count", 80).end()
    .node("root", "koper_lib:group").links("children", "fx").end()
    .output("root").budget(300, 50).build();

KfxApi.declareGraph(graph);
KfxApi.spawnGraph(level, "aq:void_spell_java", castSeed, Map.of(), start, end);
```

Declaration order does not matter. Linking happens against a complete registry snapshot, reports full
include cycles such as `a -> b -> a`, and replaces the last linked snapshot only after every graph is
valid. A bad reload therefore does not partially install a mixed graph. Two origins cannot own the same
graph id; the same origin may replace its own declaration during reload.

JSON graph files under `<pack>/kfx/**/*.json` (or the `fx` alias) are scanned recursively, so packs may
group reusable fragments in directories such as `kfx/fragments/`. Static Lua graph files belong under
`<pack>/scripts/kfx/**/*.lua`. Fullpack eagerly executes that directory after all JSON graphs have been
declared, then runs the KFX after-reload linker. These declarations are server-independent and are
kept even when startup preload runs before an integrated or dedicated world exists. This is the Lua
tier that JSON or persistent Java graphs may safely include. A graph declared later by an ordinary
event script is dynamic: it can include static fragments, but static content must not depend on it.
Reload clears and rebuilds the JSON and Lua declarations; if parsing, Lua preload, or linking fails,
runtime lookup continues serving the last complete linked snapshot. Explicit authoring/link calls still
report the candidate error so a broken edit is visible instead of silently becoming current.

### Live anchors and handles

`KfxApi.play(level, request)` spawns a linked graph and returns a `KfxHandle`. The request binds both
effect endpoints to anchors:

```java
KfxAnchor hand = new KfxAnchor.Entity(
    player.getId(), KfxSocket.MAIN_HAND, Vec3.ZERO, KfxMissingPolicy.FADE
);
KfxAnchor target = new KfxAnchor.Entity(
    player.getId(), KfxSocket.EYES, new Vec3(0, 0, 8), KfxMissingPolicy.FADE
);

KfxHandle spell = KfxApi.play(level, new KfxPlayRequest(
    "aq:void_spell_java", Map.of(), hand, target, castSeed
));

spell.set("accent", KfxResolvedValue.color(0xFF66DDFF));
spell.reanchor(otherStart, otherEnd);
spell.detach(); // keeps the last resolved frame
spell.stop();
```

`set` and `setAll` require the request-aware handle returned by `KfxApi.play`. A handle reconstructed
only from an old numeric id can still stop, detach, or reanchor an effect, but it has no immutable graph
request to rebuild and therefore rejects typed input changes. Replaying a typed input retains the same
handle, seed, graph id, and latest anchors.

### Remnants of Chaos composition pattern

AQ now demonstrates the intended three-level authoring split instead of constructing backend stage
JSON strings in gameplay Java:

- JSON under `kfx/fragments/` owns reusable visual vocabulary such as chaos cores, impact bursts,
  independently seeded runic circles, and shroom particles.
- Static Lua under `scripts/kfx/spells.lua` includes those JSON fragments, adds spell-specific nodes,
  exposes typed inputs, assigns budgets, and registers the finished spell graphs.
- Java `MagicVisuals` supplies cast color, radius, form, seed, and world/entity anchors through
  `KfxPlayRequest`. It does not create `KfxDef` objects or serialize graph JSON.

Short effects use bounded graph lifetimes. Channelled beams, shields, sky charges, runic circles, and
persistent spell objects use explicit long-lived graph variants owned by one `KfxHandle`; ticks
reanchor or update that handle, and release, removal, failure, or disconnect stops it. This avoids
respawning overlapping copies while ensuring one-shot visuals do not accidentally live forever.
Gameplay collision, damage, explosions, and mana remain authoritative AQ Java mechanics. KFX only
draws their result.

Available anchors:

- `World(position, normal, missing)` is fixed in world space. A ray or block hit can be stored as a
  world anchor with the authoritative hit point and normal.
- `Entity(entityId, socket, localOffset, missing)` follows an entity. Sockets are `FEET`, `CENTER`,
  `EYES`, `MAIN_HAND`, and `OFF_HAND`. Hand positions rotate with body yaw. An `EYES` forward offset
  follows view yaw and pitch.
- `Bone(entityId, boneName, entityFallback, missing)` subscribes to the current rendered Kodel bone
  transform. Kodel only captures bones requested by a live KFX anchor. Before that bone has rendered,
  or when the model has no such bone, the declared entity socket fallback is used.
- `Between(start, end, mix, missing)` recursively resolves two anchors. `mix=0.5` is the midpoint and its
  forward axis points from start to end.

Entity positions, body yaw and view angles are interpolated with the current render partial tick.
Kodel publishes the already-posed bone frame, including its forward and normal axes. Render plans
retain the complete frame so beams, ribbons, meshes and decals can use position plus orientation where
their lowering supports it. The server sends compact entity/socket/bone references once instead
of a new position packet every tick. `reanchor` sends another reference pair only when the binding
itself changes. Disconnect and client-level changes clear effect and bone caches, so reused entity ids
cannot inherit a transform from the previous dimension. Bone subscriptions are rebuilt from the live
anchor set every frame, indexed per entity, and an unrendered pose expires instead of freezing forever.

Missing policies are explicit: `DETACH` keeps the last frame and removes the binding, `FREEZE` keeps the
last frame while retrying the binding, `FADE` switches to the CPU fallback for the declared fade-out
window and then removes the effect, and `KILL` removes it immediately. Handles use monotonic ids and can
control both graph v2 effects and old backend effects during migration. Request-aware Java handles
expose typed `set`/`setAll`, `reanchor`, `detach`, and `stop`; Lua handles additionally expose `signal`.
Reconstructed numeric Java handles cannot change typed inputs because they do not retain the immutable
graph request.

### Authoritative controllers and impacts

Server controllers move gameplay-relevant effect roots at 20 Hz while the client interpolates their
visual segments each render frame. A projectile controller is independent from the graph that draws
it, so JSON/Lua visuals and a Java mechanic can share one handle:

```java
long handle = KfxRuntime.nextInstanceId();
KfxApi.spawnControlled(level, beamDef,
    KfxController.projectile(handle, owner.getUUID(), start, velocity)
    .radius(0.16)
    .response(KfxController.Response.BOUNCE)
    .restitution(0.72)
    .surfaceFriction(0.12)
    .maxBounces(3)
    .lifetime(120)
    .build());
```

`spawnControlled` reserves the controller before broadcasting its visual and rolls the reservation
back if spawning fails. Prefer it over separate `spawn`/`launchController` calls so a rejected hard cap
or duplicate handle cannot leave an orphan visual.

Each server tick performs one continuous segment sweep from the previous transform to the new one, so
a fast sphere cannot skip a thin block or entity between ticks. A voxel DDA walks the centerline and
checks nearby collision AABBs inflated on each axis by the radius. This conservative swept-box
approximation includes diagonal edge/corner approaches without scanning the entire corridor; it may
report slightly early at a box corner. Entity bounds are inflated by the controller radius. The owner,
spectators, non-pickable entities, entities rejected by `entityFilter`, and an entity still inside its
hit cooldown are ignored. The nearest block/entity contact wins.

Responses are:

- `STOP` commits the contact and ends the controller.
- `BOUNCE` reflects velocity around the hit normal and ends after `maxBounces`. Optional
  `restitution` controls retained normal energy and `surfaceFriction` removes tangential energy; their
  defaults `1` and `0` preserve the original perfect bounce.
- `SLIDE` removes only velocity into the surface.
- `STICK` holds at the authoritative contact until lifetime ends.
- `SPLIT` reports a terminal contact; the listener decides which child controllers to launch.
- `PASS` reports contact and continues the original segment, with per-entity hit cooldown protection.

`KfxApi.addEventListener(listener)` receives an ordered `KfxImpact` after controller state is committed.
It contains the handle, node id, monotonically increasing sequence, owner, optional entity or block,
authoritative point/normal, incoming and outgoing velocity, bounce count, seed and typed inputs. KFX
only reports contact: the AQ spell listener owns damage, explosions, block changes and mana. Client
particle contacts never enter this server listener path and there is no clientbound-to-server hit claim
packet. Spawn, update, impact, and stop currently go to every player in the controller's level so every
recipient sees a consistent lifecycle. Clients reject duplicate or late impact sequences. Recipient
LOD is deferred until it can retain that lifecycle guarantee.

Controller construction rejects non-finite or oversized motion, radius, lifetime, hit/bounce counts,
cooldown, and input maps. Speed has a hard terminal cap, at most 128 authoritative controllers may be
active across a server, and one level gets at most 8192 block-shape checks per tick. Motion is sent as
one bounded batch per level/tick rather than one packet per controller. A controller stops without an
impact if its collision corridor touches an unloaded chunk or the collision budget is exhausted;
sensors never load chunks.
These are safety boundaries, not tuning recommendations.

An accepted client impact immediately spawns a short independent CPU-fallback ring using the source
effect's color. Its own handle survives the following terminal stop packet, so the authoritative
contact has a default visual response. This fallback never
uploads a separate native program and is capped at 32 births per client tick and 64 live rings.

### Native graph IR, deterministic variation and budgets

Graph particle programs now lower to a versioned `KFX2` byte buffer shared by Java and Rust. One
immutable program is cached by content hash; live instances reference it with a handle, cast seed,
quality-adjusted particle budget, endpoints and start tick. Endpoint changes update the instance rather
than allocating or parsing the graph again. The older `float[24]` program remains as automatic fallback
while AQ content is migrated.

The native header and every table are length checked before storage. Hard limits are 1024 nodes, 32,768
float constants, 4096 particles for one graph, and 65,536 particles across the native live set. Quality
tiers reduce creation budgets before allocation (`low=40%`, `medium=70%`, `high=100%`), and the portable
path applies the matching `KfxQualityPlan` node reduction. Under the default
`SKIP_DECORATIVE` overflow policy core node costs are filled first and decorative costs use only the
remainder. Other explicit policies are `SCALE_RATE`, `DROP_OLDEST`, and `REJECT`; a rejecting program
whose declared cost exceeds its budget never reaches rendering.

Particle variation is derived from SplitMix64 using `(cast seed, stable node id, particle serial)`.
Replaying a seed produces the same batch while changing the seed varies each node independently. Ring,
burst-ring and spiral sources use their compiled constants; unknown/extension opcodes retain a bounded
random spatial fallback. Invalid programs and unavailable native functions fall back without dropping
the visual. See [KENDER.md](KENDER.md#render-paths) for backend selection and rendering limits.

### Materials and render primitives

A `koper_lib:render/particles` node draws whatever source feeds it. The source decides the silhouette:

| source type | shape-specific fields | what it draws |
|---|---|---|
| `koper_lib:source/ring` | `radius`, `radius_to`, `spin`, `wobble`, `mode` | a ring, or an expanding shockwave with `"mode":"burst"` |
| `koper_lib:source/spiral` | `radius`, `spin`, `depth`, `speed` | a helix along the effect axis |
| `koper_lib:source/sigil` | `radius`, `points`, `skip`, `spin` | the star polygon `{points/skip}`, e.g. a `{5/2}` pentagram |
| `koper_lib:source/stream` | `wobble`, `speed` | sparks climbing the start-to-end line |
| `koper_lib:source/orb` | `depth` | one particle at the anchor, for a core or a spark |

Every source also accepts the canonical `radius`, `radius_to`, `spin`, `wobble`, `depth` and `speed`
fields so a particle node can read them uniformly. `points` is 3..16 and `skip` is 1..points/2; both are
ordinary graph values, so a cast can roll its own sigil and no two casts draw the same figure. A sigil
needs at least 15 particles to draw its lines, a spiral or burst ring at least 8, a stream at least 2.

Graph v2 render nodes are no longer limited to particles. These built-ins share one registry and have
both native and portable lowerings:

| node type | main fields | portable/native meaning |
|---|---|---|
| `koper_lib:render/particles` | `source`, `count`, `size`, `style`, `color`, `priority` | instanced sprites/small 3D meshes |
| `koper_lib:render/beam` | `thickness`, `style`, `color`, `core_color`, `glow` and more, see [Lasers](#lasers) | laser from start to end: hot core, light sheath, glow, end flares |
| `koper_lib:render/ribbon` | `thickness`, `points`, `color`, `material` | bounded waving strip along anchors |
| `koper_lib:render/trail` | `thickness`, `points`, `color`, `material` | tapered anchor-history silhouette |
| `koper_lib:render/mesh` | `mesh`, `scale`, `color`, `material` | registered 3D geometry instance |
| `koper_lib:render/decal` | `texture`, `size`, `color`, `material` | normal-oriented contact plane/ring |
| `koper_lib:render/light` | `radius`, `intensity`, `color` | cosmetic light request; no gameplay light |
| `koper_lib:group` | `children` | composition only, draws nothing itself |

Every render node, particles included, declares `priority` as `core` or `decorative`; the default is
`core`. `KfxQualityPlan` turns that declaration into one tier decision that both render paths apply, so
a client sees the same silhouette whichever backend it runs:

| tier | core nodes | decorative nodes |
|---|---|---|
| `high` | full declared count | kept as declared |
| `medium` | 70% of declared count | kept at 35% of declared count |
| `low` | 40% of declared count | removed |

A core beam or mesh therefore survives at every tier while sparks, halos, decals and cosmetic lights
thin out and finally disappear. Counts never fall below a primitive's backend minimum, so a `spiral` or
`burst_ring` keeps at least 8 particles. Lowering is idempotent: a program that already records its
`quality` passes through unchanged, so a program may cross both the client spawn path and a backend.
`KfxRenderPlan` reports particle, geometry and batch cost before a renderer receives it.

Built-in materials are `koper_lib:additive`, `koper_lib:translucent`, and `koper_lib:decal`. A material
keys texture, `ALPHA`/`ADDITIVE`/`MULTIPLY` blend, emissive state, and `TEST`/`ALWAYS`/`DECAL_BIAS` depth
mode. `KfxBatchBook` groups by backend + primitive + material, so compatible nodes share a submit/draw.
Custom Java extensions register through `KfxApi.registerPrimitive` and `KfxApi.registerMaterial`; a
primitive supplies native and portable compiler callbacks instead of editing an engine enum.

Example core beam plus optional details:

```json
{
  "nodes": {
    "beam": {"type":"koper_lib:render/beam", "thickness":0.16,
             "color":"#b967ff", "material":"koper_lib:additive", "priority":"core"},
    "runes": {"type":"koper_lib:render/decal", "texture":"aq:kfx/void_rune",
              "size":1.8, "color":"#8855ff", "priority":"decorative"},
    "root": {"type":"koper_lib:group", "children":["beam","runes"]}
  }
}
```

### Particle styles and look

`style` on a `render/particles` node picks the shape of every particle. 3D styles are real meshes that
tumble slowly about a random axis of their own, so a ring of cubes never shows the same face twice.

| style | aliases | shape |
|---|---|---|
| `sprite` | | soft round glow with a white hot middle, no mesh |
| `spark` | `streak`, `trail` | thin needle; an emitter spark streaks along its motion |
| `star` | `flare` | six spikes on a small core |
| `ring` | `halo` | torus |
| `shard` | `diamond`, `crystal` | long six sided crystal |
| `cube` | `box`, `voxel` | cube |
| `tetra` | `tetrahedron`, `pyramid` | tetrahedron |
| `orb3d` | `orb`, `sphere`, `ball`, `sphere3d`, `mini_sphere` | smooth sphere (the default) |
| `gem` | `octa`, `octahedron` | faceted octahedron |

Every mesh is lit the same way on both backends: a key light from above, a sky fill, and a pale fresnel
rim that brightens the silhouette. Each particle varies its brightness slightly from its seed, so a ring
of one colour does not read as a flat cutout. An additive halo sits around every particle; it is full
strength in the dark and toned down in daylight, where added light would only wash the scene out. The
`low` quality tier drops halos. On the portable path a sphere only a few pixels wide is drawn as the gem.
`"dim": "2d"` still selects the cheaper flat forms.

### Lasers

`koper_lib:render/beam` draws a laser between the effect's start and end anchors. The colour is mostly
light: a near white core, a translucent sheath that is dense through its middle and clear at its edge,
bands of brightness flowing toward the end, a bright band and a wide soft glow added on top, and a flare
at each end.

| field | default | meaning |
|---|---|---|
| `style` | `tube` | `tube`, `square` (solid prism), `lightning` (jagged bolt with a branch, re-rolled while it lives), `helix` (two strands twisting around a core), `pulse` (energy beads riding toward the end) |
| `thickness` | `0.12` | sheath radius in blocks |
| `color` | `#FFFFFFFF` | sheath and glow colour |
| `core_color` | `#00000000` | core colour; fully transparent means a near white tint of `color` |
| `core` | `0.4` | core radius as a fraction of `thickness` |
| `glow` | `1.0` | wide glow strength and width, `0` turns it off (0..4) |
| `flicker` | `0.12` | how much the width breathes over time (0..1) |
| `taper` | `0.0` | how much thinner the end is than the start (0..1) |
| `noise` | `0.0` | sideways waving in blocks; for `lightning` the jag amplitude (default 2.5 x `thickness`) |
| `speed` | `1.0` | animation speed of bands, waving, bolts, strands and beads |
| `segments` | `12` | joints of a `lightning` bolt (4..64) |
| `caps` | `both` | end flares: `both`, `start`, `end`, `none` |

An unknown `style` logs one error naming the valid styles and draws a tube.

```json
{
  "version": 2,
  "id": "aq:storm_lance",
  "nodes": {
    "bolt": {"type":"koper_lib:render/beam", "style":"lightning", "thickness":0.08,
             "color":"#b9a0ff", "segments":16, "glow":1.4, "caps":"end"},
    "lance": {"type":"koper_lib:render/beam", "style":"helix", "thickness":0.14,
              "color":"#66ffb0", "speed":2.0, "taper":0.5},
    "root": {"type":"koper_lib:group", "children":["bolt","lance"]}
  },
  "outputs": ["root"],
  "budget": { "max_particles": 64, "lifetime": 40 }
}
```

`ribbon` and `trail` use the same drawing: a ribbon waves by default and a trail thins toward its end;
both read `noise`, `taper`, `glow` and `speed`. Beams, ribbons, trails, meshes and decals are geometry
and are drawn on the Java side on every backend; the native particle batch only carries particles.

`/koperlib kfx gallery` (Koperstuff) spawns every particle style, a sigil, a spiral and every laser
style in front of the player for side by side comparison.

### Cosmetic particle collision

Stateful native emitters can opt into a bounded local voxel field. This is deliberately separate from
the authoritative controller described above: it changes only particle position/velocity, never sends
a hit packet, invokes `KfxEventListener`, damages an entity, or edits a block. The available visual
responses are `bounce`, `slide`, `stick`, and `die`.

```json
{
  "shape": "emitter",
  "emitter": {
    "burst": 180,
    "gravity": -0.03,
    "collision": {
      "response": "bounce",
      "radius": 8,
      "fluids": false,
      "restitution": 0.65,
      "friction": 0.08
    }
  }
}
```

`radius` is clamped to `1..16`, so one upload contains at most `33³ = 35,937` byte cells. Only loaded
blocks are sampled. Solid collision shapes use the `SOLID` bit and fluids use an independent `FLUID`
bit. `KfxCollisionCache` is revision-keyed and bounded to 128 fields for integrations which know their
chunk-section revision. `KfxCollisionField.capture(...)` and `upload(...)` expose the lower Java layer.
Kender then sweeps each stateful particle from its previous to proposed position (maximum 64 samples),
applies the response in Rust, and retains no gameplay/event channel. Stateless ring/spiral geometry
does not pretend to collide.

Normal energy after bounce is multiplied by `restitution`; tangential energy is multiplied by
`1-friction`. Thus `1/0` is a perfect bounce, while lower restitution and higher friction make sparks
lose energy naturally. This cosmetic field currently belongs to the Kender stateful-emitter path; the
portable renderer keeps drawing the effect but does not claim equivalent collision simulation.

### Live inspection

Koperstuff adds `/koperlib kfx inspect <handle>`. Spawn commands print the handle. Inspection reports
the graph ID/hash and source chain, chosen quality, anchors, authoritative controller state, sensor
count, particle/geometry/batch budgets, cosmetic collision field cells/response, and the last known
problem. `backend=auto` is intentional on a server: native versus portable is selected independently
on each receiving client and a dedicated server must not invent one client-wide answer.

### Steering and controller-owned graphs

`KfxApi.spawnControlledGraph(level, graphId, seed, arguments, controller)` is the graph v2 counterpart of
`spawnControlled`. It launches the controller, spawns the linked graph under the controller's handle and
rolls the controller back if the spawn throws. The visual is deliberately **not** anchored: authoritative
controller motion drives it through the per-level motion batch, and adding anchors on top would fight
that. Use `play` for anchored effects and this for moving ones.

`KfxApi.steerController(level, handle, velocity)` points a running controller somewhere else without
restarting it or touching its visual. It returns false when that handle is not running in that level.
The new velocity passes the same terminal-speed cap as construction, so gameplay cannot steer past it.
Homing, curving and wind-pushed projectiles belong here rather than in a respawned controller.

A reanchor keeps the live client instance and only swaps its anchors, so the resolved frame and its
smoothing survive. Before this, every `reanchor` allocated a fresh client instance and the effect visibly
restarted; a beam updated a few times a second looked like it was respawning.

Sockets: `MAIN_HAND`/`OFF_HAND` sit where a held item renders: out to the side, slightly forward and just
under eye height. The right-hand vector is `(-flatForward.z, 0, flatForward.x)`; facing south (yaw 0) the
right hand is west. An earlier sign error put every hand anchor on the opposite hand and sent a positive
`offset.x` to the left.

### Known graph v2 limits

- Value sources currently support constants, typed graph inputs, and deterministic cast-time uniform,
  integer, or palette distributions. Curves, runtime expressions, and explicit per-frame/per-particle
  random scopes are not public authoring contracts yet.
- The public anchor types are `World`, `Entity`, `Bone`, and `Between`. Store a ray/block/entity hit as
  a `World` anchor with its point and normal; there is no separate persistent `Hit` or effect-to-effect
  anchor type yet.
- Cosmetic voxel collision runs only for stateful native emitters. Portable rendering keeps the visual
  but does not simulate particle bounce, slide, stick, or die.
- A typed input change respawns the instance under the same id. The client drops the cached portable
  program for that id when it does, so both backends see the new values; the effect's age still restarts.
- `koper_lib:source/orb` is a single point, not a volume: it emits one particle at the anchor. Build a
  cloud from several `source/ring` nodes with `depth`, which spreads particles vertically into a band.
- Graph v2 accepts only the built-in typed node schemas. Java can register render primitives and
  materials, while registration of entirely new graph node/value families remains planned.
- An include binding is a constant or one of the including graph's inputs. Curves, expressions and
  per-particle random scopes are still absent, so a sampled value is fixed for a whole cast.
- Controller lifecycle packets currently target the whole level. Recipient-aware distance LOD remains
  deferred until it can preserve spawn, update, impact, and stop ordering.


See [Legacy KFX](KFX_LEGACY.md) for definition/program compatibility and [Kender](KENDER.md) for render and shader limits.

*Claude AI used for documentation.*
