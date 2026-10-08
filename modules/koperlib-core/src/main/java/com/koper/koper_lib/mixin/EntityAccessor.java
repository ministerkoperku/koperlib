package com.koper.koper_lib.mixin;

@org.spongepowered.asm.mixin.Mixin(net.minecraft.world.entity.Entity.class)
public interface EntityAccessor {
    @org.spongepowered.asm.mixin.gen.Accessor("syncVelocity")
    void setVelocityModified(boolean modified);

    @org.spongepowered.asm.mixin.gen.Accessor("syncVelocity")
    boolean isVelocityModified();

    // Server-side forced moves need both old-position sets shifted.
    // MC 26 renders from xOld/yOld/zOld, debug hitboxes use xo/yo/zo.
    @org.spongepowered.asm.mixin.gen.Accessor("xo")
    double getXo();

    @org.spongepowered.asm.mixin.gen.Accessor("xo")
    void setXo(double v);

    @org.spongepowered.asm.mixin.gen.Accessor("yo")
    double getYo();

    @org.spongepowered.asm.mixin.gen.Accessor("yo")
    void setYo(double v);

    @org.spongepowered.asm.mixin.gen.Accessor("zo")
    double getZo();

    @org.spongepowered.asm.mixin.gen.Accessor("zo")
    void setZo(double v);

    @org.spongepowered.asm.mixin.gen.Accessor("xOld")
    double getXOld();

    @org.spongepowered.asm.mixin.gen.Accessor("xOld")
    void setXOld(double v);

    @org.spongepowered.asm.mixin.gen.Accessor("yOld")
    double getYOld();

    @org.spongepowered.asm.mixin.gen.Accessor("yOld")
    void setYOld(double v);

    @org.spongepowered.asm.mixin.gen.Accessor("zOld")
    double getZOld();

    @org.spongepowered.asm.mixin.gen.Accessor("zOld")
    void setZOld(double v);
}
