package com.koper.koper_lib.kodel.mixin;

import com.koper.koper_lib.kodel.bedrock.BrKlatka;
import com.koper.koper_lib.kodel.bedrock.BrNosiciel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class BrStanMixin implements BrNosiciel {
    @Unique private BrKlatka koperlib$klatka;

    @Override public BrKlatka koperlib$br() { return koperlib$klatka; }

    @Override public void koperlib$br(BrKlatka k) { koperlib$klatka = k; }

    @Unique private BrKlatka[] koperlib$przyczepy;

    @Override public BrKlatka[] koperlib$att() { return koperlib$przyczepy; }

    @Override public void koperlib$att(BrKlatka[] a) { koperlib$przyczepy = a; }
}
