package com.koper.koper_lib.kfx.graph;

import java.util.List;

sealed interface KfxDistribution permits KfxDistribution.Uniform, KfxDistribution.Palette {
    KfxResolvedValue sample(long castSeed, String propertyPath);

    double numericUpperBound(String path);

    double numericLowerBound(String path);

    record Uniform(double min, double max, boolean integer) implements KfxDistribution {
        public Uniform {
            if (!Double.isFinite(min) || !Double.isFinite(max) || max < min) {
                throw new IllegalArgumentException("invalid uniform range");
            }
        }

        @Override
        public KfxResolvedValue sample(long castSeed, String propertyPath) {
            double unit = KfxRandom.unit(castSeed, propertyPath);
            if (integer) {
                int low = (int)Math.ceil(min);
                int high = (int)Math.floor(max);
                int value = low + (int)Math.floor(unit * (high - low + 1L));
                return KfxResolvedValue.integer(Math.min(value, high));
            }
            return KfxResolvedValue.number(min + (max - min) * unit);
        }

        @Override
        public double numericUpperBound(String path) {
            return max;
        }

        @Override
        public double numericLowerBound(String path) {
            return min;
        }
    }

    record Palette(List<KfxResolvedValue> values) implements KfxDistribution {
        public Palette {
            values = List.copyOf(values);
            if (values.isEmpty()) throw new IllegalArgumentException("palette cannot be empty");
        }

        @Override
        public KfxResolvedValue sample(long castSeed, String propertyPath) {
            int index = (int)Math.floor(KfxRandom.unit(castSeed, propertyPath) * values.size());
            return values.get(Math.min(index, values.size() - 1));
        }

        @Override
        public double numericUpperBound(String path) {
            double max = Double.NEGATIVE_INFINITY;
            for (KfxResolvedValue value : values) max = Math.max(max, value.asNumber(path));
            return max;
        }

        @Override
        public double numericLowerBound(String path) {
            double min = Double.POSITIVE_INFINITY;
            for (KfxResolvedValue value : values) min = Math.min(min, value.asNumber(path));
            return min;
        }
    }
}
