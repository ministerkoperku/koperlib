package com.koper.koper_lib.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface KoperEntity {
    String value();
    String[] also() default {};
}
