package com.koper.koper_lib.api.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Immutable multiblock pattern authored facing north and rotatable horizontally. */
public final class KoperMultiblockPattern {
    private final List<Part> parts;

    private KoperMultiblockPattern(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Match validate(BlockGetter level, BlockPos controller, Direction facing) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(controller, "controller");
        Direction horizontal = facing == null || facing.getAxis().isVertical() ? Direction.NORTH : facing;
        List<MissingPart> missing = new ArrayList<>();
        for (Part part : parts) {
            BlockPos worldPos = controller.offset(rotate(part.offset, horizontal));
            BlockState state = level.getBlockState(worldPos);
            if (!part.matcher.test(state))
                missing.add(new MissingPart(worldPos.immutable(), part.description, state));
        }
        return new Match(missing.isEmpty(), List.copyOf(missing));
    }

    public int size() {
        return parts.size();
    }

    private static BlockPos rotate(BlockPos offset, Direction facing) {
        int x = offset.getX();
        int y = offset.getY();
        int z = offset.getZ();
        return switch (facing) {
            case EAST -> new BlockPos(-z, y, x);
            case SOUTH -> new BlockPos(-x, y, -z);
            case WEST -> new BlockPos(z, y, -x);
            default -> offset;
        };
    }

    private record Part(BlockPos offset, Predicate<BlockState> matcher, String description) {}

    public record MissingPart(BlockPos position, String expected, BlockState found) {}

    public record Match(boolean complete, List<MissingPart> missing) {
        public String firstProblem() {
            if (missing.isEmpty()) return "complete";
            MissingPart part = missing.getFirst();
            return part.expected + " at " + part.position.toShortString();
        }
    }

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();

        public Builder block(int x, int y, int z, Block block) {
            Objects.requireNonNull(block, "block");
            return where(x, y, z, state -> state.is(block), block.getName().getString());
        }

        public Builder anyBlock(int x, int y, int z, String description, Block... blocks) {
            List<Block> accepted = List.copyOf(Arrays.asList(blocks));
            return where(x, y, z, state -> accepted.contains(state.getBlock()), description);
        }

        public Builder tag(int x, int y, int z, TagKey<Block> tag, String description) {
            Objects.requireNonNull(tag, "tag");
            return where(x, y, z, state -> state.is(tag), description);
        }

        public Builder where(int x, int y, int z, Predicate<BlockState> matcher, String description) {
            parts.add(new Part(new BlockPos(x, y, z), Objects.requireNonNull(matcher, "matcher"),
                    description == null || description.isBlank() ? "matching block" : description));
            return this;
        }

        public KoperMultiblockPattern build() {
            if (parts.isEmpty()) throw new IllegalStateException("A multiblock pattern needs at least one part");
            return new KoperMultiblockPattern(parts);
        }
    }
}
