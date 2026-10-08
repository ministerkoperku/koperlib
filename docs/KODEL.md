# Kodel

`.kodel` is a single binary container for a low poly model: geometry, skeleton,
animations and texture in one zip. KoperLib reads it in Rust through Panama and falls
back to Java when the native is missing.

The format reference and authoring tools are kept in the Kopermod repository under
`kodel/SPEC.md`, `kodel/python/` and `kodel/blockbench/`. Use the reference version
matching the installed runtime.

Kodel is KoperLib's only model system. It draws model blocks, fullpack and Java mobs,
armour, item icons and the optional player model, and supplies bone hitboxes, bone
anchors and physics shapes. The older KGecko module was removed in October 2026; pack
json written for it keeps working, because Kodel reads the same keys and converts
Bedrock geometry on load (see [Geo files in a pack](#geo-files-in-a-pack)).

## Contents

1. [The layout](#the-layout)
2. [Where models live](#where-models-live) and [geo files in a pack](#geo-files-in-a-pack)
3. [Posing](#posing)
4. [Entities](#entities) and [choosing a clip](#choosing-a-clip)
5. [Blocks](#blocks) and [blocks bound from the pack](#blocks-bound-from-the-pack)
6. [Armour](#armour), [item icons](#item-icons) and [the player model](#the-player-model)
7. [Bone hitboxes](#bone-hitboxes), [combat](#combat) and [bone anchors](#bone-anchors)
8. [Converting](#converting), [Kender](#kender), [Native](#native)
9. [Settings](#settings), [checking a model](#checking-a-model), [limits](#limits)

## The layout

A `.kodel` is a zip. Any zip tool opens it.

```
my_model.kodel
  manifest.json          metadata and the entry index
  model.bin              geometry and skeleton
  animation.anim.bin     clips, optional
  texture.png            optional, 0..n
```

`manifest.json` names the other entries and carries `frame`, the atlas size the face UVs
are measured against. `model.bin` deliberately does not store that number, so anything
writing a container has to fill `frame` in. `KodelPackager.pack(model, clips, png, name)`
takes the live model and fills it in, which is the reason that overload exists.

## Where models live

Drop them in a fullpack:

```
my_pack/
  kodel/
    kopertrex.kodel
    ritual_table.kodel
```

Everything else asks for them by bare name, no extension and no namespace:

```java
KodelBook.Entry entry = KodelBook.get("kopertrex");
```

`KodelBook` searches every enabled pack source, parses once, and keeps the rest pose, the
resolved animation tracks and the bone hitboxes next to the model. A model that fails to
parse logs an error naming the file, poisons its own name and nothing else, so one broken
file cannot take the renderer down with it. `KodelBook.clear()` runs on resource and
fullpack reload; `KodelBook.forget(name)` drops one model.

## Geo files in a pack

A fullpack may still ship Bedrock geometry instead of a `.kodel`:

```
my_pack/
  models/frost_wolf.geo.json        (or geo/frost_wolf.geo.json)
  animations/frost_wolf.animation.json
  textures/entity/frost_wolf.png
```

Fullpack hands these files to `KodelGeoSources` by base name, and the first
`KodelBook.get("frost_wolf")` converts them through `KodelConverters` into the same
container a packed `.kodel` would be. The clips come from the `.animation.json` of the same
base name. A packed `.kodel` of the same name always wins, so converting a model by hand
changes nothing for the json that uses it. `.geo.hjson` is read as well.

## Posing

```java
float[] bones = KodelBook.pose("kopertrex", "walking_animation", seconds, reusableBuffer);
```

Sixteen floats per bone, column major. Pass a buffer that is big enough and it gets
written into rather than replaced. An unknown clip name gives the rest pose instead
of nothing, which is easier to debug on screen than an empty model.

`KodelAnimationController` is an optional playback head: loop, hold or once,
speed, reverse, and it folds time into the clip domain. One head per animated
object.

## Entities

A fullpack entity draws its `model` through `KodelMobek`:

```json
{
  "max_health": 30,
  "model": "frost_wolf",
  "texture": "frost_wolf",
  "idle_animation": "idle",
  "run_animation": "run",
  "attack_animation": "attack",
  "death_animation": "death",
  "spawn_animation": "emerge",
  "color": "#bfe6ff"
}
```

The named clip fields pick what plays in the common situations. `preset_animations: true`
fills any blank one with the Blockbench defaults (`idle`, `walk`, `attack`, `death`);
without it a blank field means no clip. `color` / `colors` tint the whole model, which saves
recoloured copies of one mob. The render box is widened by the model's own size, so a big
model does not blink out at the edge of the screen.

Java mods register `KodelMobek` directly, or go through `KodelEntityBridge` without naming
any Kodel type.

### Choosing a clip

Highest first:

1. a clip a script asked for with `entity:play_animation`, for its hold time (the server
   sends `KodelClipPayload`; the client lets it expire on its own)
2. the first matching `animation_conditions` rule
3. `death_animation` while dying
4. `run_animation` once the mob moves faster than a small threshold
5. `idle_animation` otherwise

`attack_animation` is laid over the base clip on the bones it owns, starting when the mob
swings and playing to its end, so a jaw can bite while the legs keep walking. A forced or
ruled clip owns the whole body and suppresses the attack overlay. Clip changes cross-fade
over 0.2 s, and clips whose names read like locomotion (`walk`, `run`, `swim`, `fly`,
`crawl`) advance with the distance walked rather than the clock, so a mob does not
moonwalk.

```json
{
  "animation_conditions": [
    { "when": "health_below", "value": 5, "play": "limp" },
    { "when": "in_water",                  "play": "swim" },
    { "when": "always",                    "play": "idle" }
  ]
}
```

Conditions are evaluated on the client, where everything they ask about already is:
`always`, `health_below`, `health_above`, `health_percent_below`, `on_fire`, `in_water`,
`in_lava`, `on_ground`, `in_air`, `sneaking`, `sprinting`, `attacking`, `dying`, `moving`,
`still`, `baby`, `invisible`, `riding`, `just_spawned`. First match wins, so put `always`
last. An unknown `when` behaves like `always`, which makes a typo show as a stuck pose
rather than a silently ignored list. `spawn_animation` is a `just_spawned` rule covering
the first 40 ticks.

## Blocks

A block entity implements `KodelKlocowy` and says what it wants drawn:

```java
public class RitualTableEntity extends BlockEntity implements KodelKlocowy {
    @Override public String kodelModel()      { return "ritual_table"; }
    @Override public Identifier kodelTexture() { return MY_TEXTURE; }
    @Override public String kodelClip()       { return active ? "spin" : null; }
    @Override public float kodelScale()       { return 1.5f; }
}
```

and gets registered like any other renderer:

```java
BlockEntityRenderers.register(RITUAL_TABLE, ctx -> new KodelKlocRender<>());
```

`KodelKloc` is the transform underneath, usable on its own for item icons, kontraptions
or anything else that needs a model on a block. It puts the model at the centre bottom of
the block and turns it to whatever `HORIZONTAL_FACING` or `FACING` says, rotating around
the middle of the block rather than its floor so a wall mounted model does not swing out
of its own hitbox. The same transform is available as a plain `Matrix4f` from
`KodelKloc.placement`, which is what the hitbox path uses, so the boxes and the pixels
cannot drift apart.

## Armour

An armour item says what it wears in its own json:

```json
{
  "type": "helmet", "slot": "head",
  "model": "fine_armor", "armor_texture": "fine_armor",
  "armor_bones": { "bipedHead": "head", "bipedBody": "body", "armorHead": "none" }
}
```

`armor_bones` maps a bone on the worn model to a limb on the wearer. Every bone then
moves by the amount its limb has moved away from rest, so at rest the armour sits
exactly where it was authored and nothing doubles up, and a sneak or a swing carries it
along. A value of `none` pins a bone where it is. Leave the map out and the bone names
are matched by ear instead.

`KodelZbrojaLayer` is added to every humanoid renderer (players, zombies, skeletons) by a
mixin, and the flat vanilla piece is skipped for items it draws. `render_bones` limits what
a slot draws, so one model serves a whole set (the helmet says `["Head"]`, the chestplate
the torso bones); without it every piece draws the whole model on top of the others.
`bone_placement` nudges individual bones, `armor_scale`, `armor_offset` (default
`[0, 1.5, 0]`) and `armor_rotate` move the whole thing, `armor_animation` loops a clip on
the worn model while the body still drives it, `dyeable` follows the dyed colour and
`color` tints it. A bare `armor_texture` name is `textures/entity/<name>.png`; a name with a
slash is under `textures/`. With fullpack installed the bindings come from its item
registry, so they use the item's real id.

## Item icons

```json
{ "type": "sword", "model": "great_sword", "item_display": "model",
  "item_scale": 0.9, "item_offset": [0, 0.05, 0] }
```

`item_display: "model"` swaps the flat sprite for the model in the inventory, on the
ground and in hand; model blocks get the same automatically. The item model type is
`koperlib:kodel` (`koperlib:kgeo` in older item json still works). An armour model is
fitted to the icon using its `render_bones`, then nudged by `item_scale` and `item_offset`.

## The player model

`playerModel` in `config/koperlib/kodel.json` replaces every player's model with a Kodel
model wearing that player's own skin; `playerModelAnim` loops a clip on it while the body
drives the limbs the same way it drives armour. `KodelPlayerModel.set` does the same from
code. A name that no pack provides is logged once and players stay vanilla.

## Bone hitboxes

Name a bone so that it contains `hitbox` and it becomes an oriented box wrapping that
bone's cubes.

```java
List<KodelHitboxer.Obb> boxes = KodelBook.hitboxes("kopertrex", bones);
KodelHitboxer.Obb hit = KodelHitboxer.pick(boxes, ox, oy, oz, dx, dy, dz);
```

These are computed from the posed bone matrices, so they follow the animation rather than
sitting at the bind pose. Pass the rest pose when only the bind shape is needed.

A box is a centre plus three half axis vectors, so a rotated bone gets a box that turns
with it instead of a fatter axis aligned one. `contains` and `raycast` work in the same
space the boxes are in, which is model space pixels; `KodelKloc.worldHitboxes` lifts them
into world blocks around a `BlockPos`.

`KodelHitboxer.bounds` gives a model space aabb around every box, which is the right input
for culling, because a kodel mob is routinely much larger than whatever aabb its entity
type claims. It returns null when the model marks no hitbox bones at all, and that null
means keep the existing culling.

## Combat

Two entity json keys turn the model into the thing that gets hit:

* `"obb_hitboxes": true` makes picking (melee, projectiles, the crosshair) test the
  model's `hitbox` bones instead of the entity's aabb, at the rest pose. A shot through
  the aabb that misses every box does not hit; a box outside the aabb still does.
  `/koperlib obb` draws the boxes.
* `"hitbox_bones": { "head": { "width": 0.5, "height": 0.5, "depth": 0.5, "damage_multiplier": 2 } }`
  puts damage boxes on bones. The client sends the bone it hit (`KodelBoneHitPayload`)
  and the server applies its own multiplier for that bone, so a client cannot claim a
  bigger one.

Java mods bind a type with `KodelEntities.bind(type, model, obb, boxes)`.

## Bone anchors

Effects and attachments pinned to a bone (`KoperBoneAnchors`, KFX `Bone` anchors) are
published by the Kodel mob renderer. Only bones somebody asked for are computed, and a
pose older than one frame expires, so something pinned to a mob that stopped rendering
lets go. The server can list a model's bones (`entity:get_bone_transforms`) but not their
live pose, which exists only where the model is drawn.

## Blocks bound from the pack

A block json's `kender` object already says which model a block draws, so kodel reads
that rather than asking for a second spelling of the same thing:

```json
{
  "kender": {
    "model": "wheel", "texture": "wheel", "render_type": "cutout",
    "rotate_by": "facing", "rotate_by_base": "north",
    "offset": [0, 0, 0], "bones": ["bone"]
  }
}
```

`rotate_by` names a blockstate property and `rotate_by_base` the facing the model was
authored for, so a floor mounted part says `"up"` and does not spin like it was drawn
facing north. Left out, it is `"facing"`, as block json has always meant: a block with a facing state
turns with it. `"rotate_by": false` keeps the model as authored, and `horizontal_facing`
reads as `facing`. `bones` limits what draws, which lets two blocks share one model file.

`KodelBlockBook` reads the rest of the object as well and `KodelBlockRenderer` draws every
placed model block from it: the centre-bottom placement, `KoperStateOffset`, `rotate_by`,
the json `offset`, `scale` and `rotate`, then the bone pose from the clip (`animation`,
`clips`, triggers) with the procedural `anim` ops stacked on top. `shift`, `stretch`,
`spin`, `hold`, `sway` and `bob` work on the world and on a moving kontraption alike.
Static blocks are instanced on the Kender Vulkan path and animated ones skinned on the GPU;
on OpenGL they go through custom geometry with the same placement. `KodelKlocPozaTest`
pins the pose stacking.

The block's collision and outline shape can come from the model: bones named `hitbox`
(or listed in the json) become the shape, so an overhanging roof gets a matching hitbox.
`KodelPhysicsShapes` gives Khysics the same boxes, and `KodelTriggerBox` lets code flip a
named trigger on a placed block or on a block riding a kontraption.

Both pack layouts are read, flat `blocks/` and the older `koperlib/blocks/`.

## Converting

Bedrock geometry and animations go in through `KodelConverters`:

```java
KodelModel model = KodelConverters.geometry(geoJson);
List<KodelAnimation> clips = KodelConverters.animations(animationJson);
byte[] container = KodelPackager.pack(model, clips, pngBytes, "kopertrex");
```

Vanilla Java models go in through `KodelJavaModels.fromModelPart`, which names each bone
after its own key in the parent's child map, so animation tracks have something to bind
to.

`aq/kodel/python/kodel_cli.py` does the same from the command line and also exports OBJ
and glTF.

## Kender

Kender keeps geometry in its own Vulkan buffer and skins on the GPU. `KodelModelRender.bakeSkinned` bakes the bind pose once, cube
local, with a bone index on every vertex, and `boneMatricesForKender` converts the
sampler's pixel space matrices into the block space the batch uploads. Per frame only
the matrices move; the mesh stays in VRAM.

`KenderEntityBatch.submit` returns false when it cannot draw, which is the normal case
on OpenGL, and the caller falls through to `submitCustomGeometry` with the same content.
Nothing in a pack changes either way.

`KodelSkinnedTest` skins the mesh on the CPU the way the shader would and compares it
against the direct bake over every clip, because two paths that disagree give a
model that is fine on one machine and possessed on another.

## Native

`kodel_load_model`, `kodel_load_animations`, `kodel_sample` and `kodel_free` live in
`engine/kodel`. Java keeps a complete second implementation in `KodelSampler` and uses it
whenever the native is not loadable, so a missing `.so` costs speed and nothing else.

Two implementations of the same maths will drift if nobody checks, so
`KodelNativeParityTest` samples both over every easing mode, nested channels and the real
`kopertrex.kodel` and compares matrices. Native parity tests guard against sampling drift, rather than loosening its tolerance.

## Settings

`config/koperlib/kodel.json`: `playerModel` and `playerModelAnim` (both empty by default).
A first start copies them from the old `kgecko.json` if that file exists. Render settings
live in Kender's `kender.json`, see [KENDER.md](KENDER.md).

## Checking a model

* `KodelGeoImportTest` is the conformance suite. Every number in it was derived from the
  spec by hand and it has no `Assumptions` in it, so it fails on a machine with no
  native rather than quietly passing.
* `KodelPythonGoldenTest` pins the Java importer against bytes the Python library
  produced from the geo checked in beside them.
* `KodelNativeParityTest` compares Java against Rust. It skips without a native.
* `KodelHitboxerTest` and `KodelBookTest` are plain maths and caching, no Minecraft.
* `KodelBoneAnchorsTest` covers anchor publishing and expiry; `KodelZbrojaTest` the armour
  json, texture paths included.

## Bedrock actors and particles

Bedrock resource packs render through Kodel: `engine/kodel/src/aktor/` runs client entities, animations, animation controllers and render controllers with compiled MoLang, and `engine/kodel/src/czastki.rs` simulates Bedrock particle effects. The Java side is `kodel/bedrock/`. [BEDROCK.md](BEDROCK.md) has the details. `KodelEntityRender.submitPosed` draws any externally computed pose through the same Kender and custom-geometry lanes as `submit`.

Kodel's importer keeps Bedrock's X axis as is; `KodelBook` mirrors it once into render space when a model loads, and so does the Bedrock actor path. A model with asymmetric geometry or texture imported through `KodelConverters` alone shows mirrored. Nothing has been changed here, because existing `.kodel` content depends on it.

## Limits

**The container has no material or render type.** A model carries one texture and the
caller picks the render type. `KodelKlocRender.renderType` is there to be overridden for
translucency or a glow.

**Two implementations will not agree byte for byte.** Python does its trig in float64 and
Java in float32, so a quaternion built from a 15 degree rotation lands about one ulp
apart. Compare conversions with a tolerance. Byte equality only holds for read then write
round trips, where nothing is recomputed.

**A parent bone must be written before its children.** The importers sort bones automatically, but
anything hand writing a `model.bin` has to respect it, and both samplers reject a forward
parent at load rather than sampling garbage.

**MoLang keyframes are dropped on import.** They need game state and the container stores
numbers, so a geo clip with `"math.sin(query.anim_time * 90) * 5"` in a keyframe loses that
motion when Kodel converts it. **Workaround:** bake the motion into keyframes in Blockbench,
or pick between clips with `animation_conditions` or `entity:play_animation`.

**`entity:play_animation(name)` holds for its hold time, then releases.** There is no way to
set the hold length from Lua yet.

**One model per entity.** No layered models on a mob the way armour layers on a player.
**Workaround:** build the variants into one model and switch clips.

**`poly_mesh` triangles go out as quads with a doubled corner.** The vertex consumer
upstream is quad based and cannot be handed a triangle, so each one is emitted with its
last corner repeated. It rasterises identically and costs a third more vertices on mesh
geometry only; cubes are unaffected.

**Blockbench integration needs separate runtime verification.** Binary round-trip tests cover the codec, not every Blockbench import/export interaction. See the tool README in Kopermod's `kodel/blockbench/`.

*Claude AI used for documentation.*
