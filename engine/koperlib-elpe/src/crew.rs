// koper crew — tiny persistent thread pool. spawning 8 threads per phase cost more than the physics
// on a phone (1-2ms each time), so the workers stay alive and nap on a condvar between jobs

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;

struct KoperJob {
    // lifetime erased. only called while the owner is stuck inside `run`, see below
    f: *const (dyn Fn(usize) + Sync),
    parts: usize,
    next: AtomicUsize,
    left: AtomicUsize,
}
unsafe impl Send for KoperJob {}
unsafe impl Sync for KoperJob {}

struct KoperBoard {
    job: Option<Arc<KoperJob>>,
    gen: u64,
    quit: bool,
}

struct KoperShared {
    board: Mutex<KoperBoard>,
    wake: Condvar,
    done: Condvar,
    done_lock: Mutex<()>,
}

pub struct KoperCrew {
    shared: Arc<KoperShared>,
    workers: Vec<JoinHandle<()>>,
}

impl KoperCrew {
    pub fn new(threads: usize) -> Self {
        let shared = Arc::new(KoperShared {
            board: Mutex::new(KoperBoard { job: None, gen: 0, quit: false }),
            wake: Condvar::new(),
            done: Condvar::new(),
            done_lock: Mutex::new(()),
        });
        let workers = (1..threads.max(1)).map(|k| {
            let sh = shared.clone();
            std::thread::Builder::new().name(format!("elpe-crew-{k}")).spawn(move || koper_worker(sh)).unwrap()
        }).collect();
        KoperCrew { shared, workers }
    }

    pub fn size(&self) -> usize { self.workers.len() + 1 }

    // calls f(0..parts) spread over the crew + the calling thread, returns when all are done
    pub fn run(&self, parts: usize, f: &(dyn Fn(usize) + Sync)) {
        if parts == 0 { return; }
        if parts == 1 || self.workers.is_empty() {
            for k in 0..parts { f(k); }
            return;
        }
        let f_static: *const (dyn Fn(usize) + Sync) = unsafe { std::mem::transmute(f) };
        let job = Arc::new(KoperJob { f: f_static, parts, next: AtomicUsize::new(0), left: AtomicUsize::new(parts) });
        {
            let mut b = self.shared.board.lock().unwrap();
            b.job = Some(job.clone());
            b.gen = b.gen.wrapping_add(1);
        }
        self.shared.wake.notify_all();
        koper_chew(&job, &self.shared);
        // wait for the stragglers. f must outlive every call, so we cant leave before left hits 0
        let mut g = self.shared.done_lock.lock().unwrap();
        while job.left.load(Ordering::Acquire) != 0 {
            g = self.shared.done.wait(g).unwrap();
        }
        drop(g);
        self.shared.board.lock().unwrap().job = None;
    }
}

fn koper_chew(job: &KoperJob, sh: &KoperShared) {
    loop {
        let k = job.next.fetch_add(1, Ordering::Relaxed);
        if k >= job.parts { return; }
        // k < parts means left > 0 so the owner is still waiting and f is alive
        unsafe { (*job.f)(k) };
        if job.left.fetch_sub(1, Ordering::AcqRel) == 1 {
            let _g = sh.done_lock.lock().unwrap();
            sh.done.notify_all();
        }
    }
}

fn koper_worker(sh: Arc<KoperShared>) {
    let mut seen = 0u64;
    loop {
        let job = {
            let mut b = sh.board.lock().unwrap();
            while b.gen == seen && !b.quit { b = sh.wake.wait(b).unwrap(); }
            if b.quit { return; }
            seen = b.gen;
            b.job.clone()
        };
        if let Some(job) = job { koper_chew(&job, &sh); }
    }
}

impl Drop for KoperCrew {
    fn drop(&mut self) {
        self.shared.board.lock().unwrap().quit = true;
        self.shared.wake.notify_all();
        for w in self.workers.drain(..) { let _ = w.join(); }
    }
}
