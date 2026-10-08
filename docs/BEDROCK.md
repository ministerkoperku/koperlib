# Bedrock addons

KoperLib converts Bedrock addon files into Fullpacks and provides a JavaScript compatibility runtime. Supported content includes items, blocks, entities, recipes, loot, functions, sounds and text. Bedrock APIs, behavior components and rendering are implemented selectively; test the particular addon and inspect conversion warnings before relying on it.

None of this is Mojang's code. The `@minecraft/server` and `@minecraft/server-ui` modules the scripts import are KoperLib's own implementation, written against the shape of the public API, running on QuickJS inside the Rust engine. The converter only reads the addon's own files.

Resource packs work too, including big ones that replace vanilla mobs and items. A resource pack's models, animations, animation controllers, render controllers, attachables and particle effects run in Kodel's native engine. MoLang is compiled once, and each mob costs one Panama call per frame.

**Note: compatibility between addons can be poor, and the API is not perfect. It has many bugs, not everything works, and it is still a work in progress.**

## Installing one

The file goes in either of these folders:

```
koperlib/fullpacks/cool_addon.mcaddon
mods/cool_addon.mcaddon
```

`mods/` is there for launchers like Zalith, where the mods folder is the only one a player can reach. Fabric ignores anything that is not a jar, so the addon sitting there does no harm.

Also accepted: a `.mcpack`, a `.zip` with a Bedrock `manifest.json` inside, or an unpacked Bedrock folder. Newer packs are flattened first: `__brarchive` files (the packed folders a `pack_optimization_version` manifest ships) are unpacked, and one subpack is layered over the root. The subpack with the most content wins unless `koperlib/bedrock_subpacks.json` says otherwise: `{"<addon file name, pack name or uuid>": "<subpack folder or its display name>"}`. A behavior pack and a resource pack that arrive as two separate files find each other through the dependency uuid in the behavior pack's manifest, same as on Bedrock. A `.mctemplate` or `.mcworld` works too: its `behavior_packs/` and `resource_packs/` are taken, the world itself is not. A pack folder sitting inside another pack's folder (Mowzie's Mobs carries a whole second BP like that) is skipped with a warning, Bedrock does not load it either; before, both converted under the same name and the second one replaced the first.

On the next start (or `/koperlib reload`) a converted pack appears at `koperlib/fullpacks/<name>_bedrock/`. From there it is a normal fullpack: enable and disable it like any other, and look inside it when something is off, because every file in it is the plain KoperLib JSON a person would write by hand. `bedrock.koper.json` in that folder lists every warning the converter had.

**Pack priority.** Packs have an order, top first, kept as `pack_order` in `koperlib/options.json`. `/koperlib fullpack list` shows it, `/koperlib fullpack priority <pack> top|up|down|bottom` changes it (restart or `/koperlib reload` to apply). Packs not in the list follow the listed ones alphabetically. When several packs define the same entity (Actions & Stuff, RLCraft and Mowzie's all redraw the player), the highest one owns it, like the top of Bedrock's pack stack, and the others hook on instead of being dropped: on the client their geometries, textures, materials, animations, particles and sounds join where the owner has no such key, and their render controllers and `scripts.animate` / `pre_animation` / `initialize` entries are appended; on the server their components, component groups, events and properties join the same way. A key both define stays the owner's. The player's body (`geometry.humanoid*` other than armor) is always Java's own model, even when a pack ships its own copy of Bedrock's humanoid; drawing that copy as well gave a second player and put the first person camera inside its head.

A converted pack is only rebuilt when its addon changes. Delete the addon and its `_bedrock` folder goes away on the next start. Do not edit the `_bedrock` folder itself, it gets replaced; edit the addon.

### Conversion caching

Conversion uses cached unpacked files and parallel file processing. Converted data is rebuilt when the converter version or addon content changes. Check `bedrock.koper.json` for unsupported content and conversion warnings.

## What converts

| Bedrock | Becomes |
|---|---|
| `manifest.json` header | `pack.kopermeta`. The namespace is the one most identifiers in the addon use. |
| `items/*.json` | `items/*.json`. Icon, display name, stack size, durability, damage, food, glint, fire resistance, cooldown, rarity, wearable slot and protection, digger (becomes a tool type). `minecraft:throwable` with `minecraft:projectile` throws its `projectile_entity` on use (power from `max_launch_power`/`launch_power_scale`, swing, one used up outside creative). |
| `blocks/*.json` | `blocks/*.json`. Hardness from `seconds_to_destroy`, explosion resistance, light, friction, collision off, textures from `material_instances` or the old `blocks.json`, render method, loot table. A custom `minecraft:geometry` becomes a `kender` model. |
| `entities/*.json` | `entities/*.json`. Health, movement, attack, collision box, follow range, families, the common behaviours (float, melee, nearest target, stroll, look at player, look around), baby, persistent, rideable, tameable, burns in daylight, loot. Components switched on by `minecraft:entity_spawned` are included. |
| RP client entity + geometry + animations | a `.kodel` in `kodel/`, with the walk, idle, attack and death clips wired to the mob. Without the Kodel module installed at conversion time the converter writes `models/*.geo.json` and `animations/*.animation.json`, which Kodel converts on load once it is installed. |
| `recipes/*.json` | Java recipes: shaped, shapeless, furnace (smelting, blasting, smoking, campfire), smithing transform. |
| `loot_tables/**` | Java loot tables at the same path. `set_count`, `looting_enchant`, `set_damage`, `furnace_smelt`, `random_chance`, `killed_by_player`. |
| `functions/**.mcfunction` | Java functions, every line run through the command translator below. `tick.json` becomes the `minecraft:tick` function tag. |
| `texts/en_US.lang` | `assets/<ns>/lang/en_us.json`, and display names are resolved through it. `~LINEBREAK~` becomes a newline. |
| `sounds/sound_definitions.json` + `.ogg` | `assets/<ns>/sounds.json`, event names kept. |
| `scripts/` + the script module | `bedrock_scripts/`, run on QuickJS. |

Item/block texture lookup can reject TGA files with a conversion warning. The resource-pack texture importer also decodes TGA to PNG; undecodable files are reported. PNG is preferred when both versions exist.

## Scripts

Each converted addon with a script module gets its own QuickJS VM, created when the world starts and restarted on `/koperlib reload`. Modules load exactly the way Bedrock loads them: ES modules, relative imports with or without `.js`, JSON imports, top level `await`. An import can not leave the addon's own `scripts` folder.

The manifest's `@minecraft/server` version decides which API shape the script sees. 1.x and 2.x differ in a few places (`isValid()` versus `isValid`, `GameMode` casing, `onPlayerDestroy` versus `onPlayerBreak`, `worldInitialize` versus `startup`) and both are handled.

The same wall clock limit as Lua applies (`scriptTimeoutMs` in the fullpack config). A runaway loop gets interrupted instead of freezing the server, and the VM keeps working afterwards. A script error is logged with its stack trace and shown to operators in chat.

### What is there

`world`: players and entities with the full query options (type, tags, families, name, location and distance, volume, closest and farthest, game mode, level, scores), dimensions, messages and rawtext, time of day, absolute time, day, moon phase, weather, difficulty, default spawn, dynamic properties, scoreboard, game rules, sounds.

`system`: `run`, `runTimeout`, `runInterval`, `runJob` (generators, about 2 ms of work per tick), `clearRun`, `clearJob`, `waitTicks`, `currentTick`, `sendScriptEvent`, and `/scriptevent` from chat or command blocks.

`Dimension`: blocks, entities and players, `spawnEntity`, `spawnItem`, `spawnParticle`, `playSound`, `createExplosion`, `runCommand`, `setBlockType`, `setBlockPermutation`, `fillBlocks` (32768 limit like `/fill`), `getTopmostBlock`, `getBlockAbove` and `Below`, block and entity raycasts, light levels, biome, weather.

`Entity` and `Player`: location, rotation, velocity, view direction, head location, name tag, sneaking and the other state flags, `teleport` and `tryTeleport`, `applyDamage` with causes, `applyImpulse`, `applyKnockback` (1.x and 2.x signatures), `kill`, `remove`, tags, effects, fire, riding, dynamic properties, `runCommand`, `matches`, view direction raycasts. Components: health, inventory, equippable, movement, type family, is baby, on fire, item, scale, variant, mark variant, skin id, riding and rideable.

`entity.playAnimation(name, {blendOutTime, stopExpression, controller, players})` and `/playanimation <who> <animation> [next_state] [blend_out_time] [stop_expression] [controller]` play on every client near the mob (or only `players`): a runtime controller on top of the entity's own animations, on its own clock, until its stop expression holds (default `query.any_animation_finished`), then fading out over `blend_out_time` from its last frame. A second one with the same controller name replaces the first; the default name is shared, so by default a mob plays one at a time, as on Bedrock. The animation may be any the pack defines that moves a bone of the mob (up to 512 per mob besides the ones it lists), a full id or the entity's short name. `next_state` is accepted and ignored.

`entity.getProperty`/`setProperty`/`resetProperty` on a mob with a behavior definition (an addon mob or one of Java's own a pack redefines) read and write its behavior state: typed, clamped to the property's range, saved with the mob, synced to clients, so render controllers and animations reading `q.property` see what the script set. Before they went to dynamic properties, which nothing on the client sees. Mobs without a definition still use dynamic properties.

Player only: `sendMessage` with rawtext, `onScreenDisplay` (title, subtitle, action bar, times), game mode, level and experience, selected slot, sounds, spawn point, item cooldowns, op status.

`ItemStack`: type, amount, name tag, lore, durability, enchantments, lock mode, keep on death, dynamic properties, tags, stack checks. Stacks round trip through real Minecraft item stacks, so what a script writes survives in the world.

`Container` and `ContainerSlot` for player inventories, container blocks and inventory mobs. A player's container is the 36 slot Bedrock one, hotbar first.

`Block` and `BlockPermutation`: type, states, `withState`, `resolve`, tags, neighbours, waterlogging, redstone power. Common Bedrock state names (`minecraft:cardinal_direction`, `open_bit`, `growth`...) are read and written as their Java twins.

Events, before (cancel works) and after: `chatSend`, `playerBreakBlock`, `itemUse`, `itemUseOn`, `playerInteractWithBlock`, `playerInteractWithEntity`, `playerLeave`. After only: `playerJoin`, `playerSpawn` (initial and respawn), `playerPlaceBlock`, `playerDimensionChange`, `entityHurt`, `entityHitEntity`, `entityDie`, `entitySpawn`, `worldLoad` / `worldInitialize`, `scriptEventReceive`, `entityHealthChanged`, `entityHeal`, `itemStartUse` / `itemCompleteUse` / `itemStopUse` / `itemReleaseUse`, `projectileHitEntity` / `projectileHitBlock` (with `getEntityHit()` / `getBlockHit()`), `effectAdd`, `playerSwingStart`, `playerGameModeChange` (before cancels too), `dataDrivenEntityTrigger` (with `getModifiers()`, added and removed component groups). Subscribing to any other event name never throws: `world.afterEvents.<anything>` is a lazy signal that simply never fires until java learns to send it, so an addon that listens to something koper does not produce yet still loads. Event subscribe filters (`entityTypes`, `entities`, `namespaces`, `itemTypes`, `blockTypes`) are honoured. Java only builds an event when some addon listens to it.

Custom components from `startup` (or `worldInitialize` in 1.x). Items: `onUse`, `onUseOn`, `onConsume`, `onCompleteUse`, `onHitEntity`, `onMineBlock`. Blocks: `onPlayerInteract`, `onPlace`, `onPlayerBreak` / `onPlayerDestroy`, `onBreak`, `onTick`, `onRandomTick`, `onStepOn`. 2.x component parameters arrive as the second argument.

`world.structureManager`: `get`, `place` (rotation, mirror, include blocks and entities, waterlogging, integrity with seed, block by block or layer by layer animation over seconds), `createFromWorld`, `delete`, `getWorldStructureIds`. Structures are the pack's `.mcstructure` files (little endian NBT), ids `mystructure:<name>` or `<folder>:<name>`. Bedrock block names and states become Java block states (`BedrockBloki`); containers with loot, signs, beds, skulls, banners, flower pots and spawners carry their block entity over. A block name it does not know is an ERROR once and turns into air.

Custom commands from `customCommandRegistry.registerCommand`, as `/ns:name` and the bare `/name` when nothing else owns it. Parameters are parsed by type, selectors included.

**Exported names and implemented behavior are separate.** Generated JavaScript modules supply API-shaped enums, classes and component IDs so imports can resolve. Unimplemented methods can throw `koperlib bedrock: X.y is not implemented yet`. An addon loading successfully does not prove that all of its APIs or events are supported.

`@minecraft/server-ui`: `ActionFormData`, `MessageFormData`, `ModalFormData` (text field, dropdown, slider, toggle, labels) show as vanilla dialogs, and `show()` resolves with the Bedrock response shape, `canceled` included. Like Bedrock, a form is not shown while the player is still loading in, has a menu open or is looking at another form: it resolves at once as canceled with `UserBusy`. Packs that re-show a form every few ticks until it is answered (RLCraft's "other packs" notice) depend on that; before, the dialog reopened under the cursor every two seconds and could not be clicked. Button icons are not drawn, a vanilla dialog has no place for them.

The 2.x screens too: `MessageBox` (a dialog, `button1` answers 1 and `button2` 0 as on Bedrock, `closeReason` included) and `CustomForm` with `ObservableBoolean`/`Number`/`String`/`UIRawMessage`. Bedrock keeps a `CustomForm` open and live; a Java dialog closes on any button, so a button writes every field back into its observable, runs its `onClick` and the form is done. Welcome boxes, settings pages and shops work that way.

`world.tickingAreaManager` keeps areas loaded and ticking on Java chunk tickets (`BedrockStrefy`, ticket `koperlib:bedrock_ticking`, radius 2 like `/forceload` so mobs tick too). `createTickingArea` resolves once every chunk in it ticks, `isFullyLoaded` is real. Areas last until the server stops; Bedrock keeps them over a restart.

**Camera.** `player.camera.setCamera`, `fade`, `clear` and the `/camera` command (`set <preset> [ease <time> <type>] [pos x y z] [rot x y] [facing <x y z|entity>]`, `fade [time in hold out] [color r g b]`, `clear`). `minecraft:free` pins the view and eases there with every curve of `EasingType`, facing a spot or following an entity; the first and third person presets switch Java's view. The server only sends the request (`BedrockKameraPayload`), the client draws it (`BedrockKamera`, `BedrockKameraMixin`, a HUD layer for the fade).

**Names.** Scripts' block and item ids, `give`/`clear`, loot tables and recipes take Bedrock's own names (`muttonRaw`, `record_cat`, `dye:4`, `wool:14`, `wooden_pressure_plate`, `slime`) through one table in `BedrockNazwy`. Sounds too: `koperlib_core/bedrock_dzwieki.json` pairs 1092 of Bedrock's vanilla sound events with Java's by the sound files both games play (`random.explode` is `entity.generic.explode`, `dig.stone` is `block.stone.break`), with a short hand list where that guessed wrong; `/playsound`, `playSound` and mob sounds all use it, and a name Java has as is stays.

**A promise rejected with nobody catching it** is logged as `Unhandled promise rejection: ...`, like Bedrock does, once the job queue drained (a `.catch()` added a moment later still counts). An async `start()` that threw used to leave an addon stuck and silent.

### Commands

Bedrock and Java commands are close but not the same. Everything a script runs, every line of a converted function, `queue_command` and behavior pack animation commands go through a translator first (`BedrockSkladnia`, no Minecraft classes in it, tested in `BedrockKomendyTest`): `effect <who> <effect>` without `give`, `give` and `clear` data values, numeric and short game modes, `titleraw`, `testfor`, `replaceitem`, `xp 5L`, `playsound` without a source, old style `execute <who> <pos> <command>`, quoted block states, and selector arguments `r`, `rm`, `c`, `m`, `l`, `lm`, `rx`, `ry`. Also `tellraw` and `titleraw` with `rawtext` (to a Java text component, `translate`/`with`, `selector` and `score` kept), `scoreboard players random` and `test`, `setdisplay` sort orders, `testforblock(s)`, data values in `setblock` and `fill`, `tp ... true`, `ride start_riding/stop_riding`, `damage` causes and `entity <damager>`, entity types in `summon` and `type=`. `/particle` goes to `bparticle` (Bedrock effects, a pack's own included, through Kodel's particle engine), `/summon` with a spawn event to `bsummon` (spawns, then fires the event), and `/event entity`, `/playanimation` exist as commands. Commands that only exist on Bedrock (`camera`, `tickingarea`, `inputpermission` and so on) are passed through and fail like any unknown command.

## Resource packs on the client

Everything below lives in the Kodel module and runs natively in `engine/kodel` (`aktor.rs`, `czastki.rs`) on the shared MoLang compiler in `engine/koperlib-molang`.

**MoLang.** The full syntax from the creator docs: statements, `return`, `loop`, `break`, `continue`, `{}` blocks, `?:` and `?` with the 1.18.20 precedence, `??`, strings, `temp`/`variable`/`context`/`query`, the aliases, arrays from render controllers, and every `math.*` function including the easing set. Constant subexpressions are folded at load. Every `query.*` call with constant arguments becomes a numbered slot that Java fills once per frame, so there is no string lookup or callback per query. Queries answer from the real entity; the list is in `BrPytania.java`. A query that has no Java counterpart answers 0, which is what Bedrock does for a query that does not apply.

**Entities.** A client entity from a pack replaces the drawing of the entity with that identifier, vanilla mobs included. Everything in the client entity is run: `initialize`, `pre_animation`, the `animate` list with blend expressions, animations (keyframes with MoLang, `linear`/`catmullrom`/`step`, `pre`/`post`, `loop`/`hold_on_last_frame`, `anim_time_update`, `blend_weight`, `start_delay`, `loop_delay`, `override_previous_animation`, `relative_to`, timelines), animation controllers (states, transitions, `blend_transition` cross fades, `on_entry`/`on_exit`, `all_animations_finished`), render controllers (texture and geometry arrays, `part_visibility`, overlay and hurt colours), `scale`, and sound and particle effects on animation keys. Geometry becomes a Kodel model in memory, so Kender draws it on the GPU when it can. Bedrock geometry is mirrored on X relative to Java model space: the native actor poses bones in Bedrock space and `BrAktorzy` conjugates each bone matrix by the X mirror into Kodel's render space (`KodelBedrock.toRenderSpace`, the space kopermod's models already draw in).

Rotation convention, checked by `BrLustroTest` and the cow test in `engine/kodel/src/aktor/tests.rs`: a Bedrock euler `[rx, ry, rz]` is, in Bedrock's own y-up unmirrored space, the textbook ZYX rotation with angles `(-rx, ry, -rz)`. Blockbench and GeckoLib flip the same two axes. The textbook signs put a cow's udders on its back.

**Old client entities (format 1.8.0)** list their controllers in `"animation_controllers": [{"move": "controller.animation.x.move"}]` and every one of them runs, with no `scripts.animate`. Their short names are their own namespace (a cat has an animation and a controller both called `look_at_target`), so they run under the full id. Before, none of them ran and every mob from an older addon stood frozen in its geometry pose. A name missing everywhere gets one more try with underscores ignored (old packs say `controller.animation.polarbear.move`, vanilla has `polar_bear` now).

**`bind_pose_rotation`** (1.8 geometry: Bedrock's cat, ocelot and every pack copying them) is the bone's rest turn. It is where the bone sits in the model, not a turn its children inherit: a child's pivot is already written where the turned parent put it. So a child takes its parent's bind pose back out, turn and pivot (`KodelConverters.spoczynek`). Unread, the cat's body stood on its end; summed naively, its tail pointed at the sky.

**Built ins and the vanilla base.** Addons name things Bedrock ships without shipping them: `animation.common.look_at_target` (in 43 of 73 client entities across Microsoft's sample addons), `controller.render.default`, `controller.render.item_default`, `controller.render.armor`, the humanoid look animations, vanilla geometry like `geometry.chicken`. Two layers under every pack answer those lookups; the packs themselves always win:

* `koperlib_kodel/bedrock_wbudowane.json` in the kodel jar: KoperLib's own minimal stand ins for the look animations and the three render controllers. They are not used for entities that stay on the java model (the player), whose head look and walk Java already does.
* `koperlib/bedrock_vanilla/`, optional: Bedrock's own resource pack, dropped there by the player (the `resource_pack` folder of Mojang's `bedrock-samples` repository, or the folder itself). It cannot ship with KoperLib, Mojang keeps its rights on it. With it every vanilla animation, controller, render controller and geometry an addon names resolves exactly as on Bedrock: on Microsoft's sample addons, names that resolved to nothing went from 132 to 2 (and those 2 are missing from the samples themselves). Its client entities, attachables, particles and materials are not used, it never draws anything by itself; its player body geometry (`geometry.humanoid*` apart from the armor pieces) is skipped so the player stays on the java model. With it, a player pack naming Bedrock's own movement animations gets them instead of Java's walk; that path has not been looked at in game yet.

**Geometry inheritance.** A 1.8 geometry named `geometry.child:geometry.parent` starts from its parent's bones: a child bone with the same name (case ignored) replaces the parent's whole, new ones go at the end, the texture size is the child's when it gives one and the parent's otherwise. The parent can live in another pack of the stack or in the vanilla base. Bedrock's sheep, witch, zombies, pigmen and all armor pieces are built like this. Before, the child was drawn alone. The behavior pack converter does the same for the models it bakes.

**Scripts spread over several lines.** `pre_animation`, `initialize`, `on_entry`, `on_exit`, `parent_setup` and timeline arrays are one expression on Bedrock: a `(v.x) ? {` in one entry and its `};` twenty entries later is valid (vanilla husk, piglin and zombie pigman do it). Each entry is compiled alone first; when one does not parse, the whole array is glued (a `;` added after entries that end a statement without one) and compiled as one.

**`this`** in a bone channel is what the animations before it left on that channel, so `q.target_x_rotation - this` puts the head exactly on the target whatever else turned it.

**Materials.** A pack's `materials/*.material` files are read and each material is walked down its `name:parent` chain to a stock one, collecting `+defines`/`-defines`/`+states`/`-states`, `blendSrc`/`blendDst`. The result picks the Java render type (`BrFarby`): `Blending` → translucent (`One`/`One` → additive), `ALPHA_TEST` → cutout, neither → solid (stock `entity` ignores alpha, packs keep masks in it), `DisableColorWrite` → not drawn. Two families read alpha as something other than coverage and are drawn in two passes from a split copy of the texture (`BrMaska`): `USE_COLOR_MASK`/`MULTI_COLOR_TINT` (every `*change_color*`, `*multicolor_tint*`, leather `armor`), where high alpha pixels are multiplied by the mob's color (sheep wool, dyed leather), and `USE_EMISSIVE` (every `*emissive*`), where high alpha pixels are drawn full bright and `USE_ONLY_EMISSIVE` draws only those. In both, a pixel with alpha 1..127 is an ordinary opaque pixel; only alpha 0 is a hole, and only under `ALPHA_TEST`. A plain cutout ate them: sheep without legs, most held items gone. The render controller's `materials` list assigns them per bone with wildcards, later entries winning.

**Several render controllers.** Every render controller whose condition passes draws, each with its own geometry, texture and materials (the native actor reports them through `kodel_br_layers`). The first one on the main geometry is the base; the rest draw on top (golem cracks, faces, extra parts). A layer on another geometry ticks its own instance of the animations. Each controller's `part_visibility` is its own (`kodel_br_layer_vis`): it hides parts in that controller's layer only, the first controller's is the model's. Merged into one, Bedrock's villager (`villager_v3_level` hides every part for a villager without a job, its badge layer only) was invisible.

**`this` on a position channel** is 0 for addons, as on bedrock today. Vanilla's old wolf geometry (`geometry.wolf`, `geometry.wolf.armor`) is the one legacy case: its setup animation says `"-14 - this"` (body, pivot y 10), meaning "stay where the pivot puts the bone", so for those two geometries `this` starts at the bone's pivot `(x, y - 24, z)` (`BrPaczki.legacyPozycja`, the `legacy_pos` flag). Doing that for every geometry broke Mojang's own MC Live bear and biceson. On a rotation channel `this` is what the animations before left (the rest pose is not in it): the new chicken's `"-this"` keeps its rest pose.

**`query.standing_scale`** is how far a mob is up on its hind legs (polar bear's standing animation, or the `is_standing` flag), 0..1. It is not the model scale; answering 1 made every pack bear stand up. `query.model_scale` is the scale.

**Rest poses Bedrock keeps in the engine.** A few vanilla geometries lie flat on Bedrock with nothing in their files to say so (their old Java models set it in code): the villager's hat brim, the chicken's and the cat's bodies. Packs copying those files got an upright brim and standing bodies, so `BrPaczki.legacyPozy` gives those bones (`brim` on any villager geometry, `body` on chicken, cat, ocelot) Java's rest turn when they have none of their own.

**Two files, one identifier.** Vanilla keeps `creeper.entity.json` next to `creeper.v1.0.entity.json`; as on Bedrock, the one with the highest `description.min_engine_version` wins (none counts as the oldest). Before, whichever file was read last won: the old creeper drew its charged layer always, the old skeleton its arms off the body.

`KOPER_ZRZUT=<entity type>` in the client's environment logs the nearest such actor every 200 frames: its queries, variables, bone visibility and the layers it drew.

**The player and other humanoids.** When a pack animates a geometry that Bedrock itself ships (players: `geometry.humanoid.custom`), the Java model stays. Each Java part gets the full world matrix of its Bedrock bone, so `root`, `waist` and `body` carry the head and arms the way Bedrock's bone tree does: `part = F·W·F·base` with `F` the y flip around 24px. If every movement animation the entity names is in the packs, the pack owns the pose and Java's walk is off (`base` = rest pose). If it names Bedrock's own movement animations, which we do not have, Java's walk stands in and the pack's animations go on top. A missing `look_at_target` keeps Java's head look only. Extra render controller layers draw over the Java model.

**Attachables.** Held items and worn armour with an attachable draw the attachable instead of the item or armour model, third person and first person (the arm is drawn like an empty hand and the attachable rides it, with `c.is_first_person` set). A bone with `binding` is placed relative to the pivot of the bone it binds to (`q.item_slot_to_bone_name(c.item_slot)` → `rightItem`/`leftItem`); a bone without one that is named like a holder bone (`head`, `rightArm`…) follows that bone in the holder's model space. An attachable's `item` condition on `is_owner_identifier_any` is honoured.

**Texture meshes** (`texture_meshes` on a bone, used for held tools) become geometry only where the texture has pixels, one 1px slab per run of solid pixels in each row, so they work on opaque materials the way Bedrock's do. The placement follows Blockbench's reading (`bedrock.js` import and `texture_mesh.js`), the only reference there is: the picture lies in X/Z (u along +x, v along +z), one pixel deep going down, `local_pivot` is added unscaled, the rotation is a cube rotation about the mesh origin, and **`position` y counts down from the bone's pivot** (`origin.y = pivot.y - position.y`). Checked on A&S's sword: its blade middle lands exactly on the helper bone the pack placed at (0, 11.31). With y as authored the sword hung 20px under the hand. Texture pixels are read with `BrPng` (no stb), so meshes build off the render thread and in tests; `BrMeshTest` prints where a held item's mesh ends up. Edge faces are the border texels, not Java's per pixel sides.

**Particles.** Every emitter and particle component in the docs: `emitter_initialization`, lifetime `once`/`looping`/`expression`, rates `instant`/`steady`/`manual`, shapes `point`/`sphere`/`box`/`disc`/`custom`/`entity_aabb`, `local_space`, `initial_speed`, `initial_spin`, `lifetime_expression`, `kill_plane`, `motion_dynamic`, `motion_parametric`, billboards with all eleven facing modes, UVs and flipbooks, tinting including gradients, lighting, curves (`linear`, `bezier`, `catmull_rom`, `bezier_chain`), and events (`sequence`, `randomize`, sub effects, sounds, expressions) on emitter and particle lifetimes and timelines. Effects start from animation keys (at the named locator, following the mob), from `emit_particle` in behavior events, and from `dimension.spawnParticle` in scripts, which reaches the client through a payload.

**Vanilla textures.** Pack textures under `textures/blocks` and `textures/items` replace the Java texture with the same meaning. Bedrock's legacy names (`log_oak`, `planks_birch`, `wool_colored_red`, `silver`, `sword_iron` and so on) are mapped by rule and checked against the textures the game really has. `.tga` is decoded without `java.desktop` (true colour, grey, colour mapped, each raw or RLE), and a `.tga` that is really a PNG stays a PNG.

A replacement is only made when it means the same thing in Java: a texture with see-through pixels whose Java twin is opaque is a tint mask (grass sides) and is skipped; a strip listed in `textures/flipbook_textures.json` gets an `.mcmeta` from it (`ticks_per_frame`, `blend_frames`, `frames`); any other size or aspect that differs from Java's keeps Java's; a texture Java animates gets Java's `.mcmeta` copied along. Entity textures a pack names by Bedrock's vanilla path (`textures/items/gold_sword`) fall back to Java's (`textures/item/golden_sword`).

## Natural spawning

A behavior pack's `spawn_rules` come along (`bedrock_bp/spawn_rules`) and `BedrockRozsiewacz` spawns the addon's mobs itself, since Java's spawner only knows mobs with biome spawn lists. Once a second, three tries per player 24 to 64 blocks away: a surface spot, a cave spot (air with a sturdy floor) and water for `spawns_underwater`, each checked against every rule: `brightness_filter` (`adjust_for_weather`), `height_filter`, `difficulty_filter` (monsters never on peaceful), `distance_filter`, `spawns_on_block_filter` and `spawns_on_block_prevented_filter`, `biome_filter`, `density_limit`, plus a cap per `population_control` group around the player (monster 25, animal 12, water animal 6, ambient 8). A passing rule is picked by `weight`, spawns its `herd` (`permute_type` included) and fires its `spawn_event`. The mob spawning gamerule is honoured. Rules for Java's own mobs are left to Java. Delay, world age, mob event and village filters are ignored.

Bedrock's biome tags that Java has no tag for are read by meaning, for spawn rules and every other filter: `monster` (everywhere but mushroom fields and the deep dark), `animal`, `cold`, `frozen`, `warm`, `mesa`, `roofed`, `mega`, `extreme_hills`, `hills`, `mooshroom_island`, `deep`, `caves` and the like; the rest by `is_<tag>` and the biome's name, as before.

## Java's own mobs in a pack (Villager News and friends)

Bedrock and Java call some mobs differently (`villager_v2`/`villager`, `zombie_villager_v2`, `evocation_illager`/`evoker`, `zombie_pigman`/`zombified_piglin`, `ender_crystal`, `fishing_hook`, `xp_orb`, `thrown_trident`, `tropicalfish`, boats...). `BedrockNazwy` in core is the one table both ways, and everything uses it:

* **Drawing.** A resource pack's `minecraft:villager_v2` client entity draws Java's villager (before: nothing matched, the pack's villager was never used). When a pack has both the old and the current name, the current one wins.
* **Villager numbers.** `variant` is the job, `mark_variant` the biome and `skin_id` one of six faces, numbered like Bedrock's villager file (`BedrockWiesniak`, from Java's `VillagerData`), for render controllers, behavior filters and `getComponent("variant"|"mark_variant"|"skin_id")` in scripts. The generic registry order the other mobs use is not Bedrock's order for villagers.
* **Scripts** see Bedrock's names: `entity.typeId` is `minecraft:villager_v2`, `spawnEntity`, `EntityTypes`, entity queries (`type`, `excludeTypes`) and event filters take either name.
* **Commands:** `summon villager_v2`, `@e[type=villager_v2]`, `type=!zombie_pigman` are translated.
* **Behavior.** A behavior pack that redefines one of Java's mobs (`minecraft:villager_v2` with the pack's properties, groups and events) no longer gets skipped: the converter writes it to `bedrock_bp/entities/vanilla/` and `BedrockZachowanie` lays it over the Java mob as a *nakladka*. Java stays the mob (health, speed, AI, breeding, trading, taming, growing up, eggs, how much damage hurts); the pack gets properties (synced to clients, so the resource pack's queries and render controllers see them), component groups, events from scripts (`triggerEvent`), `minecraft:timer`, the environment sensor, damage sensor events, `on_hurt`/`on_death`, `set_property`, `queue_command`, sounds and particles. Hooks come from `BedrockZyjeMixin` (tick, hurt, die) and `BedrockWidzeMixin` (a player starting to see the mob gets its state).
* **Voices.** A pack giving Java's mobs new sounds (its `sounds.json` says `villager_v2`'s `ambient` is `mob.villager.idle` and its `sound_definitions.json` brings files for it, or it just redefines `mob.<mob>.idle|hit|death|step...`) becomes `replace` entries in `assets/minecraft/sounds.json` for Java's events (`entity.villager.ambient`, `haggle` → `trade`, `haggle.yes`/`no` → `yes`/`no`, `ambient.in.water` → `ambient_water`...). Only when the pack really has files for it and Java really has the event (read from the game jar), so on a dedicated server nothing happens. Sounds that exist only as `.wav`/`.fsb` are listed in `bedrock.koper.json`: Java plays ogg only.

None of this has been seen in game yet.

## Behavior packs at run time

Entities keep their whole behavior definition. `bedrock_bp/entities` in the converted pack is read by `BedrockZachowanie`:

* component groups are added and removed by events, and the mob follows: health, movement, attack, scale, knockback resistance, follow range, baby, persistence, and AI goals are rebuilt when behaviour components change;
* events run `add`, `remove`, `sequence`, `randomize`, `trigger`, `set_property` (MoLang, evaluated natively on the server), `queue_command`, `reset_target`, `play_sound` and `emit_particle`, with `filters` on any node;
* sensors and triggers: `environment_sensor`, `timer`, `interact`, `damage_sensor` (cause, `deals_damage`, `damage_multiplier`), `on_hurt`, `on_hurt_by_player`, `on_death`, `on_target_acquired`, `on_target_escape`, `tameable`, `healable`, `ageable` with `grow_up`, `spawn_entity`, `transformation`, `explode`, `instant_despawn`;
* AI: `behavior.*` components become goals whenever they change: float, melee attack (plain and box), ranged attack with the mob's `minecraft:shooter` (`BedrockStrzelec`: walks into `attack_radius`, shoots `def` every attack interval; the projectile comes from its entity type and flies with `Projectile.shoot`, a pack's own projectile that converted to a plain mob is pushed the same way), leap at target, panic, tempt (with its items), random stroll/swim/fly, look at player, look around, hurt by target, avoid, restrict sun and flee sun, and `nearest_attackable_target` with its `entity_types` (`BedrockCelownik`: every entry's filters with the candidate as `other`, `max_dist`, `must_see`), so zombies go for villagers and wolves for sheep instead of only players. Without `entity_types` it is players, as before;
* behavior pack animations and animation controllers: an entity's `description.animations` and `scripts.animate` (plus `initialize`, `pre_animation`) run on the server in a native actor (`engine/koperlib-fullpack-native/src/bp.rs`), compiled once per type: timelines, controller states, transitions, `on_entry`/`on_exit`, `anim_time_update`, loops, `q.all_animations_finished`. Lines starting with `/` run as commands on the mob, `@s event` fires a behavior event on it (`@target` on its target), everything else is MoLang with the mob's own variables. Queries come from the server: properties, variant and friends, health, water/rain/lava, fire, ground, moving, sneaking, target, riders, time of day, moon phase, position, families, scores;
* `/event entity <who> <event>` exists as a command, for functions, scripts and `queue_command`;
* `minecraft:projectile`: the mob is converted with no AI and `BedrockPocisk` flies it: `gravity`, `inertia`, `liquid_inertia`, the first block or entity on its path (not its shooter), then `on_hit`: `impact_damage` (value or range, knockback, catch_fire), `mob_effect`, `catch_fire`, `teleport_owner`, `definition_event`, `particle_on_hit`, `stick_in_ground`, `remove_on_hit`. The shooter is kept in a `bowner:<uuid>` tag. Scripts shoot with `getComponent("projectile").shoot(velocity, {owner})`, which also works on Java's projectiles;
* `minecraft:boss`: a boss bar (its `name`, a lang key or text; `should_darken_sky`) for every player within `hud_range`, following the mob's health, gone when it dies or unloads. Not on Java's own mobs, the wither and the dragon have theirs;
* filters: about sixty of the tests in the docs, with subjects, operators and `all_of`/`any_of`/`none_of`. A test Java has no counterpart for logs once and reads false;
* entity properties with their types, ranges and defaults, plus `variant`, `mark_variant`, `skin_id`, families and the `is_*` flags, are synced to clients, so resource pack queries read the server's values;
* scripts reach all of it through `entity.triggerEvent` and `getProperty`.

Groups and properties are stored in entity tags (`bcg:<group>`, `bprop:<name>=<value>`), so they survive saving.

**Blocks** get their states as real Java block states (`bool`, `int` range, and string enums through a custom property), traits (`placement_direction` becomes `facing` with Bedrock's meaning, `placement_position` becomes `facing`/`half`), and permutations expanded at conversion. Each state combination evaluates the permutation conditions with the native MoLang, and the result becomes blockstate variants with their own textures and rotations, plus light per state.

## Known limits

**Mob voices.** A pack mob's `sounds.json` `entity_sounds` (ambient, hurt, death, step, volume, pitch ranges, `defaults`) reaches the mob (`BedrockGlos`, sidecar `voices`). Before, every addon mob was silent. Other events there (attack, roar, fall) are not played yet.

**Sounds that replace vanilla ones.** Entity sounds are mapped (see Java's own mobs above). Block, item and UI sounds Bedrock and Java name with no rule between them (`individual_event_sounds`, `block_sounds`) are still not applied. A pack's own sounds and every sound an addon plays by name work.

**Bedrock-only systems:** JSON UI (screens and HUD), feature and biome rules, trading tables, NPC dialogue, camera presets from `cameras/`, fog and Vibrant Visuals lighting files, sign text, and the `server-net`, `server-admin` and `server-gametest` modules (these import but throw when used). A script call into any of them throws an error naming it.

**Actor references in MoLang**: `c.owning_entity -> v.name` reads the holder's variable in an attachable, strings included, and an attachable's `parent_setup` runs on its holder's variables (one frame late). Every other `->` and `for_each` over entities reads 0 on the client; there is no entity graph on that side.

**Engine variables.** Bedrock's engine sets some variables on actors itself and packs that replace the player read them without ever writing them: `v.attack_time` (swing), `v.is_first_person`, `v.is_paperdoll` (the inventory preview, which also answers `q.is_in_ui` and gets its own actors), `v.is_holding_right`/`left`, `v.player_x_rotation`, and on attachables `v.is_enchanted`/`v.has_trim`. They are set before `pre_animation` every frame. `c.item_slot` is a string context (`main_hand`, `off_hand`, `head`, `chest`, `legs`, `feet`).

**A query computed at run time** (`q.relative_block_has_any_tag(0, t.y, 0, 'water')` with a variable argument) has no slot and reads 0. The same query with constant arguments works. The pure ones (`q.in_range`, `q.any`, `q.all`, `q.approx_eq`) are compiled into plain compares and take any arguments.

**Loot tables Bedrock ships.** An entity or block naming a table the pack does not have (`loot_tables/entities/armor_stand.json`) gets a table that hands over to Java's own one of the same name, and a nested `loot_table` entry points at the pack's table or Java's. Before, the first dropped nothing and the second was skipped.

**Trailing junk after the json.** Some packs glue a second value after the real object (`{"Code by ... DO NOT STEAL"}`). Bedrock reads the first value and stops; KoperLib does the same. A file that does not parse at all is an ERROR naming it, not a silent gap.

**Bedrock's own geometry and animations.** Packs name vanilla Bedrock things they do not carry: `geometry.shield`, `geometry.elytra`, `geometry.fireball`, `geometry.arrow`, `geometry.wither_skull`, bow and crossbow poses, `animation.humanoid.*`. Those resolve only with `koperlib/bedrock_vanilla/` in place (see below). Without it, measured on 2026-10-01: RLCraft names 80 animations and controllers and 18 geometries it does not have, Actions & Stuff 33 and 5 (its shield and elytra among them); with it 9 and 0, 6 and 1.

**Behaviors that change a mob's shape and state.** `minecraft:collision_box` from component groups resizes the mob live and reaches the client (it picks what the crosshair hits). `minecraft:entity_sensor` (old and `subsensors` form), `minecraft:target_nearby_sensor` (with the gap between `inside_range` and `outside_range`) and `minecraft:behavior.emerge` (the emerging pose for `duration`, then `on_done`) run. A transformed mob gets `minecraft:entity_transformed`, a spawn event given to `summon`, `spawn_entity` or a spawn rule replaces `minecraft:entity_spawned`, and `spawn_entity` honours `num_to_spawn` and `single_use`. Before these, RLCraft's tree spirit stayed half buried and unhittable forever, Mowzie's dying foliaath left an unkillable copy behind, and RLCraft's peacock multiplied without end.

**Pack sounds by name.** A Bedrock sound name is any string, `:` included (Villager News plays `oreville_vn:acuqxm`). The converter files each under the pack's namespace with every character Java refuses turned into `_`, and scripts, functions and animation sound effects resolve names by the same rule; a client entity's `sound_effects` entry may be a plain string as well as `{"effect": ...}`. Before, Villager News' villagers were silent and every Mowzie's animation sound was unknown.

**Server side molang** in behavior pack controllers answers `q.is_delayed_attacking`, `q.is_levitating`, `q.has_any_effect`, `q.is_attacking`, `q.modified_move_speed` and any `q.is_<flag>` for a flag component the mob has (0 when it lacks it). A query with no server side answer is logged once instead of silently reading 0. Mowzie's Ferrous steps its whole attack chain (stomp, stuck, open back) on these.

**Attacks.** `minecraft:behavior.delayed_attack` is its own goal: the mob walks up at its own `speed_multiplier`, stops in reach (`reach_multiplier` times twice its width), winds up for `attack_duration` and lands the hit at `hit_delay_pct` (once with `attack_once`), standing still the whole swing; the client sees it as `q.is_delayed_attacking`. A `melee_attack` with `reach_multiplier: 0` only follows and faces its target. `minecraft:ageable` counts down on grown mobs too, packs use it as a clock. Before, Mowzie's Ferrous Wroughtnaut ran at 2.5 times its speed with plain instant hits and its stomp never came, and its attack state machine stalled on the ageable clock.

**Client definitions load asynchronously.** The runtime builds a type's definition on a background worker, retaining vanilla rendering while it loads. An entity with no client definition may have no model to render.

**Despawning.** Pack mobs leave the world the Bedrock way: only with `minecraft:despawn`, when its filters pass and the nearest player is beyond `despawn_from_distance` (max at once, min now and then); `minecraft:persistent` never. Java's own 32/128 block rule made Villager News' villagers and RLCraft's bosses vanish while scripts still held them.

**Marketplace packs are encrypted** and cannot be read. Plain `.mcpack`/`.mcaddon` files are fine.

## Source and verification

The Fullpack converter and JavaScript world bridge live in [`modules/koperlib-fullpack/src/main/java/com/koper/koper_lib/bedrock/`](../modules/koperlib-fullpack/src/main/java/com/koper/koper_lib/bedrock/). Kodel's resource-pack client runtime lives in [`modules/koperlib-kodel/src/main/java/com/koper/koper_lib/kodel/bedrock/`](../modules/koperlib-kodel/src/main/java/com/koper/koper_lib/kodel/bedrock/).

The native JavaScript VM is under [`engine/koperlib-bedrock-js/`](../engine/koperlib-bedrock-js/), MoLang under [`engine/koperlib-molang/`](../engine/koperlib-molang/), and native actors and particles under [`engine/kodel/`](../engine/kodel/).

```bash
./gradlew :koperlib-fullpack:test :koperlib-kodel:test
cd engine
cargo test -p koperlib-bedrock-js -p koperlib-molang -p kodel
```

The Java tests include converter, packaging and model checks. Native tests use isolated runtimes; they do not establish full addon compatibility in a running world.

For a locally available pack, `KOPER_BEDROCK_PACK=<file>` can be used with the `BedrockPrawdziwyTest` Gradle test, and `KOPER_BEDROCK_CONVERTED=<folder>` with `BrPrawdziwyTest`. Inspect reported unsupported components and test the actual client/server behavior.

*Claude AI used for documentation.*
