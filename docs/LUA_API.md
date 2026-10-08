# Lua API

Reference for the `koper` table and handler objects. Start with [KOPERLIB.md](KOPERLIB.md) for pack layout and binding scripts.

Lua bindings are supplied by the native scripting engine and Fullpack's Java bridges. Object methods use `:`, and namespace functions use `.`.

## Contents

1. [Read this first: what does not work yet](#read-this-first-what-does-not-work-yet)
2. [Objects versus namespaces](#objects-versus-namespaces)
3. [How a script is loaded](#how-a-script-is-loaded)
4. [Event handlers](#event-handlers)
5. [Entity and player methods](#entity-and-player-methods)
6. [Item stack methods](#item-stack-methods)
7. [koper.world](#koperworld)
8. [koper.gui](#kopergui)
9. [koper.pstate and koper.bstate](#koperpstate-and-koperbstate)
10. [koper.events](#koperevents), including [World events](#world-events-player)
11. [koper.quest](#koperquest)
12. [koper.kontra and koper.physics](#koperkontra-and-koperphysics)
13. [koper.particles and koper.kfx](#koperparticles-and-koperkfx)
14. [koper.commands, koper.network, koper.data](#kopercommands-kopernetwork-koperdata)
15. [koper.math](#kopermath)
16. [Limits and how to get around them](#limits-and-how-to-get-around-them)

## Read this first: what does not work yet

The following legacy entrypoints raise errors rather than implementing a feature. They should not be used by new packs.

### Raises an error on purpose

Unsupported entrypoints:

* `koper.ui.screen(title)`. Replaced by KUI. Define a page in `guis/` and open it with `koper.gui.open`.
* `koper.mixin.inject(...)` and `koper.mixin.override(...)`. A pack is hot reloaded and class loading happens once, so a mixin in a pack could never work. Use a `@KoperHook` in the pack's `java/` folder, or write a real Fabric mod.
* `koper.register_attribute(name, opts)`. Attributes must exist before the world loads. Use the `attributes` field in item or entity JSON.
* `koper.physics.spawn_ragdoll(opts)`. No ragdoll system exists. Spawn a small kontraption and push it with `koper.kontra.apply_impulse`.
* `koper.physics.set_gravity_zone(opts)`. Khysics gravity is per dimension, not per region. Set `gravity` for the dimension in the pack's khysics json.

### One thing with a sharp edge

`koper.commands.register` works, but Brigadier builds its command tree once at server start. A command declared during a reload is remembered and becomes live after the next `/reload` or relog. The call returns `true` when the command is already live and `false` when it is queued.

### Reads and callbacks

The current bridge supplies: `get_block`, `get_entities_in_radius`, `raycast`, `has_effect`, `has_tag`, `get_target`, `set_target`, `move_to`, `attack`, `play_animation`, `get_bone_transforms`, `get_inventory`, `get_main_hand`, `get_off_hand`, `get_holder`, `set_durability`, `network.on_receive`, and `koper.data`, which is saved with the world.

Reads go through the same synchronous bridge `koper.pstate` uses, so a read is a real read and a value written just before is visible immediately.

## Objects versus namespaces

Two different things, and mixing them up is the most common beginner mistake.

**Namespaces** hang off `koper` and act on the world. Call with a dot.

**Objects** arrive as handler arguments and carry their own methods. Call with a colon, because every method takes the object as its hidden first argument.

```lua
function on_use(player, pos)
  player:send_message("hi")     -- object, colon
  koper.world.set_time(0)       -- namespace, dot
end
```

`player.send_message("hi")` will not work. There is no `koper.player` and no `koper.item`.

## How a script is loaded

A script is a file. When KoperLib loads it, in order:

1. handler globals (`on_use`, `on_tick`, and the rest) are set to nil
2. the file runs top to bottom
3. every handler it defined is copied into a per file table
4. those handler globals are set to nil again

Consequences worth internalising:

**Handlers are private per file.** Two scripts in the same pack can both define `on_use`.

**Every other global is shared across the whole pack.** One VM per namespace. If `a.lua` sets `count = 0` at file level, `b.lua` sees it.

**File level locals stay reachable from the file's handlers**, because closures capture them. This is the clean way to keep state without touching globals.

```lua
local uses = 0                    -- private to this file, survives between calls

function on_use(player, pos)
  uses = uses + 1
  player:send_message("used " .. uses .. " times")
end
```

That counter resets on reload and is not saved. For anything that must survive, use `pstate` or `bstate`.

**Across packs nothing is shared**, since they are separate VMs. Use `koper.events` to talk between packs.

## Event handlers

Define them as global functions. Arguments differ per event.

```
on_use(player, pos)
on_place(player, pos)
on_break(player, pos)
on_step(player, pos)
on_hit(player, entity)
on_damage(entity, target)
on_spawn(entity)
on_tick(entity)
on_death(entity)
on_ai(entity)
on_interact(entity, player)
on_target(entity)
on_equip(player)
on_unequip(player)
on_consume(player)
on_craft(player)
```

`on_tick` on a block runs from the block entity ticker at the block's `tick.interval`. `on_ai` runs from a `script_goal` on a mob at whatever interval that goal declares.

Everything runs synchronously on the server thread. A slow handler is a slow server. There is a wall clock kill switch set by `scriptTimeoutMs`, enforced by an instruction hook inside the VM, so a runaway loop dies instead of freezing the game, but every millisecond before that is spent tick time.

## Entity and player methods

All of these are called with a colon.

### Identity and position

```
entity:get_id()                    -> string
entity:get_uuid()                  -> string
entity:get_name()                  -> string
entity:set_name(name)
entity:get_pos()                   -> {x=, y=, z=}
entity:teleport(x, y, z)
entity:get_look_dir()              -> {x=, y=, z=}
```

`get_look_direction` is an alias of `get_look_dir`.

### Health and damage

```
entity:get_health()                -> number
entity:get_max_health()            -> number
entity:set_health(value)
entity:heal(amount)
entity:damage(amount [, source])
entity:kill()
entity:ignite(ticks)
entity:freeze(ticks)
```

### Effects and tags

```
entity:add_effect(effectId, ticks, amplifier)
entity:remove_effect(effectId)
entity:add_tag(tag)
entity:remove_tag(tag)
```

`entity:has_effect(id)` and `entity:has_tag(tag)` both query the real entity.

### Player only

```
player:send_message(text)
player:say(text [, opts])
player:show_title(title [, subtitle])
player:set_gamemode(mode)
player:kick(reason)
player:give(itemId [, count])
```

`give_item` is an alias of `give`. Calling a player method on a non player entity is harmless, the command just goes nowhere.

### State queries

```
entity:is_player()      entity:is_sneaking()    entity:is_sprinting()
entity:is_on_ground()   entity:is_in_water()
```

These read fields Java injected into the entity table when the event fired. They are a snapshot from the moment of the call, not a live view.

### Data and memory

```
entity:set_data(key, value)
entity:get_data(key)
entity:remove_data(key)

entity:remember(uuid, value)
entity:get_memory(uuid)
```

`set_data` persists with the entity. `remember` is a scratch table on the entity object and does not survive the tick.

## Item stack methods

```
stack:get_id()          -> string
stack:get_count()       -> number
stack:get_durability()  -> number
stack:get_data(key)
stack:set_data(key, value)
```

`stack:set_durability(v)` applies to whichever hand holds the stack. `stack:get_holder()` returns the holding entity.

## koper.world

```
koper.world.get_time()                                  -> number
koper.world.is_day()                                    -> boolean
koper.world.set_time(ticks)
koper.world.set_weather(name)

koper.world.set_block(x, y, z, blockId)
koper.world.fill_blocks(x1,y1,z1, x2,y2,z2, blockId)

koper.world.summon(entityId, x, y, z)
koper.world.drop_item(x, y, z, itemId [, count])
koper.world.spawn_xp(x, y, z, amount)
koper.world.explosion(x, y, z, power [, fire])

koper.world.play_sound(x, y, z, soundId, volume, pitch)
koper.world.play_sound_at(x, y, z, soundId, volume, pitch)
koper.world.spawn_particle(particleId, x, y, z [, count])
koper.world.spawn_particles(particleId, x, y, z [, count])

koper.world.log(message)
```

`get_block` returns `{id=, air=, solid=, hardness=, light=, x=, y=, z=}`. `get_entities_in_radius` returns real entity objects, capped at 64 and a 128 block radius so one call cannot stall the tick.

`fill_blocks` takes integers. Everything else takes doubles for coordinates.

## koper.gui

```
koper.gui.open(player, pageId)
koper.gui.close(player)
koper.gui.hud(player, pageId [, show])
koper.gui.set(widgetId, value)
koper.gui.set_slot(slot, itemId [, count])
koper.gui.clear_slot(slot)
koper.gui.clear_all()
koper.gui.consume(slot [, count])
```

Full KUI reference lives in [KUI.md](KUI.md).

## koper.pstate and koper.bstate

The two real persistent stores. Same shape on purpose.

```
koper.pstate.set(player, ns, key, value)
koper.pstate.get(player, ns, key [, default])
koper.pstate.add(player, ns, key [, amount])     -> new total
koper.pstate.has(player, ns, key)
koper.pstate.remove(player, ns, key)
koper.pstate.all(player [, ns])                  -> table

koper.bstate.set(pos, ns, key, value)
koper.bstate.get(pos, ns, key [, default])
koper.bstate.add(pos, ns, key [, amount])        -> new total
koper.bstate.has(pos, ns, key)
koper.bstate.remove(pos, ns, key)
koper.bstate.all(pos, ns)                        -> table
```

`ns` is the pack name. It keeps two packs from colliding on the same player or block.

`player` accepts the entity object or a raw uuid string. `pos` is the position table the handler received; add a `dim` field to reach another dimension.

`add` returns a clean integer when the result is whole, so counters read `5` rather than `5.0`.

**`bstate` needs the block to have a block entity.** Any block with a `gui`, an `on_tick` or `"block_entity": true` has one. On a plain decorative block the call does nothing, because there is nowhere to put the data.

These go through a synchronous bridge, so a `get` immediately after a `set` sees the new value.

## koper.events

The cross pack bus.

```
koper.events.on(name, function(data) end)
koper.events.fire(name, data)
```

Two packs can talk without knowing about each other. Namespaced names like `mypack:door_opened` avoid collisions.

Every handler registered for a name gets called, in registration order. This used to overwrite instead, so two scripts in one pack caring about the same event meant the second one silently won. Older docs that mention that limit are out of date.

Java can fire into the same bus, and `@KoperSubscribe` methods receive bus events.

### World events (`player:*`)

KoperLib puts the whole server on this bus. These fire for every player, no script binding needed, and every one of them carries `player`.

```lua
koper.events.on("player:kill", function(e)
  e.player:send_message("that was a " .. e.id)
end)
```

| name | extra fields | when |
|---|---|---|
| `player:kill` | `id`, `name` | player killed something that is not a player |
| `player:died` | `by`, `source` | the player died |
| `player:craft` | `id`, `count` | taken out of a crafting result slot |
| `player:got` | `id`, `count` | something entered the inventory by pickup or `/give`. **Not** menu transfers: shift-clicking a crafting result or a chest stack is silent, see the note below |
| `player:advancement` | `id`, `criterion` | an advancement finished, not merely progressed |
| `player:join` / `player:left` | `id` | connection |
| `player:dimension` | `from`, `to` | changed dimension |
| `player:chunk` | `x`, `z` | crossed into another chunk |
| `player:heartbeat` | `dim` | once a second, per player |
| `player:idle` | `secs` | 90 seconds of no events and no real movement |

`player:idle` is the one worth knowing about. It fires once, then stays quiet until the player does something, so it is a "this person is stuck" signal rather than a timer. Anything else on this list counts as doing something.

`player:got` and `player:heartbeat` are the chatty ones. Do the cheap check first inside the handler.

`player:got` sits on `Inventory.add`, which a container menu never calls: a quick-move writes into the slots directly. So shift-clicking a crafting result does **not** fire it, while picking the same stack up with the mouse does, because the carried stack goes back through `add()` when the screen closes. If a handler must not miss an item, poll what the player is holding instead of trusting the event on its own.

## koper.quest

Quests are declared as JSON in `quests/` and tracked by the engine off the world events above. This namespace is for reaching into that from a script.

```lua
koper.quest.start(player, "mypack:waking_up")     -> bool, false if it can't start yet
koper.quest.complete(player, "mypack:waking_up")  -- skips the goals, runs on_complete
koper.quest.reset(player, "mypack:waking_up")     -- back to never touched
koper.quest.status(player, id)                    -> "none" | "active" | "done"
koper.quest.done(player, id)                      -> bool
koper.quest.active(player, id)                    -> bool
koper.quest.bump(player, id, goal_id, by)         -- move one goal along by hand
koper.quest.progress(player, id, goal_id)         -> number
koper.quest.list()                                -> array of every quest id loaded
```

These go through the synchronous addon path, the same one `koper.pstate` uses, so a `status` read straight after a `start` sees the new value instead of lagging a tick.

`bump` is the escape hatch. For a goal about something the engine does not report, give the goal a type nothing fires, then bump it from whichever script does know. The loader will warn about the unknown type at load, which is a nuisance rather than a problem.

## koper.kontra and koper.physics

The split is not the obvious one. **`koper.kontra` acts on an existing kontraption by id. `koper.physics` makes new physical things.**

```
koper.kontra.apply_force(id, fx, fy, fz)
koper.kontra.apply_impulse(id, ix, iy, iz)
koper.kontra.self_right(id)
koper.kontra.destroy(id)
koper.kontra.restore(id)

koper.physics.launch(entity, vx, vy, vz)
koper.physics.spawn_projectile({ entity_type =, x =, y =, z =, ... })
```

`spawn_ragdoll` and `set_gravity_zone` raise an error saying what to use instead: a small kontraption with an impulse, and per dimension gravity in the khysics json.

Kontraption lifecycle events arrive on the normal bus: listen for `kontra_spawn`, `kontra_tick`, `kontra_destroy`.

Deeper material in [KHYSICS.md](KHYSICS.md).

## koper.particles and koper.kfx

```
koper.particles.spawn(id, x, y, z [, count])
koper.particles.burst(id, x, y, z, count, spread)
koper.particles.line(id, x1,y1,z1, x2,y2,z2, steps)

koper.kfx.spawn(effectId, sx,sy,sz, ex,ey,ez)
koper.kfx.spawn_json(jsonString, sx,sy,sz, ex,ey,ez)
koper.kfx.spawn_program(specTable, sx,sy,sz, ex,ey,ez)
koper.kfx.cast(player, effectId [, range])
koper.kfx.cast_json(player, jsonString [, range])
koper.kfx.cast_program(player, specTable [, range])

-- fluent graph v2: build, mix, play, then control a live effect
local fx = koper.kfx.graph("mypack:spell")
fx:input("accent", "color", "#55ccff")
fx:include("core", "mypack:json_core", { accent = "#bb66ff" })
fx:node("shape", "koper_lib:source/ring", { radius = 2.5 })
fx:node("particles", "koper_lib:render/particles", {
  source = "shape", count = 64, color = { input = "accent" }
})
fx:output("particles"):budget(96, 40)

local handle = fx:play({
  start = { type = "entity", entity = player:get_entity_id(), socket = "main_hand" },
  finish = { 0, 80, 0 },
  parameters = { accent = "#ff8844" }
})
handle:set("accent", "#66ddff")
handle:reanchor({0, 70, 0}, {0, 70, 12})
handle:signal("charge:phase", { amount = 0.7 })
handle:detach()
handle:stop()
```

`spawn` draws between two points. `cast` fires from the player along their look direction and does its own targeting.

The fluent builder is the composable graph v2 authoring path. `register()` converts its state to the
same `version`, `id`, `include`, `nodes`, `outputs`, and `budget` shape as JSON, then validates it once;
Lua does not run every render frame. A Lua graph may include a JSON graph, and Java may then include the
Lua graph. The resulting chain is linked and compiled as one effect. See the mixed-source section in
the KFX guide.
Put reusable static declarations in `scripts/kfx/**/*.lua`; that directory is eagerly loaded after
recursive `kfx/**/*.json` content on startup and `/koperlib reload`. Startup declarations do not need
an active world/server, so they are available when the first world starts. Calls from ordinary event
scripts are dynamic declarations and are linked on their next lookup.

`koper.kfx.graph(id)` is the normal scripting tier. Its builder methods are `input`, `include`, `node`,
`link`, `output`, `on`, `budget`, `register`, and `play`; they return the builder for chaining. `play`
registers/validates the graph, accepts `start`, `finish` (or `end`), `parameters`, and optional `seed`,
then returns a live handle. A vector array such as `{1, 2, 3}` is a fixed world anchor. Explicit anchor
tables support `world`, `entity`, `bone`, and `between`, plus `socket`, `offset`, and missing policies
`detach`, `freeze`, `fade`, or `kill`. Entity sockets are `feet`, `center`, `eyes`, `main_hand`, and
`off_hand`.

Live controller impacts arrive on `koper.events.on("kfx:impact", callback)`. The event includes handle,
ordered sequence, position, normal, incoming/outgoing velocity, bounce count, hit entity/block, seed,
and controller inputs. `handle:signal(name, data)` fans out as `kfx:signal:<name>` with `handle`, `signal`,
and `data`. New KFX bridge commands are UTF-8 length checked and Base64 encoded; colons, newlines and
non-ASCII text in names/data are safe.

Effect authoring is covered in [KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md).

## koper.commands, koper.network, koper.data

```
koper.commands.run(commandString)         -- works, runs as the server
koper.network.send_to_player(uuid, msg)   -- works
koper.network.broadcast(msg)              -- works
koper.network.send_title_all(title [, sub])
```

`koper.commands.register(id, opts)` declares a real command. Answer it on the bus:

```lua
koper.commands.register("mypack:heal", { description = "top me up" })
koper.events.on("command:mypack:heal", function(d)
  d.player:set_health(20)
end)
```

`koper.network.on_receive(channel, fn)` listens for a client sending on that channel. It is the same bus underneath, so `koper.events.on("net:<channel>", fn)` is equivalent.

`koper.data` is a per world store saved next to the level. It has `set`, `get`, `remove`, `has` and `all`. One store shared by every pack, so prefix keys with the pack namespace.

## koper.math

```
koper.math.vec3(x, y, z)              -> {x=, y=, z=}
koper.math.distance(ax,ay,az, bx,by,bz) -> number
koper.math.normalize(x, y, z)         -> {x=, y=, z=}
koper.math.lerp(a, b, t)              -> number
```

Note these are convenience wrappers; plain Lua arithmetic is fine and often clearer.

## Limits and how to get around them

**Everything is synchronous.** There is no async, no coroutine that survives a tick, no timer. To do something later, use the JSON action `later` with a delay, or count ticks in `on_tick`.

**No timer API.** Count in `on_tick` against `koper.world.get_time()`, or store a deadline in `bstate`.

**Handlers on one event name run in registration order** and none of them can cancel the rest. Order between two packs cannot be expressed, so nothing should depend on it.

**World reads are bounded on purpose.** `get_entities_in_radius` caps at 64 entities and a 128 block radius, because the query crosses to Java synchronously and an unbounded sweep would stall the tick. Larger queries belong in a pack Java hook, which holds the real `ServerLevel`.

**Reads cost a round trip.** Every `get_block` is a synchronous call into Java. One per tick is nothing; a thousand in a loop is a stutter. Cache what is possible in a file local.

**A new command needs a dispatcher rebuild.** `commands.register` during a reload takes effect after the next `/reload` or relog, because Brigadier builds its tree at server start. The return value says which happened.

**Heavy loops block the tick.** The timeout kills a runaway script, but a merely slow one just makes the server slow. Move real computation to Java or Rust.

**Globals are shared inside a pack.** Use `local` unless sharing is intended.

**Nothing survives a reload except the real stores.** File level locals and everything on the entity `remember` table are gone. `koper.data`, `pstate`, `bstate`, `entity:set_data` and `stack:set_data` all survive.

*Claude AI used for documentation.*
