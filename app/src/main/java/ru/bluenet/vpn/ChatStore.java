package ru.bluenet.vpn;
import android.content.Context;
import org.json.*;
import java.util.*;
/** Private encrypted, bounded history; no message text enters diagnostic logs. */
final class ChatStore {
 private static ChatStore singleton;private final IdentityStore store;private JSONArray rows;private boolean unread,unreadable;
 static synchronized ChatStore get(Context c){if(singleton==null)singleton=new ChatStore(c.getApplicationContext());return singleton;}
 private ChatStore(Context c){store=new IdentityStore(c,"chat.enc","bluenet.chat.v1");try{String raw=store.read();rows=raw.isEmpty()?new JSONArray():new JSONArray(raw);for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x!=null&&x.optBoolean("out")&&x.optString("state").equals("pending"))x.put("state","failed");}}catch(Exception e){rows=new JSONArray();unreadable=true;} }
 synchronized JSONArray rows(){try{JSONArray visible=new JSONArray();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&!row.optBoolean("deleted"))visible.put(new JSONObject(row.toString()));}return visible;}catch(Exception e){return new JSONArray();}}
 synchronized boolean unread(){return unread;}synchronized void read(){unread=false;}
 synchronized boolean has(String peer,String id){for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x!=null&&!x.optBoolean("out")&&peer.equals(x.optString("peer"))&&id.equals(x.optString("id")))return true;}return false;}
 synchronized void add(String peer,String name,String id,String text,boolean out)throws Exception{
  if(unreadable)throw new IllegalStateException("history_unavailable");JSONObject row=new JSONObject().put("peer",peer).put("name",name).put("id",id).put("text",text).put("out",out).put("time",System.currentTimeMillis()).put("state",out?"pending":"received");rows.put(row);
  while(rows.length()>48||rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>56000)rows.remove(0);
  store.write(rows.toString());if(!out)unread=true;
 }
 synchronized void state(String peer,String id,String state){if(unreadable)return;for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x!=null&&x.optBoolean("out")&&peer.equals(x.optString("peer"))&&id.equals(x.optString("id")))try{x.put("state",state);}catch(Exception ignored){}}try{store.write(rows.toString());}catch(Exception ignored){}}
 synchronized void delete(String peer,String id,boolean out)throws Exception{if(unreadable)throw new IllegalStateException("history_unavailable");JSONArray updated=new JSONArray(rows.toString());for(int i=0;i<updated.length();i++){JSONObject row=updated.optJSONObject(i);if(row!=null&&peer.equals(row.optString("peer"))&&id.equals(row.optString("id"))&&out==row.optBoolean("out")){row.put("deleted",true);row.remove("text");}}store.write(updated.toString());rows=updated;}
 synchronized void clear()throws Exception{store.write("[]");rows=new JSONArray();unread=false;unreadable=false;}
}
