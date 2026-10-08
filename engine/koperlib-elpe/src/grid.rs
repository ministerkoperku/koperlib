// koper hash grid — intrusive doubly linked buckets, so moving one point is O(1)
// and a point that sleeps costs literally nothing. no rebuild per step, ever.
// hello person reading this, if you know a faster broadphase for 10M points PLEASE HELP A SILLY LITTLE KOPERDEV

pub const NONE: u32 = u32::MAX;

// 21 bits per axis, packs a cell into one u64. ±1M cells is plenty for a mc world
#[inline(always)]
pub fn pack_cell(cx: i32, cy: i32, cz: i32) -> u64 {
    ((cx as u64 & 0x1F_FFFF) << 42) | ((cy as u64 & 0x1F_FFFF) << 21) | (cz as u64 & 0x1F_FFFF)
}

pub struct KoperGrid {
    pub cell: f32,
    pub inv_cell: f32,
    mask: u32,
    head: Vec<u32>,
    // per point stuff, indexed by point id
    next: Vec<u32>,
    prv: Vec<u32>,
    bucket: Vec<u32>,
    pub cellk: Vec<u64>,
}

impl KoperGrid {
    pub fn new(cell: f32, points: usize) -> Self {
        let buckets = (points.max(512) * 2).next_power_of_two();
        KoperGrid {
            cell,
            inv_cell: 1.0 / cell,
            mask: buckets as u32 - 1,
            head: vec![NONE; buckets],
            next: Vec::new(),
            prv: Vec::new(),
            bucket: Vec::new(),
            cellk: Vec::new(),
        }
    }

    pub fn buckets(&self) -> usize { self.head.len() }

    pub fn grow_points(&mut self, n: usize) {
        if self.next.len() < n {
            self.next.resize(n, NONE);
            self.prv.resize(n, NONE);
            self.bucket.resize(n, NONE);
            self.cellk.resize(n, u64::MAX);
        }
    }

    #[inline(always)]
    pub fn cell_of(&self, p: [f32; 3]) -> (i32, i32, i32) {
        (
            (p[0] * self.inv_cell).floor() as i32,
            (p[1] * self.inv_cell).floor() as i32,
            (p[2] * self.inv_cell).floor() as i32,
        )
    }

    #[inline(always)]
    pub fn bucket_of(&self, cx: i32, cy: i32, cz: i32) -> u32 {
        let h = (cx as u32).wrapping_mul(73856093)
            ^ (cy as u32).wrapping_mul(19349663)
            ^ (cz as u32).wrapping_mul(83492791);
        h & self.mask
    }

    #[inline(always)]
    pub fn head(&self, b: u32) -> u32 { self.head[b as usize] }
    #[inline(always)]
    pub fn next(&self, i: u32) -> u32 { self.next[i as usize] }

    pub fn insert(&mut self, i: u32, p: [f32; 3]) {
        let (cx, cy, cz) = self.cell_of(p);
        let b = self.bucket_of(cx, cy, cz);
        let iu = i as usize;
        self.cellk[iu] = pack_cell(cx, cy, cz);
        self.bucket[iu] = b;
        let h = self.head[b as usize];
        self.next[iu] = h;
        self.prv[iu] = NONE;
        if h != NONE { self.prv[h as usize] = i; }
        self.head[b as usize] = i;
    }

    pub fn remove(&mut self, i: u32) {
        let iu = i as usize;
        let b = self.bucket[iu];
        if b == NONE { return; }
        let (n, p) = (self.next[iu], self.prv[iu]);
        if p != NONE { self.next[p as usize] = n; } else { self.head[b as usize] = n; }
        if n != NONE { self.prv[n as usize] = p; }
        self.bucket[iu] = NONE;
        self.cellk[iu] = u64::MAX;
    }

    // only relinks when the point actually changed cell, which is rare for slow stuff
    #[inline]
    pub fn moved(&mut self, i: u32, p: [f32; 3]) {
        let (cx, cy, cz) = self.cell_of(p);
        if self.cellk[i as usize] == pack_cell(cx, cy, cz) { return; }
        self.remove(i);
        self.insert(i, p);
    }

    // visit every point whose cell overlaps the box. each point is visited once because
    // we only accept points whose cellk matches the cell we are walking — hash collisions filtered for free
    #[inline]
    pub fn for_box(&self, lo: [f32; 3], hi: [f32; 3], mut f: impl FnMut(u32)) {
        let (x0, y0, z0) = self.cell_of(lo);
        let (x1, y1, z1) = self.cell_of(hi);
        for cx in x0..=x1 {
            for cy in y0..=y1 {
                for cz in z0..=z1 {
                    let key = pack_cell(cx, cy, cz);
                    let mut j = self.head[self.bucket_of(cx, cy, cz) as usize];
                    while j != NONE {
                        if self.cellk[j as usize] == key { f(j); }
                        j = self.next[j as usize];
                    }
                }
            }
        }
    }

    // after the bucket table got too small. rare, called from spawn
    pub fn rehash(&mut self, points: usize, live: impl Iterator<Item = (u32, [f32; 3])>) {
        let buckets = (points.max(512) * 2).next_power_of_two();
        self.mask = buckets as u32 - 1;
        self.head = vec![NONE; buckets];
        for b in self.bucket.iter_mut() { *b = NONE; }
        for (i, p) in live { self.insert(i, p); }
    }
}
