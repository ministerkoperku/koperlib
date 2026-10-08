# Elpe: extremely light physics engine

Elpe is the second physics engine in KoperLib, built for quantity. It uses points and constraints where a full Rapier rigid body is unnecessary. Capacity depends on active contacts, constraints, loaded terrain and available memory.

Everything in elpe is a point with a radius and a mass. There are no rotations, no inertia tensors, no quaternions and no shapes. On top of the points there are:

- **joints**: a distance range between two points, or between a point and a fixed spot in the world
- **voxel terrain**: MC blocks as 16³ bitsets, fed in on demand
- **sleeping**: inactive points skip active integration work

A "rigid body" is a cluster of points held together by stiff joints. It still tumbles, because its points move. A rope is a chain, a jelly cube is a lattice, a ragdoll is a stick figure.

## How it stays cheap

- **Verlet integration plus position based constraints.** Velocity is `pos - prev` and is never stored. Contacts are solved Jacobi style, so each thread writes only its own points. Joints are solved Gauss-Seidel style, alternating direction between passes.
- **Step cost is O(awake), not O(alive).** Sleeping points are not integrated and not tested for anything. They sit in the broadphase as walls. An awake point that hits a sleeper slowly treats it as immovable, so a resting pile never wakes itself up. A fast hit (above `wakeSpeed`) wakes it.
- **Broadphase is a hashed grid with intrusive doubly linked buckets.** Moving one point costs O(1), and it is only relinked when it changes cell. Nothing is rebuilt per step.
- **Terrain streams on demand.** A point that walks into a section elpe does not have **freezes** and asks Java for that section. Java only answers from chunks that are already loaded and never forces a load, so physics stops at the edge of the loaded world instead of dragging it along. Sections nobody touched for 1200 ticks are forgotten.
- **Persistent thread pool** (`crew.rs`). Spawning threads per phase cost more than the physics on a phone.
- **Zero dependencies** in the crate. std only.

## Performance

Sleeping and active point counts have different costs. Dense contacts and scattered awake points can cost much more than a mostly sleeping world. The source includes a benchmark for measuring the workload on the target machine:

```bash
cargo run --release -p koperlib-elpe --example elpe_bench -- 1000000
```

Run it from `engine/`. It measures the native point engine, not complete Minecraft gameplay, rendering or network cost.

## Try it in game

Everything is under `/koperlib elpe` (players only, like the rest of `/koperlib`):

| command | does |
|---|---|
| `spawn <count>` | a block of loose balls above the player. Try 100000 |
| `rope <links>` | a rope pinned in the air above the player |
| `cube <size>` | a jelly cube, every lattice neighbour welded. It tumbles without any rotation code |
| `blast <radius>` | pushes everything away from the player |
| `rubble` | toggles explosion rubble (off by default, see below) |
| `crumble <radius>` | rips a ball of ground out under the player and throws it up as rubble |
| `show` | toggles END_ROD particles on awake points near players (capped at 1500 per tick). Debug view only, there is no client renderer yet |
| `stats` | live / awake / asleep / frozen / joints / sections / step ms, plus rubble flying / landed / dropped |
| `clear` | drops the level's elpe world |

## Rubble

The first real use of elpe. With `/koperlib elpe rubble` on, a share (35%) of the blocks an explosion eats fly off as physical rubble instead of vanishing. The pieces bounce, pile up on each other, and **turn back into real blocks** when they land. A settled pile becomes ordinary terrain rather than active rubble.

- **In flight** each piece is an elpe point (radius 0.45) with a vanilla `BlockDisplay` on top. It needs no client mod, so it works on vanilla clients and on Zalith. The display tumbles as it flies. That spin is fake, driven by the distance travelled, since elpe has no rotation.
- **Landing:** a piece has landed once it moves slower than 1 block/s for 10 ticks. This is deliberately looser than elpe's own sleep, because rubble snaps to the block grid anyway. With the stricter rule a crater full of rubble kept nudging itself awake.
- **Placement:** the block goes into the cell the piece's centre is in, or the cell above. If both are full it drops as items.
- **Never thrown:** blocks with a block entity (chests keep their contents) and unbreakable blocks.
- **Nothing is lost:** rubble displays are never saved to the world. On server stop, and on `/koperlib elpe clear`, everything in flight is put down where it is first. Rubble older than 30 s is placed wherever it is.
- **Cap:** at most 3000 pieces per level at once. Beyond that, blocks are handled the vanilla way.
- **API:** `ElpeRubble.fling(level, at, state, velocity)` throws any block state, and `ElpeRubble.flingBlock(level, pos, velocity)` rips a real block out and throws it.

The developer self-test exercises rubble settlement and cleanup. Re-run it on the target runtime before relying on a previous version's result.

## Java API

`ElpeLevelBoss.of(serverLevel)` returns the level's `ElpeKoperWorld`, creating it the first time. It is ticked automatically after `ServerLevel.tick`. Block changes reach it through a `LevelChunk.setBlockState` mixin. The physics world is created on demand.

```java
ElpeKoperWorld w = ElpeLevelBoss.of(level);
int a = w.spawn(x, y, z, 0.25f, 1f, 0);            // radius, inverse mass (0 = static), group
int b = w.spawn(x + 1, y, z, 0.25f, 1f, 0);
w.joint(a, b, 1f, 1f, 1f, 0f);                     // min, max, stiffness, snap (0 = never breaks)
w.pin(a, x, y + 3, z, 3f, 1f, 0f);                 // rope to a world point
w.weld(a, b, 1f, 0.5f);                            // stick at the current distance, snaps if stretched 0.5
w.push(b, 0, 10, 0);                               // blocks per second
int[] loaded = w.spawnMany(xyz, 0.25f, 1f, 0, true); // true = spawn asleep, for loading saves
MemorySegment pos = w.positions();                 // zero copy xyz floats per id, refetch every tick
MemorySegment moved = w.awakeIds();                // what moved this tick, sync only these
```

**Groups:** points with the same non zero group do not collide with each other. Use one group per body so its own points do not fight their joints.

**Joint recipes:** `min == max` is a stick, `min = 0` is a rope, `stiffness < 1` is a spring, and `snap > 0` breaks the joint when it is stretched past that distance.

**States:** `DEAD 0`, `AWAKE 1`, `ASLEEP 2`, `FROZEN 3` (waiting for terrain).

Tuning goes through `ElpeTuning` (`KOPER_DEFAULT` uses gravity −28 like khysics, 2 iterations, 4 joint passes, and sleep after 20 still ticks).

## C ABI

Everything is `koper_elpe_*` in `engine/koperlib-elpe/src/lib.rs`, and each function carries a one line comment on its layout. The world is an opaque pointer. Nothing calls back into Java: Java pulls terrain requests with `koper_elpe_requests` and answers with `koper_elpe_section`. That keeps the engine usable outside MC too (`floor_y` gives a flat floor without voxels).

## Klocs: running khysics on elpe

Elpe can stand in for Rapier as the khysics physics backend. A **kloc** is a kontraption: a pile
of MC blocks that slides around and **never turns**. That one rule is what makes it cheap: every
block stays axis aligned, so its collider is the unit cube it sits in and terrain collision is a
bitset lookup. No inertia tensor, no quaternion integration, no angular solver.

The only thing that turns is a kloc hanging on a hinge, around that joint's single axis, stored as
one `f32`. That is the wheel case. Everything else reports an identity rotation, forever.

```
/koperlib engine elpe     # then reload the world
```

Supported: kontraptions, terrain and kloc-vs-kloc collision, revolute (hinge) and prismatic
(slider) joints with limits and velocity motors, raycasts, sleeping, parking.

Not supported (no-ops rather than errors): rotation of a body, aerodynamics,
wind, water and buoyancy, per block materials and collision shapes, bodies splitting into pieces
when cut, torque, self-righting, and profiling numbers. Position motors are faked on top of the
velocity motor: fine for a piston, not for a servo.

A vehicle built for Rapier will run, and it will look stiffer: the chassis keeps its orientation
whatever the ground does, and only the wheels spin.

`koper_elpe_kloc_*` in `lib.rs` is the C ABI, `kloc.rs` is the solver, and `ElpeKoperer` on the
Java side is a thin wrapper that maps the khysics vocabulary onto it.

## Limits, honestly

- **No rotation, by design.** Orientation of a body has to be derived from its points (for example, two points give a direction).
- **Terrain is full cubes.** Any block with a non empty collision shape counts as solid, so slabs, stairs and fences are cubes. Modded blocks whose shape needs a real level are guessed solid.
- **No point/entity interaction yet.** Players and mobs do not push points and points do not push them. `querySphere` exists to build that on.
- **No client renderer.** `show` is a debug hack with particles. A real renderer would read `positions()` on the client after a sync. Nothing is synced to clients today.
- **No persistence.** A world is gone on server stop. `spawnMany(..., asleep=true)` is the hook for a save loader.
- Jacobi contacts are soft in tall stacks. Height based mass scaling (shock propagation) keeps a 6 high tower standing, but a 50 high one will squish a bit. More `iterations` helps.
- Joints are solved on one thread. Millions of joints will cost milliseconds.
- `max_step` (0.45 blocks per substep) caps speed at about 18 blocks per second with 2 substeps. Faster things need more substeps.

## Porting onto another koperlib tree

Elpe was written against the koperlib checkout in mods-src and is meant to be copied onto a newer tree (the one on the PC) without a merge fight. Almost everything is new files:

```
engine/koperlib-elpe/            the whole crate (no deps)
modules/koperlib-elpe/           the whole java module
docs/ELPE.md
```

The only edits to shared files are one line or small hunks:

1. `engine/Cargo.toml`: add `"koperlib-elpe",` to `members`
2. `settings.gradle`: `include 'koperlib-elpe'` plus its `projectDir` line
3. `build.gradle`: `elpe: 'koperlib_elpe_engine'` in `nativeModules`, `dependsOn ':koperlib-elpe:build'` in `build`, `include project(':koperlib-elpe')` in the jar-in-jar list
4. `docs/README.md` and `docs/KOPERLIB.md`: one index entry and one short section

From core, elpe uses only `KoperModuleNative.load/function/loaded`, `KoperCommands.register`, `KoperModules.register` and `KoperCore.LOGGER`. If any of those were renamed on the newer tree, `ElpeNativeKoperer` and `ElpeMod` are the only two files that need touching.

With git: `git fetch <this repo> elpe && git cherry-pick <the elpe commits>`. The conflicts, if any, will be in those four shared files.

## Dev self test

`ELPE_SELFTEST=1 ./gradlew :koperlib-elpe:runServer` force loads the chunks around 0,0, drops a ball, a 1000 point pile and a 20 link rope, breaks the block under the ball and logs every step with `[ElpeSelfTest]`.

On proot-on-Android the koperlib native loader thinks it is running on Android (`/system/build.prop` exists) and copies the bionic `.so` over `engine/target/release`. For a proot dev run, build without the android native first.

*Claude AI used for documentation.*
