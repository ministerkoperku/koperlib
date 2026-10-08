package com.koper.koper_lib.api.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;

// block states in OUR saved nbt. 26.3 writes {id, properties}, 26.2 saves still have {Name, Properties}
// and vanilla's reader turns those into AIR without a peep. read through here = old worlds keep their blocks
public final class KoperBlockStateNbt {
    private KoperBlockStateNbt() {}

    private static final Codec<BlockState> LEGACY_CODEC = RecordCodecBuilder.create(i -> i.group(
            BuiltInRegistries.BLOCK.byNameCodec().fieldOf("Name").forGetter(BlockState::getBlock),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("Properties", Map.of()).forGetter(s -> Map.of())
        ).apply(i, KoperBlockStateNbt::withProperties));

    // BlockState.CODEC that eats the old shape too, writes the new one
    public static final Codec<BlockState> CODEC = Codec.withAlternative(BlockState.CODEC, LEGACY_CODEC);

    // NbtUtils.readBlockState but old saves work
    public static BlockState read(HolderGetter<Block> blocks, CompoundTag tag) {
        return NbtUtils.readBlockState(blocks, upgrade(tag));
    }

    // old tag -> new tag copy, new tags come back as they are
    public static CompoundTag upgrade(CompoundTag tag) {
        if (tag == null || tag.contains("id") || !tag.contains("Name")) return tag;
        CompoundTag out = new CompoundTag();
        tag.getString("Name").ifPresent(name -> out.putString("id", name));
        tag.getCompound("Properties").ifPresent(props -> out.put("properties", props.copy()));
        return out;
    }

    private static BlockState withProperties(Block block, Map<String, String> properties) {
        BlockState state = block.defaultBlockState();
        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        for (var entry : properties.entrySet()) {
            Property<?> property = definition.getProperty(entry.getKey());
            if (property != null) state = set(state, property, entry.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }
}
