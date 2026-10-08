package com.koper.koper_lib.kodel;

import net.minecraft.resources.Identifier;

// a block entity that wants a .kodel drawn on it implements this and hands the
// renderer everything it needs. no registry, no json binding, no reflection --
// the block entity already knows what it is
public interface KodelKlocowy {

    /// bare name of the .kodel in <pack>/kodel/, no extension
    String kodelModel();

    Identifier kodelTexture();

    /// clip to play, null for the bind pose
    default String kodelClip() {
        return null;
    }

    /// seconds into the clip. the default runs off world time so every block of a
    /// kind moves together; override it to give each one its own head
    default float kodelSeconds(float partialTick) {
        return (System.nanoTime() % 1_000_000_000_000L) / 1_000_000_000f;
    }

    default float kodelScale() {
        return 1f;
    }

    /// pixels, applied after the facing turn
    default float[] kodelOffset() {
        return null;
    }

    default int kodelTint() {
        return 0xFFFFFFFF;
    }

    /// true while the block should draw nothing at all this frame
    default boolean kodelHidden() {
        return false;
    }
}
