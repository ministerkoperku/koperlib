// cargo run --release --example elpe_bench -- 1000000
use koperlib_elpe_engine::world::KoperElpeWorld;
use koperlib_elpe_engine::terrain::pack_section;

fn main() {
    let n: usize = std::env::args().nth(1).and_then(|s| s.parse().ok()).unwrap_or(1_000_000);
    let side = (n as f64).sqrt().ceil() as i32;
    let mut w = KoperElpeWorld::new(n, 0.5);
    if let Ok(t) = std::env::var("ELPE_THREADS") { w.cfg.threads = t.parse().unwrap(); }
    // flat voxel floor at y=0 covering the whole field
    let secs = side / 16 + 2;
    let mut bits = Box::new([0u64; 64]);
    for i in 0..256 { bits[i >> 6] |= 1 << (i & 63); }
    for sx in -1..secs { for sz in -1..secs {
        w.set_section(pack_section(sx, 0, sz), Some(bits.clone()));
        for sy in 1..4 { w.set_section(pack_section(sx, sy, sz), None); }
    }}
    let t = std::time::Instant::now();
    let mut k = 0;
    'o: for x in 0..side { for z in 0..side {
        if k == n { break 'o; }
        // jitter so they dont stack perfectly
        let j = ((k * 7919) % 100) as f32 * 0.001;
        if std::env::var("ELPE_ASLEEP").is_ok() {
            w.spawn_asleep([x as f32 + 0.5 + j, 1.3, z as f32 + 0.5 - j], 0.3, 1.0, 0);
        } else {
            w.spawn([x as f32 + 0.5 + j, 1.3 + (k % 3) as f32 * 0.9, z as f32 + 0.5 - j], 0.3, 1.0, 0);
        }
        k += 1;
    }}
    println!("spawned {} in {:?}, threads {}", n, t.elapsed(), w.cfg.threads);
    let mut idle = 0u128;
    for _ in 0..20 { w.step(0.05, 2); idle += w.stats.step_us as u128; }
    println!("idle tick with {} awake: {:.3} ms", w.stats.awake, idle as f64 / 20000.0);
    for tick in 0..200 {
        w.step(0.05, 2);
        if tick % 10 == 0 || w.awake.is_empty() {
            let s = w.stats;
            println!("tick {:3}  awake {:8}  asleep {:8}  step {:7.2} ms", tick, s.awake, s.asleep, s.step_us as f64 / 1000.0);
        }
        if w.awake.is_empty() { break; }
    }
    // the realistic server case: 10k things moving inside a sleeping world of n
    let ids: Vec<u32> = (0..10_000u32).map(|i| i * (n as u32 / 10_000).max(1)).collect();
    for &i in &ids { w.add_velocity(i, [3.0, 12.0, 0.0]); }
    let mut total = 0u128;
    for _ in 0..20 { w.step(0.05, 2); total += w.stats.step_us as u128; }
    println!("10k awake in a world of {}: avg step {:.3} ms (awake now {})", n, total as f64 / 20000.0, w.stats.awake);
    for want in [1_000usize, 50_000, 100_000, 250_000] {
        while w.stats.awake > 0 { w.step(0.05, 2); }
        let step = (n / want).max(1) as u32;
        // a clustered chunk of ids (like a collapsing building) instead of scattered
        for i in 0..want as u32 { w.add_velocity(i + 100_000, [0.0, 8.0, 0.0]); }
        let mut total = 0u128;
        for _ in 0..10 { w.step(0.05, 2); total += w.stats.step_us as u128; }
        let _ = step;
        println!("{:7} clustered awake: avg step {:.3} ms", want, total as f64 / 10000.0);
    }
    let t = std::time::Instant::now();
    w.blast([side as f32 / 2.0, 1.0, side as f32 / 2.0], 30.0, 20.0);
    println!("blast query {:?}", t.elapsed());
    let mut total = 0u128;
    for _ in 0..20 { w.step(0.05, 2); total += w.stats.step_us as u128; }
    println!("after blast: avg step {:.3} ms, awake {}", total as f64 / 20000.0, w.stats.awake);
}
