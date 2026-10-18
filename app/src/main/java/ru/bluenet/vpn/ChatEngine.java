package ru.bluenet.vpn;
import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
final class ChatEngine implements AutoCloseable {
 interface Directory{JSONObject member(String ip);}
 private final Directory directory;
 interface Sender{void send(byte[] packet)throws Exception;}
 private final String vip;private final Sender sender;private final ChatStore store;
 private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1);
 private final Map<String,String> pending=new ConcurrentHashMap<>();private final Map<String,Long> rate=new HashMap<>();private volatile boolean closed;
 ChatEngine(Context c,String ip,Sender s){this(c,ip,s,ChatEngine::member);}
 ChatEngine(Context c,String ip,Sender s,Directory d){directory=d;vip=ip;sender=s;store=ChatStore.get(c);worker.setRemoveOnCancelPolicy(true);}
 static JSONObject peers(){try{JSONObject st=new JSONObject(NativeBridge.status());if(!st.optString("phase").equals("connected"))return new JSONObject();JSONObject snap=st.optJSONObject("snapshot");return snap==null?new JSONObject():snap.optJSONObject("peers");}catch(Exception e){return new JSONObject();}}
 static JSONObject member(String ip){try{return member(new JSONObject(NativeBridge.status()),ip);}catch(Exception e){return null;}}
 static JSONObject member(JSONObject state,String ip){
  if(!state.optString("phase").equals("connected"))return null;JSONObject snap=state.optJSONObject("snapshot");if(snap==null)return null;JSONObject peers=snap.optJSONObject("peers");JSONArray own=snap.optJSONArray("networks");if(peers==null||own==null)return null;
  Iterator<String> keys=peers.keys();while(keys.hasNext()){JSONObject view=peers.optJSONObject(keys.next()),p=view==null?null:view.optJSONObject("peer");if(p==null||!ip.equals(p.optString("vip"))||!view.optString("status").equals("Connected"))continue;JSONArray ids=p.optJSONArray("network_ids");if(ids!=null)for(int n=0;n<ids.length();n++)for(int i=0;i<own.length();i++){JSONObject network=own.optJSONObject(i);if(network!=null&&!ids.optString(n).isEmpty()&&ids.optString(n).equals(network.optString("network_id")))return p;}}return null;
 }
 static String clean(String text){if(text==null)return "";String s=text.trim();if(s.isEmpty()||s.length()>400||s.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'))throw new IllegalArgumentException("message_invalid");return s;}
 synchronized void send(String peer,String text)throws Exception{
  String body=clean(text);if(body.isEmpty()||directory.member(peer)==null||closed||pending.size()>=8)throw new IllegalStateException("chat_unavailable");String id=UUID.randomUUID().toString();
  synchronized(pending){if(pending.size()>=8)throw new IllegalStateException();pending.put(id,peer);}
  JSONObject packet=new JSONObject().put("v",1).put("id",id).put("text",body);byte[] wire;
  try{wire=ChatWire.packet(vip,peer,packet.toString());store.add(peer,directory.member(peer).optString("name",peer),id,body,true);}catch(Exception e){pending.remove(id);throw e;}
  for(int i=0;i<3;i++)worker.schedule(()->{if(!closed&&peer.equals(pending.get(id)))try{if(directory.member(peer)!=null)sender.send(wire);}catch(Exception ignored){}},i*2,TimeUnit.SECONDS);
  worker.schedule(()->{if(pending.remove(id,peer))store.state(peer,id,"failed");},7,TimeUnit.SECONDS);
 }
 boolean receive(byte[] p,int n){
  String[] data;try{data=ChatWire.parse(p,n,vip);}catch(Exception e){return false;}if(data==null)return false;
  JSONObject member=directory.member(data[0]);if(member==null)return true;
  synchronized(rate){long now=android.os.SystemClock.elapsedRealtime();Long old=rate.get(data[0]);if(old!=null&&now-old<150)return true;if(rate.size()>200)rate.clear();rate.put(data[0],now);}
  if(worker.getQueue().size()>40)return true;
  try{worker.execute(()->{try{
   if(closed||directory.member(data[0])==null)return;JSONObject json=new JSONObject(data[1]);String id=json.optString("id");if(json.optInt("v")!=1||!id.matches("[a-f0-9-]{36}"))return;
   if(json.optBoolean("ack")){if(pending.remove(id,data[0]))store.state(data[0],id,"delivered");return;}
   String text=clean(json.optString("text"));if(text.isEmpty())return;
   if(!store.has(data[0],id))store.add(data[0],member.optString("name",data[0]),id,text,false);
   sender.send(ChatWire.packet(vip,data[0],new JSONObject().put("v",1).put("id",id).put("ack",true).toString()));
  }catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}return true;
 }
 public synchronized void close(){closed=true;worker.shutdownNow();for(Map.Entry<String,String> p:pending.entrySet())store.state(p.getValue(),p.getKey(),"failed");pending.clear();}
}
