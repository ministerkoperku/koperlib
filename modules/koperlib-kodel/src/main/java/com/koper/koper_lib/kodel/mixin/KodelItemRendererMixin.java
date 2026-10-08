package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.KodelItemRenderer;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// registers the model item renderer, so model blocks and armour show as 3D items in the gui and hand
@Mixin(SpecialModelRenderers.class)
public class KodelItemRendererMixin {

    @Shadow
    @Final
    private static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends SpecialModelRenderer.Unbaked<?>>> ID_MAPPER;

    @Inject(method = "bootstrap", at = @At("TAIL"), require = 0)
    private static void koperlib$addKodelItemRenderer(CallbackInfo ci) {
        ID_MAPPER.put(Identifier.fromNamespaceAndPath("koperlib", "kodel"), KodelItemRenderer.Unbaked.MAP_CODEC);
        // item json written before kodel took this over
        ID_MAPPER.put(Identifier.fromNamespaceAndPath("koperlib", "kgeo"), KodelItemRenderer.Unbaked.LEGACY_CODEC);
    }
}
