package com.koper.koper_lib.api;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.List;
import java.util.Map;

// typed handle to a json-defined item — access all fields without parsing json yourself
// hello people reading this. if you found a better way to do this PLEASE HELP A SILLY LITTLE KOPERDEV
public interface KoperItemRef {

    String namespace();
    String path();
    // full id like "koper_proof:echo_stick"
    String fullId();

    // raw minecraft item handle — can be null if pack was disabled after registration
    Item asItem();
    // convenience — creates a stack of 1
    ItemStack asStack();

    // basic stats
    int damage();
    int durability();
    double attackSpeed();
    double miningSpeed();
    Rarity rarity();
    int maxStack();

    // type/preset from json
    String type();
    String preset();

    // food
    boolean isFood();
    int foodHunger();
    float foodSaturation();
    boolean alwaysEdible();

    // armor
    int defense();
    float toughness();
    float knockbackResistance();
    String slot();

    // combat extras
    double attackAoeRadius();
    String onHitCommand();

    // ranged
    float projectileSpeed();
    float drawTime();
    boolean throwable();

    // visual
    String texture();
    boolean hasGlint();

    // components
    List<String> lore();
    Map<String, Integer> enchantments();
    boolean unbreakable();
    int customModelData(); // -1 if not set

    // scripts bound to this item
    List<String> scripts();

    // fires a script event as if the item was used — useful for Java hooks
    void fireEvent(com.koper.koper_lib.scripting.ScriptEvent event, Object... args);

    // is the pack that owns this item currently enabled?
    boolean isPackEnabled();
}
