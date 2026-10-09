package com.koper.koper_lib.block;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Stands in, after a restart, for a Fullpack block whose pack is gone. Without it the chunk loader
// meets an unknown id and turns every placed copy into air. It carries the same state properties by
// name, so palette entries like pack:pipe[facing=north] still decode, and it stays bound to the
// shared brain type so the block entity data is kept for the day the pack comes back.
public class FullpackTombstoneBlock extends KoperBrainyBlock {

    private static final ThreadLocal<List<Property<?>>> PENDING = new ThreadLocal<>();

    private final String missingId;

    private FullpackTombstoneBlock(Properties props, String missingId) {
        super(props);
        this.missingId = missingId;
    }

    public static FullpackTombstoneBlock create(Properties props, String missingId,
            Map<String, List<String>> properties, Map<String, String> defaults) {
        List<Property<?>> made = new ArrayList<>();
        properties.forEach((name, values) -> {
            if (!values.isEmpty()) made.add(new TombstoneProperty(name, values));
        });
        // createBlockStateDefinition runs inside Block's constructor, before any field of this class
        PENDING.set(made);
        FullpackTombstoneBlock block;
        try {
            block = new FullpackTombstoneBlock(props, missingId);
        } finally {
            PENDING.remove();
        }
        BlockState state = block.getStateDefinition().any();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            String wanted = defaults.get(property.getName());
            if (wanted != null) state = withValue(state, (TombstoneProperty) property, wanted);
        }
        block.registerDefaultState(state);
        return block;
    }

    private static BlockState withValue(BlockState state, TombstoneProperty property, String value) {
        return property.getValue(value).map(token -> state.setValue(property, token)).orElse(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        List<Property<?>> properties = PENDING.get();
        if (properties != null) properties.forEach(builder::add);
    }

    @Override
    public net.minecraft.network.chat.MutableComponent getName() {
        return Component.literal("Missing: " + missingId);
    }

    // a value is only ever looked up by its name, so one string-backed property covers booleans,
    // integers and every enum the original block had
    public static final class TombstoneProperty extends Property<TombstoneProperty.Token> {

        public record Token(int index, String name) implements Comparable<Token> {
            @Override public int compareTo(Token other) { return Integer.compare(index, other.index); }
            @Override public String toString() { return name; }
        }

        private final List<Token> values;

        TombstoneProperty(String name, List<String> names) {
            super(name, Token.class);
            List<Token> tokens = new ArrayList<>(names.size());
            for (int i = 0; i < names.size(); i++) tokens.add(new Token(i, names.get(i)));
            this.values = List.copyOf(tokens);
        }

        @Override public List<Token> getPossibleValues() { return values; }
        @Override public String getName(Token value) { return value.name(); }
        @Override public int getInternalIndex(Token value) { return value.index(); }

        @Override
        public Optional<Token> getValue(String name) {
            for (Token token : values) if (token.name().equals(name)) return Optional.of(token);
            return Optional.empty();
        }
    }
}
