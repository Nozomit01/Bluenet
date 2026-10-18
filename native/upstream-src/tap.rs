pub use crate::android_tap::*;
pub fn is_privileged() -> bool {
    false
}
pub fn user_id() -> u32 {
    unsafe { libc::getuid() }
}
