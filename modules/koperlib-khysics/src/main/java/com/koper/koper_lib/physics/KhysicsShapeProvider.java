package com.koper.koper_lib.physics;

// block entities can expose sub-block occupancy without making KoperLib depend on an addon
public interface KhysicsShapeProvider {
    int khysicsResolution();
    long khysicsCells();
    default float khysicsMass(float fallbackMass) {
        int resolution = khysicsResolution();
        int totalCells = resolution * resolution * resolution;
        return totalCells > 0 ? fallbackMass * Long.bitCount(khysicsCells()) / totalCells : fallbackMass;
    }
}
