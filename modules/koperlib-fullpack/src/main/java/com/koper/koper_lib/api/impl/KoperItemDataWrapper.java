package com.koper.koper_lib.api.impl;

import com.koper.koper_lib.api.KoperItemRef;
import com.koper.koper_lib.data.KoperItemData;
import com.koper.koper_lib.loader.FullPackLoader;
import com.koper.koper_lib.scripting.ScriptEvent;
import com.koper.koper_lib.scripting.UniversalScriptEngine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.Collections;
import java.util.List;
import java.util.Map;

// wraps KoperItemData into the typed KoperItemRef interface
// idk it just holds the data and gives it out when asked
class KoperItemDataWrapper implements KoperItemRef {

    private final Identifier id;
    private final KoperItemData data;
    private final String packNamespace; // which pack owns this item

    KoperItemDataWrapper(Identifier id, KoperItemData data, String packNamespace) {
        this.id = id;
        this.data = data;
        this.packNamespace = packNamespace;
    }

    @Override public String namespace() { return id.getNamespace(); }
    @Override public String path()      { return id.getPath(); }
    @Override public String fullId()    { return id.toString(); }

    @Override
    public Item asItem() {
        return BuiltInRegistries.ITEM.getValue(id);
    }

    @Override
    public ItemStack asStack() {
        Item item = asItem();
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Override public int    damage()       { return data.damage   != null ? data.damage   : 1; }
    @Override public int    durability()   { return data.durability != null ? data.durability : 0; }
    @Override public double attackSpeed()  { return data.attackSpeed != null ? data.attackSpeed : -2.4; }
    @Override public double miningSpeed()  { return data.miningSpeed != null ? data.miningSpeed : 1.0; }
    @Override public Rarity rarity()       { return data.rarity != null ? data.rarity : Rarity.COMMON; }
    @Override public int    maxStack()     { return data.maxStack != null ? data.maxStack : 64; }

    @Override public String type()   { return data.type   != null ? data.type   : "generic"; }
    @Override public String preset() { return data.preset != null ? data.preset : ""; }

    @Override public boolean isFood()          { return data.foodHunger != null && data.foodHunger > 0; }
    @Override public int     foodHunger()      { return data.foodHunger != null ? data.foodHunger : 0; }
    @Override public float   foodSaturation()  { return data.foodSaturation != null ? data.foodSaturation : 0f; }
    @Override public boolean alwaysEdible()    { return Boolean.TRUE.equals(data.alwaysEdible); }

    @Override public int    defense()               { return data.defense != null ? data.defense : 0; }
    @Override public float  toughness()             { return data.toughness != null ? data.toughness : 0f; }
    @Override public float  knockbackResistance()   { return data.knockbackResistance != null ? data.knockbackResistance : 0f; }
    @Override public String slot()                  { return data.slot != null ? data.slot : ""; }

    @Override public double attackAoeRadius() { return data.attackAoeRadius != null ? data.attackAoeRadius : 0.0; }
    @Override public String onHitCommand()    { return data.onHitCommand != null ? data.onHitCommand : ""; }

    @Override public float   projectileSpeed() { return data.projectileSpeed != null ? data.projectileSpeed : 1.0f; }
    @Override public float   drawTime()        { return data.drawTime != null ? data.drawTime : 1.0f; }
    @Override public boolean throwable()       { return Boolean.TRUE.equals(data.throwable); }

    @Override public String  texture()  { return data.texture != null ? data.texture : ""; }
    @Override public boolean hasGlint() { return Boolean.TRUE.equals(data.glint); }

    @Override public List<String>         lore()           { return data.lore != null ? Collections.unmodifiableList(data.lore) : List.of(); }
    @Override public Map<String, Integer> enchantments()   { return data.enchantments != null ? Collections.unmodifiableMap(data.enchantments) : Map.of(); }
    @Override public boolean              unbreakable()    { return Boolean.TRUE.equals(data.unbreakable); }
    @Override public int                  customModelData() { return data.customModelData != null ? data.customModelData : -1; }

    @Override public List<String> scripts() { return data.scripts != null ? Collections.unmodifiableList(data.scripts) : List.of(); }

    @Override
    public void fireEvent(ScriptEvent event, Object... args) {
        for (String script : scripts()) {
            UniversalScriptEngine.call(script, event, args);
        }
    }

    @Override
    public boolean isPackEnabled() {
        return FullPackLoader.isEnabled(packNamespace);
    }
}
