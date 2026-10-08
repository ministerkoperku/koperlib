package com.koper.koper_lib.api.workstation;

/**
 * A workstation block entity that draws its own items in its block entity renderer.
 * {@link KoperPhysicalWorkstation} then spawns no item display entities for it.
 *
 * <p>Use it for anything that can ride a kontraption: a display entity is a separate entity in the
 * world and does not travel with the kontra's blocks, while a block entity renderer is drawn with
 * them. The renderer places each item at its {@link KoperWorkstationLayout.SlotPosition}, turned by
 * yaw then pitch and scaled, as the displays did.
 */
public interface KoperWorkstationRendered {
}
