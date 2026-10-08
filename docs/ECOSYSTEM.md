# KoperLib modules

KoperLib has independently built Fabric modules. Players install them as three release jars, each a separate Modrinth project. Every module stays its own Fabric mod nested inside, so addons depend on the module ids they use.

| Release jar | Fabric id | Nested modules | Requires |
|---|---|---|---|
| KoperLib Core (`koperlib-core-<version>.jar`) | `koperlib` | Core, Specific, Kender | nothing |
| KoperLib Fullpack APIs (`koperlib-fullpack-apis-<version>.jar`) | `koperlib_fullpack_apis` | Fullpack, Kodel, Effects | KoperLib Core |
| KoperLib Khysics (`koperlib-khysics-<version>.jar`) | `koperlib_physics` | Khysics, Elpe | KoperLib Core |

Fullpack APIs and Khysics work without each other; installed together, they also use each other's features (Kodel model shapes in Khysics, for example). Koperstuff is a developer mod. It is not published as a release jar and can be built from source. `./gradlew releaseJars` writes the three jars to `build/release/`.

## Module dependencies

| Jar | Fabric id | Provides | Required KoperLib modules |
|---|---|---|---|
| `koperlib-core` | `koperlib_core` | Shared module/config/network lifecycle, native loading and bridges | None |
| `koperlib-kender` | `koperlib_kender` | Geometry and render backend | `koperlib_core` |
| `koperlib-fullpack` | `koperlib_fullpack` | JSON/Lua/Java packs, KUI, quests and dialogue | `koperlib_core` |
| `koperlib-effects` | `koperlib_effects` | KFX graphs and effect runtime | `koperlib_core`, `koperlib_kender` |
| `koperlib-khysics` | `koperlib_khysics` | Moving block grids and native physics | `koperlib_core`, `koperlib_kender` |
| `koperlib-specific` | `koperlib_specific` | Attachments, companion, combat and multiblock APIs | `koperlib_core` |
| `koperlib-elpe` | `koperlib_elpe` | Point physics and rubble | `koperlib_core` |
| `koperlib-kodel` | `koperlib_kodel` | Models on blocks, mobs, armour, items and the player; bone hitboxes and anchors; animation; Bedrock actors | `koperlib_core`, `koperlib_kender` |
| `koperlib-koperstuff` | `koperlib_koperstuff` | Developer commands, diagnostics and runtime probes | `koperlib_core`, `koperlib_kender`, `koperlib_fullpack`, `koperlib_effects`, `koperlib_kodel`, `koperlib_khysics`, `koperlib_specific` |

Kodel is the only model module. The KGecko module was removed in October 2026; an addon that still declares `koperlib_kgecko` will not load until it depends on `koperlib_kodel` instead.

## Pack integrations

Fullpack discovers integrations through the `koperlib-fullpack-addon` entrypoint. Feature modules contribute their loaders, factories and reload hooks. An absent module removes that feature's authoring surface.

Core exposes shared bridges so independent feature modules can connect without making every integration mandatory. Networking belongs to the module that registers its payloads and handlers.

## Configuration

Settings are written below `<game directory>/config/koperlib/`, in `core.json`, `fullpack.json`, `effects.json`, `kender.json`, `kodel.json` and `khysics.json`. `kender.json` and `kodel.json` copy their keys from an old `kgecko.json` on first start. Keys use the owning Java config's camelCase field names. Old `config/koperlib/config.json` is read for migration when a module config is absent.

See [the configuration reference](KOPERLIB.md#config) and [Kender](KENDER.md) for exact keys and defaults.

## Optional compatibility

Conditional adapters remain in their owning modules and activate when their target mod is present. Create integration is deferred for the current 26.3 release; retained source is not a claim of compatibility with a released Create build.

## Fullpack connections

Servers send a manifest containing pack identity, version, hash, size, requirements and optional HTTPS links. Clients install missing packs manually. The connection handler does not download or execute a server-provided archive. Packs carrying executable Java receive a separate notice.

## Building modules

Modules publish to Maven Local under `com.koper.koper_lib:koperlib-<module>`. Current sibling consumers use either published coordinates or local jars according to their own build scripts. See [building](BUILDING.md) and [Java addons](JAVA_ADDONS.md).

*Claude AI used for documentation.*
