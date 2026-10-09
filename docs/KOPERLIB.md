# KoperLib

KoperLib is a content engine for Minecraft. Items, blocks, mobs, dimensions and menus are described in JSON, behaviour is added in Lua where needed, and Java takes over when Lua is not enough. The heavy work (physics, animation sampling, the scripting VM, the Vulkan render path) runs in Rust and talks to Java over Panama FFM.

This document covers what actually exists in the code today. Where something has a sharp edge or behaves unexpectedly, it says so.

## System status

The current version targets Minecraft 26.3. `/koperlib status` reports the loaded modules. Fullpack provides JSON content, Lua and pack Java; native Lua needs the Fullpack engine library.

Kodel is the model and animation system for blocks, mobs, armour, items and the player model; it also reads the Bedrock geometry packs used to ship. Kender, KFX and Khysics have backend and compatibility limits described in their respective guides. Their APIs are evolving; a separately versioned stable addon API is not yet defined.

## Contents

1. [Five minute fullpack](#five-minute-fullpack)
2. [Reload or restart](#reload-or-restart)
3. [Requirements](#requirements)
4. [The three tiers](#the-three-tiers)
5. [Fullpack layout](#fullpack-layout)
6. [Items](#items)
7. [Blocks](#blocks)
8. [Block entities and per position state](#block-entities-and-per-position-state)
9. [Entities](#entities)
10. [Enchantments](#enchantments)
11. [Menus and HUD (KUI)](#menus-and-hud-kui)
12. [Lua scripting](#lua-scripting)
13. [The Java tier](#the-java-tier)
14. [Persistent state](#persistent-state)
15. [World events](#world-events)
16. [Quests](#quests)
17. [Dialogue](#dialogue)
18. [JSON actions](#json-actions)
19. [Reloading](#reloading)
20. [Commands](#commands)
21. [Config](#config)
22. [Physics (khysics)](#physics-khysics)
23. [Rendering](#rendering)
24. [The native engine](#the-native-engine)
25. [Bedrock addons](#bedrock-addons)
26. [When something does not load](#when-something-does-not-load)
27. [Known limits](#known-limits)
28. [Stress testing](#stress-testing)

## Five minute fullpack

A folder in `koperlib/fullpacks/` with two files is enough for a working item.

```
koperlib/fullpacks/hello_pack/
    pack.kopermeta
    items/
        hello_sword.json
```

`pack.kopermeta` marks the folder as a pack. Without it (or a `fullpack.json`) the loader walks straight past. Everything except the file existing is optional:

```json
{
  "pack_format": "K1",
  "name": "Hello Pack",
  "namespace": "hello_pack",
  "description": "my first pack",
  "author": "koper",
  "version": "1.0.0"
}
```

Without `namespace`, the folder name is used. Other keys it understands: `koperlib_min`, `requires_packs`, `requires_mods`, and `download_links` (an array of HTTPS pages/files).

When a server requires a missing or different pack, it sends only this metadata plus a SHA-256 hash and size. The client displays the links as plain text and requires manual installation. It never downloads, extracts, installs, or executes anything received from the server. Packs containing Java or JAR files get an additional executable-content warning.

`items/hello_sword.json`:

```json
{
  "type": "sword",
  "texture": "hello_sword",
  "damage": 9,
  "attack_speed": 1.6,
  "durability": 500,
  "rarity": "rare",
  "lore": ["Warm to the touch."]
}
```

Then in game:

```
/koperlib reload
/koperlib give hello_pack:hello_sword
```

New registry content can be added during a reload. See [Reload or restart](#reload-or-restart) for the short list of things that do need one.

Want behaviour? Add `scripts/hello_sword.lua`. It binds by filename, no wiring needed:

```lua
function on_use(player, pos)
  player:send_message("swing")
  koper.world.play_sound_at(pos, "minecraft:block.anvil.land", 1.0, 1.0)
end
```

The remaining sections cover content types and runtime behavior.

## Reload or restart

**`/koperlib reload` is enough for:** textures, models, stats, shapes, hitboxes, drops, recipes, loot tables, menus, Lua scripts, pack Java, enabling and disabling packs, JSON events, and **new items, blocks and entities that were not there at launch**.

That last one is unusual and worth explaining, because most mods cannot do it. Minecraft freezes its registries after startup. KoperLib keeps them writable with a mixin that cancels the frozen check, and restores the intrusive holder cache that `freeze()` wipes, so `Registry.register` keeps working all session. That is why editing a pack feels like editing a config file.

**Removing content keeps its id: it becomes missing content.**

A registry entry is never taken out. Removing one would shift numeric ids under connected clients and turn every placed copy of a block into air. When an item or block is deleted from a pack, or its pack is disabled, the id stays and its behaviour is replaced:

* The name reads `Missing: pack:id` and the model is the missing model. Items carry a tooltip saying their Fullpack is not loaded.
* Missing items cannot be used and missing blocks cannot be placed. Scripts, JSON events and menus of the removed pack no longer run.
* Missing items appear only in the creative tab *Missing content*. `/give pack:id` still works and gives the missing item.
* A placed block keeps its block state and its block entity data, including `koper.bstate` state and inventories.
* Breaking a placed missing block drops the block itself plus the items stored in its block entity. Hardness is unchanged, so a block that could not be broken before still cannot be broken.
* Stacks in inventories and containers stay the same item with the same components.

When the pack comes back, the content works again as before. Only content built from pack JSON is covered. Blocks and items a Java mod registers itself are left alone.

This also works across restarts. Every id a pack has registered is recorded, with its block state properties, in `koperlib/registry_manifest.json`. When a world loads and a recorded id is not provided by any pack, KoperLib registers a placeholder under that id, so placed blocks and stored stacks load instead of disappearing. If the pack returns while the game is running, its content stays missing until the next restart, and the log says so. Deleting the manifest removes this protection for content of packs that are already gone.

Entities are not covered yet. On a dedicated server, a client that never had the removed pack has no placeholder for its ids, so registry sync refuses the connection; the client needs the same `registry_manifest.json` entries.

The one thing outside content: swapping the native engine binary needs a restart, because a loaded native library cannot be unloaded. That only matters when building KoperLib from source.

If something ever looks half registered after a reload, restart and it will be correct. Keeping registries writable in a running game is the least conventional thing KoperLib does, and it is the reason editing a pack feels like editing a config file.

## Requirements

* Minecraft 26.3
* Java 25
* Fabric Loader 0.19.5
* Fabric API 0.161.0+26.3

Install KoperLib Core, plus KoperLib Fullpack APIs and/or KoperLib Khysics when a mod or Fullpack requires them. Core carries Core, Specific and Kender; Fullpack APIs carries Fullpack, Kodel and Effects; Khysics carries Khysics and Elpe. Koperstuff is a developer mod built from source. See [module dependencies](ECOSYSTEM.md).

Native libraries are extracted to `<game directory>/.koperlib/natives/<module>/<platform>/`. Features requiring a missing native remain unavailable and report the load failure. Kodel's sampler has a Java fallback. See [building and native packaging](BUILDING.md).

## The three tiers

The whole design is one ladder. Each rung is additive, so reaching the one above never means rewriting the rung below.

**JSON** says what a thing is. Stats, textures, models, drops, shapes.

**Lua** says how it reacts. Event handlers that run when something happens.

**Java** takes over when Lua cannot do it. Compiled from inside the pack, gets real Minecraft types.

Every event dispatch runs Java first, then Lua, then the JSON `events` block. If the Java handler returns anything other than `PASS`, Lua and JSON never run for that event. That is how one single behaviour is overridden without deleting the rest of the pack.

```
fireHook("mypack:sword/on_use")   Java, if it exists
  returns PASS?
    yes  -> run the Lua on_use
          -> run the JSON events.on_use
    no   -> stop here
```

Use JSON for definitions, Lua for event logic, and Java where access to Minecraft types or an extension API is needed.

## Fullpack layout

A fullpack is a folder (or a zip) in `koperlib/fullpacks/`. The layout is flat:

```
my_pack/
  pack.kopermeta
  items/
  blocks/
  entities/
  dimensions/
  recipes/
  loot/
  guis/
  quests/
  books/
  dialogs/
  scripts/
  java/
  textures/
  models/
  sounds/
```

Older nested layouts (`data/items/`, `koperlib/items/`, `jsons/items/`) still load, so nothing has to be migrated.

`pack.kopermeta` declares the pack. The most important field is the namespace, which defaults to the folder name. Every id in the pack is `<namespace>:<name>`.

Scripts bind by filename. A file at `scripts/flame_sword.lua` automatically attaches to `items/flame_sword.json`. No wiring, no reference in the JSON. For a different name, set `logic` or `scripts` in the JSON explicitly.

The loader scans these folder names, in singular or plural, for the obvious content types: `items`, `blocks`, `entities` / `mobs`, `dimensions`, `recipes`, `loot` / `loot_tables`, `advancements`, `attributes`, `effects` / `status_effects`, `enchantments`, `potions`, `sounds`, `spawn_eggs`, `creative_tabs`, `guis`, `quests`, `particles` / `fx` / `vfx`, `portals`, `scripts`, `models`, `textures`.

## Items

`items/my_sword.json`:

```json
{
  "type": "sword",
  "texture": "my_sword",
  "damage": 7,
  "attack_speed": 1.6,
  "durability": 800,
  "rarity": "epic",
  "creative_tab": "combat",
  "lore": ["Warm to the touch."]
}
```

`type` picks the behaviour class. The available types are fixed in code: `sword`, `axe`, `pickaxe`, `shovel`, `hoe`, `spear`, `trident`, `bow`, `crossbow`, `shield`, `helmet`, `chestplate`, `leggings`, `boots`, `armor`, `food`, `drink`, `material`, `gem`, `block`, `bundle`, `brush`, `spyglass`, `portal_igniter`, plus the physics tools (`khysics_wand` and friends). A pack cannot invent a new type. Java mods can, through `KoperItemTypes.register`.

Available fields, grouped by what they do:

**Combat and tools.** `damage`, `attack_speed`, `mining_speed`, `durability`, `tool_tier` (or a `custom_tool_tier` object), `reach`, `attack_aoe_radius`, `cooldown_ticks`, `enchantability`, `glint`.

**Armor.** `defense`, `toughness`, `knockback_resistance`, `slot`, and for a 3D model instead of a flat texture: `model`, `armor_texture`, `armor_animation`, `armor_scale`, `armor_offset`, `armor_rotate`, `armor_bones`, `bone_placement`, `render_bones`.

**Food.** `food_hunger`, `food_saturation`, `always_edible`, `effects`, `consume_effects`.

**Projectiles.** `projectile_speed`, `draw_time`, `throwable`, `texture_in_hand`.

**Appearance.** `texture`, `item_display` (`texture` for a flat icon, `model` for a 3D geo model), `tint`, `dyeable`, `item_scale`, `item_offset`, `max_stack`, `fireproof`, `lore`, `item_states` and `item_animations` for models that change with state.

**Behaviour.** `logic` and `scripts` for Lua, `events` for JSON actions, `attributes` for raw attribute modifiers, `on_use_effects`, `on_hit_effects`, `on_hit_command`, `consume_on_use`, `right_click_drop_xp`.

### Naming things in more than one language

`name` and `lore` are the fallback text, and they land in `en_us`. A `lang` block adds any other
language beside them:

```json
{
  "type": "material",
  "texture": "essence_core",
  "name": "Essence Core",
  "lore": ["An empty core.", "It ripens into whatever surrounds it."],
  "lang": {
    "pl_pl": {
      "name": "Rdzeń esencji",
      "lore": ["Pusty rdzeń.", "Dojrzewa w to, co go otacza."]
    }
  }
}
```

Lore lines are emitted as translation keys (`item.<namespace>.<path>.lore.<line>`), not as literal
text, so a line written once can be said in every language. A pack that never writes a `lang` block
keeps working exactly as before: whatever it put in `name` and `lore` becomes the `en_us` entry, and
Minecraft falls back to `en_us` for any language that is missing a key.

The same `lang` block works on blocks and entities, where only `name` is read: a block name also
becomes its item name, and an entity name also feeds its spawn egg.

Language codes are Minecraft's own, lower case: `en_us`, `pl_pl`, `de_de`. A capitalised file name
like `pl_PL.json` is never loaded, which is the usual reason a translation "does not work".

## Blocks

`blocks/forge.json`:

```json
{
  "hardness": 3.5,
  "resistance": 6.0,
  "texture": "forge",
  "mining_tool": "pickaxe",
  "mining_level": 1,
  "requires_tool": true,
  "light_level": 12,
  "sound": "stone",
  "gui": "mypack:forge_menu",
  "events": { "on_tick": [] },
  "tick": { "interval": 20 }
}
```

**Physical properties.** `hardness`, `resistance`, `light_level`, `sound`, `slipperiness`, `speed_factor`, `jump_factor`, `bounce`, `collidable`, `transparent`, `replaceable`, `random_ticks`, `ignited_by_lava`, `no_terrain_particles`, `mass`.

**Mining.** `mining_tool` (`pickaxe`, `axe`, `shovel`, `hoe`), `mining_level` (0 wood through 4 netherite), `requires_tool`, `drops_self`, `drops`.

**Shape.** `shape` accepts `cube`, `slab`, `stairs` or `cross`. For anything else, give `hitbox` and optionally `collision_box` as boxes in pixel coordinates from 0 to 16. With a bound model the shape can come from the model instead.

**Appearance.** `texture`, `texture_faces` for per face textures, `render_type` (`solid`, `cutout`, `translucent`), `tint`.

**States and connections.** `state_properties` and `default_states` define blockstate properties. `connect_group` makes neighbours of the same group link up like pipes or casings, with `connect_vertical` to restrict it to the four horizontal sides, and `connected_textures` to swap textures per connection shape. The link state rides vanilla's `updateShape`, so it re-evaluates on any neighbour change (piston, explosion, `/fill`, worldgen, a kontraption eating the block next door), not just on hand place and hand break.

**Redstone.** `redstone_power` emits a constant signal from 0 to 15.

**Behaviour.** `gui`, `container`, `drop_container`, `tick`, `events`, `logic`, `scripts`, `block_entity`.

## Block entities and per position state

By default a pack block is just a block. It has no storage of its own. That is fine for decoration and terrible for machines, so blocks can opt into a block entity.

A block gets one when any of these is true:

* it has a `gui`
* it has an `on_tick` event
* the JSON sets `"block_entity": true`

`"block_entity": false` forces it off.

What a block entity provides:

**A real inventory.** The inventory is an ordinary Minecraft container. Hoppers can insert and extract, and comparators read its fill level. Third-party transfer APIs require their own compatibility checks.

**Per position state.** The `koper.bstate` API, described below.

**A real ticker.** Blocks with a block entity tick themselves instead of being found by the proximity scan. This matters a lot: see [Known limits](#known-limits).

Flipping `block_entity` in JSON works on a plain `/koperlib reload`, in both directions. Nothing about the brain is cached on the block object; it is asked from the registry every time. Turn it on and blocks already placed in the world grow one the next time something asks. Turn it off and the ones that exist are dropped the next time their chunk loads.

The single brain `BlockEntityType` learns each block through Fabric's `addValidBlock` in `KoperBrainRegistry.bind`. Fabric replaces the type's block set with its own copy, so the older trick of handing the type koperlib's live set only worked until that happened: from then on, blocks bound afterwards got no block entity at all (no inventory, no `bstate`). Fabric's set only grows, so after a reload turns a brain off, `KoperBrainRegistry.wants` is what keeps that block brainless.

### koper.bstate

Per position storage for scripts. Same shape as `koper.pstate`, so the two read the same way.

```lua
koper.bstate.set(pos, "mechanics_dream", "fuel", 40)
local fuel = koper.bstate.get(pos, "mechanics_dream", "fuel", 0)
local n    = koper.bstate.add(pos, "mechanics_dream", "ticks_run", 1)
local lit  = koper.bstate.has(pos, "mechanics_dream", "lit")
koper.bstate.remove(pos, "mechanics_dream", "fuel")
local everything = koper.bstate.all(pos, "mechanics_dream")
```

`pos` is the position table the event handlers already receive. The second argument is the pack namespace, which keeps two packs from stepping on each other on the same block. A `dim` field in the position table reaches into another dimension.

This only works on blocks that have a block entity. On a plain decorative block there is nowhere to put the data and the call quietly does nothing.

### Containers

If a block has a `gui` with slots, the items live in that block's own block entity. Two blocks placed side by side have two separate inventories, which is what everyone expects.

One shared inventory across every copy of the block in the world has to be requested explicitly:

```json
{
  "container": "shared"
}
```

Older packs that relied on sharing being the default will see an info line in the log the first time the block loads, telling them which block it was and what to add.

`drop_container` controls whether contents spill when the block breaks. It defaults to true for per position containers.

## Entities

`entities/frost_wolf.json`:

```json
{
  "max_health": 30,
  "movement_speed": 0.32,
  "attack_damage": 5,
  "ai_type": "HOSTILE",
  "model": "frost_wolf",
  "texture": "frost_wolf",
  "entity_ai": ["swim", "wander:1.1", "attack_melee", "target_nearest_player"],
  "idle_animation": "idle",
  "run_animation": "run",
  "drops": [{ "item": "minecraft:bone", "count": 2, "chance": 0.5 }]
}
```

**Stats.** `max_health`, `movement_speed`, `attack_damage`, `armor`, `follow_range`, `width`, `height`, `attributes`.

**Behaviour flags.** `ai_type` (`HOSTILE`, `NEUTRAL`, anything else is passive), `burns_in_daylight`, `ranged`, `persistent`, `no_ai`, `baby`, `taming_item`, `rideable`, `ride_speed`.

**Looks.** `model`, `texture`, `tint`, `animations`, `preset_animations`, plus named clips: `idle_animation`, `run_animation`, `attack_animation`, `death_animation`, `spawn_animation`, and `animation_conditions` for anything conditional.

**Spawn egg.** Every entity gets one automatically. Given two colours, KoperLib builds the icon, no drawing needed:

```json
{
  "spawn_egg_primary": "#4a7c3f",
  "spawn_egg_secondary": "#c9d8a0"
}
```

Colours accept `#rrggbb`, `0xrrggbb` or a plain number. `spawn_egg_texture` overrides the whole thing with a custom sprite and wins over the colours.

### AI goals

`entity_ai` is a list of goal names. Goals take arguments after a colon. A bare name keeps the old default, so existing packs keep their meaning.

* `swim`
* `wander:<speed>` (default 0.8)
* `look_at_player:<range>` (default 8)
* `look_around`
* `follow_player:<range>` (default 16)
* `flee_player:<distance>:<slowSpeed>:<fastSpeed>` (defaults 6, 1.0, 1.2)
* `attack_melee:<speed>:<followEvenIfNotSeen>` (defaults 1.0, false)
* `target_nearest_player:<mustSee>` (default true)
* `revenge`
* `script_goal:<everyNTicks>` (default 10)

`script_goal` is the escape hatch. It is a real goal with a real lifecycle, holding no movement or look flags, so the mob's pathfinder and every other goal keep working normally. It calls `on_ai` on the script at the chosen interval:

```lua
function on_ai(entity)
  -- runs every 10 ticks alongside wander and swim
end
```

Use `on_ai` for decisions and `on_tick` for continuous per tick work. Keep both short, because they run on the server thread.

## Enchantments

`enchantments/frost_bite.json`:

```json
{
  "name": "Frost Bite",
  "max_level": 3,
  "min_level": 1,
  "slot": "weapon",
  "weight": 4,
  "anvil_cost": 2,
  "is_curse": false,
  "script": "mypack:scripts/frost_bite.lua"
}
```

`slot` picks which items accept it: `weapon`, `armor`, `tool` (or `mining`), anything else means the vanishing set.

`weight` is how often it turns up, `anvil_cost` what combining costs.

`is_curse` puts the enchantment in the vanilla `#minecraft:enchantment/curse` tag, which is what actually makes something a curse in modern Minecraft. Curses stay out of the non treasure pool, so they only appear from treasure sources.

`min_level` has no direct vanilla equivalent. KoperLib maps it onto where the cost curve starts, so `min_level: 3` shows up about as late in the enchanting table as a level 3 enchantment would.

## Menus and HUD (KUI)

Full reference: **[KUI.md](KUI.md)**

A KUI page is a JSON file in `guis/`. It has two modes.

**`json` mode** reads a `.layout.json` file describing elements: labels, buttons, sliders, item slots. Suited to menus edited as data.

**`texture` mode** reads a PNG plus a `.regions.json` describing clickable areas. Suited to interfaces drawn by hand.

A page declares `id`, `title`, `w` and `h` (defaults 176 by 166), `mode`, and either `layout` or `texture`. Pages with item slots become real container menus. Pages can also carry shapeless `recipes` and `craft_buttons` so a menu can craft without any script.

The same layout can be shown as a HUD overlay, anchored with `hud_x` and `hud_y`.

Open a menu from a block by setting `gui` on the block. Open one from a script with `koper.gui.open`.

## Lua scripting

Full reference: **[LUA_API.md](LUA_API.md)**

One Lua 5.4 VM per pack namespace. Scripts define event handlers as plain global functions.

### What is shared and what is not

The two halves of this behave differently, and knowing which is which saves an afternoon.

**Event handler names are private to each file.** When a script loads, KoperLib clears the handler globals, runs the file, then copies whatever handlers it defined into a per file table and wipes those globals again. So two scripts in the same pack can both define `on_use` without touching each other. That is the whole point.

**Every other global is shared across the whole pack.** If `sword.lua` writes `counter = 0` at file level, `shield.lua` sees the same `counter`. That is genuinely shared state, and it survives for the life of the VM.

```lua
-- sword.lua
charges = 3                 -- global, every script in this pack sees it
local secret = "mine"       -- file local, nobody else sees it

function on_use(player, pos) -- snapshotted, private to this file
  charges = charges - 1
  player:send_message(secret)   -- closures keep their file locals, so this works
end
```

Use `local` for anything not meant to be shared. Use a global deliberately when two scripts in the same pack need to talk. Across different packs, use `koper.events` instead, because they are separate VMs and share nothing at all.

```lua
function on_use(player, pos)
  player:send_message("you clicked at " .. pos.x)
  koper.world.play_sound_at(pos, "minecraft:block.anvil.land", 1.0, 1.0)
end
```

The handlers KoperLib will call: `on_use`, `on_tick`, `on_hit`, `on_damage`, `on_death`, `on_place`, `on_break`, `on_step`, `on_spawn`, `on_interact`, `on_target`, `on_equip`, `on_unequip`, `on_consume`, `on_craft`, `on_ai`.

### Objects versus namespaces

Objects and namespaces use different calling conventions.

**Namespaces** hang off `koper` and act on the world in general. `koper.world.set_block(...)`.

**Objects** are the arguments a handler receives, and they carry their own methods. The player and the item are objects, not namespaces. There is no `koper.player` and no `koper.item`.

```lua
function on_use(player, pos)
  player:send_message("hello")        -- method on the object passed in
  koper.world.set_time(0)             -- namespace, acts on the level
end
```

Object methods take the object as their first argument, so call them with a colon. `player:get_health()` works, `player.get_health()` does not. Namespace functions take no such argument, so those use a dot. Getting this backwards is the single most common mistake when starting out.

### Methods on an entity or player

Every entity passed to a handler, including players, carries these:

Identity and position: `get_id`, `get_uuid`, `get_name`, `set_name`, `get_pos`, `teleport`, `move_to`, `get_look_dir`, `get_look_direction`.

Health and damage: `get_health`, `set_health`, `get_max_health`, `heal`, `damage`, `attack`, `kill`, `ignite`, `freeze`.

Effects and tags: `add_effect`, `remove_effect`, `has_effect`, `add_tag`, `remove_tag`, `has_tag`.

Inventory: `get_inventory`, `get_main_hand`, `get_off_hand`, `give`, `give_item`.

Player only: `send_message`, `say`, `show_title`, `set_gamemode`, `kick`.

State queries: `is_player`, `is_sneaking`, `is_sprinting`, `is_on_ground`, `is_in_water`.

Targeting and memory: `get_target`, `set_target`, `remember`, `get_memory`.

Data and animation: `get_data`, `set_data`, `remove_data`, `play_animation`, `get_bone_transforms`.

### Methods on an item stack

`get_id`, `get_count`, `get_durability`, `set_durability`, `get_data`, `set_data`, `get_holder`.

### Before building on any of this

Some legacy entrypoints raise unsupported-operation errors: `ui.screen`, both `mixin` functions, `register_attribute`, `physics.spawn_ragdoll` and `physics.set_gravity_zone`. The current list is at the top of **[LUA_API.md](LUA_API.md)**.

One thing with a sharp edge: `koper.commands.register` works, but a command declared during a reload only becomes live after the next `/reload` or relog, because Brigadier builds its tree at server start.

### Namespaces

`koper.log(level, msg)` and `koper.print(msg)` write to the game log.

`koper.math` has `vec3`, `distance`, `normalize`, `lerp` and `raycast`. Raycast takes either an entity and a range, or a table with an origin and direction, and reports the block it hit with the face and distance.

`koper.world` is the level: `get_block`, `get_entities_in_radius`, `set_block`, `fill_blocks`, `get_time`, `set_time`, `set_weather`, `is_day`, `summon`, `spawn_particle`, `spawn_particles`, `spawn_xp`, `explosion`, `drop_item`, `play_sound`, `play_sound_at`, `log`.

`koper.entity` holds a few helpers that work on an entity handle rather than an object: `get_id`, `get_name`, `get_pos`, `get_health`, `get_max_health`, `get_look_dir`, `apply_effect`, `teleport`. `koper.Entity.new` builds an entity object.

`koper.physics` is the engine: `launch`, `spawn_projectile`, `spawn_ragdoll`, `set_gravity_zone`.

`koper.kontra` drives kontraptions: `apply_force`, `apply_impulse`, `destroy`, `restore`, `self_right`.

`koper.particles` has `spawn`, `burst`, `line`. `koper.kfx` runs the effect engine. The old surface is
`spawn`, `spawn_json`, `spawn_program`, `cast`, `cast_json`, `cast_program`; graph v2 starts with
`graph(id)` and uses its typed `input`, `include`, `node`, `link`, `output`, `on`, `budget`, `register`,
and `play` builder methods. `play` returns a live handle with `set`, `reanchor`, `signal`, `detach`, and
`stop`. The exact current contract and its limits are in
[KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md).

`koper.gui` drives menus: `open`, `close`, `set`, `set_slot`, `clear_slot`, `clear_all`, `consume`, `hud`. The older `koper.ui.screen` entrypoint raises an error; use a KUI page instead.

`koper.network` talks to clients: `send_to_player`, `broadcast`, `send_title_all`, and `on_receive` for a channel a client sends on.

`koper.commands` has `run`, which executes a command as the server, and `register`, which declares a `/command` the pack answers on the bus.

`koper.data` is a per world store saved next to the level: `get`, `set`, `remove`, `has`, `all`. Shared by every pack, so prefix the keys.

`koper.events` is the cross pack bus: `koper.events.on(name, fn)` and `koper.events.fire(name, data)`. Two packs can talk without knowing about each other, and every handler on a name gets called rather than only the last one registered. The server fires its own `player:*` events onto this bus, which is the subject of the next section.

`koper.addons.call` reaches other packs. The older `koper.mixin.inject` and `override` entrypoints raise errors; reloadable packs cannot apply mixins.

Added by the Java side rather than Rust: `koper.pstate`, `koper.bstate`, `koper.blocks`, `koper.items`, `koper.calls`. These go through a synchronous bridge, so a `get` right after a `set` sees the new value with no queue lag to work around.

### What Lua is good for and what it is not

Good for reacting to discrete events, gameplay logic that finishes in microseconds, gluing packs together, tracking player progression.

Bad for anything running every tick across many blocks, heavy loops or math, and anything that needs a real Minecraft object rather than a description of one. Scripts run synchronously on the server thread. There is a wall clock kill switch (see `scriptTimeoutMs` in the config) enforced by an instruction hook inside the VM, so a runaway loop gets killed instead of freezing the server, but everything up to that point is still spent tick time.

## The Java tier

Full reference: **[JAVA_ADDONS.md](JAVA_ADDONS.md)**

`.java` files go in `java/` inside the pack. They compile on every reload into `.cache/classes/` and load in an isolated classloader.

```java
@KoperItem("mypack:flame_sword")
public class FlameSword {
    @KoperHook("on_use")
    public InteractionResult onUse(KoperContext ctx) {
        ctx.player().igniteForSeconds(3);
        return InteractionResult.SUCCESS; // Lua and JSON for this event are skipped
    }
}
```

Method names bind automatically inside a `@KoperItem`, `@KoperBlock` or `@KoperEntity` class: `onUse` becomes `on_use` and so on. `@KoperHook` overrides that with an explicit name. `@KoperSubscribe` attaches a method to a bus event.

Return `InteractionResult.PASS` to let Lua and JSON keep running. Return anything else to stop there.

**Compiling needs a JDK, and most players run a JRE from their launcher.** If `javac` is missing, the java tier of the pack is off for that player, with only a log line about it. A shipped pack should therefore be compiled once by its author, with the result included:

```
my_pack/
  java/
    FlameSword.java     <- source, for the author
    out/
      FlameSword.class  <- what actually ships
```

If `java/out/` contains class files, KoperLib uses them and never touches `javac`. That is the difference between a pack that works for everyone and one that works only on its author's machine.

Pack Java can hook behaviour but cannot register new content types. A genuinely new kind of item needs a real Fabric mod using `KoperLibAPI`.

### From a real mod

```java
KoperLibAPI.items().require("mypack:flame_sword");
KoperLibAPI.fullpack().addItemHook("mypack:flame_sword", "on_use", ctx -> {
    return InteractionResult.PASS;
});
KoperLibAPI.content().spawnEntity("mypack:frost_wolf", level, pos);
```

Hooks added this way survive reloads. Hooks from inside a pack do not, because the pack is reloaded with them.

Depend on the feature modules the addon uses. The current build publishes them to Maven Local; see [Java addon dependencies](JAVA_ADDONS.md#java-from-a-real-mod). Use `implementation` with the current Loom build.

## Persistent state

Three separate stores, each for a different lifetime.

**`koper.pstate`** is per player, namespaced, survives relog and restart.

```lua
koper.pstate.set(player, "mypack", "quest_stage", 3)
local stage = koper.pstate.get(player, "mypack", "quest_stage", 0)
```

**`koper.bstate`** is per block position, namespaced, lives in the block's block entity. Requires the block to have one.

**Container contents** live in the block entity for per position containers, or in a world file keyed by gui id for shared ones.

**Item and entity data** are methods on the objects themselves, and ride along with the stack or the entity:

```lua
stack:set_data("charge", 5)
local c = stack:get_data("charge")
entity:set_data("phase", "angry")
```

## World events

Every other hook in KoperLib is attached to one piece of content. `on_use` fires for the item that declares it, `on_death` for the mob that declares it. Quests and journals also need events about activity outside the pack's own content. `KoperSnitch` provides those player events.

It is one bus with two front ends. Lua listens with `koper.events.on`, Java listens with `KoperSnitch.listen`, and both hear the same event.

```java
KoperSnitch.listen(KoperSnitch.KILL, tattle -> {
    if (tattle.id().equals("minecraft:zombie")) doSomething(tattle.who());
});

KoperSnitch.listen("*", tattle -> log(tattle.what())); // everything, for a director or a debug readout
```

```lua
koper.events.on("player:advancement", function(e)
  koper.pstate.add(e.player, "mypack", "milestones", 1)
end)
```

The full list of names and their fields is in **[LUA_API.md](LUA_API.md)** under World events. Firing one from code is `KoperSnitch.snitch(player, name, "key", "value", ...)`, and a mod is welcome to invent its own names on the same bus.

Two of them are worth calling out.

**`player:idle`** fires after ninety seconds in which the player did nothing on this list and did not really move. It fires once and then goes quiet until something resets it. That makes it a stuck detector rather than an idle timer, which is the difference between a guide that helps and one that nags.

**`player:got`** fires when something arrives in the inventory by pickup or by command, because it sits on the inventory rather than on the item entity. A full inventory still reports, so treat it as "the player is acquiring this" and re check when certainty matters.

It has one real gap. It hangs off `Inventory.add`, and a container menu does not go through there: shift-clicking a crafting result, or quick-moving a stack out of a chest, writes into the slots directly and is **silent**. Picking that same stack up with the mouse does report, because the carried stack is put back through `add()` when the screen closes, which is why the two clicks behave differently and why a `get` quest goal can look broken for one and fine for the other. A goal that must not miss an item should also poll what is held.

Where they come from: kills, deaths and connections ride Fabric events, crafting and inventory and advancements are mixins, and everything positional is a once a second diff of where each player was last time. That last part is why dimension changes, chunk crossings and standing still all cost one pass instead of three hooks.

## Quests

A quest is a file in `quests/`. It is pure data, so it reloads without a restart, and it hangs off the world events above rather than off any one item or block.

```json
{
  "id": "mypack:waking_up",
  "name": "§6Waking Up",
  "icon": "minecraft:oak_log",
  "auto_start": true,
  "goals": [
    { "id": "wood", "type": "get", "target": "minecraft:oak_log", "count": 4, "text": "Punch some oak" },
    { "id": "table", "type": "craft", "target": "minecraft:crafting_table", "count": 1 }
  ],
  "on_complete": [
    { "give": { "item": "minecraft:bread", "count": 3 } },
    { "sound": "minecraft:entity.player.levelup" }
  ]
}
```

`on_start` and `on_complete` take the ordinary [JSON actions](#json-actions) list, the same one items and blocks use, so a reward is anything the action language can already do.

**A target starting with `#` is a tag.** `"target": "#minecraft:logs"` counts any log, `"#minecraft:skeletons"` counts any skeleton. Item goals read item tags, `kill` reads entity type tags. Leave the target blank and anything counts, which is how "kill ten of whatever" is written.

**Goal types are `kill`, `craft`, `get`, `have`, `advancement`, `dimension`, `break`, `place`, `talk`, `die`, `dialog` and `script`, and that is the whole list.** Each one exists because something actually reports it. Write a goal type that is not on the list and the loader says so at load time, naming the ones that work, rather than accepting a quest that can never finish. `get` counts everything that ever entered the inventory, `have` counts what is in there right now and can go back down.

`advancement` is useful for packs that already have an advancement tree, because it turns that progression into quest goals without writing any new detection. `talk` takes a mob id, an entity tag, or `tag:<scoreboard tag>` so one specific villager counts and the rest do not. `script` is the open one: nothing in the world feeds it, so a script decides. While such a goal is waiting, `quest:check` fires once a second with the quest and goal id, and the script answers by calling `koper.quest.bump`.

**Per goal actions.** A goal can carry `on_done`, an action list that runs the moment that one goal fills rather than when the whole quest does. That is how a line of dialogue plays after the third zombie instead of only at the end.

**Quest givers.** `giver` on a quest names who hands it out: a mob id, an entity tag, or `tag:<scoreboard tag>` for one marked mob. Right clicking them starts the quest if the player is allowed to have it, with a line saying so if not. Mark a specific mob with vanilla `/tag @e[...] add my_marker` and point `giver` at `tag:my_marker`.

**Chaining.** `requires` is a list of quest ids that must be done first. With `auto_start`, a quest begins the moment its requirements are met, which is checked when a player joins and after every completion. That is how an opening sequence runs itself instead of expecting the player to find a quest giver.

Progress lives in the [soul vault](#persistent-state) under the `quest` namespace, so it survives relogs and restarts and there is no second save file to keep in step.

From Lua it is `koper.quest.start`, `.complete`, `.status`, `.done`, `.active`, `.bump`, `.progress`, `.reset` and `.list`, documented in [LUA_API.md](LUA_API.md).

**Books.** A file in `books/` declares one, with a title, an icon and an `order`. A quest finds its book in three steps: its own `book` field, then the book sharing its namespace, then a catch-all called *Everything else* that only appears when something actually landed there. That middle rule is the point: **a mod's quests find that mod's book on their own**, so a modpack with five content mods gets five books instead of one soup, and nobody has to tag every quest. With more than one book in play, the shelf opens first; with one, it goes straight in.

**Chapters.** `category` puts a quest in a chapter of its book and defaults to `main`. Side quests are not a separate feature, they are `"category": "side"`. Any name works, so a pack can have `main`, `side` and `secret` and the book grows the tabs by itself.

### The book

`/koperlib quest` opens it. Two pages: chapters and their quests on the left, the open quest on the right with its icon, description, goals and progress. Each chapter shows how many of its quests are done, and each quest carries a mark: done, in progress, ready to start, or locked. A locked one says which quest is holding it back rather than just refusing.

It redraws itself while open, so progress moves during play. Only a quest changing state rebuilds the lists; everything else redraws the right page, or the list would jump under the cursor mid scroll.

### The editor

`/koperlib quest make` opens a browser over the quest files in a pack: pick the pack, pick a quest, then New, Edit or Delete. Editing loads the file back into the form, so it is a real editor rather than a one shot generator.

Editing itself is three steps, because a form works best as one column with its labels above the fields and that does not fit on one Minecraft screen.

1. **Basics.** Id and name are required and marked, everything else says it is optional. It reports what is wrong while typing instead of waiting for Save.
2. **Goals.** The list of goals with Add, Replace and Remove, so a mistake is fixable. Pick a type, then type a target or hit **From hand**: that takes the held item and fills the list underneath with every tag it belongs to. Click a tag and the target becomes `#that:tag`, which is the quick way to write "any log" without going to look it up.
3. **Finish.** Which book it belongs to, a click-to-toggle list of quests this one should wait for, a preview of the exact file about to be written, and the way into the action editor.

**The action editor** is where `on start` and `on complete` get built. It lists what can happen; pick one, fill in its argument and add it, and the actions stack up in order with Remove for mistakes. It writes `give`, `command`, **`lua`**, **`java`**, `sound`, `message` and `kfx` in the exact shapes `KoperActions` reads, and it shows the JSON it is about to emit before committing it. The last entry is **custom json**, which accepts any pasted action object, which is the escape hatch for the rest of the action language and for anything a pack adds itself.

What comes out is the same JSON a person would type, in `<pack>/quests/`, so it can be read, diffed and committed. Fields the editor does not know about are carried through untouched, so hand written extras survive a round trip. The editor is a front end over the file format, never a replacement for it.

`/koperlib quest list` prints the journal as text, and `/koperlib quest start <id>` forces one open.

## Dialogue

A file in `dialogs/` is a conversation: named nodes, each with a line of text and some choices, and `speaker` deciding who says it in the world. `speaker` matches the same three ways `giver` does, so `tag:demo_boss` is one specific marked mob and `minecraft:villager` is all of them.

```json
{
  "id": "mypack:warden_words",
  "speaker": "tag:demo_boss",
  "start": "intro",
  "nodes": {
    "intro": {
      "text": "You came down here on purpose.",
      "choices": [
        { "text": "Who are you?", "go": "who" },
        { "text": "[ back away ]", "go": "" }
      ]
    },
    "who": { "text": "Older than the stone you are standing on." }
  }
}
```

A choice with a blank `go` ends the talk. `once` burns a choice after it is picked, remembered per player. Pointing `go` at an already visited node makes a loop, and nothing stops a conversation cycling back to `intro` forever.

**Gating a choice.** `when` hides a choice until it applies, or greys it out instead with `"hide": false`, which is often better because the player can see there is something they cannot say yet.

```json
{ "text": "I brought what you asked for.", "go": "paid",
  "when": { "has": "#minecraft:coals", "count": 4 }, "hide": false }
```

`when` understands `quest_done`, `quest_active`, `quest_none`, `spoke_to` (a dialogue already finished), and `has` with an optional `count`, where the item takes the same `#tag` form goals do. `"not": true` flips the whole thing. Anything more complicated than that belongs in a script, and a choice can call one through its actions.

**Gating on a mod's own progression.** `state` reads the player's soul vault directly, written as `namespace:key`. The namespace is everything before the first colon and the key keeps its own dots, so a mod that already tracks progress there needs no KoperLib support to gate a line on it:

```json
{ "text": "Who sent you?", "go": "orders",
  "when": { "state": "koper_mod_fabric:boss.unlocked.springikoper" } }
```

`state_num` does the same for a number, passing when it is at least `at_least` (default 1). Both combine with the other keys and with `"not": true`. This is the seam that lets a content mod put a conversation in front of its own boss without the two knowing about each other at compile time.

Both `on_show` on a node and `actions` on a choice take the ordinary action list, so a conversation can give items, run commands or call Lua.

**Starting one from anywhere.** `{ "action": "dialog", "id": "mypack:warden_words" }` opens a conversation from any action list, so a quest can start talking the moment it begins, or a goal can when it fills. No mob needs to be involved.

Right clicking a mob opens whatever it has to say. Finishing a conversation fires `dialog:done`, which is exactly what a `dialog` goal waits for, so **"this quest needs a talk with the boss" is one goal with the dialogue id as its target**.

`/koperlib quest dialog list` prints what is loaded and `/koperlib quest dialog play <id>` plays one on the spot, which tests a conversation without finding the mob that owns it.

`/koperlib quest dialog` opens the editor: pick a pack, name the dialogue, set the speaker with **From aim** by looking at the mob, then add lines and hang choices off them. It writes `dialogs/<id>.json` and reloads.

## JSON actions

The `events` block on any item, block or entity runs a small action language. No script file needed.

```json
{
  "events": {
    "on_use": [
      { "action": "play_sound", "sound": "minecraft:entity.generic.explode" },
      { "action": "particles", "particle": "flame", "count": 30, "spread": 0.5 },
      { "action": "damage", "amount": 4, "target": "target" },
      { "if": { "sneaking": true },
        "then": [{ "action": "teleport", "to": "target" }],
        "else": [{ "action": "message", "message": "hold shift" }] }
    ]
  }
}
```

Actions cover sound, particles, damage and healing, effects, teleporting, giving and taking items, setting blocks, spawning entities and projectiles, explosions, lightning, velocity, food and saturation, XP, attributes, cooldowns, item name and lore and model data, blockstate changes, animations, KFX effects, running a command, calling into Lua or Java, and control flow: `if` / `then` / `else`, `random`, `choose`, `repeat`, `every`, `later`, `delay`.

Conditions available to `if`: `sneaking`, `sprinting`, `has_player`, `has_stack`, `has_target`, `has_item`, `health_above`, `health_below`, `target_health_above`, `target_health_below`, `day_time`, `dimension`, `chance`, `inventory_count`, `not`, `all`, `any`.

Use JSON actions for simple sequences. Move to Lua as soon as a variable is needed.

## Reloading

`/koperlib reload` runs these phases in order:

1. Clear everything: content registry, scripts, virtual pack assets, geo caches, KUI, KFX, block entity bindings, queued script commands
2. `FullPackLoader` rediscovers packs and the disabled set from disk
3. `UniversalLoader` walks every enabled pack and dispatches to the factories
4. Creative tabs get rebuilt
5. Server resources reload, then clients are pinged to refresh

The production Fullpack module exposes `/koperlib reload`. Koperstuff retains the deeper diagnostic reload and legacy benchmark commands used during development.

New content registers fine during a reload, because KoperLib keeps the registries writable all session. The short list of things that still need a restart is in [Reload or restart](#reload-or-restart).

## Commands

Everything lives under `/koperlib`.

* `reload`
* `fullpack list`, `fullpack enable <name>`, `fullpack disable <name>`
* `status` shows module tiers, native engine version, what is off and why
* `config`, `config reload`
* `give <item> [count]`
* `gui open <id>`
* `calls`, `itemtypes` list registered extension points

Installing the developer Koperstuff mod (built from source, not published) replaces this production tree with the complete developer tree. That adds `stress`, noisy debug dumps, OBB inspection, KFX previews, and physics inspection commands.

KFX previews print their live handle. `/koperlib kfx inspect <handle>` reports graph/source identity,
anchors, controller state, render and particle budgets, batches, and cosmetic collision field state.
Cosmetic emitter collision is client-side visual physics only; authoritative spell contacts still come
exclusively from the server KFX controller.

## Config

Module settings are under `<game directory>/config/koperlib/`. They use camelCase keys, matching the owning config classes. `kender.json` and `kodel.json` copy their keys from an old `kgecko.json` on first start. `/koperlib config reload` reloads the registered sections.

| File | Setting | Default |
|---|---|---|
| `core.json` | `debugMode` | `false` |
| `fullpack.json` | `scriptTimeoutMs` | `5000` |
| `fullpack.json` | `autoReloadScripts` | `false` |
| `fullpack.json` | `globalDamageMultiplier`, `globalHealthMultiplier` | `1.0` |
| `fullpack.json` | `disableCustomMobs` | `false` |
| `effects.json` | `kenderVulkanParticles` | `true` |
| `kender.json` | `rendering` | `"koperlib"` |
| `kender.json` | `kenderBlockCullDistance` | `128` |
| `kender.json` | `kenderEntityRender`, `kenderShaderCompat` | `true` |
| `kender.json` | `kenderShadowCast` | `false` |
| `kodel.json` | `playerModel`, `playerModelAnim` | Empty string |
| `khysics.json` | `enablePhysics` | `true` |
| `khysics.json` | `physicsBackend` | `"rapier"` |
| `khysics.json` | `defaultAeroMode` | `"correct"` |
| `khysics.json` | `maxKontraktionBlocks`, `kenderMaxBlocksPerKontraktion` | `-1` |
| `khysics.json` | `kenderBlockCullDistance` | `128` |
| `khysics.json` | `kontraCameraMode` | `"vanilla"` |
| `khysics.json` | `kontraFancyLight` | `true` |
| `khysics.json` | `kontraLightSpillRange` | `7` |
| `khysics.json` | `createRender` | `"auto"`, deferred compatibility |
| `khysics.json` | `kenderGeometryStreamThreshold`, `kenderGeometryChunkBytes` | `8192` |
| `khysics.json` | `kenderGeometryBytesPerTick`, `kenderGeometryWindowBytes` | `65536` |

Example `fullpack.json`:

```json
{
  "scriptTimeoutMs": 5000,
  "autoReloadScripts": false,
  "globalDamageMultiplier": 1.0,
  "globalHealthMultiplier": 1.0,
  "disableCustomMobs": false
}
```

The old combined `config/koperlib/config.json` is a migration input, not the current settings file. Unknown fields are ignored. Development-only automatic script reload adds work to each invocation.

## Physics (khysics)

Dimension flight policy, bounded collision readiness and occupied assembly transfer are documented in [Khysics](KHYSICS.md#dimensions-and-gravity). Fast flight is opt-in and requires matching native libraries.

Full reference: **[KHYSICS.md](KHYSICS.md)**

Kontraptions are groups of blocks lifted out of the world into a Rapier rigid body. They move, rotate, collide, take joints and carry players.

Physics is experimental and on by default. `enablePhysics: false` turns it off.

Khysics routes its simulation through the configured backend: Rapier for rigid-body physics or the alternative Elpe backend. Native simulation and the Minecraft integration have different tick cadences. Backend limits are described in [Khysics](KHYSICS.md) and [Elpe](ELPE.md).

Two namespaces, and the split is not the obvious one. **`koper.kontra` acts on an existing kontraption by id. `koper.physics` creates new physical things in the world.**

```lua
koper.kontra.apply_force(id, x, y, z)
koper.kontra.apply_impulse(id, x, y, z)
koper.kontra.self_right(id)
koper.kontra.destroy(id)
koper.kontra.restore(id)

koper.physics.launch(...)
koper.physics.spawn_projectile(...)

```

Ragdoll spawning and region gravity-zone Lua calls raise unsupported-operation errors; they are not implemented features. `entity:freeze()` is an entity method, separate from the kontraption APIs.

Java addons that drive vehicles use `com.koper.koper_lib.physics.body`: `KhysBody` as the handle, a `KhysPusher` called every 60 Hz physics step with a fresh `KhysBodyState`, held forces, spin kicks, per-body gravity and buoyancy scale, per-block mass, and a `stash()` tag saved with the hull. See [KHYSICS.md](KHYSICS.md#from-java-the-vehicle-toolkit).

With the developer Koperstuff module installed: `/koperlib physics make` turns the selection into a kontraption, `list`, `destroy <id>`, `tp <id>`, `selfright <id>`, `aero <id> ...`, `joint ...`, `motor <joint> <velocity> <torque>`, `pause`, `resume`, `step`, `seat`.

Collision uses oriented bounding boxes and the separating axis theorem, running alongside vanilla axis aligned boxes so selection and combat still behave normally.

Grid bootstrap initializes physical-world block contacts before publishing the body spawn, so assembly and new grid parts are immediately queryable without waiting for a native pose poll. Client parked-block collision and projected state queries translate centroid offsets into the stable local keys shipped by the server, using normalized quaternion math and double world-cell centers. They retain per-cell shape/local-data lookup rather than replacing partial shapes with cubes. This addresses missing parked cells caused by half-block rounding, recentering and distant coordinates; it does not change native resting thresholds or general shape rotation rules.

Bodies with nonuniform float offsets use a prebuilt client offset-bucket index instead of assuming a regular local lattice. Queries inspect only nearby buckets and retain the supplied local key for the chosen cell. Recursive body creation during contact callbacks seeds its physical projection immediately; neighbor notifications wait until the outer refresh finishes. Further callback-created generations are drained on later outer refreshes to avoid an unbounded recursive notification chain.

Explicit disassembly uses `KoperPhys.tryRestoreToWorld(server, id)`, or the overload with a proposed position and quaternion. It requires an upright cardinal yaw and preflights every real destination, refusing occupied terrain or other physical hulls, duplicate, out-of-height or out-of-border cells before changing the world. Unsupported pitch/roll is refused because arbitrary static blocks cannot represent it faithfully. Refusal keeps the physical body intact. A successful batch restores rotated states, block-entity inventories, registered local data and pending block/fluid ticks, then removes the body. Upright yaw uses the block's own rotation hook, including mod-defined state properties. The legacy `restoreToWorld` cleanup API retains its upward-relocation/drop policy for existing detach callers.

With Koperstuff installed, `/koperlib physics land <id>` uses the refusal-safe path and reports failure instead of claiming an obstructed hull landed. Eureka Khysics uses the same path through its helm disassembly button; it aligns a proposed pose using the current server cache and clears alignment flags on refusal. Its 26.3 engine resolves data-driven fuel against `CONTAINER_PROCESS` with the actual transformed world origin and container parameters, so both static and grid engines can fuel and heat without rejected menu clicks.

### Entity queries from moving blocks

Server block and block-entity ticks run in `KontraGridContext`. Entity queries using
that grid's logical coordinates search the host level at the body's current pose.
The two list-returning `Level.getEntities` overloads preserve entity types, exclusions, and predicates;
predicate callbacks retain the original grid context for their own block queries.
The enclosing world AABB selects candidates, then oriented-box overlap rejects
empty corners of rotated queries. Returned entities keep their world coordinates.

World-space queries inside a grid tick, client queries, and queries into another
dimension retain vanilla behavior. A grid with no cached pose returns no matches.
This allows vanilla container viewer rechecks to find players using physical chests
instead of closing the lid while their menus remain open. Contact callbacks,
entity carrying, crop hydration, and shader rendering are separate paths. Direct
`Level.hasEntities` and list-output/capped-output overloads are not routed yet.

Existing server `BlockState.entityInside` callbacks discovered through a physical
projection use the owning grid's canonical state, logical position and context.
Real world blocks keep their normal callback. This keeps a moving pressure plate's
entity query, state change and scheduled release recheck in the same local frame;
it does not leave a recheck behind at a world position the deck has vacated.
Plate queries also reject entities in empty rotated projection corners. This
route does not add contacts that vanilla's projection scan missed, or change
`stepOn`, `onInsideBlock`, fluid callbacks or fast swept-contact discovery.

The world-cell projection SAT scales its contact tolerance by the length of each
test axis. Nearly parallel block edges therefore cannot produce a negative overlap
threshold and erase the collision projection of an almost aligned body.

Uncached grid/world boundary lookups prepare one immutable SAT test for their
current orientation and reuse it across candidate world cells. Each lookup owns
its test, so nested terrain reads cannot overwrite shared scratch. Candidate
bounds, contact ordering and overlap tolerances stay unchanged. This removes
repeated temporary SAT arrays without reducing collision or simulation detail.

Omni's server GameTests use the configured game tick cadence so their timed
assertions advance alongside the independent 60 Hz Khysics worker. Vanilla
GameTestServer normally runs ticks without waiting, which is unsuitable for
wall-clock physics assertions. This harness pacing applies only in Omni runs;
it does not alter normal servers or count as a physics throughput optimization.

Dropped items already tracked on a physical deck run their server movement each
tick, so inherited deck motion is applied every tick rather than in vanilla's
four-tick idle bursts. The ordinary idle gate still applies to untracked items,
and later vanilla ground checks still control friction and bounce. This repair
does not change player movement or the general mob collision solver.

### Geometry updates and motion continuity

Client block deltas preserve the body's interpolation window, authoritative
velocity samples, and any transform waiting for the next client tick. Full
geometry replacements carrying the explicit KEEP_POSE sentinel preserve the same
timeline. Building on a moving body therefore does not discard a queued movement
or erase velocity prediction. Prediction remains capped at two ticks beyond the
latest server sample. An explicit legacy finite spawn still uses its supplied pose.

Peers advertising `koper_lib:kender_snapshot` receive timestamped compact full
snapshots. Existing bodies keep their motion history while geometry is replaced;
only a snapshot newer than both received and queued authority contributes a pose,
at the next client tick boundary. New finite bodies seed their authority tick.
Edits keep a body's reference frame, while split-off bodies receive a new ID.
Older peers receive the unchanged `kender_spawn` payload.

The compact codec uses a block-state palette, signed local coordinate deltas,
an offset frame with exact float overrides, and sparse block entity tags. It
preserves cell ordering, raw offset bits and local metadata. A uniform 100,000-cell
probe shrank from 2,900,040 to 401,070 codec bytes. This is a codec measurement,
not measured live throughput. Compression, encoding time and diversity affect
actual traffic; this format still sends a whole snapshot and allocates the full
client geometry. Regional storage and rendering remain separate work.

Peers advertising the geometry start/data/cancel channels stream snapshots from
8,192 cells onward. Each player has one fair queue and cumulative ACK window,
shared by all transferring bodies. Data fragments are sent after ordinary pose
updates. Cancelling or replacing a body retains credit for already sent fragments
until their ACK arrives; disconnect resets the connection, while dimension changes
cancel the old jobs and retain their sequence and outstanding credit.

Khysics configuration controls `kenderGeometryStreamThreshold` (8,192; zero or
negative selects the existing direct path), `kenderGeometryChunkBytes` (8,192),
`kenderGeometryBytesPerTick` (65,536), and `kenderGeometryWindowBytes` (65,536).
Data accounting includes 128 bytes per fragment and uses uncompressed codec
bytes, rather than measured internet throughput. Chunk size is clamped to
1..65,536 and tick/window values to at least 129. Small or unsupported snapshots
retain compact/legacy negotiation. Controls, poses, and ordinary edits keep their
existing delivery paths.

The client keeps old geometry usable until every part is validated and complete.
New bodies retain the latest two received poses; existing bodies preserve their
motion history. Ordered block, NBT and delta updates are replayed after publication.
Already delivered BE events fire once, including on a BE added during transfer: its
living instance is retained when its queued placement replays. Events with missing
targets wait for completion. Ghost checks skip bodies actively loading. Removal, direct replacement,
cancellation and disconnect discard staging. Replay overflow (4,096 changes or
4 MiB estimated metadata per body) abandons the stale transfer and requests resync;
ignored fragments still acknowledge credit.

Part encoding visits at most 4,096 cells, stops after its first NBT cell, and
limits encoded size to 8 MiB with ordinary NBT read accounting. A Start header
alone never allocates the declared geometry. This bounds geometry data in flight,
not total body memory: enqueue still captures dense immutable arrays/NBT, and
completion still builds the full client arrays, maps and renderer geometry.

### Lighting on a kontraption

A block riding a kontraption is not a block in the host world, so the host light engine knows nothing about it. That means a torch bolted to a ship is not a light source as far as the world is concerned, and for a long time it lit nothing at all.

Kontraption light now comes from two places, maxed together:

* **the host world**, sampled at the block's transformed world position; this is what puts a ship in shadow when it flies under an overhang
* **the kontraption itself**, flood filled across its own local grid from its own emitters, so lamps on the ship actually light the ship. Opaque blocks receive the light on their face but do not pass it on, so a lantern below deck does not shine through the hull.

`kontraFancyLight: false` turns the second half off and goes back to host light only. `kontraLightSpillRange` controls how far a kontraption's light reaches into the real world.

Light is cached per block and refreshed a slice at a time rather than resampled every frame. It used to cost two light engine queries per block per frame, which on a real ship is a few hundred thousand chunk walks a second for a value that changes at most twenty times a second.

### Render performance

`/kender prof` arms a frame profiler, and calling it again prints where the kontraption frame actually went: blocks seen, what each culling stage rejected, how the drawn blocks split across the GPU, instanced and CPU paths, milliseconds in the block loop, the block entity loop and the flush, and floats uploaded per frame. Use it before and after any change here; the numbers are not intuitive.

Kontraptions are frustum rejected as a whole before any block is touched, using a rotation invariant bounding sphere so nothing has to be rebuilt as the ship turns.

### Blocks that draw themselves from local data

Some blocks keep their whole shape in per position local data instead of the block state (a micro grid is the obvious one: the state says "micro container", the data says which 4px cells are filled with what). On a kontraption these register a `KenderLocalBlockRenderer`, which gets the cell's data and draws it on the direct GPU path.

When that path says no (OpenGL backend, or a mesh that is not on the GPU yet on its first frame) the block goes through the CPU moving block path instead, never the shared GPU mesh cache, because that cache keys on neighbours and would hand two different micro grids the same mesh. The model on that CPU path must read its data from `KenderMovingBlockContext.kenderLocalData()`, not from the world: the world position under a kontraption is whatever it happens to be flying over. The first time a block type takes the CPU path the log says so once, with whether Vulkan was active.

### Shader compatibility

Kender has conditional shader integration through Core's `SulkanHandshake` and the render modules. Shadow participation, receiving shadows, held-light illumination and translucent/skinned geometry have separate paths and remain incomplete.

The `kenderShaderCompat` and `kenderShadowCast` settings are in `config/koperlib/kender.json`; shadow casting defaults to false. Source support does not establish compatibility with every Sulkan or Iris pack. See [Kender](KENDER.md#shader-compatibility) for current limitations.

## Light physics (elpe)

Full reference: **[ELPE.md](ELPE.md)**

Elpe is a separate module depending on Core. Its particle world represents bodies as points with radii, connected by joints and interacting with voxel terrain. Sleeping reduces active simulation work; practical capacity depends on the workload. `/koperlib elpe spawn|rope|cube|blast|show|stats|clear` exposes its tools. Explosion rubble can settle back into blocks. See [ELPE.md](ELPE.md) for the Java API and its limits.

## Rendering

Full references: **[KODEL.md](KODEL.md)** for models and animation, **[KENDER.md](KENDER.md)** for the render path, **[KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md)** for effects.

**Kender** is the Vulkan path. It keeps geometry in its own VRAM buffer and lays out blocks with compute shaders, so there is no transfer across PCIe every frame. It shares Minecraft's Vulkan device when Minecraft is running on Vulkan and silently falls back to the vanilla pipeline when it is not. That fallback is why `rendering: koperlib` is a safe default.

**Kodel** is the model system: one binary format (`model.bin` + `animation.anim.bin` inside a `.kodel` ZIP) for block, entity and item models, packed by the Python toolchain in the `aq` repo or by the `koperlib` converters. Loading and per-bone sampling run against a Rust engine (`koperlib_kodel_engine`) over Panama, with a pure Java fallback in `KodelSampler` so a missing `.so` never breaks the game.

* Full bone hierarchy (pivot/position/quaternion/scale), box cubes with per-face UV (rotation + mirror) and raw triangle meshes.
* Keyframe clips with linear / step / Catmull-Rom smoothing / true bezier easing. A bezier key with zero control points means "automatic smooth", for automatically smoothed keyframes.
* `KodelConverters` imports Bedrock `.geo.json` / `.animation.json` (MoLang keys skipped); a fullpack that ships geo files instead of `.kodel` is converted on load. `KodelJavaModels` exports any live vanilla `ModelPart` tree (kopermod's embedded Java models included) as exact triangle meshes per bone.
* `KodelLoader` unpacks a `.kodel` container; `KodelAnimationController` is the per-playhead clip runtime (loop/once/hold, speed, reverse, seek).
* Java and Rust share the same sampling math and are cross-checked by JUnit against the real `.so` (`KodelFormatSmokeTest`).

Model blocks, fullpack mobs, armour, item icons, the player model, OBB picking, damage bones, bone anchors and model physics shapes all run through Kodel (see [KODEL.md](KODEL.md)). The KGecko module that used to do this was removed in October 2026; its pack json keys still work.

**KFX** is the particle and effect engine. Its graph v2 composes named primitive nodes, typed inputs and deterministic random value sources instead of selecting spell presets. JSON, Lua and Java fragments share one registry, can include one another, and compile into one effect. Live handles can bind both endpoints to smooth entity sockets, model bones, fixed world points, or a transform between anchors without position packets every tick. Server controllers continuously sweep moving spell roots against blocks and entities, then publish ordered authoritative impacts; AQ still owns damage, explosions and mana. Stateful native emitters may additionally use bounded `bounce`/`slide`/`stick`/`die` voxel collision, which is visual-only and cannot produce gameplay impacts. The renderer has a GPU instanced path and a CPU path and moves between them automatically. See [KFX_PARTICLE_ENGINE.md](KFX_PARTICLE_ENGINE.md) for the exact implemented surface.

## The native engine

Rust libraries provide the scripting VMs, model sampling, physics and native geometry backend. Java loads each library through Panama FFM. The native workspace contains multiple crates and separately bundled feature libraries; it is not one shared monolithic DLL.

See [building](BUILDING.md#native-libraries) for library names, packaging targets and cross-compilation tasks. A host build does not establish native coverage for every supported platform. Replacing a loaded native requires restarting the game.

## Bedrock addons

Full reference: **[BEDROCK.md](BEDROCK.md)**

A Bedrock `.mcaddon` or `.mcpack` dropped into `koperlib/fullpacks/` or `mods/` is converted on start into `koperlib/fullpacks/<name>_bedrock/`, a normal fullpack. Items, blocks, mobs, recipes, loot tables, functions, lang and sounds become the usual JSON; mob geometry and animations become a `.kodel`. The addon's scripts run on QuickJS in the Rust engine against KoperLib's own `@minecraft/server` and `@minecraft/server-ui`, one VM per addon, restarted on `/koperlib reload`. Custom components hook into the same item and block hooks pack Java uses, forms show as vanilla dialogs, and `runCommand` goes through a Bedrock to Java command translator. Lua and pack Java are untouched and keep working next to it.

## When something does not load

KoperLib does not fail quietly. Three places watch for the kinds of breakage that used to go unnoticed:

* **Mixins.** Every KoperLib mixin config is wired to one plugin (`KoperMixinKrzykacz`) that looks at each target class after it is transformed. An injector whose handler is never called from the target (target method renamed, descriptor changed, `require = 0` hiding it) logs an ERROR `[koperlib mixin] NIE ZAŁADOWAŁEM SIĘ / DID NOT TAKE EFFECT` with the mixin, the handler, the target and the reason. Once the server (and on a client, the client) has started, a summary line says either that all of them applied or which ones did not. Mixins are applied lazily, so a class that has not loaded yet has not been checked yet; start with `-Dkoperlib.mixinAudit=true` to force every target to load and be checked at startup (slower, meant for testing).
* **Old data formats.** Minecraft 26.3 silently ignores several pre 26.3 fields (loot `functions` and `conditions`, block states written as `Name` / `Properties`, advancement shorthands and more). Pack JSON going through the embedded datapack is scanned, and every file still in the old format logs one ERROR `[26.3] <file> is in a pre-26.3 data format, Minecraft will IGNORE these parts: ...`. KoperLib's own generators (loot, recipes, advancements, potions, the Bedrock converter) write the 26.3 format.
* **Block states in saved NBT.** Code that reads a block state from its own saved data uses `KoperBlockStateNbt`, which accepts both the 26.3 `id` / `properties` form and the old `Name` / `Properties` one. Vanilla's reader turns the old form into air, which is how kontraptions saved on 26.2 would have come back empty.

Potions with brewing recipes are generated as `minecraft:brewing` data recipes, which is how 26.3 does brewing.

## Known limits

These limits affect pack behavior and deployment.

**The proximity tick scan.** An `on_tick` block gets a block entity automatically, so most packs never meet this. The scan only applies to blocks whose block entity was turned off by hand with `"block_entity": false`.

When that happens, the block is found by sweeping a cube around every player, one blockstate read per position. The cube is sized by the largest `tick_radius` any block in any loaded pack asks for, capped at 16, which is a cube of nearly 36 thousand positions per player. KoperLib skips ticks where no block type is due and skips every block that has a block entity, so the cost only appears for blocks that opted out. **A ticking block should keep its block entity.**

**Item types are a closed set.** JSON picks from what exists. New types need a Fabric mod.

**AI goals are a fixed list.** `script_goal` is the way out, and it is a proper goal, but a custom pathfinder cannot be written from a pack.

**Fully removing content waits for a restart.** Deleting an item from a pack or disabling a pack stops the definitions being applied, but the registry entries survive until next launch. Adding and editing are both live.

**Lua VMs are per namespace, not per script.** Shared globals inside a pack.

**Pack Java needs a JDK unless the pack ships `java/out/`.**

**The event bus has no per handler removal from Lua.** `unsubscribe` and `clearNamespace` exist on the Java side; from a pack, handlers clear on reload.

**Script commands are rate limited.** A script that emits thousands of commands in one call gets a slice of the tick and the rest is queued for following ticks. Correct behaviour, but it does not all land in the same tick.

## Stress testing

Stress testing needs the developer Koperstuff module, which is not published as a release jar and has to be built from source (`modules/koperlib-koperstuff`). `/koperlib stress all` runs a suite that hammers the parts of KoperLib that are easy to break and hard to notice. Each test reports OK, ZLE (broken) or POMINIETE (skipped) with what it measured.

* `luabomba` runs an infinite loop in a throwaway VM and checks the timeout actually kills it
* `vpackracer` writes to the virtual resource pack from four threads while reading and wiping it
* `busbrawl` fires the event bus with handlers that throw and handlers that subscribe mid dispatch
* `molangevil` feeds the MoLang parser deeply nested and malformed expressions on a thin stack thread
* `idfuzz` throws malformed identifiers at the registry path
* `cmdpotop` floods the script command dispatcher and checks the time slice holds and the backlog drains
* `reloadsztorm` reloads five times and measures heap and metaspace afterwards to catch leaks

It is meant to be run after changes, not during play. It will stutter the game on purpose, and it says so before it starts.

*Claude AI used for documentation.*
