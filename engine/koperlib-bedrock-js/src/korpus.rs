// real addon scripts, one vm each, against a fake java that answers like a quiet world with one
// player in it. KOPER_JS_CORPUS=<entries.txt> cargo test --release -p koperlib-bedrock-js korpus -- --nocapture
// entries.txt: "<script root>\t<entry file>" per line. without the variable it does nothing.
// what it counts: addons that do not even load (a missing export kills the whole module), api
// calls koper has not built, and everything else scripts threw. the last group partly comes from the
// fake answering null where java would answer something, the first two never do
use super::*;
use std::collections::BTreeMap;
use std::sync::Mutex;

static LOG: Mutex<Vec<String>> = Mutex::new(Vec::new());
static BIG: Mutex<Vec<u8>> = Mutex::new(Vec::new());

const PLAYER: &str = r#"{"e":"u-1","ty":"minecraft:player","p":1,"n":"koper"}"#;

fn answer(text: &str) -> String {
    let op = text.split("\"op\":\"").nth(1).and_then(|s| s.split('"').next()).unwrap_or("");
    match op {
        "players" => format!("[{PLAYER}]"),
        "ents" | "select" => format!("[{PLAYER}]"),
        "ent" => r#"{"ok":true,"x":0.5,"y":64,"z":0.5,"dim":"minecraft:overworld","hp":20,"mhp":20,"tags":[],"gm":"survival","fam":["player"],"head":{"x":0.5,"y":65.6,"z":0.5},"look":{"x":0,"y":0,"z":1},"rot":{"x":0,"y":0},"vel":{"x":0,"y":0,"z":0},"sel":0,"lvl":0,"xp":0}"#.into(),
        "spawn" => r#"{"e":"u-2","ty":"minecraft:pig"}"#.into(),
        "block" => r#"{"ty":"minecraft:stone","st":{},"x":0,"y":63,"z":0,"dim":"minecraft:overworld","ok":true}"#.into(),
        "cont" => r#"{"ok":true,"size":36,"empty":36}"#.into(),
        "cmd" => r#"{"n":1}"#.into(),
        "form" => "7".into(),
        "time" | "day" | "tick" | "absTime" => "0".into(),
        "dyn.get" | "dyn" => "null".into(),
        "iteminfo" | "info" => r#"{"max":64}"#.into(),
        // a world where every objective a pack asks for already exists (a template's world has them saved)
        "sb" if text.contains(r#""a":"has""#) => "true".into(),
        "sb" if text.contains(r#""a":"get""#) => "0".into(),
        _ => "null".into(),
    }
}

extern "C" fn fake(req: *const u8, len: i32, out: *mut u8, cap: i32) -> i32 {
    let ans = if len < 0 {
        String::from_utf8(BIG.lock().unwrap().clone()).unwrap_or_default()
    } else {
        let text = unsafe { std::str::from_utf8(std::slice::from_raw_parts(req, len as usize)).unwrap_or("").to_string() };
        let a = answer(&text);
        LOG.lock().unwrap().push(text);
        a
    };
    let b = ans.as_bytes();
    if b.len() > cap as usize {
        *BIG.lock().unwrap() = b.to_vec();
        return b.len() as i32;
    }
    unsafe { std::ptr::copy_nonoverlapping(b.as_ptr(), out, b.len()) };
    b.len() as i32
}

// "koperlib bedrock: Entity.foo is not implemented yet" -> "Entity.foo"; other errors: first line, numbers blanked
fn kind(msg: &str) -> (u8, String) {
    if let Some(i) = msg.find("koperlib bedrock: ") {
        let rest = &msg[i + 18..];
        let what = rest.split(" is not").next().unwrap_or(rest);
        return (1, what.trim().to_string());
    }
    let first = msg.lines().next().unwrap_or("").to_string();
    let first: String = first.chars().map(|c| if c.is_ascii_digit() { '#' } else { c }).collect();
    (2, first.chars().take(140).collect())
}

#[test]
fn korpus() {
    let Ok(list) = std::env::var("KOPER_JS_CORPUS") else { return };
    let text = std::fs::read_to_string(list).unwrap();
    let (mut loaded, mut failed) = (0, 0);
    let mut load_err: BTreeMap<String, usize> = BTreeMap::new();
    let mut missing: BTreeMap<String, usize> = BTreeMap::new();
    let mut other: BTreeMap<String, usize> = BTreeMap::new();
    let loud = std::env::var("KOPER_JS_LOUD").is_ok();
    for line in text.lines().filter(|l| !l.is_empty()) {
        let (root, entry) = line.split_once('\t').unwrap();
        LOG.lock().unwrap().clear();
        let croot = std::ffi::CString::new(root).unwrap();
        let h = js_create(croot.as_ptr().cast());
        js_set_query(h, fake as *const () as usize);
        js_set_timeout(h, 1000);
        let centry = std::ffi::CString::new(entry).unwrap();
        let ok = js_load(h, centry.as_ptr().cast()) == 0;
        let call = |json: &str| {
            let c = std::ffi::CString::new(json).unwrap();
            let mut out = vec![0u8; 1 << 16];
            js_call(h, c.as_ptr().cast(), out.as_mut_ptr(), out.len());
        };
        if ok {
            loaded += 1;
            call(r#"{"t":"init","major":2,"tick":0}"#);
            call(r#"{"t":"startup"}"#);
            call(r#"{"t":"load"}"#);
            let p = format!(r#"{{"$e":{PLAYER}}}"#);
            let item = r#"{"$i":{"typeId":"minecraft:diamond_sword","amount":1}}"#;
            let mob = r#"{"$e":{"e":"u-3","ty":"minecraft:villager_v2"}}"#;
            for ev in [
                format!(r#"{{"t":"ev","n":"playerJoin","b":false,"d":{{"playerId":"u-1","playerName":"koper"}}}}"#),
                format!(r#"{{"t":"ev","n":"playerSpawn","b":false,"d":{{"player":{p},"initialSpawn":true}}}}"#),
                format!(r#"{{"t":"ev","n":"chatSend","b":true,"d":{{"sender":{p},"message":"!help"}}}}"#),
                format!(r#"{{"t":"ev","n":"chatSend","b":false,"d":{{"sender":{p},"message":"hello"}}}}"#),
                format!(r#"{{"t":"ev","n":"itemUse","b":false,"d":{{"source":{p},"itemStack":{item}}}}}"#),
                format!(r#"{{"t":"ev","n":"itemUse","b":true,"d":{{"source":{p},"itemStack":{item}}}}}"#),
                format!(r#"{{"t":"ev","n":"entityHitEntity","b":false,"d":{{"damagingEntity":{p},"hitEntity":{{"$e":{{"e":"u-2","ty":"minecraft:pig"}}}}}}}}"#),
                format!(r#"{{"t":"ev","n":"scriptEventReceive","b":false,"d":{{"id":"test:run","message":"x","sourceEntity":{p}}}}}"#),
                // the mob side: an addon about villagers only wakes up when one spawns, loads, gets clicked, dies
                format!(r#"{{"t":"ev","n":"entitySpawn","b":false,"d":{{"entity":{mob},"cause":"Spawned"}}}}"#),
                format!(r#"{{"t":"ev","n":"entityLoad","b":false,"d":{{"entity":{mob}}}}}"#),
                format!(r#"{{"t":"ev","n":"playerInteractWithEntity","b":true,"d":{{"player":{p},"target":{mob},"itemStack":{item}}}}}"#),
                format!(r#"{{"t":"ev","n":"playerInteractWithEntity","b":false,"d":{{"player":{p},"target":{mob},"itemStack":{item}}}}}"#),
                format!(r#"{{"t":"ev","n":"dataDrivenEntityTrigger","b":false,"d":{{"entity":{mob},"eventId":"minecraft:entity_spawned","modifiers":[]}}}}"#),
                format!(r#"{{"t":"ev","n":"entityHurt","b":false,"d":{{"hurtEntity":{mob},"damage":2,"damageSource":{{"cause":"entityAttack","damagingEntity":{p}}}}}}}"#),
                format!(r#"{{"t":"ev","n":"entityItemPickup","b":true,"d":{{"entity":{mob},"item":{{"$e":{{"e":"u-4","ty":"minecraft:item"}}}}}}}}"#),
                format!(r#"{{"t":"ev","n":"entityItemPickup","b":false,"d":{{"entity":{mob},"items":[{item}]}}}}"#),
                format!(r#"{{"t":"ev","n":"playerInventoryItemChange","b":false,"d":{{"player":{p},"slot":0,"inventoryType":"Hotbar","itemStack":{item}}}}}"#),
                format!(r#"{{"t":"ev","n":"entityDie","b":false,"d":{{"deadEntity":{mob},"damageSource":{{"cause":"entityAttack","damagingEntity":{p}}}}}}}"#),
                format!(r#"{{"t":"ev","n":"entityRemove","b":true,"d":{{"removedEntity":{mob}}}}}"#),
                format!(r#"{{"t":"ev","n":"entityRemove","b":false,"d":{{"removedEntityId":"u-3","typeId":"minecraft:villager_v2"}}}}"#),
            ] {
                call(&ev);
            }
            for t in 1..=60 {
                call(&format!(r#"{{"t":"tick","tick":{t}}}"#));
            }
        } else {
            failed += 1;
        }
        if !ok {
            // the vm says why through the log: "module <entry> failed: <error>"
            let last = LOG.lock().unwrap().iter().rev().find(|l| l.contains(" failed: ")).cloned().unwrap_or_default();
            let last = serde_json::from_str::<serde_json::Value>(&last).ok().and_then(|v| v.get("msg").and_then(|m| m.as_str()).map(String::from)).unwrap_or(last);
            let last = last.split_once(" failed: ").map(|(_, e)| e.to_string()).unwrap_or(last);
            let (_, k) = kind(&last);
            *load_err.entry(k.clone()).or_default() += 1;
            if loud {
                println!("LOAD {entry}: {last}");
            }
        }
        let log = LOG.lock().unwrap().clone();
        let mut here: Vec<(u8, String)> = Vec::new();
        for l in &log {
            if !l.contains("\"lvl\":\"error\"") {
                continue;
            }
            let msg = serde_json::from_str::<serde_json::Value>(l).ok().and_then(|v| v.get("msg").and_then(|m| m.as_str()).map(String::from)).unwrap_or_default();
            if std::env::var("KOPER_JS_STACK").is_ok() {
                println!("STACK {entry}: {msg}");
            }
            // "[x] handler threw Error: ..." -> the error part
            let msg = msg.split_once(" threw ").map(|(_, e)| e.to_string()).unwrap_or(msg);
            here.push(kind(&msg));
        }
        here.sort();
        here.dedup();
        for (k, m) in here {
            if loud {
                println!("  {entry}: {m}");
            }
            *(if k == 1 { &mut missing } else { &mut other }).entry(m).or_default() += 1;
        }
        js_destroy(h);
    }
    let top = |t: &str, m: &BTreeMap<String, usize>, n: usize| {
        let mut v: Vec<_> = m.iter().collect();
        v.sort_by(|a, b| b.1.cmp(a.1));
        println!("{t}: {} distinct, {} addon hits", m.len(), m.values().sum::<usize>());
        for (k, c) in v.iter().take(n) {
            println!("  {c:4}  {k}");
        }
    };
    println!("scripts: {} loaded, {} did not load", loaded, failed);
    top("load errors", &load_err, 40);
    top("api koper has not built (addons hitting it)", &missing, 60);
    top("other script errors (some from the fake world)", &other, 40);
}
