// fake java on the other end of __kq, enough to run a real looking addon script end to end
use super::*;
use std::sync::Mutex;

static SEEN: Mutex<Vec<String>> = Mutex::new(Vec::new());
static STASH: Mutex<Vec<u8>> = Mutex::new(Vec::new());

extern "C" fn fake_java(req: *const u8, len: i32, out: *mut u8, cap: i32) -> i32 {
    let answer: String = if len < 0 {
        String::from_utf8(STASH.lock().unwrap().clone()).unwrap()
    } else {
        let text = unsafe { std::str::from_utf8(std::slice::from_raw_parts(req, len as usize)).unwrap().to_string() };
        SEEN.lock().unwrap().push(text.clone());
        if text.contains("\"op\":\"players\"") {
            r#"[{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}]"#.into()
        } else if text.contains("\"op\":\"ent\"") {
            r#"{"ok":true,"x":1.5,"y":64,"z":-2,"dim":"minecraft:overworld","hp":20,"mhp":20,"tags":["vip"],"gm":"survival","head":{"x":1.5,"y":65.6,"z":-2},"look":{"x":0,"y":0,"z":1}}"#.into()
        } else if text.contains("\"op\":\"spawn\"") {
            r#"{"e":"u-2","ty":"kbt:koper_bug"}"#.into()
        } else if text.contains("\"op\":\"ents.ray\"") {
            "[]".into()
        } else if text.contains("\"op\":\"form\"") {
            "7".into()
        } else if text.contains("\"op\":\"big\"") {
            format!("\"{}\"", "x".repeat(40000))
        } else {
            "null".into()
        }
    };
    let bytes = answer.as_bytes();
    if bytes.len() > cap as usize {
        *STASH.lock().unwrap() = bytes.to_vec();
        return bytes.len() as i32;
    }
    unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len()) };
    bytes.len() as i32
}

fn seen_has(needle: &str) -> bool {
    SEEN.lock().unwrap().iter().any(|s| s.contains(needle))
}

#[test]
fn addon_runs_end_to_end() {
    let dir = std::env::temp_dir().join(format!("koper_js_test_{}", std::process::id()));
    std::fs::create_dir_all(dir.join("lib")).unwrap();
    std::fs::write(dir.join("lib/util.js"), "export const hi = (p) => `hi ${p.name}`;").unwrap();
    std::fs::write(dir.join("main.js"), r#"
        import { world, system, ItemStack } from "@minecraft/server";
        import { ActionFormData } from "@minecraft/server-ui";
        import { hi } from "./lib/util";
        let ticks = 0;
        system.runInterval(() => { ticks++; if (ticks === 3) world.sendMessage("three ticks"); }, 1);
        world.afterEvents.playerSpawn.subscribe((ev) => {
            ev.player.sendMessage(hi(ev.player));
            if (ev.player.hasTag("vip")) ev.player.sendMessage("vip at " + ev.player.location.y);
            new ActionFormData().title("menu").button("a").show(ev.player).then((r) => world.sendMessage("picked " + r.selection));
        });
        world.beforeEvents.chatSend.subscribe((ev) => { if (ev.message === "bad") ev.cancel = true; });
        system.beforeEvents.startup.subscribe((ev) => {
            ev.itemComponentRegistry.registerCustomComponent("test:zap", { onUse(e, { params }) { e.source.sendMessage("zap " + params.power); } });
        });
        const s = new ItemStack("diamond", 3);
        if (s.typeId !== "minecraft:diamond" || s.amount !== 3) throw new Error("itemstack broke");
        globalThis.bigOne = () => __koper.ask("big");
    "#).unwrap();

    let root = std::ffi::CString::new(dir.to_str().unwrap()).unwrap();
    let h = js_create(root.as_ptr().cast());
    assert_ne!(h, 0);
    js_set_query(h, fake_java as *const () as usize);
    js_set_timeout(h, 2000);
    let entry = std::ffi::CString::new(dir.join("main.js").to_str().unwrap()).unwrap();
    assert_eq!(js_load(h, entry.as_ptr().cast()), 0, "module did not load: {:?}", SEEN.lock().unwrap());

    let call = |json: &str| -> String {
        let c = std::ffi::CString::new(json).unwrap();
        let mut out = vec![0u8; 4096];
        let n = js_call(h, c.as_ptr().cast(), out.as_mut_ptr(), out.len());
        String::from_utf8(out[..n as usize].to_vec()).unwrap()
    };

    call(r#"{"t":"init","major":2,"tick":0}"#);
    call(r#"{"t":"startup"}"#);
    for t in 1..=4 { call(&format!(r#"{{"t":"tick","tick":{t}}}"#)); }
    assert!(seen_has("three ticks"));

    call(r#"{"t":"ev","n":"playerSpawn","b":false,"d":{"player":{"$e":{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}},"initialSpawn":true}}"#);
    assert!(seen_has("hi koper"));
    assert!(seen_has("vip at 64"));
    assert!(seen_has("\"op\":\"form\""));

    // form answer resolves the promise, the .then runs inside the same call
    call(r#"{"t":"form","fid":7,"sel":0}"#);
    assert!(seen_has("picked 0"));

    let cancel = call(r#"{"t":"ev","n":"chatSend","b":true,"d":{"sender":{"$e":{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}},"message":"bad"}}"#);
    assert_eq!(cancel, r#"{"cancel":true}"#);
    let fine = call(r#"{"t":"ev","n":"chatSend","b":true,"d":{"sender":{"$e":{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}},"message":"ok"}}"#);
    assert_eq!(fine, r#"{"cancel":false}"#);

    call(r#"{"t":"cc","kind":"item","ev":"onUse","comps":[{"n":"test:zap","p":{"power":9}}],"d":{"source":{"$e":{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}}}}"#);
    assert!(seen_has("zap 9"));

    // answers bigger than the first buffer go through the stash path
    let c = std::ffi::CString::new(r#"{"t":"nothing"}"#).unwrap();
    let mut out = vec![0u8; 8];
    js_call(h, c.as_ptr().cast(), out.as_mut_ptr(), out.len());
    js_destroy(h);
}

#[test]
fn runaway_loop_gets_killed() {
    let dir = std::env::temp_dir().join(format!("koper_js_loop_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("main.js"), "globalThis.__koper.on('tick', () => { while (true) {} });").unwrap();
    let root = std::ffi::CString::new(dir.to_str().unwrap()).unwrap();
    let h = js_create(root.as_ptr().cast());
    js_set_timeout(h, 50);
    let entry = std::ffi::CString::new(dir.join("main.js").to_str().unwrap()).unwrap();
    assert_eq!(js_load(h, entry.as_ptr().cast()), 0);
    let c = std::ffi::CString::new(r#"{"t":"tick","tick":1}"#).unwrap();
    let start = Instant::now();
    let mut out = vec![0u8; 64];
    js_call(h, c.as_ptr().cast(), out.as_mut_ptr(), out.len());
    assert!(start.elapsed().as_millis() < 2000);
    // vm is still usable after being interrupted
    let c = std::ffi::CString::new(r#"{"t":"nothing"}"#).unwrap();
    assert_eq!(js_call(h, c.as_ptr().cast(), out.as_mut_ptr(), out.len()), 0);
    js_destroy(h);
}

#[test]
fn imports_cannot_leave_the_pack() {
    let dir = std::env::temp_dir().join(format!("koper_js_jail_{}", std::process::id()));
    std::fs::create_dir_all(dir.join("pack")).unwrap();
    std::fs::write(dir.join("secret.js"), "export const s = 1;").unwrap();
    std::fs::write(dir.join("pack/main.js"), "import { s } from '../secret.js';").unwrap();
    let root = std::ffi::CString::new(dir.join("pack").to_str().unwrap()).unwrap();
    let h = js_create(root.as_ptr().cast());
    let entry = std::ffi::CString::new(dir.join("pack/main.js").to_str().unwrap()).unwrap();
    assert_eq!(js_load(h, entry.as_ptr().cast()), 1);
    js_destroy(h);
}

#[test]
fn sample_addon_script_loads_and_runs() {
    // the same main.js the fullpack converter test uses, straight out of the java test resources
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../modules/koperlib-fullpack/src/test/resources/bedrock/koper_bedrock_test/BP/scripts");
    let root = root.canonicalize().unwrap();
    let croot = std::ffi::CString::new(root.to_str().unwrap()).unwrap();
    let h = js_create(croot.as_ptr().cast());
    js_set_query(h, fake_java as *const () as usize);
    js_set_timeout(h, 2000);
    let entry = std::ffi::CString::new(root.join("main.js").to_str().unwrap()).unwrap();
    assert_eq!(js_load(h, entry.as_ptr().cast()), 0, "{:?}", SEEN.lock().unwrap().iter().filter(|s| s.contains("error")).collect::<Vec<_>>());
    let call = |json: &str| -> String {
        let c = std::ffi::CString::new(json).unwrap();
        let mut out = vec![0u8; 4096];
        let n = js_call(h, c.as_ptr().cast(), out.as_mut_ptr(), out.len());
        String::from_utf8(out[..n.max(0) as usize].to_vec()).unwrap()
    };
    call(r#"{"t":"init","major":2,"tick":0}"#);
    call(r#"{"t":"startup"}"#);
    assert!(seen_has("\"op\":\"cmd.reg\"") && seen_has("kbt:menu"));
    let bug = call(r#"{"t":"ev","n":"chatSend","b":true,"d":{"sender":{"$e":{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}},"message":"!bug please"}}"#);
    assert_eq!(bug, r#"{"cancel":true}"#);
    call(r#"{"t":"tick","tick":1}"#);
    assert!(seen_has("\"op\":\"spawn\"") && seen_has("kbt:koper_bug"));
    call(r#"{"t":"cc","kind":"item","ev":"onUse","comps":[{"n":"kbt:zap","p":{"power":7}}],"d":{"source":{"$e":{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}}}}"#);
    assert!(seen_has("* zap for 7 *"));
    // nothing threw on the way
    assert!(!SEEN.lock().unwrap().iter().any(|s| s.contains("\"lvl\":\"error\"") && s.contains("kbt")), "{:?}", SEEN.lock().unwrap());
    js_destroy(h);
}

#[test]
fn unhandled_rejection_is_logged() {
    let dir = std::env::temp_dir().join(format!("koper_js_rej_{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("main.js"), r#"
        import { MessageBox, ObservableString } from "@minecraft/server-ui";
        async function start() { throw new Error("no usable sites"); }
        start();
        Promise.reject(new Error("caught later")).catch(() => {});
        const o = new ObservableString("a");
        let got = "";
        o.subscribe((v) => { got = v; });
        o.setData("b");
        if (got !== "b" || o.getData() !== "b" || typeof MessageBox !== "function") throw new Error("observable broke");
    "#).unwrap();
    let root = std::ffi::CString::new(dir.to_str().unwrap()).unwrap();
    let h = js_create(root.as_ptr().cast());
    js_set_query(h, fake_java as *const () as usize);
    js_set_timeout(h, 2000);
    let entry = std::ffi::CString::new(dir.join("main.js").to_str().unwrap()).unwrap();
    assert_eq!(js_load(h, entry.as_ptr().cast()), 0, "module did not load: {:?}", SEEN.lock().unwrap());
    assert!(seen_has("Unhandled promise rejection: Error: no usable sites"), "{:?}", SEEN.lock().unwrap());
    assert!(!seen_has("caught later"));
    js_destroy(h);
}
