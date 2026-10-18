package ru.bluenet.vpn;
import android.net.Uri;
import org.json.*;
import java.util.UUID;

/** Accept a single VLESS share URI, not arbitrary executable Xray configuration. */
final class VlessProfile {
    static JSONObject outbound(String link) throws Exception {
        if(link==null||link.length()>32768||!link.startsWith("vless://")||link.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
        Uri u=Uri.parse(link);String id=u.getUserInfo(),host=u.getHost();int port=u.getPort();
        if(id==null||!id.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException();UUID.fromString(id);
        if(host==null||host.isEmpty()||host.length()>253||port<1||port>65535)throw new IllegalArgumentException();
        String encryption=q(u,"encryption","none"),flow=q(u,"flow",""),type=q(u,"type","tcp"),security=q(u,"security","none");
        if(!encryption.equals("none")||(!flow.isEmpty()&&!flow.equals("xtls-rprx-vision"))||!security.matches("none|tls|reality"))throw new IllegalArgumentException();
        if(q(u,"allowInsecure","0").equals("1")||q(u,"allowInsecure","false").equals("true"))throw new IllegalArgumentException();
        if(type.equals("raw"))type="tcp";
        if(!type.matches("tcp|ws|grpc|xhttp|splithttp"))throw new IllegalArgumentException();
        JSONObject stream=new JSONObject().put("network",type).put("security",security);
        String sni=q(u,"sni",q(u,"peer",host)),fp=q(u,"fp","chrome");
        if(security.equals("tls")){
            JSONObject tls=new JSONObject().put("serverName",sni).put("fingerprint",fp).put("allowInsecure",false);
            String alpn=q(u,"alpn","");if(!alpn.isEmpty())tls.put("alpn",new JSONArray(java.util.Arrays.asList(alpn.split(","))));stream.put("tlsSettings",tls);
        }else if(security.equals("reality")){
            String pbk=q(u,"pbk",""),sid=q(u,"sid","");if(pbk.isEmpty()||!sid.matches("[0-9a-fA-F]{0,16}")||sid.length()%2!=0)throw new IllegalArgumentException();
            stream.put("realitySettings",new JSONObject().put("serverName",sni).put("fingerprint",fp).put("publicKey",pbk).put("shortId",sid).put("spiderX",q(u,"spx","/")));
        }
        if(type.equals("ws"))stream.put("wsSettings",new JSONObject().put("path",q(u,"path","/")).put("headers",new JSONObject().put("Host",q(u,"host",sni))));
        if(type.equals("grpc"))stream.put("grpcSettings",new JSONObject().put("serviceName",q(u,"serviceName","")).put("multiMode",q(u,"mode","").equals("multi")));
        if(type.equals("xhttp")||type.equals("splithttp"))stream.put("xhttpSettings",new JSONObject().put("path",q(u,"path","/")).put("host",q(u,"host",sni)).put("mode",q(u,"mode","auto")));
        if(type.equals("tcp")&&!q(u,"headerType","none").equals("none"))throw new IllegalArgumentException();
        JSONObject user=new JSONObject().put("id",id).put("encryption","none");if(!flow.isEmpty())user.put("flow",flow);
        return new JSONObject().put("tag","vless").put("protocol","vless").put("settings",new JSONObject().put("vnext",new JSONArray().put(new JSONObject().put("address",host).put("port",port).put("users",new JSONArray().put(user))))).put("streamSettings",stream);
    }
    private static String q(Uri u,String name,String fallback){String value=u.getQueryParameter(name);if(value==null||value.isEmpty())return fallback;if(value.length()>2048||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();return value;}
    static JSONObject configuration(String link,int port,String user,String pass)throws Exception{
        JSONObject inbound=new JSONObject().put("listen","127.0.0.1").put("port",port).put("protocol","socks").put("settings",new JSONObject().put("auth","password").put("udp",true).put("accounts",new JSONArray().put(new JSONObject().put("user",user).put("pass",pass))));
        return new JSONObject().put("log",new JSONObject().put("loglevel","info")).put("inbounds",new JSONArray().put(inbound)).put("outbounds",new JSONArray().put(outbound(link)));
    }
    static JSONObject directConfiguration(int port,String user,String pass)throws Exception{
        JSONObject config=configuration("vless://00000000-0000-4000-8000-000000000001@127.0.0.1:1?security=none",port,user,pass);
        return config.put("outbounds",new JSONArray().put(new JSONObject().put("protocol","freedom").put("tag","direct").put("settings",new JSONObject())));
    }
}
