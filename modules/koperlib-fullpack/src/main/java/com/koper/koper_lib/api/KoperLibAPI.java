package com.koper.koper_lib.api;

import com.koper.koper_lib.api.impl.KoperBlockVault;
import com.koper.koper_lib.api.impl.KoperContentShelf;
import com.koper.koper_lib.api.impl.KoperDimensionVault;
import com.koper.koper_lib.api.impl.KoperEntityVault;
import com.koper.koper_lib.api.impl.KoperItemVault;
import com.koper.koper_lib.api.impl.KoperPackShelf;
import com.koper.koper_lib.api.impl.KoperRuntimeFace;
import com.koper.koper_lib.api.impl.KoperScriptWizard;

// entrypoint for other mods using koperlib
// KoperLibAPI.items().require("ns:sword")
// KoperLibAPI.blocks().require("ns:pad")
// KoperLibAPI.entities().require("ns:mob")
// KoperLibAPI.dimensions().require("ns:void")
// KoperLibAPI.content().spawnEntity("ns:mob", level, pos)
// KoperLibAPI.fullpack().addEntityHook("ns:mob", "on_tick", ctx -> PASS)
public final class KoperLibAPI {

    private KoperLibAPI() {}

    private static volatile FullPackAPI fullpackInstance;
    private static volatile ScriptingAPI scriptingInstance;
    private static volatile KoperItemVault itemsVaultInstance;
    private static volatile KoperBlockVault blocksVaultInstance;
    private static volatile KoperEntityVault entitiesVaultInstance;
    private static volatile KoperDimensionVault dimensionsVaultInstance;
    private static volatile KoperContentShelf contentShelfInstance;
    private static volatile KoperRuntimeFace runtimeFaceInstance;

    public static FullPackAPI fullpack() {
        if (fullpackInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (fullpackInstance == null) fullpackInstance = new KoperPackShelf();
            }
        }
        return fullpackInstance;
    }

    public static ScriptingAPI scripting() {
        if (scriptingInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (scriptingInstance == null) scriptingInstance = new KoperScriptWizard();
            }
        }
        return scriptingInstance;
    }

    public static ItemsAPI items() {
        return vault();
    }

    public static BlocksAPI blocks() {
        if (blocksVaultInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (blocksVaultInstance == null) blocksVaultInstance = new KoperBlockVault();
            }
        }
        return blocksVaultInstance;
    }

    public static EntitiesAPI entities() {
        if (entitiesVaultInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (entitiesVaultInstance == null) entitiesVaultInstance = new KoperEntityVault();
            }
        }
        return entitiesVaultInstance;
    }

    public static DimensionsAPI dimensions() {
        if (dimensionsVaultInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (dimensionsVaultInstance == null) dimensionsVaultInstance = new KoperDimensionVault();
            }
        }
        return dimensionsVaultInstance;
    }

    public static ContentAPI content() {
        if (contentShelfInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (contentShelfInstance == null) contentShelfInstance = new KoperContentShelf();
            }
        }
        return contentShelfInstance;
    }

    public static RuntimeAPI runtime() {
        if (runtimeFaceInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (runtimeFaceInstance == null) runtimeFaceInstance = new KoperRuntimeFace();
            }
        }
        return runtimeFaceInstance;
    }

    public static FullPackJarsAPI jars() {
        return vault();
    }

    public static Object requireModule(String moduleId) {
        return switch (moduleId) {
            case "fullpack-api"    -> fullpack();
            case "scripting-api"   -> scripting();
            case "items-api"       -> items();
            case "blocks-api"      -> blocks();
            case "entities-api"    -> entities();
            case "dimensions-api"  -> dimensions();
            case "content-api"     -> content();
            case "runtime-api"     -> runtime();
            case "fullpack-jars"   -> jars();
            default -> throw new IllegalArgumentException("unknown koperlib module: " + moduleId);
        };
    }

    private static KoperItemVault vault() {
        if (itemsVaultInstance == null) {
            synchronized (KoperLibAPI.class) {
                if (itemsVaultInstance == null) itemsVaultInstance = new KoperItemVault();
            }
        }
        return itemsVaultInstance;
    }
}
