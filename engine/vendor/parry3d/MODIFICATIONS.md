# KoperLib modifications to parry3d 0.26.1

Rapier 0.32 (`engine/rapier3d/`) depends on parry3d 0.26. That version could corrupt its BVH after
collider removals: the next incremental optimization panicked in `bvh_binned_build.rs` with
"key not present", and with `panic = "abort"` the whole game closed. It happened in KoperLib when
a kontraption was removed and another one was made.

Ported from parry3d 0.30.2, marked `KoperLib:` in the source:

* `Bvh::remove` (`src/partitioning/bvh/bvh_tree.rs`): truncates orphaned wide nodes when the root
  becomes partial, and zeroes wide nodes that become unreachable so later scans cannot pick up stale
  leaf copies. The 0.30 free list for recycled nodes is not ported; zeroed nodes are dropped at the
  next refit, as before.
* `bvh_binned_build.rs`: the bin index is clamped, so a non-finite or very large leaf AABB cannot
  index past the last bin.

Remove this copy when Rapier moves to a parry release that contains these fixes.
