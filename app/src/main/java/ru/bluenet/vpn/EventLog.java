package ru.bluenet.vpn;
import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

/** Bounded app-private log. Only time and allowlisted categories, never raw errors or identifiers. */
final class EventLog {
 private static final Object lock=new Object();
 private static final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"bluenet-event-log");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardPolicy());
 private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"events.json"));}
 static JSONArray read(Context c){synchronized(lock){try(InputStream in=file(c).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
  byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>32768)return new JSONArray();out.write(b,0,n);}JSONArray input=new JSONArray(out.toString("UTF-8")),safe=new JSONArray();
  for(int i=Math.max(0,input.length()-128);i<input.length();i++){JSONObject entry=input.optJSONObject(i);if(entry!=null){String code=entry.optString("code");if(allowed(code))safe.put(new JSONObject().put("time",entry.optLong("time")).put("code",code));}}return safe;
 }catch(Exception ignored){return new JSONArray();}}}
 private static boolean allowed(String s){return s.matches("profile|permission|auth|timeout|queue|network|engine|vless_profile|vless_engine|vless_network|connected|disconnected|reconnecting|subscription_failed|server_switched|tool_failed");}
 static void add(Context c,String code){if(!allowed(code))return;Context app=c.getApplicationContext();writer.execute(()->{synchronized(lock){try{
  JSONArray old=read(app),list=new JSONArray();for(int i=Math.max(0,old.length()-127);i<old.length();i++)list.put(old.get(i));list.put(new JSONObject().put("time",System.currentTimeMillis()).put("code",code));save(app,list);
 }catch(Exception ignored){}}});}
 static void clear(Context c){Context app=c.getApplicationContext();writer.execute(()->{synchronized(lock){try{save(app,new JSONArray());}catch(Exception ignored){}}});}
 private static void save(Context c,JSONArray value)throws Exception{AtomicFile f=file(c);FileOutputStream out=null;try{out=f.startWrite();out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
}
