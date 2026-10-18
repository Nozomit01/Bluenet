//! Android exposes IP packets; OpenRad authenticates Ethernet frames.
//! MAC bindings come only from authenticated peer channels, never guesses.
use crate::{mobile, session::readable, tunnel};
use anyhow::{bail, ensure, Result};
use std::{
    collections::{HashMap, VecDeque},
    fs::File,
    io::{Read, Write},
    net::Ipv4Addr,
    os::fd::{AsRawFd, FromRawFd},
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex, OnceLock,
    },
};

static MACS: OnceLock<Mutex<HashMap<Ipv4Addr, [u8; 6]>>> = OnceLock::new();
fn macs() -> &'static Mutex<HashMap<Ipv4Addr, [u8; 6]>> {
    MACS.get_or_init(Default::default)
}
pub fn reset_peers() {
    macs().lock().unwrap_or_else(|p| p.into_inner()).clear();
}
pub fn remember_peer(ip: Ipv4Addr, mac: [u8; 6]) {
    macs()
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .insert(ip, mac);
}
pub fn forget_peer(ip: Ipv4Addr) {
    macs().lock().unwrap_or_else(|p| p.into_inner()).remove(&ip);
}

pub const NAME: &str = "Синяя сеть";
pub struct Tap {
    file: File,
    vip: Ipv4Addr,
    pending: VecDeque<Vec<u8>>,
    buffer: [u8; 1500],
}
impl Tap {
    pub fn create(vip: Ipv4Addr, _: &[Ipv4Addr]) -> Result<Self> {
        let fd = mobile::create_tun(vip)?;
        ensure!(fd >= 0, "Android VPN permission was not granted");
        // Java detaches the descriptor; this File becomes its sole owner.
        let file = unsafe { File::from_raw_fd(fd) };
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        ensure!(
            flags >= 0 && unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } >= 0,
            "Could not configure Android VPN descriptor"
        );
        Ok(Self {
            file,
            vip,
            pending: VecDeque::new(),
            buffer: [0; 1500],
        })
    }
    pub fn create_with_helper(
        vip: Ipv4Addr,
        peers: &[Ipv4Addr],
        _: &std::path::Path,
    ) -> Result<Self> {
        Self::create(vip, peers)
    }
    pub fn create_lan_with_helper(vip: Ipv4Addr, _: &std::path::Path) -> Result<Self> {
        Self::create(vip, &[])
    }
    #[cfg_attr(
        target_os = "android",
        expect(dead_code, reason = "Android uses VpnService without the Linux helper")
    )]
    pub(crate) fn create_lan_with_helper_cancellable(
        vip: Ipv4Addr,
        _: &std::path::Path,
        stop: &AtomicBool,
    ) -> Result<Self> {
        ensure!(!stop.load(Ordering::Relaxed), "VPN setup cancelled");
        Self::create(vip, &[])
    }
    pub fn ready(&self, timeout: i32) -> Result<bool> {
        if !self.pending.is_empty() {
            Ok(true)
        } else {
            readable(self.file.as_raw_fd(), timeout)
        }
    }
    pub(crate) fn poll_fd(&self) -> Option<i32> {
        Some(self.file.as_raw_fd())
    }
    pub fn receive(&mut self) -> Result<Vec<u8>> {
        if let Some(frame) = self.pending.pop_front() {
            return Ok(frame);
        }
        let n = self.file.read(&mut self.buffer)?;
        ensure!(n > 0, "Android VPN descriptor was closed");
        let bindings = macs().lock().unwrap_or_else(|p| p.into_inner());
        // Unsupported or unresolved packets are dropped, not misrouted.
        Ok(ip_to_ethernet(&self.buffer[..n], self.vip, &bindings).unwrap_or_default())
    }
    pub fn send(&mut self, frame: &[u8]) -> Result<()> {
        if let Some(packet) = ethernet_to_ip(frame, self.vip) {
            let written = self.file.write(packet)?;
            ensure!(written == packet.len(), "Partial Android TUN write");
            return Ok(());
        }
        if let Some(reply) = arp_reply(frame, self.vip) {
            if self.pending.len() < 64 {
                self.pending.push_back(reply);
            }
        }
        Ok(())
    }
}
pub fn helper(_: Ipv4Addr, _: u32, _: &[Ipv4Addr]) -> Result<()> {
    bail!("Android uses VpnService")
}
pub fn helper_with_lan(_: Ipv4Addr, _: u32, _: &[Ipv4Addr], _: bool) -> Result<()> {
    bail!("Android uses VpnService")
}

fn ip_to_ethernet(
    packet: &[u8],
    vip: Ipv4Addr,
    bindings: &HashMap<Ipv4Addr, [u8; 6]>,
) -> Option<Vec<u8>> {
    if packet.len() < 20 || packet.len() > 1500 || packet[0] >> 4 != 4 {
        return None;
    }
    let h = (packet[0] & 15) as usize * 4;
    let n = u16::from_be_bytes([packet[2], packet[3]]) as usize;
    if h < 20 || n < h || n != packet.len() || tunnel::checksum(&packet[..h]) != 0 {
        return None;
    }
    let src = Ipv4Addr::from(<[u8; 4]>::try_from(&packet[12..16]).ok()?);
    let dst = Ipv4Addr::from(<[u8; 4]>::try_from(&packet[16..20]).ok()?);
    if src != vip {
        return None;
    }
    let destination = if dst == Ipv4Addr::BROADCAST || dst == Ipv4Addr::new(26, 255, 255, 255) {
        [255; 6]
    } else if dst.is_multicast() {
        let d = dst.octets();
        [1, 0, 0x5e, d[1] & 0x7f, d[2], d[3]]
    } else {
        *bindings.get(&dst)?
    };
    let mut out = Vec::with_capacity(n + 14);
    out.extend_from_slice(&destination);
    out.extend_from_slice(&tunnel::mac(vip));
    out.extend_from_slice(&[8, 0]);
    out.extend_from_slice(packet);
    Some(out)
}
fn ethernet_to_ip(frame: &[u8], vip: Ipv4Addr) -> Option<&[u8]> {
    let (_, dst) = tunnel::ipv4_endpoints(frame)?;
    if dst != vip
        && !dst.is_multicast()
        && dst != Ipv4Addr::BROADCAST
        && dst != Ipv4Addr::new(26, 255, 255, 255)
    {
        return None;
    }
    let n = u16::from_be_bytes([frame[16], frame[17]]) as usize;
    Some(&frame[14..14 + n])
}
fn arp_reply(frame: &[u8], vip: Ipv4Addr) -> Option<Vec<u8>> {
    let (source, target) = tunnel::arp_endpoints(frame)?;
    if frame[21] != 1 || target != vip || frame[22..28] != frame[6..12] {
        return None;
    }
    let own = tunnel::mac(vip);
    Some(
        [
            &frame[6..12],
            &own,
            &[8, 6, 0, 1, 8, 0, 6, 4, 0, 2],
            &own,
            &vip.octets(),
            &frame[6..12],
            &source.octets(),
        ]
        .concat(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn malformed_packet_corpus_does_not_panic_or_escape_length_validation() {
        let own = Ipv4Addr::new(26, 1, 2, 3);
        let peer = Ipv4Addr::new(26, 4, 5, 6);
        let bindings = HashMap::from([(peer, [2, 1, 2, 3, 4, 5])]);
        let mut seed = 0x12345678u32;
        for n in 0..4096 {
            let mut data = vec![0u8; n % 1600];
            for b in &mut data {
                seed ^= seed << 13;
                seed ^= seed >> 17;
                seed ^= seed << 5;
                *b = seed as u8;
            }
            let _ = ip_to_ethernet(&data, own, &bindings);
            let _ = ethernet_to_ip(&data, own);
            let _ = arp_reply(&data, own);
            let _ = crate::protocol::records(&data);
            let _ = tunnel::decode(&data);
        }
        // Truncate valid frames at every position and mutate IHL/total length.
        let valid = ip_to_ethernet(&ip(own, peer), own, &bindings).unwrap();
        for n in 0..valid.len() {
            let _ = ethernet_to_ip(&valid[..n], peer);
        }
        for ihl in 0..16 {
            let mut frame = valid.clone();
            frame[14] = 0x40 | ihl;
            frame[16..18].copy_from_slice(&65535u16.to_be_bytes());
            assert!(ethernet_to_ip(&frame, peer).is_none());
        }
    }
    fn ip(src: Ipv4Addr, dst: Ipv4Addr) -> Vec<u8> {
        let mut p = vec![0u8; 28];
        p[0] = 0x45;
        p[2..4].copy_from_slice(&28u16.to_be_bytes());
        p[8] = 64;
        p[9] = 17;
        p[12..16].copy_from_slice(&src.octets());
        p[16..20].copy_from_slice(&dst.octets());
        let c = tunnel::checksum(&p[..20]);
        p[10..12].copy_from_slice(&c.to_be_bytes());
        p
    }
    #[test]
    fn android_packets_use_the_authenticated_windows_peer_mac() {
        let a = Ipv4Addr::new(26, 1, 2, 3);
        let b = Ipv4Addr::new(26, 9, 8, 7);
        let mac = [2, 0x50, 1, 2, 3, 4];
        let bindings = HashMap::from([(b, mac)]);
        let frame = ip_to_ethernet(&ip(a, b), a, &bindings).unwrap();
        assert_eq!(&frame[..6], &mac);
        assert!(tunnel::deliver_to(&frame, a, tunnel::mac(a), b, mac));
        assert_eq!(ethernet_to_ip(&frame, b).unwrap(), &ip(a, b));
    }
    #[test]
    fn unknown_peers_and_spoofed_ip_sources_are_not_forwarded() {
        let a = Ipv4Addr::new(26, 1, 2, 3);
        let b = Ipv4Addr::new(26, 9, 8, 7);
        assert!(ip_to_ethernet(&ip(a, b), a, &HashMap::new()).is_none());
        assert!(ip_to_ethernet(&ip(b, a), a, &HashMap::from([(a, [2, 0, 0, 0, 0, 1])])).is_none());
    }
    #[test]
    fn broadcast_and_multicast_preserve_ip_bytes() {
        let a = Ipv4Addr::new(26, 1, 2, 3);
        for dst in [
            Ipv4Addr::BROADCAST,
            Ipv4Addr::new(26, 255, 255, 255),
            Ipv4Addr::new(224, 0, 0, 251),
        ] {
            let packet = ip(a, dst);
            let frame = ip_to_ethernet(&packet, a, &HashMap::new()).unwrap();
            assert_eq!(ethernet_to_ip(&frame, a).unwrap(), packet);
        }
    }
    #[test]
    fn arp_for_android_is_replied_to_without_exposing_arp_to_tun() {
        let a = Ipv4Addr::new(26, 1, 2, 3);
        let b = Ipv4Addr::new(26, 9, 8, 7);
        let m = [2, 0x50, 1, 2, 3, 4];
        let request = [
            &[255; 6][..],
            &m,
            &[8, 6, 0, 1, 8, 0, 6, 4, 0, 1],
            &m,
            &b.octets(),
            &[0; 6],
            &a.octets(),
        ]
        .concat();
        let reply = arp_reply(&request, a).unwrap();
        assert_eq!(tunnel::arp_endpoints(&reply), Some((a, b)));
        assert_eq!(&reply[..6], &m);
    }
    #[test]
    fn malformed_ipv4_and_nonlocal_inbound_packets_are_rejected() {
        let a = Ipv4Addr::new(26, 1, 2, 3);
        let b = Ipv4Addr::new(26, 9, 8, 7);
        let bindings = HashMap::from([(b, [2, 0, 0, 0, 0, 1])]);
        let mut p = ip(a, b);
        p[0] = 0x4f;
        assert!(ip_to_ethernet(&p, a, &bindings).is_none());
        let frame = ip_to_ethernet(&ip(a, b), a, &bindings).unwrap();
        assert!(ethernet_to_ip(&frame, a).is_none());
    }
}
