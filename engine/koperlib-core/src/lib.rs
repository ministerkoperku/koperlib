//! Native half of koperlib-core: base ABI plus the shared Kender backend.
//!
//! Scripting, animation and physics deliberately live in different cdylibs so installing one
//! KoperLib feature no longer drags every native subsystem into memory.

pub use koperlib_kender::*;

#[repr(C)]
pub enum KoperResult {
    Ok = 0,
    Invalid = 2,
}

#[no_mangle]
pub extern "C" fn koper_init() -> i32 {
    KoperResult::Ok as i32
}

#[no_mangle]
pub extern "C" fn koper_version(buf: *mut u8, buf_len: usize) -> i32 {
    if buf.is_null() || buf_len < 8 {
        return KoperResult::Invalid as i32;
    }
    let version = b"0.1.0\0";
    unsafe { std::ptr::copy_nonoverlapping(version.as_ptr(), buf, version.len().min(buf_len)); }
    KoperResult::Ok as i32
}
