# Third-party notices

KoperLib is licensed under the GNU Lesser General Public License v3.0 (`COPYING.LESSER`, which builds on `COPYING`). The components below keep their own licenses. All of them are compatible with LGPL-3.0.

## Code included in this repository

| Component | Where | License | Notes |
|---|---|---|---|
| Rapier 0.32.0 | `engine/rapier3d/` | Apache-2.0 | Modified copy. See `engine/rapier3d/LICENSE` and `engine/rapier3d/MODIFICATIONS.md`. Compiled into the Khysics native library. |
| rquickjs-sys 0.14.0 and QuickJS | `engine/vendor/rquickjs-sys/` | MIT | Vendored JavaScript engine bindings and sources, used by the Bedrock script layer. License in `engine/vendor/rquickjs-sys/LICENSE`. |
| GeckoLib Bedrock model and animation math | Kodel (`modules/koperlib-kodel`) | MIT | Clean reimplementation, no upstream files are shipped. Notice reproduced below. |

## Libraries compiled into the native engines

The Rust engines link crates from crates.io, including Lua 5.4 through `mlua` (MIT), `ash` and `naga` for the Vulkan path (MIT or Apache-2.0). Every crate in the dependency tree uses a permissive license: MIT, Apache-2.0, BSD-3-Clause, Zlib, ISC, CC0-1.0, Unlicense or Unicode-3.0. `cargo metadata` in `engine/` lists each crate with its license.

## GeckoLib notice

Kodel's Bedrock geometry and animation import, and the model render conventions it shares with Kender, are a clean reimplementation derived from GeckoLib. No upstream source files are shipped, but the format math and runtime ideas trace back to it, so its notice is reproduced here:

```
MIT License

Copyright (c) 2026 GeckoLib

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Apache License 2.0 (Rapier)

The full text is in `engine/rapier3d/LICENSE` and is shipped inside the KoperLib Khysics release jar as `LICENSE-rapier.txt`.

## Minecraft

KoperLib is an unofficial mod. It contains no Minecraft code or assets and is not approved by or associated with Mojang or Microsoft.

*Claude AI used for documentation.*
