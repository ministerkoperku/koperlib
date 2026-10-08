package com.koper.koper_lib.physics.weight;

// per-block physics properties — loaded from datapacks
// mass in kg, friction may exceed 1 for tyres, restitution stays in 0-1
// aero = this block is an aerodynamic element (wing/fin/balloon). ONLY aero blocks generate forces.
// dragCoeff = Cd, liftCoeff > 0 = airfoil WING (lift from motion), balloonLift > 0 = BALLOON (air buoyancy
// in mass units — rises to a density-altitude). buoyancyVolume is for WATER, separate from balloonLift.
public record KhysBlockProps(float mass, float friction, float restitution, float fragilityImpulse,
                             float buoyancyVolume, boolean aero, float dragCoeff, float liftCoeff,
                             float balloonLift, boolean wheel) {

    public static final KhysBlockProps DEFAULT = new KhysBlockProps(1.0f, 0.6f, 0.2f, 9999f, 1.0f, false, 1.0f, 0.0f, 0.0f, false);

    // quick validity check — mass must be positive, rest clamped
    public KhysBlockProps {
        if (mass <= 0) mass = DEFAULT.mass;
        friction    = Math.max(0f, Math.min(4f, friction));
        restitution = Math.max(0f, Math.min(1f, restitution));
        if (fragilityImpulse <= 0) fragilityImpulse = DEFAULT.fragilityImpulse;
        buoyancyVolume = Math.max(0f, buoyancyVolume);
        dragCoeff = Math.max(0f, dragCoeff);
        liftCoeff = Math.max(0f, liftCoeff);
        balloonLift = Math.max(0f, balloonLift);
    }

    public boolean isWing()    { return liftCoeff > 0f; }
    public boolean isBalloon() { return balloonLift > 0f; }
}
