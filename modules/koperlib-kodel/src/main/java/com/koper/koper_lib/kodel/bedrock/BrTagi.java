package com.koper.koper_lib.kodel.bedrock;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

// bedrock item tags (minecraft:is_sword, minecraft:golden_tier...) are not java tags. packs ask
// them for every held item to pick poses and animations; as java tags they never matched once
final class BrTagi {

    private static final Map<String, Predicate<ItemStack>> TAGI = new HashMap<>();

    private static void tag(String name, TagKey<Item> java) { TAGI.put(name, s -> s.is(java)); }

    private static void tag(String name, Predicate<ItemStack> p) { TAGI.put(name, p); }

    private static String id(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(); }

    private static Predicate<ItemStack> tier(String... prefixes) {
        return s -> {
            String p = id(s);
            for (String x : prefixes) if (p.startsWith(x)) return true;
            return false;
        };
    }

    private static boolean armor(ItemStack s) {
        var eq = s.get(DataComponents.EQUIPPABLE);
        return eq != null && eq.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
    }

    static {
        tag("is_sword", ItemTags.SWORDS);
        tag("is_axe", ItemTags.AXES);
        tag("is_pickaxe", ItemTags.PICKAXES);
        tag("is_shovel", ItemTags.SHOVELS);
        tag("is_hoe", ItemTags.HOES);
        tag("is_spear", ItemTags.SPEARS);
        tag("is_trident", s -> id(s).equals("trident"));
        tag("is_mace", s -> id(s).equals("mace"));
        tag("digger", s -> s.is(ItemTags.PICKAXES) || s.is(ItemTags.AXES) || s.is(ItemTags.SHOVELS) || s.is(ItemTags.HOES));
        tag("is_tool", s -> TAGI.get("digger").test(s) || s.is(ItemTags.SWORDS) || s.is(ItemTags.SPEARS) || id(s).equals("mace")
            || id(s).equals("trident") || id(s).equals("shears") || id(s).equals("flint_and_steel"));
        tag("is_armor", BrTagi::armor);
        tag("is_food", s -> s.has(DataComponents.FOOD));
        tag("is_meat", ItemTags.MEAT);
        tag("is_fish", ItemTags.FISHES);
        tag("is_cooked", s -> id(s).startsWith("cooked_") || id(s).equals("baked_potato"));
        tag("is_minecart", s -> id(s).endsWith("minecart"));
        tag("boat", ItemTags.BOATS);
        tag("boats", ItemTags.BOATS);
        tag("planks", ItemTags.PLANKS);
        tag("logs", ItemTags.LOGS);
        tag("wool", ItemTags.WOOL);
        tag("arrow", ItemTags.ARROWS);
        tag("banner", ItemTags.BANNERS);
        tag("sign", ItemTags.SIGNS);
        tag("hanging_sign", ItemTags.HANGING_SIGNS);
        tag("egg", ItemTags.EGGS);
        tag("coals", ItemTags.COALS);
        tag("sand", ItemTags.SAND);
        tag("music_disc", s -> s.has(DataComponents.JUKEBOX_PLAYABLE));
        tag("horse_armor", s -> id(s).endsWith("horse_armor"));
        tag("trim_materials", ItemTags.TRIM_MATERIALS);
        tag("decorated_pot_sherds", ItemTags.DECORATED_POT_SHERDS);
        tag("lectern_books", ItemTags.LECTERN_BOOKS);
        tag("bookshelf_books", ItemTags.BOOKSHELF_BOOKS);
        tag("wooden_tier", tier("wooden_"));
        tag("stone_tier", tier("stone_"));
        tag("copper_tier", tier("copper_"));
        tag("iron_tier", tier("iron_"));
        tag("golden_tier", tier("golden_"));
        tag("diamond_tier", tier("diamond_"));
        tag("netherite_tier", tier("netherite_"));
        tag("leather_tier", tier("leather_"));
        tag("chainmail_tier", tier("chainmail_"));
        tag("turtle_tier", tier("turtle_"));
    }

    // a bedrock tag first, then the same name as a java tag (packs' own namespaces, java datapacks)
    static boolean ma(ItemStack s, String tag) {
        if (s.isEmpty()) return false;
        String t = tag.startsWith("minecraft:") ? tag.substring(10) : tag;
        Predicate<ItemStack> p = TAGI.get(t);
        if (p != null && p.test(s)) return true;
        Identifier id = Identifier.tryParse(tag);
        return id != null && s.is(TagKey.create(Registries.ITEM, id));
    }
}
