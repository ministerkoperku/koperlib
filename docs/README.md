# KoperLib documentation

KoperLib provides content packs, scripting, models, effects and physics for Minecraft Fabric. These guides describe the current 26.3 source and the limits of each feature.

## Start here

* [Fullpack guide](KOPERLIB.md): requirements, a first pack, content types, reload behavior and troubleshooting.
* [Modules](ECOSYSTEM.md): the three release jars and which module an addon depends on.
* [Building from source](BUILDING.md): Java, Rust, native packaging and verification.

## Authoring reference

| Guide | Covers |
|---|---|
| [Lua API](LUA_API.md) | Handlers, object methods, world operations and persistent state |
| [Java addons](JAVA_ADDONS.md) | Pack Java, Fabric addons, existing extension points and module dependencies |
| [KUI](KUI.md) | JSON menus, texture menus, containers, recipes and HUD |
| [Kodel](KODEL.md) | Models on blocks, mobs, armour, items and the player; animation, hitboxes, anchors, converters and native sampling |
| [Bedrock addons](BEDROCK.md) | Addon installation, conversion, resource packs and script compatibility |
| [KFX](KFX_PARTICLE_ENGINE.md) | Composable effect graphs, handles, anchors, controllers and render budgets |
| [Legacy KFX](KFX_LEGACY.md) | Existing definition/program APIs retained for compatibility |
| [Khysics](KHYSICS.md) | Moving grids, forces, joints, body APIs, persistence and local interactions |
| [Elpe](ELPE.md) | Point physics, rubble and the alternative Khysics backend |
| [Kender](KENDER.md) | Render paths, configuration, lighting and shader limitations |

Kodel is the model system; the KGecko module was removed and its pack json keys are read by Kodel. The current Java extension surface is documented, but a separately versioned stable API is future work.

Known gaps at release are listed in the [main README](../README.md#known-limitations).

*Claude AI used for documentation.*
