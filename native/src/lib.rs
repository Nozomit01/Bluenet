//! Android host for the independently implemented OpenRad protocol engine.
mod android_tap;
#[path = "../upstream-src/config.rs"]
mod config;
#[path = "../upstream-src/crypto.rs"]
pub mod crypto;
#[path = "../upstream-src/diagnostics.rs"]
pub mod diagnostics;
#[path = "../upstream-src/early_log.rs"]
pub mod early_log;
#[path = "../upstream-src/file_io.rs"]
pub mod file_io;
#[path = "../upstream-src/incoming.rs"]
pub mod incoming;
mod mobile;
#[path = "../upstream-src/network.rs"]
pub mod network;
#[path = "../upstream-src/output.rs"]
pub mod output;
#[path = "../upstream-src/peer.rs"]
pub mod peer;
#[path = "../upstream-src/protocol.rs"]
pub mod protocol;
#[path = "../upstream-src/runtime.rs"]
pub mod runtime;
#[path = "../upstream-src/scheduling.rs"]
mod scheduling;
#[path = "../upstream-src/session.rs"]
pub mod session;
#[path = "../upstream-src/tap.rs"]
pub mod tap;
#[path = "../upstream-src/tunnel.rs"]
pub mod tunnel;
#[path = "../upstream-src/udp.rs"]
pub mod udp;
#[path = "../upstream-src/wake.rs"]
mod wake;
pub use config::{DEFAULT_BOOTSTRAP_HOST, SERVER_MODULUS};
