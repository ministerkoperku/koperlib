package com.koper.koper_lib.physics.body;

// a vehicle brain. called once per physics step (60Hz) with that step's state, and pushes back.
//
// it runs ON THE PHYSICS THREAD, not the server thread. read your own fields and the state you are
// given; do not touch the level, entities or block entities from in here — sample what you need on
// the server tick into fields and read those. keep it quick: the step waits for you.
//
// an engine that can't call it on its own thread (elpe) gets it called on the server tick instead,
// with the last published state and the result held for the whole tick. same code, coarser clock.
@FunctionalInterface
public interface KhysPusher {
    void push(KhysBodyState state, KhysPush push);
}
