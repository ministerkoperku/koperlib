// runs once per vm before any addon code. console, the java pipe and the dispatch switchboard
(() => {
  const pipe = globalThis.__kq;
  const K = {
    handlers: Object.create(null),
    apiMajor: 1,
    ask(op, args) {
      const req = args ? Object.assign({ op }, args) : { op };
      const raw = pipe(JSON.stringify(req));
      if (raw === undefined || raw === null || raw === "") return undefined;
      const ans = JSON.parse(raw);
      // java throws by answering {"err": "..."} and we rethrow it here so scripts can try/catch it
      if (ans && typeof ans === "object" && !Array.isArray(ans) && typeof ans.err === "string") {
        const e = new Error(ans.err);
        e.name = ans.errName || "Error";
        throw e;
      }
      return ans;
    },
    on(type, fn) { K.handlers[type] = fn; },
  };
  Object.defineProperty(globalThis, "__koper", { value: K, enumerable: false });

  const show = (v) => {
    if (typeof v === "string") return v;
    if (v instanceof Error) return (v.name || "Error") + ": " + v.message + (v.stack ? "\n" + v.stack : "");
    try { return JSON.stringify(v); } catch (_) { return String(v); }
  };
  const say = (lvl) => (...parts) => { pipe(JSON.stringify({ op: "log", lvl, msg: parts.map(show).join(" ") })); };
  globalThis.console = { log: say("info"), info: say("info"), warn: say("warn"), error: say("error"), debug: say("debug") };

  globalThis.__koperDispatch = (json) => {
    const msg = JSON.parse(json);
    const fn = K.handlers[msg.t];
    if (!fn) return "";
    // java ran in between: whatever server.js cached about entities may be dead now
    if (K.enter) K.enter();
    const out = fn(msg);
    return out === undefined ? "" : JSON.stringify(out);
  };
})();
