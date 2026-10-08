package com.koper.koper_lib.mixin;

import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MappedRegistry.class)
public interface SimpleRegistryAccessor {
    @Mutable
    @Accessor("frozen")
    void setFrozen(boolean frozen);

    @Accessor("frozenTags")
    java.util.Map<net.minecraft.tags.TagKey<?>, net.minecraft.core.HolderSet.Named<?>> getFrozenTags();

    @SuppressWarnings("rawtypes")
    @Accessor("byKey")
    java.util.Map koperlib$getByKey();

    @SuppressWarnings("rawtypes")
    @Accessor("byLocation")
    java.util.Map koperlib$getByLocation();

    @SuppressWarnings("rawtypes")
    @Accessor("byValue")
    java.util.Map koperlib$getByValue();

    @SuppressWarnings("rawtypes")
    @Accessor("registrationInfos")
    java.util.Map koperlib$getRegistrationInfos();
    // byId is ObjectArrayList (fastutil) — type mismatch with List prevents using @Accessor;
    // handled via reflection in SimpleRegistryMixin.koperlib$removeEntry instead
}
