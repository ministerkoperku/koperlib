// kender GPU buffer — HOST_VISIBLE+COHERENT for K1 (staging, no device-local copy)
// good enough for kontraktion mesh data: update once per dirty cycle, draw many times
// K2: switch to DEVICE_LOCAL with a staging buffer for performance

#[cfg(feature = "vk")]
use ash::{vk, Device};

#[cfg(feature = "vk")]
static MEM_TIER_LOGGED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

#[cfg(feature = "vk")]
pub struct GpuBuf {
    pub buf:  vk::Buffer,
    pub mem:  vk::DeviceMemory,
    pub size: vk::DeviceSize,
    mapped: usize,
}

#[cfg(feature = "vk")]
impl GpuBuf {
    pub unsafe fn new(
        dev:       &Device,
        mem_props: &vk::PhysicalDeviceMemoryProperties,
        size:      u64,
        usage:     vk::BufferUsageFlags,
    ) -> Result<Self, vk::Result> {
        let buf = dev.create_buffer(
            &vk::BufferCreateInfo::default()
                .size(size)
                .usage(usage)
                .sharing_mode(vk::SharingMode::EXCLUSIVE),
            None,
        )?;
        let reqs = dev.get_buffer_memory_requirements(buf);
        // BAR first (DEVICE_LOCAL + mappable, rebar heap) — gpu fetches these every frame and
        // over-pcie reads are exactly the kind of tail latency that eats 1% lows. sysram fallback.
        let want = vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
        let mut mem = Err(vk::Result::ERROR_OUT_OF_DEVICE_MEMORY);
        for (flags, bar) in [(want | vk::MemoryPropertyFlags::DEVICE_LOCAL, true), (want, false)] {
            let Some(idx) = find_mem_type(mem_props, reqs.memory_type_bits, flags) else { continue };
            mem = dev.allocate_memory(
                &vk::MemoryAllocateInfo::default()
                    .allocation_size(reqs.size)
                    .memory_type_index(idx),
                None,
            );
            if mem.is_ok() {
                if !MEM_TIER_LOGGED.swap(true, std::sync::atomic::Ordering::Relaxed) {
                    crate::klog(&format!("[Kender] buf memory tier: {}", if bar { "BAR (device-local mappable)" } else { "sysram" }));
                }
                break;
            }
        }
        let mem = match mem {
            Ok(m) => m,
            Err(e) => { dev.destroy_buffer(buf, None); return Err(e); }
        };
        dev.bind_buffer_memory(buf, mem, 0)?;
        let mapped = match dev.map_memory(mem, 0, reqs.size, vk::MemoryMapFlags::empty()) {
            Ok(ptr) => ptr as usize,
            Err(err) => {
                dev.destroy_buffer(buf, None);
                dev.free_memory(mem, None);
                return Err(err);
            }
        };
        Ok(Self { buf, mem, size, mapped })
    }

    pub unsafe fn upload(&self, _dev: &Device, data: &[u8]) {
        std::ptr::copy_nonoverlapping(data.as_ptr(), self.mapped as *mut u8, data.len().min(self.size as usize));
    }

    pub unsafe fn destroy(&self, dev: &Device) {
        dev.unmap_memory(self.mem);
        dev.destroy_buffer(self.buf, None);
        dev.free_memory(self.mem, None);
    }
}

#[cfg(feature = "vk")]
pub fn find_mem_type(
    props:  &vk::PhysicalDeviceMemoryProperties,
    filter: u32,
    flags:  vk::MemoryPropertyFlags,
) -> Option<u32> {
    (0..props.memory_type_count).find(|&i| {
        filter & (1 << i) != 0 &&
        props.memory_types[i as usize].property_flags.contains(flags)
    })
}
