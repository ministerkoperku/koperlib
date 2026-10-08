package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.KoperLib;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;

// creates and registers custom potions from JSON 
public class PotionFactory {

    public record BrewingEntry(Holder<Potion> input, Item ingredient, Holder<Potion> output) {}
    private static final java.util.List<BrewingEntry> PENDING_BREWING = new ArrayList<>();

    public static java.util.List<BrewingEntry> getPendingBrewing() { return PENDING_BREWING; }
    public static void clearPendingBrewing() { PENDING_BREWING.clear(); }

    public static void createAndRegister(JsonObject json) {
        if (!json.has("id")) return;
        String idStr = json.get("id").getAsString();
        Identifier id = Identifier.tryParse(idStr);
        if (id == null) return;

        if (!json.has("effects") || !json.get("effects").isJsonArray()) {
            KoperLib.LOGGER.warn("PotionFactory: Potion " + idStr + " has no effects array.");
            return;
        }

        MobEffectInstance[] effectInstances = json.getAsJsonArray("effects").asList().stream()
                .map(e -> {
                    JsonObject eff = e.getAsJsonObject();
                    String effectId = eff.get("effect").getAsString();
                    int duration = eff.has("duration") ? eff.get("duration").getAsInt() : 3600;
                    int amplifier = eff.has("amplifier") ? eff.get("amplifier").getAsInt() : 0;

                    MobEffect MobEffect = BuiltInRegistries.MOB_EFFECT.getValue(Identifier.parse(effectId));
                    if (MobEffect == null) {
                        KoperLib.LOGGER.warn("PotionFactory: Unknown effect: " + effectId);
                        return null;
                    }
                    Holder<MobEffect> entry = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(MobEffect);
                    return new MobEffectInstance(entry, duration, amplifier);
                })
                .filter(java.util.Objects::nonNull)
                .toArray(MobEffectInstance[]::new);

        if (effectInstances.length == 0) return;

        String baseName = id.getPath();
        Potion potion = new Potion(baseName, effectInstances);
        Registry.register(BuiltInRegistries.POTION, id, potion);

        String name = json.has("name") ? json.get("name").getAsString() :
                FactoryUtils.capitalizeWords(id.getPath());
        KoperLib.VIRTUAL_PACK.addTranslation("item.minecraft.potion.effect." + id.getPath(), name);
        KoperLib.VIRTUAL_PACK.addTranslation("item.minecraft.splash_potion.effect." + id.getPath(), "Splash " + name);
        KoperLib.VIRTUAL_PACK.addTranslation("item.minecraft.lingering_potion.effect." + id.getPath(), "Lingering " + name);
        KoperLib.VIRTUAL_PACK.addTranslation("item.minecraft.tipped_arrow.effect." + id.getPath(), "Arrow of " + name);


        if (json.has("ingredient")) {
            String ingredientId = json.get("ingredient").getAsString();
            Item ingredient = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(ingredientId));
            if (ingredient != Items.AIR) {
                String basePotionId = json.has("base_potion") ? json.get("base_potion").getAsString() : "minecraft:awkward";
                Potion basePotion = BuiltInRegistries.POTION.getValue(Identifier.tryParse(basePotionId));
                if (basePotion == null) basePotion = Potions.AWKWARD.value();
                Holder<Potion> baseEntry = BuiltInRegistries.POTION.wrapAsHolder(basePotion);
                Holder<Potion> resultEntry = BuiltInRegistries.POTION.wrapAsHolder(potion);
                PENDING_BREWING.add(new BrewingEntry(baseEntry, ingredient, resultEntry));
                // 26.2's potion mix worked on whatever bottle held the base potion; 26.3 brewing is
                // one data recipe per bottle, so the mix is spelled out for all three
                Identifier baseId = BuiltInRegistries.POTION.getKey(basePotion);
                Identifier ingId = BuiltInRegistries.ITEM.getKey(ingredient);
                for (String bottle : BOTTLES)
                    addBrewing(id, bottle + "_" + id.getPath() + "_from_" + baseId.getPath(),
                        bottle, baseId, ingId, bottle, id);
            } else {
                KoperLib.LOGGER.warn("PotionFactory: Unknown ingredient '{}' for potion '{}'", ingredientId, idStr);
            }
        }

        // vanilla 26.3 only turns a bottle into splash/lingering for potions it lists one by one,
        // so a pack potion needs its own gunpowder and dragon breath recipes or it can never be thrown
        addBrewing(id, "splash_potion_" + id.getPath() + "_from_potion", "potion", id,
            Identifier.withDefaultNamespace("gunpowder"), "splash_potion", id);
        addBrewing(id, "lingering_potion_" + id.getPath() + "_from_splash_potion", "splash_potion", id,
            Identifier.withDefaultNamespace("dragon_breath"), "lingering_potion", id);

        KoperLib.LOGGER.info("PotionFactory: Registered potion: " + idStr);
    }

    private static final String[] BOTTLES = {"potion", "splash_potion", "lingering_potion"};

    // one minecraft:brewing recipe served through the virtual data pack (data/<ns>/recipe/brewing/..)
    private static void addBrewing(Identifier owner, String name, String inBottle, Identifier inPotion,
                                   Identifier reagent, String outBottle, Identifier outPotion) {
        JsonObject input = new JsonObject();
        input.addProperty("item", "minecraft:" + inBottle);
        JsonObject inContents = new JsonObject();
        inContents.addProperty("potions", inPotion.toString());
        input.add("potion_contents", inContents);

        JsonObject reagentJson = new JsonObject();
        reagentJson.addProperty("item", reagent.toString());

        JsonObject outContents = new JsonObject();
        outContents.addProperty("potion", outPotion.toString());
        JsonObject components = new JsonObject();
        components.add("minecraft:potion_contents", outContents);
        JsonObject output = new JsonObject();
        output.addProperty("id", "minecraft:" + outBottle);
        output.add("components", components);

        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:brewing");
        recipe.add("input", input);
        recipe.add("reagent", reagentJson);
        recipe.add("output", output);
        KoperLib.VIRTUAL_PACK.addServerAsset(
            Identifier.fromNamespaceAndPath(owner.getNamespace(), "recipe/brewing/" + name + ".json"), recipe.toString());
    }

}
