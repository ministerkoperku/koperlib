# Khysics

Khysics turns groups of blocks into rigid bodies. They move, rotate, collide with the world and each other, take joints and motors, carry players, and fly when given wings. The simulation is a Rapier fork running in Rust; Java owns the world and the players.

A kontraption (spelled kontra in the code and the commands) is one such body.

Overview material is in [KOPERLIB.md](KOPERLIB.md). This file covers the physics API and content settings. The `/koperlib physics`, `/koperlib engine` and developer probe commands shown here require Koperstuff, which is excluded from the aggregate jar.

## Contents

1. [Status](#status)
2. [Making one](#making-one)
3. [Block properties](#block-properties)
4. [Aerodynamics](#aerodynamics)
5. [Buoyancy](#buoyancy)
6. [Dimensions and gravity](#dimensions-and-gravity)
7. [Joints and motors](#joints-and-motors)
8. [Riding](#riding)
9. [From Lua](#from-lua)
9a. [From Java: the vehicle toolkit](#from-java-the-vehicle-toolkit)
10. [Commands](#commands)
11. [Block identity and synchronization](#block-identity-and-synchronization)
12. [Which engine it runs on](#which-engine-it-runs-on)
13. [Saving and loading](#saving-and-loading)
14. [Limits and how to get around them](#limits-and-how-to-get-around-them)

## Status

Experimental and on by default. `"enablePhysics": false` in `config/koperlib/khysics.json` turns it off, and `/koperlib config reload` applies that immediately in both directions.

The API is evolving. Rapier, aerodynamics, joints and riding have implemented paths; the remaining interaction, fluid and shader limitations are tracked in Task 22.

## Making one

Two ways.

**By selection.** Use the khysics wand to mark two corners, then `/koperlib physics make`. Every block in the cuboid is pulled out of the world and becomes one body. `/koperlib physics make <from> <to>` does the same without the wand. A selection over 4,000,000 cells (`KoperPhys.MAX_CUBOID_CELLS`), or one that reaches into unloaded chunks, is refused with the reason (`KoperPhys.cuboidProblem`). Before that limit existed, a corner typed as `0 0 0` walked billions of cells, generated every chunk on the way, and froze the server for good.

**From code.** `KoperPhys.makeKontraktion(level, positions)` takes a list of block positions and returns the kontraption id, or `-1` if it refused.

What happens on creation: the blocks are removed from the world, their block entities and any koper local data are captured and carried along, collision shapes are built from the real voxel shapes, and total mass comes from the per block properties described below.

`maxKontraktionBlocks` in the config caps how big one can be. It defaults to unlimited.

## Block properties

Physical behaviour per block comes from a weight book in the pack. Rapier only knows about mass and friction; the weight book tells it what each block is made of.

```json
{
  "khysics": {
    "block_props": [
      {
        "selector": "mypack:iron_plate",
        "mass": 8.0,
        "friction": 0.7,
        "restitution": 0.1,
        "fragility_impulse": 2000.0
      },
      {
        "selector": "mypack:wing_panel",
        "mass": 1.5,
        "lift": 2.4,
        "drag": 0.9
      },
      {
        "selector": "mypack:gasbag",
        "mass": 0.2,
        "balloon": 6.0,
        "buoyancy_volume": 4.0
      },
      {
        "selector": "mypack:wheel",
        "mass": 3.0,
        "wheel": true,
        "friction": 1.4
      }
    ]
  }
}
```

Fields:

* `selector` which blocks this applies to
* `mass` kilograms per block. This is the number that decides whether a machine flies or falls.
* `friction` surface friction, default 0.6
* `restitution` bounciness, default 0.2
* `fragility_impulse` impulse above which the block breaks off. Default is effectively never.
* `drag` air resistance contribution
* `lift` lift coefficient. Anything above zero makes the block a wing.
* `balloon` static lift. Anything above zero makes the block a balloon.
* `buoyancy_volume` displaced volume for floating in fluids
* `wheel` marks the block as a wheel, which changes how friction is applied

A block with no entry gets the default: mass 1.0, friction 0.6, restitution 0.2, no lift, no drag contribution.

Set `koper_mass_custom` (the `mass` field) on the block JSON itself as a shorthand when only the weight matters.

## Aerodynamics

Three modes. The server-wide default comes from `defaultAeroMode` in the khysics config (`correct` out of the box) and can be changed live with `/koperlib physics aero default <mode>`. A kontraption follows the default until it gets its own mode with `/koperlib physics aero <id> <mode>`; `/koperlib physics aero <id> default` hands it back to the global one. Only an override is saved with the world, so changing the default moves every machine that never had one.

* `low` cheap approximation, good for large slow structures
* `correct` the default, real lift and drag from surface orientation
* `extreme` the most detailed and the most expensive

Lift comes from blocks with a `lift` value, drag from `drag` values plus the dimension's `universal_drag`. A wing produces lift proportional to its speed through the air and its angle, which means a machine with wings needs forward thrust to stay up.

Every aero block is its own panel, in `correct` as well as `extreme`. It feels the air at its own point, body velocity plus spin times its arm, so a wing on a bearing turned to an angle lifts at that angle and the tip of a spinning part moves faster than its hub. `correct` used to average every panel into one on the body's centre, which is why a rotor felt no spin at all and a tilted wing was averaged back to flat.

A panel faces the air along the thin axis of its own shape (`KoperPhys.koperPlateOf`): a layer of micro blocks, a slab or a plate lifts across its thickness with the area of its big face, and sits where the plate really is inside its cell. Anything chunky keeps local up and one square, as before. Micro grid edits push the aero list again.

A plate lying flat in the plane it spins in, on a body turned by a revolute joint, counts as a propeller blade. Blocks cannot be twisted, so each blade gets the angle of a helical prop: `atan(PROP_PITCH / radius)`, steeper near the hub, so every part of the blade stops pulling at the same forward speed, `PROP_PITCH` (0.6) blocks per radian of spin. At 60 rad/s that is about 36 m/s. A fixed flat angle stopped pulling at about 12 m/s, below any wing's flying speed, which is why nothing took off. A prop's blade twist follows the way the rotor actually turns relative to its bearing, times a fixed blade hand. The hand is +1 (normal) unless an addon selects mirrored blades with `KoperPhys.setJointBladeHandedness(joint, -1)`. Motor commands never change the hand. Reversing the spin of a normal prop therefore reverses its thrust, and a counter-rotating pair pulls the same way only when one of the two has mirrored blades, like a real handed pair. The hand is kept with the joint: `JointSnap.bladeHand` carries it through assembly snapshots and lift schematics, and the world save (format 11) stores it, so a restored mirrored prop does not run its first steps with normal blades. The spin direction used for the twist is the rotor's turn on its own bearing, not the whole body's angular velocity, so an airframe that rolls or yaws does not tilt a slow prop out of propeller mode. A chunky block has no face of its own: Java sends a zero normal, and the engine flags it `chunky`. On a prop spinning faster than 5 rad/s it is a blade lying in the spin plane; anywhere else it is a horizontal plate. Before this, a prop built from full blocks was a paddle wheel that only fought its motor. Plates standing across the spin are paddles and push nothing. A rotor body keeps only the torque about its own axle, which is the drag its motor pays. These rotor rules (blades across the axle, the pitch, torque about the axle only, face drag included) apply in `correct` and in `extreme` alike. Until 2026-09-27 they existed only in `correct`: under `extreme` a prop of full blocks was a stack of plates facing up that barely pulled, and its off-axis torque turned the whole plane. `low` keeps one lumped wing but still turns its motor power into thrust through propeller blades; it has no flaps.

In `correct` mode the same flat plate rule applies to wing surfaces past stall: a surface swung beyond its stall angle keeps no more lift than drag times cot(angle), so a control surface driven into stall slows the craft instead of throwing it around. `extreme` keeps its own separation model for wings. Two rules keep a rotor from taking energy it does not have. Past stall a block blade is a flat plate, and a flat plate cannot beat lift over drag of cot(angle). Only the angle that the inflow adds beyond the blade's own twist counts, so the tuned behaviour on a bench and in normal forward flight is unchanged. Without this floor the material gain made a stalled blade an almost lossless autorotor, and air arriving from the pressure side wound a prop up to the 120 rad/s clamp, with the engine running or switched off. The second rule is the rotor step. A light rotor's blade load changes steeply with its speed, and applied explicitly it overshot every step, so the prop flipped between two speeds (for example 54 and 66 rad/s) and the thrust shook. The axial blade torque is integrated linearly implicit instead: the engine reads the slope of the blade load from a second evaluation with a slightly faster spin, solves the end of step speed together with the joint's velocity motor, and applies the load that lands there. At a steady speed the correction is zero, so the real load is untouched. The solve never carries a rotor through zero into the other direction, because the blade twist changes sign there. Only bodies whose blades work as a propeller in that step use it; a wing swung by a servo is not a rotor and keeps its full aerodynamic force. An unpowered prop in still air runs down within a few seconds, and an unpowered prop in steady inflow settles near inflow speed divided by `PROP_PITCH`, like a windmilling fixed pitch prop. A propeller's motor turns it against the air and pushes its airframe the other way just as hard. Game propellers turn several times slower than real ones for the same power, so that reaction is several times stronger than on a real aircraft; at full strength it took about half of the aileron authority in one direction. `propellerReaction` in the khysics config (default `0.25`, range 0 to 1) is the share of it that reaches the airframe while the motor drives the propeller its own way; the rest is handed back to the airframe. Thrust, power and propeller speed do not change, an unpowered propeller is unaffected, and `1` restores the full reaction. A blade material's propeller coefficient stands for blade area and airfoil that a block cannot show: blade lift takes the full gain, blade drag its square root. That makes a block prop much cleaner than a real one on purpose, because Mechanics Dream's gravity is about three times real and block airframes have high drag; with lift and drag both at the full gain a typical plane no longer left the ground. The material gain stays off the disk's sideways force. A prop meeting the air at an angle pushes sideways (its normal force), and that force sits far ahead of the centre of mass, where it works against the tailplane. With the gain in it reached 17% to 36% of thrust at 8 degrees; on a typical tractor plane at full power it outweighed the whole tail about two to one, so the plane was statically unstable in pitch and stood itself on its tail without any input. The sideways resultant is now that of plain, ungained block blades, about 0.04 times thrust times the angle in radians on the bench (0.13 with the old square root rule). Thrust and the drag the motor pays are unchanged.


In `correct` every other body feels the full torque of every panel about its centre of mass, so a tailplane keeps the nose into the wind and an elevator on a bearing can pitch the plane. Horizontal surfaces get a few degrees of built-in incidence (0.06 rad). Surfaces more than 1.5 blocks behind the centre of mass along the flight path get -0.02: the tailplane decalage of a real plane, without which a flat tail trims the plane at zero lift and it dives. Upright surfaces such as fins get no incidence, so they do not push the plane sideways. A plane needs a tailplane and a fin behind the centre of mass, as a real one does.

The force and torque caps (12 times the weight) use the mass of everything the body is jointed to, world joints excluded, so a light rotor can pull the machine it sits on.

## Buoyancy

`buoyancy_volume` is displaced volume. A block with volume and low mass floats. `balloon` is different: it is static lift that works in air, not just fluid, so a gasbag lifts without moving.

Combining a small `mass` with a large `balloon` gives an airship. Combining `lift` with thrust gives a plane. Both work.

## Dimensions and gravity

Per-dimension physics overrides live in `khysics/dimensions/*.json` inside an enabled fullpack. Each file configures one dimension:

```json
{
  "dimension": "mypack:space",
  "gravity": [0, 0, 0],
  "universal_drag": 0,
  "flight": {
    "enabled": true,
    "min_body_y": -10000,
    "max_body_y": 10000,
    "max_speed": 10000,
    "vacuum_momentum": true
  }
}
```

Gravity is a vector in blocks/s². The default is `[0, -28, 0]`. Ambient drag defaults to `0.01`; it scales native body damping and aerodynamic density relative to that default. Zero disables ambient damping and aerodynamics. Water and solid collisions still act. `vacuum_momentum` retains inherited occupant momentum in air; it does not disable vanilla player gravity or water/contact friction.

Fullpack [space bodies](KOPERLIB.md#dimensions-and-space) move whole assemblies with their passengers between a space dimension and planet dimensions through the same assembly transfer. The destination must allow the assembly's position under its own flight bounds, so a space dimension with bodies far from y 0 needs `min_body_y` and `max_body_y` wide enough.

A dimension file can also describe its air, water, aerodynamics and wind. Every key is optional; a missing key keeps the overworld default shown here.

```json
{
  "dimension": "mypack:mars",
  "gravity": [0, -10.6, 0],
  "universal_drag": 0.01,
  "air": { "density": 0.15, "sea_level": 64, "scale_height": 180, "min_density": 0.001 },
  "water_density": 1.0,
  "aero_mode": "extreme",
  "wind": {
    "speed": 3.0,
    "direction": 60,
    "vertical": 0,
    "gust": 0.35,
    "gust_period": 7,
    "veer": 40,
    "veer_period": 900,
    "rain": 1.8,
    "thunder": 3.5,
    "storm_gust": 2.5,
    "height_gain": 0.006,
    "max_height_factor": 2.5,
    "biomes": { "minecraft:desert": 2.0, "#minecraft:is_forest": 0.5 }
  }
}
```

`air.density` multiplies the density every aerodynamic and balloon force uses. Density falls as `exp(-(y - sea_level) / scale_height)`, never below `min_density` times the sea level value. `water_density` sets the buoyancy of this dimension's water. `aero_mode` is the default for bodies in this dimension. A body's own override (`/koperlib physics aero <id> <mode>`) still wins over it, and the global default (`/koperlib physics aero default <mode>`) applies only in dimensions without this key. A file that fails validation is rejected whole and logged as an error naming the file.

Wind is in blocks per second. `direction` is where it blows toward, in degrees clockwise from north. `vertical` is an updraft (negative pulls down). Every four ticks each body gets the wind at its position: the base speed times the first matching `biomes` entry (an id, or a `#tag`), times `rain` scaled by the rain level and `thunder` scaled by the thunder level, times `1 + height_gain * (y - sea_level)` clamped to `max_height_factor`. Gusts vary the speed by up to `gust` (multiplied by `storm_gust` during thunder) with a period of about `gust_period` seconds; their phase shifts with position, so separate craft do not lurch together. The heading swings by up to `veer` degrees over `veer_period` seconds. The engine eases each body toward its new wind over about half a second. Without a `wind` object, or with `universal_drag` 0, there is no wind. KoperLib ships no dimension files; Mechanics Dream's fullpack provides the overworld wind. `wind: false` in the khysics config turns wind off everywhere without editing any pack.

Fast flight requires explicit finite bounds and a positive `max_speed` no greater than 10000 blocks/s. These values configure the simulation; they are not a guarantee that every hull or client works at that speed. Ordinary dimensions retain the -2048..6000 body-origin altitude bounds and the existing displacement guard. No space dimension, planets, rockets or automatic portals are created by this setting.

In fast mode, the native worker checks swept terrain coverage before integration. Unknown sections hold the connected assembly without erasing its velocity. Collision sections outside the dimension's build height are known empty. Each body's corridor is limited to 512 sections; oversized corridors hold rather than skip collision. Rotating hull bounds cover every block from the first step. Native CCD handles thin obstacles and moving bodies. Minecraft chunk preparation remains on the server thread.

Standing players negotiate a body-relative movement session after geometry arrives and after vanilla teleport acknowledgement. The server validates body, dimension, epoch, sequence, finite coordinates and the player's own movement budget, then resolves that movement against the cabin. Accepted own travel is bounded to 1.5 blocks per server tick, with at most four ticks of elapsed movement budget. Seats retain their local mount anchors. Rendered player placement and targeting follow the hull's interpolated frame. Server interaction reach and grid-container range use the authenticated anchor in the current hull frame, so rigid travel does not count as distance from a clicked block. Building and use packets retain local block keys. Departure uses native linear and angular point velocity.

Fast-mode non-player occupants and seats retain direct tracking while vanilla changes chunk visibility. Their current chunks receive nonpersistent loading/simulation tickets expiring after 20 ticks (radius two); the current chunk is loaded before positioning. This prevents hidden occupants from being dropped from carry or transfer collection. Chunk generation can still stall the server; known-empty native terrain does not make vanilla entity chunks free.

`KoperPhys.transferAssembly(root, target, where, yawQuarters)` stages the complete connected assembly and its seated/tracked occupants. It preserves block entity contents, local data, body stash, scheduled grid ticks, aero overrides, joint settings and exact linear/angular velocity; destination yaw rotates velocities. The destination must be loaded and clear. After commit it stays parked until occupants are registered and players acknowledge teleport and geometry initialization; then exact motion resumes. Refusal returns `null` and restores the source. Failed occupant return removes destination copies, keeps the source parked and transfer-locked, and retries recovery on subsequent server ticks.

Transfer callbacks receive the old-to-new body remap after destination geometry and occupants are ready. Source bodies are removed afterward. Callback exceptions are logged; arbitrary external addon side effects cannot be rolled back. Addons requesting the same transferred seat can reuse its staged mount. Generic seats remain transient across save/reload; addons continue to recreate their own seats through the existing spawn/restore hooks. Body inventories and joints use the existing persisted format.

The Koperstuff developer module exposes `/koperlib flight probe <speed>`, `board`, `check`, `refuse`, `transfer`, `resume` and `stop`. The console-only alias is `koper-flight`. Use a disposable world with flight enabled in the source and End. The probe creates a bounded cabin, a jointed part, chest cargo and standing/seated mobs at `(8,1000,8)`. `refuse` injects an occupant teleport refusal; `resume` checks saved cargo and joints after restarting the server. The probe keeps a bounded recent set of source/target chunks ticking, warms up before acceleration and logs local rider error. These diagnostic chunk loads are not a void-performance benchmark. Player walking, jumping, rotation and network-delay trials require a connected client.

Coordinates still pass through native floats; precision degrades far from the origin. There is no floating-origin implementation. Fast flight requires matching Java and native builds; older native libraries and other backends can refuse it.

## Joints and motors

```
/koperlib physics joint <a> <b> <axisX> <axisY> <axisZ>
/koperlib physics motor <jointId> <velocity> <torque>
/koperlib physics jstate <jointId>
```

A joint is revolute: two kontraptions pinned on an axis, free to rotate around it. Add a motor and it drives. `velocity` is target angular speed and `torque` is how hard it will push to get there. A motor with high torque and low velocity is a winch; low torque and high velocity is a fan.

`KoperPhys.jointState(joint)` returns `[angle, speed, turned]`. `angle` is Rapier's, wrapped to ±π. `turned` is the same angle counted past a full revolution: the engine unwraps it after every physics step, where one step never moves a joint half a turn. Java samples once a tick and a throttled server skips steps, so unwrapping on the Java side guessed the wrong way round on anything fast. That is what a servo aiming at 270° or 720° needs. The elpe backend returns only the first two.

Gyroscopic forces are off. Rapier integrates them explicitly, and on a thin disc spinning near 100 rad/s (every driven wheel) that pumped energy in until the car jumped into the sky. A rotor's reaction torque on the hull still comes through its joint motor, so a single helicopter rotor still spins the hull and a counter-rotor still cancels it; only precession is missing. The engine clamps angular speed at 120 rad/s; it used to be 40, which choked every propeller long before its motor ran out.

The anti-clip pass, which lifts a body whose block centre sits deeper than 0.45 inside terrain, skips any body held by a world joint: the joint holds it, so it cannot have fallen through anything. A bearing's turning half sits on purpose in the cell behind its plate, inside the wall or floor the bearing stands on. It used to be lifted 0.35 every five steps and pulled straight back.

Joined bodies form an assembly. Freezing, saving and teleporting operate on the whole assembly, not one piece, so a machine does not tear itself apart when it is moved.

## Riding

Seats attach a player to a kontraption:

```
/koperlib physics seat <id> <localX> <localY> <localZ>
```

Coordinates are local to the kontraption, so the seat moves with it. A riding player gets their movement validated softly rather than by vanilla rules, and fall damage from the body's motion is suppressed, because otherwise landing an airship kills everyone on board.

`kontraCameraMode` in the config chooses whether the camera stays world aligned or rotates with the body.

On the client, a seated player and their camera are drawn where kender draws the seat in that frame (`KontraRideClient.renderRideOffset`), not where vanilla's own interpolation of the seat entity would put them. The seat takes the body's packet pose once a tick. Vanilla drew the rider one tick behind the ship, a whole tick of travel off (0.6 blocks at 12 blocks/s), and flipped between that and the right spot from frame to frame. Omni's client phase measures it on a flying ship (`omni/ride_frames.csv` has every frame).

## From Lua

The namespace split is not the obvious one. **`koper.kontra` acts on an existing kontraption by id. `koper.physics` makes new physical things.**

```lua
koper.kontra.apply_force(id, fx, fy, fz)      -- continuous push
koper.kontra.apply_impulse(id, ix, iy, iz)    -- instant kick
koper.kontra.self_right(id)                   -- rotate upright
koper.kontra.destroy(id)                      -- put the blocks back
koper.kontra.restore(id)

koper.physics.launch(entity, vx, vy, vz)
koper.physics.spawn_projectile({ entity_type = "minecraft:arrow", x = 0, y = 64, z = 0 })
```

`koper.physics.spawn_ragdoll` and `koper.physics.set_gravity_zone` are unsupported and raise descriptive errors. See [LUA_API.md](LUA_API.md).

Lifecycle events arrive on the normal bus:

```lua
koper.events.on("kontra_spawn",   function(d) end)
koper.events.on("kontra_tick",    function(d) end)
koper.events.on("kontra_destroy", function(d) end)
```

## From Java: the vehicle toolkit

`com.koper.koper_lib.physics.body` is the addon-facing side of khysics. It exists so a vehicle mod (including one ported from another ship engine) does not have to fake a physics tick from the 20 Hz server tick.

```java
KhysBody.Spot spot = KhysBody.at(level, helmPos);        // world pos, grid (block entity) pos, or assembled-from pos
KhysBody ship = spot.body();

ship.pusher((state, push) -> {                           // every 60 Hz physics step, physics thread
    push.cancelGravity();                                // hover
    push.localForce(new Vector3d(0, 0, throttle * state.mass() * 4));
    push.angularAcceleration(state.omega().mul(-2));     // damp the spin
});

ship.hold(force, torque);      // or: a steady push kept every step until changed, decided at 20 Hz
ship.kick(impulse); ship.kickAt(impulse, worldPoint); ship.spin(angularImpulse);
ship.gravityScale(0.5); ship.buoyancyScale(3); ship.damping(0.2, 0.2); ship.parked(true);
ship.blockMass(local, 12.0);   // reweigh one block in place
ship.stash().putString("mymod:name", "Boat");            // saved with the hull in kontras.bin
KhysBodyState s = ship.state();                          // last published state, server thread
```

**`KhysBodyState`** is one step of one body: origin, rotation, velocity of the centre of mass, angular velocity, centre of mass (world and body frame), mass, world inertia tensor about the COM, the gravity it feels, how much of it is in water, sleeping/aligned/parked, and the force khysics itself already applied this step (water, aero, held push). It has `toWorld`, `toLocal`, `velocityAt(point)` and `hullFacing(lookDir)`. Everything comes straight from Rapier, so nothing has to be differentiated from poses or re-summed from the weight book.

**`KhysPusher`** runs on the physics thread, not the server thread. It reads its own fields and the state it is handed and never touches the level, entities or block entities. Anything else it needs is sampled on the server tick into fields. An exception is logged once and that step's push is skipped. A pusher dies with its body; a body restored from disk comes back under a new id (`ON_RESTORE`) and needs its pusher re-attached, same as a seat.

**`KhysPush`** collects one step's force and torque. Helpers: `force`, `torque`, `forceAt(force, worldPoint)`, `localForce`, `localForceAt`, `localTorque`, `acceleration`, `angularAcceleration`, `cancelGravity`.

Plain `applyForce` still lasts one physics step, a third of a server tick. Use `hold` or a pusher for anything continuous.

On the elpe engine the pusher runs on the server tick with the last published state and its result is held for the tick; state there is estimated from the cached pose and the weight book (no spin, no wetness).

### Related hooks

* `KoperPhysicsEvents.ON_BLOCK_CHANGE(id, local, old, new)` fires for every block change on a live kontra, state flips included (a lever, a powered block). Use it instead of re-sweeping a hull on a timer.
* `KhysWeightBook.weigher(block, (state, bookMass) -> mass)` makes mass depend on block state. When such a block changes state on a live hull, only that block is reweighed.
* `KontraSeat.sneakToLeave(ticks)` makes sneak a control instead of an instant dismount; holding it `ticks` long still gets the rider off. `KontraSeat.pilotInput()` returns the rider's keys (forward, strafe, jump, sneak, sprint, `lift()`).
* `KontraEntry.stash` is the persisted tag behind `KhysBody.stash()`. A split-off child starts with an empty stash; copy what it should inherit in `ON_SPLIT`.
* `KoperPhysicsEvents.ON_JOINT_DESTROY(jointId)` fires when a joint goes away, before it is forgotten, so `jointSpecs()` still has it. It covers both paths: `destroyJoint` and the joints Rust drops together with a destroyed body (`dropJointsOf`). Omni hooks it to name who took a joint away.

**`getKontraVelocity` is not a speed.** It returns how far the body moved since the previous server tick, a 20 Hz delta in blocks per tick. Multiply by 20 for blocks per second, or read `KhysBody.of(id).state().velocity()`, which is the real velocity of the centre of mass from Rapier. Omni spent a day chasing tyres that "slipped at 0.4 m/s" when the car was doing 0.44 blocks a tick, which is 8.8 m/s.

`KoperPhys.buildMaterials(entry)` returns what `pushMaterials` sends to the engine without sending it: 15 floats per block (friction, restitution, wheel flag, axle, micro resolution and cells, collider offset, block offset) and 11 floats per collision box. The scene dump below uses it, so a replay sees exactly the materials the game sent.

## Commands

```
/koperlib physics make                    turn the wand selection into a kontraption
/koperlib physics list                    every live kontraption
/koperlib physics destroy <id>            put its blocks back in the world
/koperlib physics tp <id>                 teleport to it
/koperlib physics selfright <id>          rotate it upright
/koperlib physics aero <id> <mode>        low, correct or extreme; `default` drops the override
/koperlib physics aero default <mode>     server-wide mode for every kontra without an override
/koperlib physics seat <id> <x> <y> <z>   add a seat at a local position
/koperlib physics joint <a> <b> <ax> <ay> <az>
/koperlib physics motor <jointId> <velocity> <torque>
/koperlib physics jstate <jointId>
/koperlib physics pause                   freeze the simulation
/koperlib physics resume
/koperlib physics step                    advance one tick while paused
```

`pause` and `step` are the debugging pair. Freeze everything, step one tick at a time, watch what moves.

### Where the time goes

With `debugMode` on, the log prints one line a second per physics world:

```
[KhysProfile] world=1 kontras=7 step=3.41ms (sections 0.22 | fluid+aero 0.31 | solver 2.44 | joints 0.18 | post 0.26) serverTick=0.90ms
```

`step` is one 60Hz physics step on its own thread; `serverTick` is what the kontraption bookkeeping costs the server thread. If `step` is fine and the game still stutters, the physics is not the problem: look at `serverTick`, the render side, or something else entirely.

With the environment variable `KOPER_CMD_LOG` set (in mechanics dream: `./gradlew runOmni -PomniCmdLog`), the physics thread prints every 600 steps how many commands of each kind it received. It is how a replay that behaves unlike the game gets compared with what the game actually sends.

### Replaying a scene offline

Omni (mechanics dream `OmniScene`) writes a failing build to `omni/scenes/*.json`: every body with its pose, velocities, offsets, masses, materials and collision boxes, every joint with anchors, axis, motor and limits, and the real terrain within 24 blocks. The Rust test `replays_an_omni_scene` rebuilds that world and steps it:

```
KOPER_SCENE=/path/scene.json cargo test --release -p koperlib-khysics replays_an_omni_scene -- --nocapture
```

`KOPER_SCENE_MOTOR=16.75:3120` switches the drive on for a scene taken before the engine ran, `KOPER_SCENE_RESEND=1` re-sends every motor once per server tick like the game does, and `KOPER_SCENE_REUPLOAD=1` uploads the floor again every step. Joints are made between unrotated bodies first and the dumped poses applied after, because joint anchors assume unrotated bodies at creation. The replay only covers the dumped terrain: a body that drives past it falls off, which is the replay's edge, not a bug.

When omni's watchdog sees a body go over 400 blocks/s or jump more than 40 blocks in a tick, it writes `omni/scenes/fling-<phase>-<id>.json` on its own. The poses in it are from the last tick the body was still calm (under 40 blocks/s, up to 10 ticks back), since a fling can build up over a few ticks, and it includes every body within 8 blocks. A joint that stays stretched for 5 ticks gets `stretch-<phase>-<id>.json` from the tick it started. The FAIL also lists the commands of any neighbour that was moving over 30 blocks/s, because that is usually what hit it. The FAIL line also carries the last twelve commands that body got through `KoperPhysBridge` (spawn, setTransform, block add/remove, impulses, joints with their anchors), recorded only when the JVM runs with `-Domni=true`. If the replay sits still, the cause was one of those commands, not the solver.

`solver` dominating with joints on the build means the extra solver iterations a joint asks for: `JOINT_SOLVER_ITERS` in `engine/koperlib-khysics/src/world.rs`. It multiplies the whole island's solver cost and it is set high (16) because that is what stopped bearings visibly wobbling. Lowering it is the first lever on a weak CPU, and it needs a wobble check on a real car afterwards.

## Block identity and synchronization

A kontraption block is keyed by its integer assembly-local position. Its floating-point offset from the body's centroid is render data. Do not reconstruct identity by rounding a render offset: live placement can make the two differ.

Spawn/geometry payloads carry local keys. Client maps, targeting, block entities and state updates use those keys. Pick shapes can differ from collision shapes through `KoperStateShape.koperPickShape`.

Pose updates and block-state updates are separate from geometry snapshots. Capability negotiation selects the compact snapshot path where supported, with a legacy spawn fallback. Content stamps and resynchronization requests repair mismatched client geometry.

Use `debugMode` in the Core configuration for diagnostics. Repeated mismatches need investigation; resynchronization does not replace correct block identity and transfer ordering.

## Which engine it runs on

Khysics is not welded to Rapier any more. Everything it needs from a physics engine is the
`PhysKoperer` interface in `physics/koperer/`, and two engines implement it:

| engine | what it is |
|---|---|
| **rapier** | the default. Full rigid bodies, real rotation, aero, water, splitting. Everything this document describes. |
| **elpe** | the cheap one, for servers with a lot of vehicles. Bodies slide and **never turn**; only a body on a revolute joint turns, around that joint's one axis. See [ELPE.md](ELPE.md). |

Pick one with `physicsBackend` in the khysics config, or in game:

```
/koperlib engine          # what is running, and what else is available
/koperlib engine elpe     # switch
```

The engine is bound to the world that was created with it, not to a global switch, so flipping
the backend only decides what **new** worlds get. Reload the world to move one across. This is
not a style preference: a Rapier world id is an index into a Rapier map and an elpe one is a raw
pointer, so handing one to the other engine used to segfault the game the moment the backend was
switched with a vehicle loaded. A world id nobody owns now routes to a no-op engine instead of a native.

If the requested engine has no native on this machine, KoperLib logs an error and uses whichever
one loaded, so a missing library downgrades physics instead of breaking the game.

Everything above the seam (render, client sync, seats, local grids, the Lua surface) reads
`KoperPhys` and never learns which engine answered. What elpe lacks is listed in its own
doc, and the short version is: no rotation, no aero, no water, no splitting.

## Saving and loading

Kontraptions go to `kontras.bin` in the world folder on every vanilla save, including autosave, and when the server stops. Details that matter when something comes back wrong:

* Nothing is written until the world's saved bodies have been loaded. Vanilla saves once while the world is still starting, and writing then used to put an empty file over everything.
* A world that had bodies keeps being written after the last one is gone. Otherwise the old file stayed and the removed bodies came back.
* The pose saved is the live one. Only a body at rest (under 5 cm of drift, same rotation) keeps the pose it settled in, so reloads do not add up settling drift. A spinning or sagging part used to keep a pose up to a block old and came back with its joint stretched.
* Files from format 10 on are restored exactly where they were. Older files still get each block lifted out of any solid terrain it sits in. That lift did not know a rotor sits in its bearing's cell on purpose, and it moved every rotor up a block on every load.
* Block states come back as saved and are not re-shaped. Leaves with no log in reach still decay later, like anywhere else.
* Air, and blocks whose mod is gone, are left out of a restored body, with a warning in the log. A body never holds air. Anything that tries to write air into one is refused and logged with a stack trace.
* Saved joints are rebuilt on the new ids. Each body is first moved onto its joint anchor if it is less than 4 blocks off, world joints first, then down the chains.
* A body that loses its last block through the grid (an explosion, a piston, a script `setBlock`) is destroyed at the start of the next tick. A block entity that does not fit its cell's block any more is dropped, not loaded onto the wrong block.

## Limits and how to get around them

**Vanilla redstone does not run inside a kontraption.** The blocks are out of the world, so the vanilla tick does not reach them. Signal routing is emulated for a useful subset. **Workaround:** drive machinery with joints and motors rather than redstone, or keep the redstone part on solid ground and only move the mechanical part.

**Grid fluids are restricted.** `KontraGrid` accepts source-fluid cells with solid hull support and suppresses their scheduled spread ticks. Kender has a fluid renderer for those cells. This is not general flowing-water simulation: bucket interactions, enclosed-fluid assembly and pumping have separate requirements.

**Block entities inside a kontraption tick, but not everything they expect is there.** A block entity that queries its neighbours will get answers from the kontraption's own block map, not the world. **Workaround:** `koper.bstate` is carried along correctly, so store the needed values there.

**World reads depend on the Java bridge.** Fullpack's `KoperPeekLua` replaces the native placeholder for `koper.world.get_block` with real world queries. Queries are synchronous and use the current script context; see [Lua reads and callbacks](LUA_API.md#reads-and-callbacks). Queries against moving local grids still need the correct context.

**Mass affects lift and acceleration.** Check mass, thrust and aerodynamic surfaces when a machine will not lift. Default mass is 1.0 per block, so a hundred block airship weighs 100 before any tuning. **Workaround:** write a weight book entry for every block the machine uses, and check `/koperlib physics list` which reports mass.

**Large kontraptions require workload testing.** Both Kender (`kender.json`) and Khysics own a `kenderBlockCullDistance` setting; Khysics also owns `kenderMaxBlocksPerKontraktion`. These control visibility and draw limits. Storage, bandwidth and LOD work remains open in Task 22; there is no established universal block-count limit.

**Restitution and stacking do not mix.** Bouncy blocks in a tall stack jitter. **Workaround:** keep `restitution` low, near the 0.2 default, for anything structural.

**The simulation runs on its own thread.** Positions Java reads are from the last completed step, so a script reading a position immediately after applying a force sees the old value. **Workaround:** apply the force and read next tick.

**Disabling physics with saved bodies.** Disassemble bodies into ordinary world blocks before disabling physics if their blocks must remain accessible. With Koperstuff installed, `/koperlib physics land <id>` uses refusal-safe restoration and can refuse an obstructed or unsupported pose. `destroy` deletes the body; it does not put its blocks back.

## Current 26.3 compatibility limits

Bucket placement, enclosed-fluid assembly, pumping and general fluid simulation are separate work areas. Source-fluid rendering does not establish that all of them work.

Entity queries and block contact callbacks have targeted grid-space repairs. Crop hydration, hanging entities, arbitrary rotation, fast movement and third-party transfer/component lifecycles still require their own checks. Large-object memory, bandwidth and LOD work remains open.

Create integration is deferred for this release. Existing conditional adapters remain in source; older 26.2 compatibility scenes do not establish support on 26.3.

*Claude AI used for documentation.*
