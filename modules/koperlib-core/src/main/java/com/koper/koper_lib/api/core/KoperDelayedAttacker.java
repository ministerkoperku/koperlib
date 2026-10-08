package com.koper.koper_lib.api.core;

// a mob in the middle of a bedrock style delayed attack (wind up, hit, recover). the client reads it as
// q.is_delayed_attacking, the server side goal sets it. lives in core so kodel can ask without fullpack
public interface KoperDelayedAttacker {
    boolean koperDelayedAttacking();
}
