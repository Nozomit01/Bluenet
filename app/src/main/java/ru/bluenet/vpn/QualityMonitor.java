package ru.bluenet.vpn;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.*;
final class QualityMonitor {
 static final class Sample { final double milliseconds;final long at; Sample(double ms){milliseconds=ms;at=SystemClock.elapsedRealtime();} }
 private static final Map<Long,Sample> samples=new HashMap<>();private static long generation,lastPing;
 private static final java.util.ArrayDeque<Measurement> history=new java.util.ArrayDeque<>();
 private static final class Measurement{final long peer;final Sample sample;Measurement(long p,Sample s){peer=p;sample=s;}}
 static synchronized void observe(JSONObject state){long gen=state.optLong("generation");if(gen!=generation){samples.clear();history.clear();generation=gen;lastPing=0;}
  JSONObject ping=state.optJSONObject("ping");if(ping==null||ping.optLong("id")==lastPing)return;lastPing=ping.optLong("id");if(lastPing==0)return;
  if(samples.size()>=200)samples.clear();long peer=ping.optLong("peer");Sample result=new Sample(ping.isNull("rtt_ms")||!ping.has("rtt_ms")?-1:ping.optDouble("rtt_ms",-1));samples.put(peer,result);if(history.size()>=128)history.removeFirst();history.addLast(new Measurement(peer,result));
 }
 static synchronized Sample sample(long rid){Sample s=samples.get(rid);return s!=null&&SystemClock.elapsedRealtime()-s.at<=30000?s:null;}
 static int level(Sample s){if(s==null)return -1;if(s.milliseconds<0)return 0;return s.milliseconds<=80?4:s.milliseconds<=160?3:s.milliseconds<=350?2:1;}
 static synchronized JSONObject summary()throws org.json.JSONException{int measured=0,failed=0;double total=0;for(Sample s:samples.values())if(SystemClock.elapsedRealtime()-s.at<=30000){if(s.milliseconds<0)failed++;else{measured++;total+=s.milliseconds;}}
  JSONObject j=new JSONObject().put("successful_samples",measured).put("failed_samples",failed);if(measured>0)j.put("average_rtt_ms",total/measured);j.put("recent_probes",stats(0));return j;
 }
 static synchronized JSONObject stats(long peer)throws org.json.JSONException{
  int good=0,bad=0,pairs=0;double total=0,min=Double.MAX_VALUE,max=0,jitter=0;java.util.Map<Long,Double> previous=new java.util.HashMap<>();
  for(Measurement m:history){if(peer!=0&&m.peer!=peer||SystemClock.elapsedRealtime()-m.sample.at>600000)continue;double ms=m.sample.milliseconds;
   if(ms<0){bad++;continue;}good++;total+=ms;min=Math.min(min,ms);max=Math.max(max,ms);Double old=previous.put(m.peer,ms);if(old!=null){pairs++;jitter+=Math.abs(ms-old);}}
  JSONObject result=new JSONObject().put("replies",good).put("timeouts",bad).put("window_seconds",600);if(good+bad>0)result.put("probe_loss_percent",100.0*bad/(good+bad));if(good>0)result.put("mean_ms",total/good).put("min_ms",min).put("max_ms",max);if(pairs>0)result.put("jitter_ms",jitter/pairs);return result;
 }
 static String describe(android.content.Context c,long peer){try{JSONObject s=stats(peer);boolean en=c.getSharedPreferences("settings",0).getString("language","ru").equals("en");
  if(s.optInt("replies")+s.optInt("timeouts")==0)return en?"No probe history yet":"История проверок пока пуста";
  StringBuilder text=new StringBuilder(en?"Last 10 minutes: ":"За последние 10 минут: ");text.append(s.optInt("replies")).append(en?" replies, ":" ответов, ").append(s.optInt("timeouts")).append(en?" timeouts":" без ответа");
  text.append(String.format(java.util.Locale.ROOT,en?"\nProbe loss: %.1f%%":"\nПотери проверочных запросов: %.1f%%",s.optDouble("probe_loss_percent")));
  if(s.has("mean_ms"))text.append(String.format(java.util.Locale.ROOT,en?"\nRTT mean/min/max: %.1f / %.1f / %.1f ms":"\nRTT средний/мин/макс: %.1f / %.1f / %.1f мс",s.optDouble("mean_ms"),s.optDouble("min_ms"),s.optDouble("max_ms")));
  if(s.has("jitter_ms"))text.append(String.format(java.util.Locale.ROOT,en?"\nRTT variation: %.1f ms":"\nКолебания RTT: %.1f мс",s.optDouble("jitter_ms")));return text.toString();
 }catch(Exception error){return "—";}}
}
