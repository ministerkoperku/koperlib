package com.koper.koper_lib.factory;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Optional;

// a block state whose values are plain words. java only ships enum backed ones, bedrock packs
// declare {"ns:color": ["red", "blue"]} and expect exactly those words back
public final class KoperNapisowaWlasciwosc extends Property<String> {
    private final List<String> values;

    public KoperNapisowaWlasciwosc(String name, List<String> values) {
        super(name, String.class);
        this.values = List.copyOf(values);
    }

    @Override
    public List<String> getPossibleValues() {
        return values;
    }

    @Override
    public String getName(String value) {
        return value;
    }

    @Override
    public Optional<String> getValue(String name) {
        return values.contains(name) ? Optional.of(name) : Optional.empty();
    }

    @Override
    public int getInternalIndex(String value) {
        return values.indexOf(value);
    }
}
