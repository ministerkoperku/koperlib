package com.koper.koper_lib.kodel.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(ModelPart.class)
public interface KodelModelPartAccessor {
    @Accessor("cubes") List<ModelPart.Cube> kodel$cubes();
    @Accessor("children") Map<String, ModelPart> kodel$children();
}