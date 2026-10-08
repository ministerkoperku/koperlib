// bedrock addon scripts on quickjs. one vm per addon, same as the lua side does it per pack.
// everything the scripts can touch goes through __kq(json) -> java and back, the @minecraft/*
// modules are our own js on top of that (js/ folder), nothing in here knows what a creeper is
use rquickjs::loader::{Loader, Resolver, ImportAttributes};
use rquickjs::{CatchResultExt, CaughtError, Context, Ctx, Function, Module, Runtime, Value};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Instant;

// hand written api + the generated tail with every other name the real module exports (tools/gen_api.mjs)
const MC_SERVER: &str = concat!(include_str!("../js/server.js"), "\n", include_str!("../js/server_api.js"));
const MC_SERVER_UI: &str = concat!(include_str!("../js/server_ui.js"), "\n", include_str!("../js/server_ui_api.js"));
const MC_COMMON: &str = include_str!("../js/common.js");
const MC_STUBS: &str = include_str!("../js/stubs.js");
const PRELUDE: &str = include_str!("../js/prelude.js");

// (request, request_len, out, out_cap) -> written. written > cap means java kept the answer
// and wants a second call with request_len = -1 to hand it over
type QueryFn = extern "C" fn(*const u8, i32, *mut u8, i32) -> i32;

pub struct KoperJsVm {
    rt: Runtime,
    ctx: Context,
    deadline: Arc<AtomicU64>,
    epoch: Instant,
    budget_ms: u64,
    query: Arc<AtomicUsize>,
    last: Vec<u8>,
    // rejections nobody handled yet, keyed by the promise. a .catch() added a moment later takes it back
    // out, what is still here once the job queue is empty gets logged
    odrzucone: Arc<std::sync::Mutex<Vec<(usize, String)>>>,
}

fn builtin(name: &str) -> Option<&'static str> {
    match name {
        "@minecraft/server" => Some(MC_SERVER),
        "@minecraft/server-ui" => Some(MC_SERVER_UI),
        "@minecraft/common" => Some(MC_COMMON),
        "@minecraft/server-admin" | "@minecraft/server-gametest" | "@minecraft/server-net"
        | "@minecraft/debug-utilities" | "@minecraft/server-editor" => Some(MC_STUBS),
        _ => None,
    }
}

struct KoperResolver {
    root: PathBuf,
}

fn squash(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for part in path.components() {
        match part {
            Component::ParentDir => { out.pop(); }
            Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

impl Resolver for KoperResolver {
    fn resolve<'js>(&mut self, _ctx: &Ctx<'js>, base: &str, name: &str,
                    _attr: Option<ImportAttributes<'js>>) -> rquickjs::Result<String> {
        if builtin(name).is_some() {
            return Ok(name.to_string());
        }
        let from = if name.starts_with('.') {
            Path::new(base).parent().map(|p| p.join(name)).unwrap_or_else(|| PathBuf::from(name))
        } else if Path::new(name).starts_with(&self.root) {
            // the entry file java hands us is already absolute and inside the pack
            PathBuf::from(name)
        } else if name.starts_with('/') {
            // bedrock treats "/x.js" as scripts root, not the disk root
            self.root.join(name.trim_start_matches('/'))
        } else {
            self.root.join(name)
        };
        let mut file = squash(&from);
        if !file.is_file() {
            let with_js = PathBuf::from(format!("{}.js", file.display()));
            if with_js.is_file() {
                file = with_js;
            } else if file.join("index.js").is_file() {
                file = file.join("index.js");
            }
        }
        // no escaping the addon folder with ../../../ , a pack reads its own files and that is it
        if !file.starts_with(&self.root) || !file.is_file() {
            return Err(rquickjs::Error::new_resolving(base, name));
        }
        Ok(file.to_string_lossy().into_owned())
    }
}

struct KoperLoader;

impl Loader for KoperLoader {
    fn load<'js>(&mut self, ctx: &Ctx<'js>, name: &str,
                 _attr: Option<ImportAttributes<'js>>) -> rquickjs::Result<Module<'js, rquickjs::module::Declared>> {
        if let Some(src) = builtin(name) {
            return Module::declare(ctx.clone(), name, src);
        }
        let src = std::fs::read(name).map_err(|_| rquickjs::Error::new_loading(name))?;
        // json modules are a thing in some packs (import data from "./x.json" with {type:"json"})
        if name.ends_with(".json") {
            let mut wrapped = b"export default ".to_vec();
            wrapped.extend_from_slice(&src);
            wrapped.push(b';');
            return Module::declare(ctx.clone(), name, wrapped);
        }
        Module::declare(ctx.clone(), name, src)
    }
}

fn ask_java(query: &AtomicUsize, req: &str) -> Option<String> {
    let f = query.load(Ordering::Relaxed);
    if f == 0 {
        return None;
    }
    // SAFETY: java put a panama upcall stub from Arena.global() here, it never goes away
    let call: QueryFn = unsafe { std::mem::transmute(f) };
    let mut out = vec![0u8; 16 * 1024];
    let mut n = call(req.as_ptr(), req.len() as i32, out.as_mut_ptr(), out.len() as i32);
    if n < 0 {
        return None;
    }
    if n as usize > out.len() {
        out = vec![0u8; n as usize];
        n = call(std::ptr::null(), -1, out.as_mut_ptr(), out.len() as i32);
        if n < 0 || n as usize > out.len() {
            return None;
        }
    }
    out.truncate(n as usize);
    String::from_utf8(out).ok()
}

fn json_str(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

fn caught_text(err: CaughtError) -> String {
    match err {
        CaughtError::Exception(e) => {
            let msg = e.message().unwrap_or_default();
            let stack = e.stack().unwrap_or_default();
            if stack.is_empty() { msg } else { format!("{msg}\n{stack}") }
        }
        CaughtError::Value(v) => format!("thrown value: {:?}", v),
        CaughtError::Error(e) => e.to_string(),
    }
}

// what a rejection carried, the way bedrock prints it: "Error: message" and the stack
fn odrzucenie(v: &Value) -> String {
    if let Some(o) = v.as_object() {
        let get = |k: &str| o.get::<_, Option<String>>(k).ok().flatten();
        if let Some(m) = get("message") {
            let name = get("name").unwrap_or_else(|| "Error".into());
            return match get("stack") {
                Some(st) if !st.is_empty() => format!("{name}: {m}\n{st}"),
                _ => format!("{name}: {m}"),
            };
        }
    }
    if let Some(s) = v.as_string().and_then(|s| s.to_string().ok()) {
        return s;
    }
    format!("{v:?}")
}

impl KoperJsVm {
    pub fn new(scripts_root: &str) -> Option<Self> {
        let rt = Runtime::new().ok()?;
        // 64 MB is way past anything a sane addon needs, it is here so one leak can't eat the server
        // bedrock gives scripts about 250 MB before its watchdog steps in. 64 was too little for villager news
        rt.set_memory_limit(256 * 1024 * 1024);
        rt.set_max_stack_size(1024 * 1024);
        let root = squash(Path::new(scripts_root));
        rt.set_loader(KoperResolver { root }, KoperLoader);

        let epoch = Instant::now();
        let deadline = Arc::new(AtomicU64::new(0));
        let watch = deadline.clone();
        rt.set_interrupt_handler(Some(Box::new(move || {
            let at = watch.load(Ordering::Relaxed);
            at != 0 && epoch.elapsed().as_millis() as u64 >= at
        })));

        let query = Arc::new(AtomicUsize::new(0));
        // a promise rejected with nobody catching it (an async function that threw, game.start() with no
        // .catch): bedrock prints it, before this it vanished and a stuck addon said nothing at all
        let odrzucone: Arc<std::sync::Mutex<Vec<(usize, String)>>> = Arc::new(std::sync::Mutex::new(Vec::new()));
        let lista = odrzucone.clone();
        rt.set_host_promise_rejection_tracker(Some(Box::new(move |_ctx, promise, reason, handled| {
            // an object's JSValue always carries its pointer, it is only an identity key here, never followed
            let id = promise.as_object().map(|o| unsafe { o.as_raw().u.ptr } as usize).unwrap_or(0);
            let mut l = lista.lock().unwrap();
            if handled {
                l.retain(|(p, _)| *p != id);
            } else {
                l.push((id, format!("Unhandled promise rejection: {}", odrzucenie(&reason))));
            }
        })));

        let ctx = Context::full(&rt).ok()?;
        let q = query.clone();
        let ok = ctx.with(|ctx| -> rquickjs::Result<()> {
            let kq = Function::new(ctx.clone(), move |req: String| -> Option<String> {
                ask_java(&q, &req)
            })?;
            ctx.globals().set("__kq", kq)?;
            ctx.eval::<(), _>(PRELUDE)?;
            Ok(())
        });
        if let Err(e) = ok {
            eprintln!("[koper-js] prelude died: {e}");
            return None;
        }
        Some(Self { rt, ctx, deadline, epoch, budget_ms: 0, query, last: Vec::new(), odrzucone })
    }

    fn arm(&self) {
        if self.budget_ms == 0 {
            return;
        }
        let at = self.epoch.elapsed().as_millis() as u64 + self.budget_ms;
        self.deadline.store(at.max(1), Ordering::Relaxed);
    }

    fn disarm(&self) {
        self.deadline.store(0, Ordering::Relaxed);
    }

    fn complain(&self, what: &str) {
        let req = format!("{{\"op\":\"log\",\"lvl\":\"error\",\"msg\":{}}}", json_str(what));
        if ask_java(&self.query, &req).is_none() {
            eprintln!("[koper-js] {what}");
        }
    }

    // promises, awaits, system.run callbacks that resolved during a call. all of it runs now,
    // under the same deadline, so an await loop can't dodge the timeout by yielding
    fn pump(&self) {
        loop {
            match self.rt.execute_pending_job() {
                Ok(true) => continue,
                Ok(false) => break,
                Err(e) => {
                    let msg = e.0.with(|ctx| {
                        let v = ctx.catch();
                        match v.as_exception() {
                            Some(ex) => format!("{}\n{}", ex.message().unwrap_or_default(), ex.stack().unwrap_or_default()),
                            None => format!("{:?}", v),
                        }
                    });
                    self.complain(&format!("async job threw: {msg}"));
                }
            }
        }
        let zostaly: Vec<(usize, String)> = std::mem::take(&mut *self.odrzucone.lock().unwrap());
        for (_, what) in zostaly {
            self.complain(&what);
        }
    }

    pub fn load(&mut self, entry: &str) -> bool {
        self.arm();
        let res = self.ctx.with(|ctx| -> Result<(), String> {
            let promise = Module::import(&ctx, entry).catch(&ctx).map_err(caught_text)?;
            // a top level await that never settles is fine, the module just keeps going later
            match promise.finish::<Value>().catch(&ctx) {
                Ok(_) => Ok(()),
                Err(CaughtError::Error(rquickjs::Error::WouldBlock)) => Ok(()),
                Err(e) => Err(caught_text(e)),
            }
        });
        self.pump();
        self.disarm();
        match res {
            Ok(()) => true,
            Err(e) => {
                self.complain(&format!("module {entry} failed: {e}"));
                false
            }
        }
    }

    // java -> js. goes into __koperDispatch from prelude.js, whatever it returns comes back out
    pub fn call(&mut self, json: &str) -> &[u8] {
        self.arm();
        let res = self.ctx.with(|ctx| -> Result<String, String> {
            let f: Function = ctx.globals().get("__koperDispatch").map_err(|e| e.to_string())?;
            let out: Option<String> = f.call((json,)).catch(&ctx).map_err(caught_text)?;
            Ok(out.unwrap_or_default())
        });
        self.pump();
        self.disarm();
        self.last = match res {
            Ok(s) => s.into_bytes(),
            Err(e) => {
                self.complain(&format!("dispatch threw: {e}"));
                Vec::new()
            }
        };
        &self.last
    }
}

// ── c side ────────────────────────────────────────────────────────────────

fn vm<'a>(handle: i64) -> Option<&'a mut KoperJsVm> {
    if handle == 0 { None } else { Some(unsafe { &mut *(handle as *mut KoperJsVm) }) }
}

fn cstr<'a>(p: *const u8) -> Option<&'a str> {
    if p.is_null() { return None; }
    unsafe { std::ffi::CStr::from_ptr(p.cast()) }.to_str().ok()
}

fn copy_out(bytes: &[u8], out: *mut u8, cap: usize) -> i32 {
    if !out.is_null() && bytes.len() <= cap {
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len()) };
    }
    bytes.len() as i32
}

pub fn js_create(root: *const u8) -> i64 {
    let Some(root) = cstr(root) else { return 0 };
    match KoperJsVm::new(root) {
        Some(v) => Box::into_raw(Box::new(v)) as i64,
        None => 0,
    }
}

pub fn js_destroy(handle: i64) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut KoperJsVm) });
    }
}

pub fn js_set_timeout(handle: i64, ms: i64) {
    if let Some(v) = vm(handle) { v.budget_ms = ms.max(0) as u64; }
}

pub fn js_set_query(handle: i64, f: usize) {
    if let Some(v) = vm(handle) { v.query.store(f, Ordering::Relaxed); }
}

pub fn js_load(handle: i64, entry: *const u8) -> i32 {
    match (vm(handle), cstr(entry)) {
        (Some(v), Some(e)) => if v.load(e) { 0 } else { 1 },
        _ => 2,
    }
}

// returns length of the answer. bigger than cap = call js_last with a bigger buffer
// a call while this vm is already inside one: quickjs is not re-entrant, borrowing the runtime twice
// panics, and with panic=abort that is the whole game gone. -3 tells java, which logs it
static W_SRODKU: std::sync::Mutex<Vec<i64>> = std::sync::Mutex::new(Vec::new());

pub fn js_call(handle: i64, json: *const u8, out: *mut u8, cap: usize) -> i32 {
    match (vm(handle), cstr(json)) {
        (Some(v), Some(j)) => {
            {
                let mut busy = W_SRODKU.lock().unwrap();
                if busy.contains(&handle) {
                    return -3;
                }
                busy.push(handle);
            }
            let n = copy_out(v.call(j), out, cap);
            W_SRODKU.lock().unwrap().retain(|h| *h != handle);
            n
        }
        _ => -1,
    }
}

pub fn js_last(handle: i64, out: *mut u8, cap: usize) -> i32 {
    match vm(handle) {
        Some(v) => copy_out(&v.last, out, cap),
        None => -1,
    }
}

#[cfg(test)]
mod tests;

#[cfg(test)]
mod korpus;
