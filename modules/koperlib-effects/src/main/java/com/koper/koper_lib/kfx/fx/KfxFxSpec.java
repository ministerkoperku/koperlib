package com.koper.koper_lib.kfx.fx;

/**
 * The cast-time values of one {@code koper_lib:render/fx} node.
 *
 * @param fx        registered effect id
 * @param color     main colour, ARGB
 * @param coreColor hot colour; fully transparent means a near white tint of {@code color}
 * @param size      base scale in blocks: beam radius for a laser, body radius for an object
 * @param count     most particles the effect may keep alive, already scaled by the client quality tier
 * @param intensity 0..4, how much the effect shows off; a rarer roll passes a higher value
 * @param speed     animation speed multiplier
 * @param variant   effect specific choice, for example which shape an object takes
 * @param glow      glow strength, 0 turns the wide glow off
 * @param noise     effect specific turbulence
 * @param seed      stable per cast and node
 */
public record KfxFxSpec(String fx, int color, int coreColor, float size, int count, float intensity, float speed,
                        int variant, float glow, float noise, long seed) {
    /** {@link #coreColor} or, when that is transparent, a near white tint of {@link #color}. */
    public int hot() {
        return (coreColor >>> 24) != 0 ? coreColor : KfxColors.mix(color, 0xFFFFFFFF, 0.78f) | 0xFF000000;
    }

    /** True once the roll reached the given intensity, for layers that only better effects get. */
    public boolean atLeast(float level) {
        return intensity >= level;
    }
}
