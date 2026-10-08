package com.koper.koper_lib.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;
import java.util.Set;

// optional-mod gating for khysics mixins; the loud dead-injection check comes from KoperMixinKrzykacz
public class KoperMixinPlugin extends com.koper.koper_lib.coremod.KoperMixinKrzykacz implements IMixinConfigPlugin {
    private boolean isCreateLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        isCreateLoaded = FabricLoader.getInstance().isModLoaded("create");
    }


    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("CreateFlyKenderMixin")) return isCreateLoaded;
        if (mixinClassName.contains("KontraValueBox")) return isCreateLoaded;
        if (mixinClassName.contains("KontraCreateValueSettings")) return isCreateLoaded;
        if (mixinClassName.contains("KontraCreateKinetic")) return isCreateLoaded;
        if (mixinClassName.contains("KontraCreateDirectional")) return isCreateLoaded;
        if (mixinClassName.contains("KontraCreateContraption")) return isCreateLoaded;
        if (mixinClassName.endsWith("KontraFlywheelVisModeMixin")) return isCreateLoaded;
        if (mixinClassName.endsWith("KontraFlywheelSkipMixin")) return isCreateLoaded;
        return true;
    }




}
