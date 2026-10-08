package com.koper.koper_lib.loader;

import net.minecraft.resources.ResourceKey;

// applied to MappedRegistry by SimpleRegistryMixin — lets us physically remove entries at runtime
public interface IKoperRegistry {
    <T> void koperlib$removeEntry(ResourceKey<T> key);
}
