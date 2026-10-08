package com.koper.koper_lib.kui;

// holds the render data for a container gui between "data packet arrived" and "menu open packet arrived".
// the menu's client factory grabs it. plain class (no client-only types) so it loads fine on a server too,
// where it just never gets touched.
public final class KuiClientPending {
    private static KuiMenuData pending;

    private KuiClientPending() {}

    public static void set(KuiMenuData data) { pending = data; }

    public static KuiMenuData take() {
        KuiMenuData d = pending;
        pending = null;
        return d;
    }
}
