package com.koper.koper_lib.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

// reach into MC's live render pass so Hook B can grab its command buffer + identify the world pass.
// these are all private final fields on VulkanRenderPass — accessor mixin, no reflection.
@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {
    @Accessor("commandBuffer") VkCommandBuffer koperlib$cmd();
    @Accessor("label")         Supplier<String> koperlib$label();
    @Accessor("outputWidth")   int koperlib$width();
    @Accessor("outputHeight")  int koperlib$height();
    @Accessor("hasDepth")      boolean koperlib$hasDepth();
}
