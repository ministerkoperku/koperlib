package com.koper.koper_lib.kodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Set;
import java.util.function.Consumer;

// a model block or a model armour piece as an item: in the hand, in the gui, on the ground.
// item model json asks for it with "type": "koperlib:kodel" (or the older "koperlib:kgeo")
public class KodelItemRenderer implements SpecialModelRenderer<KodelItemRenderer.Data> {

    /** fitted = an armour model shrunk to the icon; visible = the bones that model shows. */
    public record Data(String model, Identifier texture, boolean fitted, Set<String> visible,
                       float scale, float[] offset, int tint) {}

    @Override
    public void submit(Data data, PoseStack ps, SubmitNodeCollector tasks,
                       int light, int overlay, boolean glint, int outline) {
        if (data == null) return;
        KodelBook.Entry entry = KodelBook.get(data.model());
        if (entry == null) return;
        float[] mesh = KodelModelRender.bake(entry.model(), entry.restPose(), new Matrix4f(), data.visible());
        if (mesh.length < KodelModelRender.STRIDE) return;
        RenderType type = RenderTypes.entityCutout(data.texture());
        int tint = data.tint();

        ps.pushPose();
        if (data.fitted()) {
            // the slot's bones centred and scaled so the biggest side is one block; item_scale and
            // item_offset nudge on top of that
            float[] b = bounds(mesh);
            float size = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
            float s = (size <= 1e-4f ? 1f : 1f / size) * (data.scale() <= 0 ? 1f : data.scale());
            float[] off = data.offset() != null ? data.offset() : new float[] {0f, 0f, 0f};
            ps.translate(0.5f + off[0], 0.5f + off[1], 0.5f + off[2]);
            ps.scale(s, s, s);
            ps.translate(-(b[0] + b[3]) / 2f, -(b[1] + b[4]) / 2f, -(b[2] + b[5]) / 2f);
        } else {
            // a block's model origin sits at the bottom centre of its cell, as in the world
            ps.translate(0.5, 0, 0.5);
        }
        tasks.submitCustomGeometry(ps, type, (snap, consumer) -> {
            Matrix4f m = snap.pose();
            for (int i = 0; i + KodelModelRender.STRIDE <= mesh.length; i += KodelModelRender.STRIDE) {
                Vector3f p = m.transformPosition(mesh[i], mesh[i + 1], mesh[i + 2], new Vector3f());
                Vector3f n = snap.transformNormal(mesh[i + 5], mesh[i + 6], mesh[i + 7], new Vector3f());
                consumer.addVertex(p.x, p.y, p.z, tint, mesh[i + 3], mesh[i + 4], overlay, light, n.x, n.y, n.z);
            }
        });
        ps.popPose();
    }

    private static float[] bounds(float[] mesh) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i + 2 < mesh.length; i += KodelModelRender.STRIDE) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], mesh[i + a]);
                b[a + 3] = Math.max(b[a + 3], mesh[i + a]);
            }
        }
        return b;
    }

    @Override
    public void getExtents(Consumer<Vector3fc> consumer) {
        // models are routinely bigger than a block; a tight box would cull them in the gui
        consumer.accept(new Vector3f(-1f, -1f, -1f));
        consumer.accept(new Vector3f(2f, 2f, 2f));
    }

    @Override
    public Data extractArgument(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem item) {
            KodelBlockBook.Binding b = KodelBlockBook.binding(item.getBlock());
            if (b != null) return new Data(b.model(), b.texture(), false, null, 1f, null, b.tint());
        }
        KodelZbrojaBook.Zbroja a = KodelZbrojaBook.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (a == null) return null;
        int tint = a.tint();
        if (a.dyeable())
            tint = 0xFF000000 | (net.minecraft.world.item.component.DyedItemColor.getOrDefault(stack, tint & 0xFFFFFF) & 0xFFFFFF);
        return new Data(a.model(), a.texture(), true, a.renderBones(), a.itemScale(), a.itemOffset(), tint);
    }

    public static final class Unbaked implements SpecialModelRenderer.Unbaked<Data> {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(Unbaked::new);
        /** The id item json used before Kodel took this over; same renderer. */
        public static final MapCodec<Unbaked> LEGACY_CODEC = MapCodec.unit(Unbaked::new);

        @Override
        public SpecialModelRenderer<Data> bake(BakingContext context) {
            return new KodelItemRenderer();
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<Data>> type() {
            return MAP_CODEC;
        }
    }
}
