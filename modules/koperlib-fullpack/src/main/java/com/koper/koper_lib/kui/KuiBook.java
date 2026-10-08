package com.koper.koper_lib.kui;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

// the one place every registered gui lives. populated by GuiFactory on load/reload,
// read by the client when something asks to open a screen by id.
public final class KuiBook {
    private static final Map<String, KuiPage> PAGES = new LinkedHashMap<>();

    private KuiBook() {}

    public static void put(KuiPage page) {
        if (page != null && page.id != null) PAGES.put(page.id, page);
    }

    public static KuiPage get(String id) { return PAGES.get(id); }

    public static Collection<KuiPage> all() { return PAGES.values(); }

    public static java.util.Set<String> ids() { return PAGES.keySet(); }

    // reload phase 0 wipes everything, factories refill it
    public static void clear() { PAGES.clear(); }
}
