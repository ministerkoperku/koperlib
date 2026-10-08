# Java addons

Two ways to write Java against KoperLib. Inside a pack, or as a real Fabric mod. They use different entry points and have different powers.

This guide documents the existing Java APIs. They are evolving; a separately versioned stable addon contract is future work. Overview material is in [KOPERLIB.md](KOPERLIB.md).

## Contents

1. [Pack Java or a Fabric mod](#pack-java-or-a-fabric-mod)
2. [Java inside a pack](#java-inside-a-pack)
3. [Shipping pack Java to players](#shipping-pack-java-to-players)
4. [Annotations](#annotations)
5. [KoperContext](#kopercontext)
6. [Return values and the ladder](#return-values-and-the-ladder)
7. [Java from a real mod](#java-from-a-real-mod)
8. [Extension points](#extension-points)
9. [Limits and how to get around them](#limits-and-how-to-get-around-them)

## Pack Java or a Fabric mod

**Pack Java** lives in the fullpack, reloads with it, and can attach behaviour to content the pack defines. It cannot create new kinds of things. Use it when a hook needs direct Minecraft types or Java logic.

**A real Fabric mod** depends on KoperLib as a library. It can register new item types, add hooks that survive reloads, and do anything Fabric allows. It fits projects that are their own mod, like Kopermod or Mechanics Dream.

Choose pack Java for reloadable content hooks and a Fabric addon for startup registration or new extension types.

## Java inside a pack

Put `.java` files in `java/` inside the pack. They compile on every reload into `.cache/classes/` and load in their own classloader.

```
my_pack/
    pack.kopermeta
    items/flame_sword.json
    java/
        FlameSword.java
```

```java
import com.koper.koper_lib.api.*;
import net.minecraft.world.InteractionResult;

@KoperItem("mypack:flame_sword")
public class FlameSword {

    @KoperHook("on_use")
    public InteractionResult onUse(KoperContext ctx) {
        ctx.player().igniteForSeconds(3);
        return InteractionResult.SUCCESS;
    }
}
```

Inside a `@KoperItem`, `@KoperBlock` or `@KoperEntity` class, well known method names bind automatically. `onUse` becomes `on_use`, `onHit` becomes `on_hit`, and so on. `@KoperHook` overrides that for an explicit name or a method name that does not match.

The full classpath of the running game is handed to the compiler, so every Minecraft and Fabric class is available.

## Shipping pack Java to players

**Compiling needs a JDK. Most players run a JRE from their launcher, which has no `javac`.** If it is missing, the java tier of the pack is off for that player and only a log line mentions it.

A shipped pack should therefore be compiled once by its author, with the output included:

```
my_pack/
    java/
        FlameSword.java      source, for the author
        out/
            FlameSword.class what actually ships
```

If `java/out/` contains class files, KoperLib loads those and never invokes `javac`. Compile shipped classes for the Java version used by the players, and include all classes belonging to the pack.

Keep the sources in the pack too. They are the readable version and they cost nothing.

## Annotations

**`@KoperItem("ns:id")`**, **`@KoperBlock("ns:id")`**, **`@KoperEntity("ns:id")`** mark a class as owning that content. Method names bind by convention. An `also` list on the annotation lets one class handle several ids.

**`@KoperHook("event")`** binds one method to one event explicitly. Works inside a marked class or standalone with a full id.

**`@KoperSubscribe("event")`** attaches a method to the cross pack event bus, the same bus `koper.events.fire` reaches.

## KoperContext

Every hook receives one:

```java
public record KoperContext(
    ServerPlayer player,
    LivingEntity entity,
    LivingEntity target,
    Level world,
    ItemStack stack,
    BlockPos pos
) {}
```

Which fields are filled depends on the event. On `on_use` the context has player, world, stack and pos. For `KoperContext.ofHit`, the attacker is in `entity` and the entity being hit is in `target`. On a block tick there is no player at all, so `player()` is null. Null check every field the handler did not fill itself.

Arguments are resolved by type, so a hook method can declare only what it needs:

```java
@KoperHook("mypack:pad/on_step")
public InteractionResult onStep(ServerPlayer player, BlockPos pos) { ... }
```

## Return values and the ladder

Every event runs Java first, then Lua, then the JSON `events` block.

**Return `InteractionResult.PASS`** and the Lua and JSON handlers for that event still run. Use this when the Java hook adds to the behaviour.

**Return anything else** and the chain stops. Use this when the Java hook replaces the behaviour.

This is what makes the ladder additive. A pack can be written entirely in JSON and Lua and then take over one single event in Java without touching either.

## Java from a real mod

Depend on the feature modules the addon uses. Build and publish KoperLib locally first:

```bash
./gradlew publishToMavenLocal
```

Then in the addon build:

```groovy
repositories { mavenLocal(); mavenCentral() }
dependencies {
    implementation "com.koper.koper_lib:koperlib-core:0.1.0-alpha"
    implementation "com.koper.koper_lib:koperlib-fullpack:0.1.0-alpha"
}
```

Declare the corresponding Fabric dependencies, `koperlib_core` and `koperlib_fullpack`, in the
addon's `fabric.mod.json`. Add Effects, Khysics, Kodel or Specific only when the addon uses them.
See [module dependencies](ECOSYSTEM.md). Players get the modules through the three release
jars (KoperLib Core, Fullpack APIs, Khysics); compile against the individual modules, not those.

Current sibling build scripts are examples of local development setups: Kopermod reads module
jars from KoperLib's `build/libs/`, while other consumers can use Maven Local. Inspect the
consumer's actual build instead of assuming it uses one shared dependency strategy.

### The API surface

```java
// require content a pack was supposed to provide
KoperLibAPI.items().require("mypack:flame_sword");
KoperLibAPI.blocks().require("mypack:forge");
KoperLibAPI.entities().require("mypack:frost_wolf");

// hooks that survive reloads, unlike pack hooks
KoperLibAPI.fullpack().addItemHook("mypack:flame_sword", "on_use", ctx -> {
    return InteractionResult.PASS;
});
KoperLibAPI.fullpack().addBlockHook("mypack:forge", "on_tick", ctx -> InteractionResult.PASS);
KoperLibAPI.fullpack().addEntityHook("mypack:frost_wolf", "on_death", ctx -> InteractionResult.PASS);

// listeners that do not need to cancel
KoperLibAPI.fullpack().addItemListener("mypack:flame_sword", "on_hit", ctx -> { });

// content operations
KoperLibAPI.content().spawnEntity("mypack:frost_wolf", level, pos);

// pack management
KoperLibAPI.fullpack().listEnabled();
KoperLibAPI.fullpack().setEnabled("mypack", false);
KoperLibAPI.fullpack().reload();
```

Hooks registered this way go into a separate table from pack hooks and survive `/koperlib reload`. Pack hooks are recreated when the pack reloads. `setEnabled` persists the switch but does not reload automatically; call `reload()` on the server thread to apply it.

## Extension points

**`KoperItemTypes.register(name, factory)`** adds a new item `type` that packs can then use in JSON. This is the way past the fixed list. KoperLib registers its own physics tools this way, so the pattern is proven.

**`KoperCalls.register(id, handler)`** registers a named handler that Lua reaches with `koper.calls`, and JSON reaches with the `call` action. This exposes a named operation to pack authors.

**`KoperEventBus.subscribe(eventId, handle)`** and its `unsubscribe`, `clearNamespace` and `handlerCount` companions.

**`KoperLocalData.register(block, id, provider)`** attaches compact per block data that travels with the block through kontraptions, lifts and dimension changes.

**`KfxApi.graph(id)`** creates a typed graph v2 builder. Use `include(alias, graphId)` to compose JSON
or Lua declarations, add built-in nodes with `node`, then call `KfxApi.declareGraph(graph)`. Resolve with
`KfxApi.linkedGraph(id)` or spawn with `KfxApi.spawnGraph(level, id, seed, arguments, start, end)`. Java,
Lua and JSON all enter the same registry and validation path. The graph compiler supports its built-in node schemas;
registering entirely new typed node families is future work. Full examples are in
[KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md).

**`KfxApi.play(level, KfxPlayRequest)`** spawns a live graph instance. A request contains the graph
id, typed parameters, cast seed, and start/end `KfxAnchor` references. It returns a `KfxHandle`; call
`set(name, value)` or atomic `setAll(values)` for typed inputs, `reanchor(start, end)` for references,
then `detach()` or `stop()` for lifecycle. Named `signal` is currently a Lua-handle operation, not a
Java `KfxHandle` method. Typed updates require the request-aware handle returned by `play`; a handle
reconstructed from an old numeric id can still reanchor or stop but cannot rebuild immutable graph
inputs. Entity anchors expose feet, center,
eyes and both hand sockets, bone anchors use Kodel with an entity-socket fallback, and `Between`
derives a transform from two other anchors. Entity positions and look angles use client partial ticks;
requested Kodel bones publish their current rendered transform. No server position packet is sent
every tick. Client-level changes clear both effect and bone caches. The complete anchor and
missing-policy table is in
[KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md#live-anchors-and-handles).

**`KfxApi.spawnControlled(level, def, controller)`** atomically reserves authoritative motion before
broadcasting its visual, preventing orphan effects on a duplicate handle or hard-cap rejection.
`KfxApi.launchController(level, controller)` remains the lower-level surface for an already managed
visual handle. Build a controller with `KfxController.projectile`; configure radius,
acceleration, lifetime, hit cooldown and `STOP`, `BOUNCE`, `SLIDE`, `STICK`, `SPLIT` or `PASS`.
For non-perfect bounces set `restitution(0..1)` and `surfaceFriction(0..1)`; leaving them unset keeps
full reflected speed.
`KfxApi.addEventListener` receives ordered `KfxImpact` values on the server thread after motion state is
committed. KFX never applies damage or changes the world by itself: the listener owns those spell rules.
Controller endpoint packets are client-interpolated. Spawn, update, impact, and stop currently use the
same whole-level recipient set so lifecycle packets cannot disagree; impact sequences are deduplicated
client-side and immediately produce a capped CPU-fallback contact ring. Controller motion shares one bounded
batch packet per level tick. `entityFilter` can exclude mod-specific targets before contact. Hard
finite-value, motion/lifetime, collision-work, input-count, and global-controller limits protect the
server. See
[KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md#authoritative-controllers-and-impacts).

**`KfxApi.registerPrimitive(primitive)`** adds a graph render primitive with a stable namespaced id and
both native and portable lowering callbacks. **`KfxApi.registerMaterial(material)`** adds its texture,
blend, emissive and depth contract. Built-in primitives are particles, beam, ribbon, trail, mesh, decal,
light and group; built-in materials are additive, translucent and decal. Lower into `KfxBatchBook`
rather than issuing a draw directly, so nodes with the same primitive/material key remain batched. See
[KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md#materials-and-render-primitives).

**`KoperAttachments`** declares physical attachment points on blocks, for mounting things. Registering an item as a *tool* there also makes it hog the attack button: `KoperToolHogger` reports it and every mining path (vanilla blocks and kontraption blocks) leaves it alone, so a press-drag-release gesture never breaks the aimed-at block.

**`KoperMountSpots`** says where a part is allowed to sit on a face. Without it free aim put every wheel a pixel off from the last one. The default for any block is five spots taken from its own shape: the four corners of the face and its middle, closest one to the click wins. A block that wants something else (a ring of bolt holes, a rail) registers `KoperMountSpots.register(block, (state, face) -> spots)` and returns block local, centre relative points; the component along the face normal is ignored.

## Limits and how to get around them

**The supported pack-Java API attaches behavior to existing content.** Register a new item type in a Fabric addon with `KoperItemTypes.register`; pack JSON can then use it. Arbitrary Minecraft registry manipulation is outside the pack reload contract.

**Pack Java needs a JDK unless the pack ships `java/out/`.** Covered above. This is the most common reason a pack behaves differently for its author than for everyone else.

**No mixins in a pack.** Class loading in a hot reloaded pack happens once, so a mixin there would need a full restart to change and would defeat the point. Mixins in core KoperLib are fine and encouraged. **Workaround:** something that truly needs a mixin is a mod, not a pack.

**Pack hooks do not survive a reload.** By design, since the pack is reloaded. **Workaround:** mod level hooks through `KoperLibAPI.fullpack()` persist.

**The `api` package is not yet a stable boundary.** It currently imports internals from `scripting`, `loader` and `data`, so a refactor inside KoperLib can break an addon's compile. There is no published slim API jar. **Workaround:** for now, pin the KoperLib version the addon builds against and expect to recompile on upgrade. A separately versioned stable API is future work.

**Reflection into KoperLib internals will break.** The internals move fast. Stick to `com.koper.koper_lib.api`.

**Compiled pack classes hold a classloader.** Each reload builds a new one and closes the old. A static reference to a pack class from outside the pack pins the old classloader and leaks metaspace. **Workaround:** do not cache pack classes in mod level statics. `/koperlib stress reloadsztorm` measures exactly this (it needs the developer Koperstuff module).

## API ownership

| API | Owning module | Used for |
|---|---|---|
| `KoperModules`, `KoperCommands`, `KoperConfigs`, `KoperNetwork` | Core | Module integration and shared lifecycle |
| `KoperLibAPI`, `FullPackAPI`, `KoperItemTypes`, `KoperCalls` | Fullpack | Content access, hooks, item types and named actions |
| `KfxApi` | Effects | Graphs, live effects and server controllers |
| `KhysBody`, `KhysPusher`, `KoperLocalData` | Khysics | Body controls and data carried with moving blocks |
| `KodelBook`, `KodelAnimationController` | Kodel | Binary model lookup and playback |
| `KoperAttachments`, `KoperMountSpots` | Specific | Attachment and mounting rules |

Keep gameplay work on the server thread where the API requires it. Client rendering APIs must not
be referenced by common/server initialization. See the individual guides for lifecycle and native
fallback behavior.

*Claude AI used for documentation.*
