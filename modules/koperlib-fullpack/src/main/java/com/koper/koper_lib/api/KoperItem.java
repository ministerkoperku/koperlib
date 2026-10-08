package com.koper.koper_lib.api;

import java.lang.annotation.*;

// slap on a class — all methods named on_use / on_hit / on_equip etc. auto-bind to the item
// cleaner than @KoperHook on every method when you have one class per item
// value: "namespace:item_id"  e.g. @KoperItem("koper_examples:soul_blade")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface KoperItem {
    String value();

    // extra events this class also handles — useful when one class covers multiple items
    // leave empty to only handle the primary value() item
    String[] also() default {};
}
