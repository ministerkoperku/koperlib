package com.koper.koper_lib.kui;

import java.util.List;

// one data-driven recipe for a kui crafting gui. shapeless: the container must hold exactly these
// ingredients (multiset), nothing more. id lets the script react to a specific recipe via gui:recipe.
public record KuiRecipe(String id, List<String> ingredients, String result, int count) {}
