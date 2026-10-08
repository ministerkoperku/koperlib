//! Native Lua runtime owned exclusively by koperlib-fullpack.

use koperlib_scripting as scripting;

mod bp;

#[no_mangle]
pub extern "C" fn koper_init() -> i32 { 0 }

#[no_mangle]
pub extern "C" fn koper_version(buf: *mut u8, len: usize) -> i32 {
    if buf.is_null() || len < 8 { return 2; }
    let version = b"0.1.0\0";
    unsafe { std::ptr::copy_nonoverlapping(version.as_ptr(), buf, version.len().min(len)); }
    0
}

#[no_mangle]
pub extern "C" fn koper_script_create_vm() -> i64 { scripting::scripting_create_vm() }

#[no_mangle]
pub extern "C" fn koper_script_destroy_vm(handle: i64) { scripting::scripting_destroy_vm(handle) }

#[no_mangle]
pub extern "C" fn koper_script_load(handle: i64, path: *const u8) -> i32 {
    if path.is_null() { return 1; }
    let path = unsafe { std::ffi::CStr::from_ptr(path.cast()) };
    match path.to_str() {
        Ok(path) => scripting::scripting_load(handle, path),
        Err(_) => 1,
    }
}

#[no_mangle]
pub extern "C" fn koper_script_exec(handle: i64, code: *const u8) -> i32 {
    if code.is_null() { return 1; }
    let code = unsafe { std::ffi::CStr::from_ptr(code.cast()) };
    match code.to_str() {
        Ok(code) => scripting::scripting_exec(handle, code),
        Err(_) => 1,
    }
}

#[no_mangle]
pub extern "C" fn koper_script_drain_commands(handle: i64, buf: *mut u8, len: usize) -> i32 {
    scripting::scripting_drain_commands(handle, buf, len)
}

#[no_mangle]
pub extern "C" fn koper_script_set_timeout(handle: i64, millis: i64) {
    scripting::scripting_set_timeout(handle, millis)
}

#[no_mangle]
pub extern "C" fn koper_script_set_upcall(handle: i64, function: usize) {
    scripting::scripting_set_upcall(handle, function)
}

#[no_mangle]
pub extern "C" fn koper_script_set_query_upcall(handle: i64, function: usize) {
    scripting::scripting_set_query_upcall(handle, function)
}

// ── bedrock addon scripts (quickjs) ─────────────────────────────────────────

use koperlib_bedrock_js as bedrock;

#[no_mangle]
pub extern "C" fn koper_js_create(root: *const u8) -> i64 { bedrock::js_create(root) }

#[no_mangle]
pub extern "C" fn koper_js_destroy(handle: i64) { bedrock::js_destroy(handle) }

#[no_mangle]
pub extern "C" fn koper_js_set_timeout(handle: i64, millis: i64) { bedrock::js_set_timeout(handle, millis) }

#[no_mangle]
pub extern "C" fn koper_js_set_query(handle: i64, function: usize) { bedrock::js_set_query(handle, function) }

#[no_mangle]
pub extern "C" fn koper_js_load(handle: i64, entry: *const u8) -> i32 { bedrock::js_load(handle, entry) }

#[no_mangle]
pub extern "C" fn koper_js_call(handle: i64, json: *const u8, out: *mut u8, cap: usize) -> i32 {
    bedrock::js_call(handle, json, out, cap)
}

#[no_mangle]
pub extern "C" fn koper_js_last(handle: i64, out: *mut u8, cap: usize) -> i32 { bedrock::js_last(handle, out, cap) }

// ── server side molang (bedrock behavior packs: set_property, permutation conditions) ──

use koperlib_molang::{Book, Ctx, Program, Val};
use std::cell::RefCell;
use std::collections::HashMap;

thread_local! {
    // expression text -> compiled. packs reuse the same few strings thousands of times
    static MOLANG: RefCell<HashMap<String, (Book, Program)>> = RefCell::new(HashMap::new());
    static MOLANG_OUT: RefCell<String> = RefCell::new(String::new());
}

/// {"expr": "...", "q": {"property('a:b')": 1, "is_baby": 0, "variant": "'x'"}, "v": {...}} ->
/// {"n": number} or {"s": "string"}, plus "v" with the variables it wrote. queries it needs but
/// did not get read 0. kodel_molang_last hands the answer over
#[no_mangle]
pub extern "C" fn koper_molang_eval(json: *const u8, out: *mut u8, cap: usize) -> i32 {
    if json.is_null() { return -1; }
    let text = unsafe { std::ffi::CStr::from_ptr(json.cast()) }.to_string_lossy().into_owned();
    let Ok(req) = serde_json::from_str::<serde_json::Value>(&text) else { return -1 };
    let expr = req.get("expr").and_then(|x| x.as_str()).unwrap_or("0").to_string();
    let answer = MOLANG.with(|cache| {
        let mut cache = cache.borrow_mut();
        if !cache.contains_key(&expr) {
            let mut b = Book::default();
            let (p, _) = b.compile(&expr);
            cache.insert(expr.clone(), (b, p));
        }
        let (book, prog) = cache.get_mut(&expr).unwrap();
        let qv = req.get("q").and_then(|x| x.as_object());
        let mut qs = vec![0f32; book.queries.len()];
        let mut qstr = vec![-1i32; book.queries.len()];
        for (i, name) in book.queries.clone().iter().enumerate() {
            match qv.and_then(|m| m.get(name)) {
                Some(serde_json::Value::String(s)) => qstr[i] = book.string_id(s).map(|x| x as i32).unwrap_or(-2),
                Some(v) => qs[i] = v.as_f64().or_else(|| v.as_bool().map(|b| b as i32 as f64)).unwrap_or(0.0) as f32,
                None => {}
            }
        }
        let vin = req.get("v").and_then(|x| x.as_object());
        let mut vars = vec![Val::default(); book.vars.len()];
        let mut set = vec![false; book.vars.len()];
        for (i, name) in book.vars.clone().iter().enumerate() {
            if let Some(v) = vin.and_then(|m| m.get(name)) {
                set[i] = true;
                vars[i] = match v {
                    serde_json::Value::String(s) => Val::Str(book.string(s)),
                    other => Val::Num(other.as_f64().unwrap_or(0.0) as f32),
                };
            }
        }
        let mut temps = Vec::new();
        let mut rng = 0x1234_5678u32 ^ (text.len() as u32);
        let arrays: Vec<Vec<Val>> = Vec::new();
        let result = {
            let mut cx = Ctx::new(&qs, &mut vars, &mut set, &mut temps, &arrays, &mut rng);
            cx.qstr = &qstr;
            prog.eval(&mut cx)
        };
        let show = |v: &Val| -> serde_json::Value {
            match v {
                Val::Num(n) => serde_json::json!({"n": n}),
                Val::Str(s) => serde_json::json!({"s": book.strings.get(*s as usize).cloned().unwrap_or_default()}),
            }
        };
        let mut o = show(&result);
        let mut written = serde_json::Map::new();
        for (i, name) in book.vars.iter().enumerate() {
            if set[i] {
                written.insert(name.clone(), match vars[i] {
                    Val::Num(n) => serde_json::json!(n),
                    Val::Str(s) => serde_json::json!(book.strings.get(s as usize).cloned().unwrap_or_default()),
                });
            }
        }
        o["v"] = serde_json::Value::Object(written);
        o.to_string()
    });
    let bytes = answer.as_bytes();
    if !out.is_null() && bytes.len() <= cap {
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len()) };
    }
    bytes.len() as i32
}

/// the query names an expression wants, so java only computes those
#[no_mangle]
pub extern "C" fn koper_molang_queries(expr: *const u8, out: *mut u8, cap: usize) -> i32 {
    if expr.is_null() { return -1; }
    let text = unsafe { std::ffi::CStr::from_ptr(expr.cast()) }.to_string_lossy().into_owned();
    let answer = MOLANG.with(|cache| {
        let mut cache = cache.borrow_mut();
        if !cache.contains_key(&text) {
            let mut b = Book::default();
            let (p, _) = b.compile(&text);
            cache.insert(text.clone(), (b, p));
        }
        serde_json::to_string(&cache[&text].0.queries).unwrap_or_default()
    });
    let bytes = answer.as_bytes();
    if !out.is_null() && bytes.len() <= cap {
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len()) };
    }
    bytes.len() as i32
}

// ── behavior pack animations and controllers (bp.rs) ───────────────────────

fn put_json(v: &serde_json::Value, out: *mut u8, cap: usize) -> i32 {
    let s = v.to_string();
    let b = s.as_bytes();
    if !out.is_null() && b.len() <= cap {
        unsafe { std::ptr::copy_nonoverlapping(b.as_ptr(), out, b.len()) };
    }
    b.len() as i32
}

/// json (see bp::Def::build) -> def handle, 0 when it does not parse
#[no_mangle]
pub extern "C" fn koper_bp_define(json: *const u8, len: usize) -> i64 {
    if json.is_null() { return 0; }
    let bytes = unsafe { std::slice::from_raw_parts(json, len) };
    let Ok(src) = serde_json::from_slice::<serde_json::Value>(bytes) else { return 0 };
    Box::into_raw(Box::new(std::sync::Arc::new(bp::Def::build(&src)))) as i64
}

/// {"queries": [...], "strings": [...], "fired": [...], "errors": [...]}
#[no_mangle]
pub extern "C" fn koper_bp_describe(def: i64, out: *mut u8, cap: usize) -> i32 {
    if def == 0 { return -1; }
    let d = unsafe { &*(def as *const std::sync::Arc<bp::Def>) };
    put_json(&serde_json::json!({"queries": d.queries(), "strings": d.strings(), "fired": d.fired, "errors": d.errors}), out, cap)
}

#[no_mangle]
pub extern "C" fn koper_bp_undefine(def: i64) {
    if def != 0 { drop(unsafe { Box::from_raw(def as *mut std::sync::Arc<bp::Def>) }); }
}

#[no_mangle]
pub extern "C" fn koper_bp_spawn(def: i64, seed: u32) -> i64 {
    if def == 0 { return 0; }
    let d = unsafe { &*(def as *const std::sync::Arc<bp::Def>) };
    Box::into_raw(Box::new(bp::Inst::new(d.clone(), seed))) as i64
}

#[no_mangle]
pub extern "C" fn koper_bp_free(inst: i64) {
    if inst != 0 { drop(unsafe { Box::from_raw(inst as *mut bp::Inst) }); }
}

/// one tick: query slots (numbers, and string ids where >= 0), out gets the fired lines as
/// indices into describe's "fired". returns how many fired (may be more than max, then only max written)
#[no_mangle]
pub extern "C" fn koper_bp_tick(inst: i64, q: *const f32, qstr: *const i32, n: u32, dt: f32, out: *mut i32, max: u32) -> i32 {
    if inst == 0 { return -1; }
    let i = unsafe { &mut *(inst as *mut bp::Inst) };
    let qs: &[f32] = if q.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(q, n as usize) } };
    let ss: &[i32] = if qstr.is_null() { &[] } else { unsafe { std::slice::from_raw_parts(qstr, n as usize) } };
    let fired = i.tick(qs, ss, dt);
    if !out.is_null() {
        for (k, f) in fired.iter().take(max as usize).enumerate() {
            unsafe { *out.add(k) = *f as i32 };
        }
    }
    fired.len() as i32
}
