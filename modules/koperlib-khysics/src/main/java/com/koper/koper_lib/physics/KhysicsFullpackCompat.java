package com.koper.koper_lib.physics;

import com.koper.koper_lib.api.KoperItemTypes;

/** Fullpack bridge kept inside Khysics and loaded only when Fullpack exists. */
final class KhysicsFullpackCompat {
    private KhysicsFullpackCompat() {}

    static void registerContentTypes() {
        KoperItemTypes.register("khysics_wand", ctx -> new KhysicsWand(ctx.properties()));
        KoperItemTypes.register("khys_selection_wand", ctx -> new KhysSelectionWand(ctx.properties()));
        KoperItemTypes.register("khys_flight_stick", ctx -> new KhysFlightStick(ctx.properties()));
    }
}
