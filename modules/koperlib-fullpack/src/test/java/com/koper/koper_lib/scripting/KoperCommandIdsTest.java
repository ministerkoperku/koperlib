package com.koper.koper_lib.scripting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

// what lua actually sends, straight out of koperlib-scripting's format strings. a namespaced id
// carries its own colon and used to break every one of these (found by omnitest's lua sweep)
class KoperCommandIdsTest {

    @Test
    void setBlockKeepsTheWholeId() {
        var c = assertInstanceOf(ScriptCommand.SetBlock.class,
            ScriptCommandDispatcher.parse("set_block:-5,200,7:minecraft:gold_block"));
        assertEquals("minecraft:gold_block", c.blockId());
        assertEquals(-5, c.x());
    }

    @Test
    void summonDropSoundParticleEffectFillAndKfx() {
        var summon = assertInstanceOf(ScriptCommand.Summon.class,
            ScriptCommandDispatcher.parse("summon:minecraft:pig:1.0000,2.0000,3.0000"));
        assertEquals("minecraft:pig", summon.entityId());
        var drop = assertInstanceOf(ScriptCommand.DropItem.class,
            ScriptCommandDispatcher.parse("drop_item:1.0000,2.0000,3.0000:minecraft:apple:3"));
        assertEquals("minecraft:apple", drop.itemId());
        assertEquals(3, drop.count());
        var sound = assertInstanceOf(ScriptCommand.PlaySound.class,
            ScriptCommandDispatcher.parse("play_sound:1.0000,2.0000,3.0000:minecraft:block.note_block.bell:1.0000,1.0000"));
        assertEquals("minecraft:block.note_block.bell", sound.id());
        var particle = assertInstanceOf(ScriptCommand.Particle.class,
            ScriptCommandDispatcher.parse("particle:minecraft:flame:1.0000,2.0000,3.0000:5"));
        assertEquals("minecraft:flame", particle.id());
        var effect = assertInstanceOf(ScriptCommand.Effect.class,
            ScriptCommandDispatcher.parse("effect:0000-1111:minecraft:speed:200:1"));
        assertEquals("minecraft:speed", effect.effectId());
        assertEquals(200, effect.ticks());
        assertEquals(1, effect.amplifier());
        var fill = assertInstanceOf(ScriptCommand.FillBlocks.class,
            ScriptCommandDispatcher.parse("fill:1,2,3:4,5,6:minecraft:diamond_block"));
        assertEquals("minecraft:diamond_block", fill.blockId());
        var kfx = assertInstanceOf(ScriptCommand.KfxSpawn.class,
            ScriptCommandDispatcher.parse("kfx_spawn:mypack:beam:1.0000,2.0000,3.0000:4.0000,5.0000,6.0000"));
        assertEquals("mypack:beam", kfx.id());
    }
}
