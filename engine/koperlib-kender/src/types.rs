// kender geometry types — flat f32 for Panama MemorySegment, no boxing ever
#![allow(dead_code)] // face constants + FACE_NORMALS used by K1 shader pipeline
// vertex layout: pos(3) uv(2) ao(1) face_id(1) block_id(1) _pad(1) = 9 floats

pub const FLOATS_PER_VERT: usize = 9;
// 6 verts per face: emit 2 triangles (v0v1v2, v0v2v3) for Vulkan TRIANGLE_LIST
// Fabric event path uses face_masks only — vertex buffer format doesn't matter for it
pub const VERTS_PER_FACE:  usize = 6;
pub const FLOATS_PER_FACE: usize = FLOATS_PER_VERT * VERTS_PER_FACE; // 54

// face indices — +X -X +Y -Y +Z -Z
pub const PX: usize = 0;
pub const NX: usize = 1;
pub const PY: usize = 2;
pub const NY: usize = 3;
pub const PZ: usize = 4;
pub const NZ: usize = 5;

// neighbor offset per face (integer block step)
pub const FACE_DIRS: [[i32; 3]; 6] = [
    [ 1, 0, 0],  // +X
    [-1, 0, 0],  // -X
    [ 0, 1, 0],  // +Y
    [ 0,-1, 0],  // -Y
    [ 0, 0, 1],  // +Z
    [ 0, 0,-1],  // -Z
];

// CCW quad corners per face, local block space (each coord ∈ {0.0, 1.0})
// order: BL, BR, TR, TL when viewed from outside the face
pub const FACE_CORNERS: [[[f32; 3]; 4]; 6] = [
    // +X  (visible from +X, normal = [1,0,0])
    [[1.,0.,1.], [1.,0.,0.], [1.,1.,0.], [1.,1.,1.]],
    // -X  (visible from -X, normal = [-1,0,0])
    [[0.,0.,0.], [0.,0.,1.], [0.,1.,1.], [0.,1.,0.]],
    // +Y  (top face, normal = [0,1,0])
    [[0.,1.,0.], [1.,1.,0.], [1.,1.,1.], [0.,1.,1.]],
    // -Y  (bottom face, normal = [0,-1,0])
    [[0.,0.,1.], [1.,0.,1.], [1.,0.,0.], [0.,0.,0.]],
    // +Z  (south face, normal = [0,0,1])
    [[0.,0.,1.], [1.,0.,1.], [1.,1.,1.], [0.,1.,1.]],
    // -Z  (north face, normal = [0,0,-1])
    [[1.,0.,0.], [0.,0.,0.], [0.,1.,0.], [1.,1.,0.]],
];

// UV per corner (BL=0,0 BR=1,0 TR=1,1 TL=0,1)
pub const QUAD_UVS: [[f32; 2]; 4] = [
    [0., 0.], [1., 0.], [1., 1.], [0., 1.]
];

// face normal vectors (for shader / future mesh shader use)
pub const FACE_NORMALS: [[f32; 3]; 6] = [
    [ 1., 0., 0.], [-1., 0., 0.],
    [ 0., 1., 0.], [ 0.,-1., 0.],
    [ 0., 0., 1.], [ 0., 0.,-1.],
];

#[derive(Clone)]
pub struct KontraBlock {
    pub ox: i32,
    pub oy: i32,
    pub oz: i32,
    pub block_id: u32, // MC block state ID (Block.getId(state))
}

pub struct KontraGeometry {
    pub blocks:     Vec<KontraBlock>,
    pub vertex_buf: Vec<f32>, // FLOATS_PER_VERT × 4 × face_count floats
    pub face_masks: Vec<u8>,  // one byte per block, bits 0-5 = which faces are visible
    pub face_count: u32,      // total visible faces after culling
    pub dirty:      bool,
}

impl KontraGeometry {
    pub fn new() -> Self {
        Self {
            blocks:     Vec::new(),
            vertex_buf: Vec::new(),
            face_masks: Vec::new(),
            face_count: 0,
            dirty:      true,
        }
    }
}
