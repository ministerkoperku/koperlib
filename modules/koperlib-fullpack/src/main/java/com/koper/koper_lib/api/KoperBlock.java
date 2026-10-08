package com.koper.koper_lib.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface KoperBlock {
    String value();
    String[] also() default {};
}
