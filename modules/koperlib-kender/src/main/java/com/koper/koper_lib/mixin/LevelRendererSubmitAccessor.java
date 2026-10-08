package com.koper.koper_lib.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererSubmitAccessor {
    @Accessor("submitNodeStorage")
    SubmitNodeStorage koperlib$submitNodeStorage();
}
