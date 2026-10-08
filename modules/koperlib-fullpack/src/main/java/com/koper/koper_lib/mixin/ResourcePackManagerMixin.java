package com.koper.koper_lib.mixin;

import com.koper.koper_lib.KoperLib;
import com.koper.koper_lib.loader.KoperLibDirectories;
import com.koper.koper_lib.loader.ManualPackProvider;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.repository.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Mixin(PackRepository.class)
public class ResourcePackManagerMixin {
    @Shadow @Final @Mutable private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void koperlib$addManualProvider(CallbackInfo ci) {
        KoperLibDirectories.init();
        KoperLib.LOGGER.info("Injecting KoperPack providers into ResourcePackManager...");

        Set<RepositorySource> newProviders = new HashSet<>(this.sources);

        // 1. ManualPackProvider: serves fullpack textures (CLIENT_RESOURCES) from disk
        newProviders.add(new ManualPackProvider(KoperLibDirectories.FULLPACKS.toFile()));

        // 2. VirtualResourcePack: generated item/block models + translations (CLIENT_RESOURCES)
        newProviders.add(profileAdder -> {
            Pack.ResourcesSupplier factory = new Pack.ResourcesSupplier() {
                @Override
                public PackResources openMetadata(PackLocationInfo info) { return KoperLib.VIRTUAL_PACK; }
                @Override
                public java.util.stream.Stream<PackResources> openResources(PackLocationInfo info, Pack.Metadata metadata) { return java.util.stream.Stream.of(KoperLib.VIRTUAL_PACK); }
            };
            PackSelectionConfig config = new PackSelectionConfig(true, Pack.Position.BOTTOM, true);

            Pack clientProfile = Pack.readMetaAndCreate(
                KoperLib.VIRTUAL_PACK.getInfo(), factory, PackType.CLIENT_RESOURCES, config);
            if (clientProfile != null) profileAdder.accept(clientProfile);

            // Also register as SERVER_DATA so recipes/loot tables/dimensions work
            Pack serverProfile = Pack.readMetaAndCreate(
                KoperLib.VIRTUAL_PACK.getInfo(), factory, PackType.SERVER_DATA, config);
            if (serverProfile != null) profileAdder.accept(serverProfile);
        });

        this.sources = newProviders;
    }
}
