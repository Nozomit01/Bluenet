package ru.bluenet.vpn;

import android.os.Build;
import org.json.*;
import java.util.Iterator;

/** Construct from an allowlist; never copy snapshots, peer IDs, names, IPs or raw errors. */
final class DiagnosticReport {
    static String create(JSONObject state, int versionCode, String versionName) throws JSONException {
        JSONObject report=new JSONObject().put("format","bluenet-diagnostics-v1")
            .put("version",versionName).put("version_code",versionCode).put("android_api",Build.VERSION.SDK_INT);
        String phase=state.optString("phase");
        if(!phase.matches("idle|connecting|connected|stopping|error"))phase="unknown";
        report.put("phase",phase).put("has_error",state.has("error")&&!state.isNull("error"));
        JSONObject snapshot=state.optJSONObject("snapshot");
        if(snapshot!=null){
            JSONArray networks=snapshot.optJSONArray("networks");report.put("network_count",networks==null?0:networks.length());
            JSONObject traffic=snapshot.optJSONObject("traffic");
            if(traffic!=null)report.put("received_bytes",Math.max(0,traffic.optLong("received_bytes"))).put("sent_bytes",Math.max(0,traffic.optLong("sent_bytes")));
            JSONObject peers=snapshot.optJSONObject("peers");int tcp=0,udp=0,relay=0,connected=0;
            if(peers!=null){Iterator<String> keys=peers.keys();while(keys.hasNext()){
                JSONObject peer=peers.optJSONObject(keys.next());if(peer==null||!peer.optString("status").equals("Connected"))continue;
                connected++;switch(peer.optString("transport")){case "DirectTcp":tcp++;break;case "DirectUdp":udp++;break;case "Relay":relay++;break;default:break;}
            }}
            report.put("connected_peer_count",connected).put("direct_tcp_count",tcp).put("direct_udp_count",udp).put("relay_count",relay);
        }
        report.put("quality",QualityMonitor.summary());
        return report.toString(2);
    }
}
