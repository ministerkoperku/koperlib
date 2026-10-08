package com.koper.koper_lib.api;

import com.koper.koper_lib.scripting.ScriptEvent;
import net.minecraft.world.level.block.Block;

import java.util.List;

public interface KoperBlockRef {
    String namespace();
    String path();
    String fullId();

    Block asBlock();

    float hardness();
    float resistance();
    int lightLevel();
    String sound();
    float slipperiness();
    boolean dropsSelf();
    boolean transparent();
    boolean collidable();

    String miningTool();
    boolean requiresTool();
    int miningLevel();

    String renderType();
    String shape();
    int redstonePower();
    float mass();

    String texture();
    String logic();
    List<String> scripts();

    void fireEvent(ScriptEvent event, Object... args);

    boolean isPackEnabled();
}
