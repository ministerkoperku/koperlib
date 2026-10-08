package com.koper.koper_lib.api;

import java.lang.annotation.*;

// subscribe to a custom event fired via KoperEventBus.fire() or koper.events.fire() in Lua
// value: event id e.g. "my_pack:ritual_complete"
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KoperSubscribe {
    String value();
}
