package com.koper.koper_lib.elpe.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// display setters are all private in 26.2, rubble needs them
@Mixin(Display.class)
public interface ElpeDisplayPoker {
    @Invoker("setTransformation")
    void koperSetTransformation(Transformation t);

    @Invoker("setTransformationInterpolationDuration")
    void koperSetTransformationInterpolation(int ticks);

    @Invoker("setTransformationInterpolationDelay")
    void koperSetTransformationDelay(int ticks);

    @Invoker("setPosRotInterpolationDuration")
    void koperSetPosRotInterpolation(int ticks);
}
