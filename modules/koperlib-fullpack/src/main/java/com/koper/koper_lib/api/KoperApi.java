package com.koper.koper_lib.api;

import com.koper.koper_lib.KoperLib;

// Public, stable entry point for OTHER mods — import this, not the internals.
// Everything koperlib exposes (geo models/animations, geo entities, koper-blocks, armor, events,
// bone control, fullpack control) hangs off here. Phase 7 fills out the sub-facades; this is the anchor
// so the rest of the codebase + downstream mods target a single stable surface.
public final class KoperApi {

    private KoperApi() {}

    /// koperlib version (x.y.z — x=release, y=beta/bugfix, z=alpha)
    public static String version() {
        return KoperLib.VERSION;
    }

    // Phase 7 — to be added (delegating to existing systems, internals hidden):
    //   models()    — register/query geo models + animations
    //   entities()  — spawn/animate geo entities, drive states
    //   blocks()    — register/place/animate koper-blocks
    //   armor()     — register geo armor
    //   events()    — hook Fabric + koperlib events (also exposed to Lua)
    //   bones()     — drive bone transforms (setBone, smooth helpers)
    //   fullpacks() — load/reload/enable fullpacks
}
