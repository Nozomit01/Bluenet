package ru.bluenet.vpn;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Bounded, encrypted catalog. Subscription URLs and credentials never enter preferences/logs. */
final class ServerCatalog {
    private static IdentityStore store(Context c){return new IdentityStore(c,"servers.enc","bluenet.servers.v1");}
    static synchronized JSONObject read(Context c)throws Exception{
        String data=store(c).read();
        if(!data.isEmpty())return new JSONObject(data);
        JSONObject result=new JSONObject().put("servers",new JSONArray()).put("selected","").put("subscription","");
        String legacy=new IdentityStore(c,"vless.enc","bluenet.vless.v1").read();
        if(!legacy.isEmpty()){JSONObject s=entry(legacy);result.getJSONArray("servers").put(s);result.put("selected",s.getString("id"));}
        return result;
    }
    static synchronized void save(Context c,JSONObject data)throws Exception{
        if(data.getJSONArray("servers").length()>24)throw new IOException("catalog_limit");
        store(c).write(data.toString());
    }
    static JSONObject entry(String link)throws Exception{
        VlessProfile.outbound(link);String fragment=Uri.parse(link).getFragment();
        String name=fragment==null||fragment.trim().isEmpty()?Uri.parse(link).getHost():fragment;
        name=name.replaceAll("[\\p{Cntrl}]","");if(name.length()>80)name=name.substring(0,80);
        String key=link.split("#",2)[0];
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        String id=Base64.encodeToString(hash,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        return new JSONObject().put("id",id).put("name",name).put("link",link);
    }
    static JSONArray parse(String body)throws Exception{
        if(body==null||body.length()>262144)throw new IOException("subscription_limit");
        body=body.trim();if(body.startsWith("\ufeff"))body=body.substring(1);
        if(!body.contains("vless://")){
            try{body=new String(Base64.decode(body,Base64.DEFAULT),StandardCharsets.UTF_8);}
            catch(IllegalArgumentException error){body=new String(Base64.decode(body,Base64.URL_SAFE),StandardCharsets.UTF_8);}
        }
        JSONArray servers=new JSONArray();Set<String> seen=new HashSet<>();int bytes=0;
        String[] lines=body.split("\\r?\\n",513);if(lines.length>512)throw new IOException("subscription_limit");
        for(String line:lines){line=line.trim();if(!line.startsWith("vless://"))continue;
            JSONObject s;try{s=entry(line);}catch(Exception unsupported){continue;}
            if(!seen.add(s.getString("id")))continue;
            bytes+=s.toString().getBytes(StandardCharsets.UTF_8).length;
            if(servers.length()==24||bytes>56000)throw new IOException("subscription_limit");
            servers.put(s);
        }
        if(servers.length()==0)throw new IOException("subscription_empty");return servers;
    }
    static URL subscriptionUrl(String value)throws Exception{
        if(value.length()>4096||value.codePoints().anyMatch(Character::isISOControl))throw new IOException("subscription_url");
        URL url=new URL(value);
        if(!url.getProtocol().equals("https")||url.getHost().isEmpty()||url.getUserInfo()!=null||url.getRef()!=null)throw new IOException("subscription_url");
        return url;
    }
    static String download(String source)throws Exception{
        URL url=subscriptionUrl(source);long deadline=android.os.SystemClock.elapsedRealtime()+45000;
        for(int hop=0;hop<4;hop++){
            HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();
            connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","BlueNetwork/"+BuildConfig.VERSION_NAME);
            int seconds=(int)Math.max(1,(deadline-android.os.SystemClock.elapsedRealtime()+999)/1000);
            java.util.concurrent.ScheduledFuture<?> timeout=NetworkDeadline.after(connection::disconnect,seconds);
            try{
                if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new IOException("subscription_timeout");
                int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){
                    String location=connection.getHeaderField("Location");if(location==null)throw new IOException("subscription_http");
                    url=subscriptionUrl(new URL(url,location).toString());continue;
                }
                if(status!=200||connection.getContentLengthLong()>262144)throw new IOException("subscription_http");
                try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(android.os.SystemClock.elapsedRealtime()>=deadline)throw new IOException("subscription_timeout");if(out.size()+n>262144)throw new IOException("subscription_limit");out.write(buffer,0,n);}
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }finally{timeout.cancel(false);connection.disconnect();}
        }
        throw new IOException("subscription_redirect");
    }
    static synchronized void importData(Context c,String source,JSONArray entries)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("import_cancelled");
        JSONObject old=read(c);String selected=old.optString("selected");boolean found=false;
        for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).getString("id").equals(selected))found=true;
        JSONObject next=new JSONObject().put("servers",entries).put("selected",found?selected:entries.getJSONObject(0).getString("id"))
            .put("subscription",source).put("updated",System.currentTimeMillis());save(c,next);
    }
    static synchronized void refreshIfCurrent(Context c,String source,JSONArray entries)throws Exception{
        if(read(c).optString("subscription").equals(source)&&!Thread.currentThread().isInterrupted())importData(c,source,entries);
    }
    static synchronized void clear(Context c){
        new android.util.AtomicFile(new File(c.getFilesDir(),"servers.enc")).delete();
        new android.util.AtomicFile(new File(c.getFilesDir(),"vless.enc")).delete();
    }
    static synchronized String active(Context c)throws Exception{
        JSONObject data=read(c);JSONArray servers=data.getJSONArray("servers");
        for(int i=0;i<servers.length();i++){JSONObject s=servers.getJSONObject(i);if(s.getString("id").equals(data.optString("selected")))return s.getString("link");}
        if(servers.length()>0)return servers.getJSONObject(0).getString("link");throw new IOException("vless_profile");
    }
    static synchronized void select(Context c,String id)throws Exception{
        JSONObject data=read(c);JSONArray servers=data.getJSONArray("servers");
        for(int i=0;i<servers.length();i++)if(servers.getJSONObject(i).getString("id").equals(id)){data.put("selected",id);save(c,data);return;}
        throw new IOException("vless_profile");
    }
    static synchronized void result(Context c,String id,long elapsed)throws Exception{
        JSONObject data=read(c);JSONArray servers=data.getJSONArray("servers");
        for(int i=0;i<servers.length();i++){JSONObject s=servers.getJSONObject(i);if(s.getString("id").equals(id)){s.put("latency",elapsed).put("tested",System.currentTimeMillis());save(c,data);return;}}
    }
    static synchronized boolean next(Context c)throws Exception{
        JSONObject data=read(c);JSONArray servers=data.getJSONArray("servers");if(servers.length()<2)return false;
        int current=0;for(int i=0;i<servers.length();i++)if(servers.getJSONObject(i).getString("id").equals(data.optString("selected")))current=i;
        JSONObject best=null;long fastest=Long.MAX_VALUE;
        for(int i=0;i<servers.length();i++){JSONObject s=servers.getJSONObject(i);long ms=s.optLong("latency",-1);
            if(i!=current&&ms>=0&&System.currentTimeMillis()-s.optLong("tested")<600000&&ms<fastest){best=s;fastest=ms;}}
        data.put("selected",(best==null?servers.getJSONObject((current+1)%servers.length()):best).getString("id"));save(c,data);return true;
    }
    static synchronized void fastest(Context c)throws Exception{
        JSONObject data=read(c);JSONArray servers=data.getJSONArray("servers");long fastest=Long.MAX_VALUE;String id=data.optString("selected");
        for(int i=0;i<servers.length();i++){JSONObject s=servers.getJSONObject(i);long ms=s.optLong("latency",-1);if(ms>=0&&System.currentTimeMillis()-s.optLong("tested")<600000&&ms<fastest){fastest=ms;id=s.getString("id");}}
        data.put("selected",id);save(c,data);
    }
}
