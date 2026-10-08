package com.koper.koper_lib.mixin;

import com.koper.koper_lib.loader.IKoperRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// keeps registries writable at runtime so fullpacks can register content during hot-reload
@Mixin(MappedRegistry.class)
public abstract class SimpleRegistryMixin<T> implements IKoperRegistry {

    // bypass the frozen-write check so Registry.register() never throws after freeze()
    @Inject(method = "validateWrite()V", at = @At("HEAD"), cancellable = true)
    private void koperlib$bypassValidateWrite(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "validateWrite(Lnet/minecraft/resources/ResourceKey;)V", at = @At("HEAD"), cancellable = true)
    private void koperlib$bypassValidateWriteKeyed(CallbackInfo ci) {
        ci.cancel();
    }

    // physically removes an entry from all internal maps — used for creative tab full-reload
    @Unique
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <E> void koperlib$removeEntry(ResourceKey<E> key) {
        SimpleRegistryAccessor self = (SimpleRegistryAccessor)(Object)this;
        java.util.Map byKey = self.koperlib$getByKey();
        Object ref = byKey.remove(key);
        if (ref == null) return;
        self.koperlib$getByLocation().remove(key.identifier());
        if (ref instanceof Holder.Reference<?> holder) {
            try { self.koperlib$getByValue().remove(holder.value()); } catch (Exception ignored) {}
        }
        self.koperlib$getRegistrationInfos().remove(key);
        // byId is ObjectArrayList (fastutil) — can't use @Accessor, reflect instead
        try {
            java.lang.reflect.Field byIdField = MappedRegistry.class.getDeclaredField("byId");
            byIdField.setAccessible(true);
            Object byIdList = byIdField.get(this);
            // fastutil ObjectArrayList has rem(Object) for value-based removal
            try {
                byIdList.getClass().getMethod("rem", Object.class).invoke(byIdList, ref);
            } catch (NoSuchMethodException e2) {
                // fallback: List.remove(Object) — safe since ref is not Integer
                ((java.util.List<Object>)(java.util.List<?>)byIdList).remove(ref);
            }
        } catch (Exception e) {
            com.koper.koper_lib.KoperLib.LOGGER.debug("[KoperLib] byId cleanup skipped: {}", e.getMessage());
        }
    }
}
