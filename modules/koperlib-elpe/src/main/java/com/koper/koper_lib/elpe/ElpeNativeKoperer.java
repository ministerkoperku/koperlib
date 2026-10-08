package com.koper.koper_lib.elpe;

import com.koper.koper_lib.api.core.KoperModuleNative;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

// every koper_elpe_* symbol, looked up once. if the .so is missing everything here is null and ElpeKoperWorld refuses to exist
final class ElpeNativeKoperer {
    static final KoperModuleNative NATIVE = KoperModuleNative.load("elpe", "koperlib_elpe_engine", ElpeNativeKoperer.class);

    static final MethodHandle VERSION = fn("koper_elpe_version", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle WORLD_NEW = fn("koper_elpe_world_new", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_FLOAT));
    static final MethodHandle WORLD_FREE = fn("koper_elpe_world_free", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle CONFIGURE = fn("koper_elpe_configure", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle STEP = fn("koper_elpe_step", FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_INT));
    static final MethodHandle SPAWN = fn("koper_elpe_spawn",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT));
    static final MethodHandle SPAWN_BULK = fn("koper_elpe_spawn_bulk",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle DESPAWN = fn("koper_elpe_despawn", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle SET_POS = fn("koper_elpe_set_pos",
        FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT));
    static final MethodHandle ADD_VELOCITY = fn("koper_elpe_add_velocity",
        FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    static final MethodHandle GET = fn("koper_elpe_get", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    static final MethodHandle WAKE = fn("koper_elpe_wake", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle JOINT = fn("koper_elpe_joint", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    static final MethodHandle JOINT_HERE = fn("koper_elpe_joint_here",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));
    static final MethodHandle UNJOINT = fn("koper_elpe_unjoint", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle JOINT_ALIVE = fn("koper_elpe_joint_alive", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle SECTION = fn("koper_elpe_section",
        FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle FORGET_SECTION = fn("koper_elpe_forget_section",
        FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    static final MethodHandle BLOCK = fn("koper_elpe_block",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    static final MethodHandle REQUESTS = fn("koper_elpe_requests", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle QUERY_SPHERE = fn("koper_elpe_query_sphere", FunctionDescriptor.of(JAVA_INT, ADDRESS,
        JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS, JAVA_INT));
    static final MethodHandle WAKE_SPHERE = fn("koper_elpe_wake_sphere",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    static final MethodHandle BLAST = fn("koper_elpe_blast",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
    static final MethodHandle POSITIONS = fn("koper_elpe_positions", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle STATES = fn("koper_elpe_states", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle AWAKE_IDS = fn("koper_elpe_awake_ids", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle HIGH_WATER = fn("koper_elpe_high_water", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle STATS = fn("koper_elpe_stats", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    private ElpeNativeKoperer() {}

    static boolean ready() {
        return NATIVE.loaded() && WORLD_NEW != null && STEP != null;
    }

    private static MethodHandle fn(String name, FunctionDescriptor d) {
        return NATIVE.function(name, d);
    }
}
