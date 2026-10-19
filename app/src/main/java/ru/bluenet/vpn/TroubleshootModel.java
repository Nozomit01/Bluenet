package ru.bluenet.vpn;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Read-only observations. Missing data never implies success or a specific failure cause. */
final class TroubleshootModel {
    enum Permission { PREPARED, CONFIRMATION_REQUIRED, UNKNOWN }
    enum PortState { OPEN, CLOSED, TIMEOUT, UNKNOWN }

    static final class Target {
        final String ip;
        final int port;
        private Target(String ip, int port) { this.ip = ip; this.port = port; }
        static Target parse(String address, String number) {
            String ip = address == null ? "" : address.trim();
            String port = number == null ? "" : number.trim();
            if (!ip.matches("26\\.(?:[0-9]{1,3}\\.){2}[0-9]{1,3}")) throw new IllegalArgumentException("address");
            String[] parts = ip.split("\\.");
            for (String part : parts) if (Integer.parseInt(part) > 255) throw new IllegalArgumentException("address");
            if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("port");
            int value = Integer.parseInt(port);
            if (value < 1 || value > 65535) throw new IllegalArgumentException("port");
            // Canonical form prevents equivalent addresses from appearing to be different peers.
            return new Target("26." + Integer.parseInt(parts[1]) + "." + Integer.parseInt(parts[2])
                    + "." + Integer.parseInt(parts[3]), value);
        }
    }

    static final class Peer {
        final String ip, name, status;
        final Set<String> networks;
        Peer(String ip, String name, String status, Set<String> networks) {
            this.ip = ip; this.name = name; this.status = status;
            this.networks = Collections.unmodifiableSet(networks);
        }
    }

    static final class Observation {
        final Permission permission;
        final String phase, vip;
        final long generation, observedAt;
        final boolean blocked, live, networksKnown, peersKnown;
        final Set<String> networks;
        final List<String> networkNames;
        final List<Peer> peers;
        Observation(Permission permission, String phase, String vip, long generation, long observedAt,
                boolean blocked, boolean networksKnown, boolean peersKnown, Set<String> networks,
                List<String> networkNames, List<Peer> peers) {
            this.permission = permission; this.phase = phase; this.vip = vip;
            this.generation = generation; this.observedAt = observedAt; this.blocked = blocked;
            live = phase.equals("connected"); this.networksKnown = networksKnown;
            this.peersKnown = peersKnown;
            this.networks = Collections.unmodifiableSet(networks);
            this.networkNames = Collections.unmodifiableList(networkNames);
            this.peers = Collections.unmodifiableList(peers);
        }
        int connectedPeers() {
            int result = 0;
            for (Peer peer : peers) if (peer.status.equals("Connected")) result++;
            return result;
        }
    }

    static Observation observe(JSONObject state, Permission permission, boolean blocked, long observedAt) {
        String phase = state == null ? "unknown" : state.optString("phase", "unknown");
        if (!phase.matches("idle|connecting|connected|stopping|error")) phase = "unknown";
        boolean live = phase.equals("connected");
        JSONObject snapshot = state == null ? null : state.optJSONObject("snapshot");
        JSONArray own = snapshot == null ? null : snapshot.optJSONArray("networks");
        JSONObject directory = snapshot == null ? null : snapshot.optJSONObject("peers");
        Set<String> networks = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        List<Peer> peers = new ArrayList<>();
        boolean networksKnown = live && own != null && own.length() <= 200;
        if (networksKnown) for (int i = 0; i < own.length(); i++) {
            JSONObject network = own.optJSONObject(i);
            String id = network == null ? "" : network.optString("network_id");
            if (id.isEmpty()) { networksKnown = false; break; }
            if (networks.add(id)) names.add(clean(network.optString("name"), 160));
        }
        boolean peersKnown = live && networksKnown && directory != null && directory.length() <= 200;
        if (peersKnown) {
            Iterator<String> keys = directory.keys();
            while (keys.hasNext()) {
                JSONObject view = directory.optJSONObject(keys.next());
                JSONObject peer = view == null ? null : view.optJSONObject("peer");
                JSONArray ids = peer == null ? null : peer.optJSONArray("network_ids");
                if (ids == null || ids.length() > 200) { peersKnown = false; break; }
                Set<String> common = new TreeSet<>();
                for (int i = 0; i < ids.length(); i++) if (networks.contains(ids.optString(i))) common.add(ids.optString(i));
                if (!common.isEmpty()) {
                    String ip = peer.optString("vip");
                    try { ip = Target.parse(ip, "1").ip; } catch (IllegalArgumentException invalid) { peersKnown = false; break; }
                    peers.add(new Peer(ip, clean(peer.optString("name"), 160), view.optString("status"), common));
                }
            }
        }
        if (!networksKnown) { networks.clear(); names.clear(); }
        if (!peersKnown) peers.clear();
        return new Observation(permission, phase, snapshot == null ? "" : snapshot.optString("vip"),
                state == null ? -1 : state.optLong("generation", -1), observedAt, blocked,
                networksKnown, peersKnown, networks, names, peers);
    }

    static boolean sameProbeContext(Observation before, Observation after, Target target) {
        if (before == null || after == null || !before.live || !after.live || before.blocked || after.blocked
                || before.permission != after.permission || before.generation < 0 || before.generation != after.generation
                || before.vip.isEmpty() || !before.vip.equals(after.vip)
                || !before.networksKnown || !after.networksKnown || !before.peersKnown || !after.peersKnown
                || !before.networks.equals(after.networks)) return false;
        return peerSignature(before, target.ip).equals(peerSignature(after, target.ip));
    }

    private static String peerSignature(Observation observation, String ip) {
        Set<String> matches = new TreeSet<>();
        for (Peer peer : observation.peers) if (peer.ip.equals(ip)) matches.add(peer.status + "/" + peer.networks);
        return matches.toString();
    }

    static PortState portState(String result) {
        if ("open".equals(result)) return PortState.OPEN;
        if ("closed".equals(result)) return PortState.CLOSED;
        if ("timeout".equals(result)) return PortState.TIMEOUT;
        return PortState.UNKNOWN;
    }

    private static String clean(String value, int limit) {
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).limit(limit).forEach(result::appendCodePoint);
        return result.toString();
    }

    private TroubleshootModel() {}
}
