# Modifications

This directory is a modified copy of [Rapier](https://github.com/dimforge/rapier) 0.32.0 (`rapier3d`, 3D, `f32`), licensed under the Apache License 2.0 (see `LICENSE` in this directory). Copyright of the original code remains with the Rapier authors (Dimforge).

Changes made for KoperLib:

* Features and modules that KoperLib does not use were removed, so the crate builds only the 3D `f32` variant.
* Code was adapted to be driven from Java through KoperLib's Panama FFI layer (`engine/koperlib-khysics`).

The exact per-file changes are recorded in this repository's git history for `engine/rapier3d/`.

*Claude AI used for documentation.*
