package com.koper.koper_lib.loader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class UniversalLoaderTest {
    @Test
    void kfxAllowsNestedFragmentDirectoriesWithoutMakingEveryContentTypeRecursive() {
        assertTrue(UniversalLoader.recursiveContentType("kfx"));
        assertTrue(UniversalLoader.recursiveContentType("fx"));
        assertFalse(UniversalLoader.recursiveContentType("items"));
    }
}
