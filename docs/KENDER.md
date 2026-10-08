# Kender

Kender provides native geometry and rendering support for physical grids, model content and KFX. It can use Minecraft's Vulkan device; supported callers fall back to Minecraft render submissions when the native draw path is unavailable.

## Render paths

The model renderer reads `rendering` from `config/koperlib/kender.json`:

* `koperlib`: request the Kender path where the backend and draw support it.
* `vanilla`: use Minecraft render submissions.

`koper` and `vulkan` are accepted aliases for the Kender mode. Other values, including the retired `glway`, resolve to vanilla.

Kender probes Vulkan through SDL. Direct Vulkan drawing requires a usable native library, the host Vulkan backend and valid device handles. A fallback does not guarantee identical shader effects: each shader integration also needs the relevant draw passes and material data.

## Configuration

Settings are split by owner rather than stored in one render configuration. `kender.json` copies its keys from an old `kgecko.json` on first start:

| File under `config/koperlib/` | Key | Default | Purpose |
|---|---|---|---|
| `kender.json` | `rendering` | `"koperlib"` | Model render mode |
| `kender.json` | `kenderBlockCullDistance` | `128` | Model block culling distance |
| `kender.json` | `kenderEntityRender` | `true` | Entity batching |
| `kender.json` | `kenderShaderCompat` | `true` | Conditional shader integration |
| `kender.json` | `kenderShadowCast` | `false` | Shadow casting path |
| `khysics.json` | `kenderBlockCullDistance` | `128` | Physical grid block culling distance |
| `khysics.json` | `kenderMaxBlocksPerKontraktion` | `-1` | Per-grid render cap |
| `khysics.json` | `kontraCameraMode` | `"vanilla"` | Riding camera mode |
| `khysics.json` | `kontraFancyLight` | `true` | Local grid lighting |
| `khysics.json` | `kontraLightSpillRange` | `7` | Host light spill range |
| `effects.json` | `kenderVulkanParticles` | `true` | Native effect rendering where available |

Nonpositive physical block culling distance disables its distance limit. A cap of `-1` disables the block-count limit. Large uncapped scenes still require enough client memory and rendering capacity.

Use `/koperlib config reload` after editing settings. See [the shared configuration reference](KOPERLIB.md#config).

## Models and physical blocks

Kodel supplies models, bone transforms and geometry for the render paths, including model blocks on moving grids (through `KenderGeoBridge`). Its Java sampler keeps model playback available without the Kodel native library.

Physical grids submit blocks and block entities using their local state and the live body pose. Blocks with per-position geometry can register `KenderLocalBlockRenderer`; the corresponding moving-block model reads data through `KenderMovingBlockContext` on its fallback path. This keeps different local shapes from sharing an inappropriate state-only mesh.

## Fluids

`KontraWodaKender` submits nonempty fluid states through Minecraft's `FluidRenderer`, using a grid-local render getter and the body's pose. This includes waterlogged blocks.

That render implementation is separate from bucket placement, liquid collision slots, assembly, spread and pumping. Fluid placement and enclosed-fluid assembly remain open work in Task 22. Full fluid simulation and shader-water parity are not established by the presence of the renderer.

## Lighting

Grid lighting combines sampled host light with local light propagation when `kontraFancyLight` is enabled. Ordinary block lighting, shader shadows and held-item illumination are separate features.

## Shader compatibility

Core contains `SulkanHandshake`; model/render modules contain the associated pass hooks. Shader support remains incomplete. Missing or inaccurate shadows, held-torch illumination and differences between OpenGL/Iris and Vulkan/Sulkan are tracked in Task 22.

A shader pack can shade static terrain correctly while physical blocks or model batches lack one of its passes. Test the same scene with static blocks, moving blocks, block entities and model entities before treating a pack as compatible. Translucent and skinned shadow coverage also needs separate verification.

## Third-party compatibility

The conditional Create/Flywheel adapter remains in the source, but Create integration is deferred for the current 26.3 release. Old 26.2 tests do not establish current compatibility.

## Diagnostics

Developer profilers and detailed debug commands are provided by Koperstuff. `/kender prof`, when installed, records grid render work and reports it on the next invocation. Native load errors appear in the game log.

For an addon, see [Kodel](KODEL.md), [KFX](KFX_PARTICLE_ENGINE.md) and [Java addons](JAVA_ADDONS.md).

*Claude AI used for documentation.*
