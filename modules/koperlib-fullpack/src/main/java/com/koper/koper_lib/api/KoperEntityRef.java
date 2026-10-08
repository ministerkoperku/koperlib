package com.koper.koper_lib.api;

import com.koper.koper_lib.scripting.ScriptEvent;
import net.minecraft.world.entity.EntityType;

import java.util.List;

public interface KoperEntityRef {
    String namespace();
    String path();
    String fullId();

    EntityType<?> asEntityType();

    float maxHealth();
    float movementSpeed();
    float attackDamage();
    String aiType();

    String model();
    String texture();
    boolean burnsInDaylight();
    boolean ranged();
    float width();
    float height();

    boolean persistent();
    boolean noAi();
    boolean baby();
    String tamingItem();
    boolean rideable();
    float rideSpeed();

    List<String> entityAi();
    String logic();
    List<String> scripts();

    void fireEvent(ScriptEvent event, Object... args);

    boolean isPackEnabled();
}
