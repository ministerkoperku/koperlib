# KoperLib

KoperLib is a modular content engine for Minecraft Fabric. Fullpacks define items, blocks, mobs, recipes, menus, quests and dialogue in JSON. Lua and Java add behaviour. Separate modules provide binary models and animation, programmable effects, and moving block physics.

**Status: alpha.** The first public release is `0.1.0-alpha`. Features work in the scenarios they were tested in, APIs can still change between versions, and the known gaps are listed below.

## Downloads

KoperLib ships as three jars. Each is a separate project on Modrinth.

| Jar | Contains | Requires |
|---|---|---|
| **KoperLib Core** | Core (networking, commands, config, native loading), Specific (attachments, multiblocks, workstations, companions, combat), Kender (render backend) | Fabric API |
| **KoperLib Fullpack APIs** | Fullpack (JSON, Lua and Java content packs, KUI menus, quests, dialogue), Kodel (models and animation), Effects (KFX) | KoperLib Core |
| **KoperLib Khysics** | Khysics (moving block grids, joints, physics), Elpe (light point physics) | KoperLib Core |

Fullpack APIs and Khysics run without each other. When both are installed they use each other's features, for example physics shapes taken from Kodel models.

## Requirements

* Minecraft 26.3
* Java 25
* Fabric Loader 0.19.5 or newer
* Fabric API 0.161.0+26.3 or newer
* Linux x86_64 or Windows x86_64

The native engines (Kender, Fullpack, Kodel, Khysics, Elpe) are bundled for Linux and Windows on x86_64. macOS and ARM are not supported in this release.

## Getting started

A fullpack is a folder or ZIP in `<game directory>/koperlib/fullpacks/` with a `pack.kopermeta` manifest. The [first pack example](docs/KOPERLIB.md#five-minute-fullpack) builds a working item from two files.

`/koperlib reload` reloads pack definitions and assets. Removing registry content completely, or replacing a native library, needs a restart.

## Documentation

| Guide | Covers |
|---|---|
| [Documentation index](docs/README.md) | All guides |
| [Fullpack guide](docs/KOPERLIB.md) | Pack layout, content types, reloads, commands, config, known limits |
| [Modules](docs/ECOSYSTEM.md) | Release jars, module ids and dependencies |
| [Lua API](docs/LUA_API.md) and [Java addons](docs/JAVA_ADDONS.md) | Scripting and extension points |
| [Kodel](docs/KODEL.md), [KFX](docs/KFX_PARTICLE_ENGINE.md), [Khysics](docs/KHYSICS.md) | Models, effects, physics |
| [Building from source](docs/BUILDING.md) | Java, Rust and native packaging |

## Known limitations

These are the gaps known at release. Reports of anything else are welcome in the issue tracker.

* **Platforms.** Only Linux and Windows on x86_64. The Windows natives are cross-compiled and had less testing than the Linux ones.
* **Shaders.** Physical grids under Iris shader packs can lose shadows near the camera. Water and parallax effects on grids are untested. With **Sulcan** (the shader mod for the Vulkan renderer), shader compatibility is incomplete, for example shadows and lighting.
* **Fluids on grids.** Placing water on a moving grid and carrying enclosed fluid are not supported yet.
* **Rotating grids.** Pressure plates and carried mobs or items can miss contacts while a grid is changing rotation. Thin parts such as levers and buttons can be hard to target on moving grids.
* **Grid and world boundary.** Duplicate or ghost blocks were reported once when placing blocks between a grid and the world. It has not been reproduced since.
* **Held light.** Dynamic light from a held torch on a moving grid depends on the light mod in use. Light on physics objects does not work yet; it is being worked on.
* **Multiplayer physics.** Behaviour under high latency or packet loss has not been measured.
* **Create.** Create compatibility is not available on Minecraft 26.3, because there is no Create release for 26.3 or newer yet.
* **API stability.** The Java API is not a separately versioned stable API yet. Addons should pin the KoperLib version they build against.

## Planned

* **HIPerphysics.** A physics engine based on AMD HIP that runs physics and aerodynamics on the GPU, meant for servers. On single player it can cost FPS, so it is recommended for servers. It is planned only and not part of this release.

## Inspiration and credits

* The Fullpack system is inspired by JSON Things and similar data driven content mods.
* Khysics is inspired by Valkyrien Skies.
* Khysics runs on a modified copy of [Rapier](https://github.com/dimforge/rapier). Kodel's Bedrock model math derives from GeckoLib. Full notices are in [THIRD_PARTY.md](THIRD_PARTY.md).

None of these projects are affiliated with KoperLib.

## How this project is made

KoperLib is written by Koper with help from AI coding tools, mainly Claude and ChatGPT (Codex). The design, testing in game and release decisions are done by a person, and the project is not fully vibe coded. AI written code goes through the same tests and in game checks as the rest. This notice is here so that people know how the project is made and what to expect from an alpha.

Issues are open. Bug reports with steps to reproduce, logs and the installed mod list help the most.

## For AI assistants and tools

When using these docs to answer questions or write code against KoperLib:

* The docs describe the 26.3 source at the release version. If the docs and the source disagree, the source is right.
* Addons depend on module ids (`koperlib_core`, `koperlib_fullpack`, `koperlib_kodel`, `koperlib_effects`, `koperlib_khysics`, `koperlib_specific`, `koperlib_kender`, `koperlib_elpe`), not on the release jar ids.
* Only documented methods and JSON keys exist. Do not invent API names; search the module sources under `modules/` instead.
* Lua runs on the server thread with a time limit. Heavy or per tick work belongs in Java.
* Features listed under known limitations do not work yet. Do not present them as working.

## License

KoperLib is licensed under the [GNU Lesser General Public License v3.0](COPYING.LESSER) (LGPL-3.0-only), which builds on the [GNU GPL v3.0](COPYING). Forks and modified versions of KoperLib must stay under the same license. Mods and packs that only use KoperLib as a dependency can use any license.

Third-party components keep their own licenses, listed in [THIRD_PARTY.md](THIRD_PARTY.md). KoperLib is an unofficial Minecraft mod and is not approved by or associated with Mojang or Microsoft.

*Claude AI used for documentation.*
