package ru.bluenet.vpn;
import android.app.Instrumentation;
import android.content.Context;
import org.json.*;
import java.util.concurrent.*;
/** Synthetic packet boundaries; no real servers or identities. Run only in disposable test profile. */
final class GameCardChecks {
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static void rejects(RunnableChecked action)throws Exception{boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(rejected,"invalid card accepted");}
 interface RunnableChecked{void run()throws Exception;}
 static void run(Instrumentation instrumentation,Context context)throws Exception{
  long now=System.currentTimeMillis();GameCard card=GameCard.create("Minecraft", "26.1.2.3",25565,"Host",now,false);
  check(card.text().contains("26.1.2.3:25565"),"legacy address missing");check(GameCard.parse(card.text()).address().equals(card.address()),"roundtrip");
  check(GameCard.allowed(card.text(),"26.1.2.3")&&!GameCard.allowed(card.text(),"26.9.9.9"),"sender binding");check(GameCard.allowed("ordinary chat", "26.9.9.9"),"legacy chat rejected");
  rejects(()->GameCard.create("a\nb","26.1.2.3",1,"Host",now,false));rejects(()->GameCard.create("A","127.0.0.1",1,"Host",now,false));rejects(()->GameCard.create("A","26.01.2.3",1,"Host",now,false));rejects(()->GameCard.create("A","26.1.2.3",0,"Host",now,false));rejects(()->GameCard.create("A","26.1.2.3",65536,"Host",now,false));rejects(()->GameCard.create("A","26.1.2.3",1,"H\rX",now,false));rejects(()->GameCard.create("A","26.1.2.3",1,"H",now+600000,false));rejects(()->GameCard.create("A".repeat(49),"26.1.2.3",1,"H",now,false));
  check(GameCard.parse(card.text()+"\nextra")==null,"extra fields");check(GameCard.parse(card.text().replace("Updated: ","Updated: +"))==null,"noncanonical timestamp");check(GameCard.parse(card.text().replace("Address: ","Other: "))==null,"wrong schema");check(!GameCard.allowed(GameCard.PREFIX+"broken","26.1.2.3"),"malformed marker accepted");
  GameCard max=GameCard.create("界".repeat(48),"26.1.2.3",65535,"界".repeat(48),now,true);JSONObject maxPacket=new JSONObject().put("v",1).put("id","00000000-0000-0000-0000-000000000000").put("text",max.text());check(max.text().length()<=400,"char limit");check(ChatWire.packet("26.1.2.3","26.4.5.6",maxPacket.toString()).length<=928,"wire limit");
  ChatStore store=ChatStore.get(context);store.clear();java.util.concurrent.atomic.AtomicBoolean membership=new java.util.concurrent.atomic.AtomicBoolean(true);ChatEngine.Directory directory=ip->membership.get()&&(ip.equals("26.1.2.3")||ip.equals("26.4.5.6"))?new JSONObject():null;
  BlockingQueue<byte[]> ack=new LinkedBlockingQueue<>();ChatEngine receiver=new ChatEngine(context,"26.4.5.6",ack::offer,directory);
  try{
   String id="00000000-0000-0000-0000-000000000001";JSONObject message=new JSONObject().put("v",1).put("id",id).put("text",card.text());byte[] packet=ChatWire.packet("26.1.2.3","26.4.5.6",message.toString());receiver.receive(packet,packet.length);check(ack.poll(3,TimeUnit.SECONDS)!=null,"card not ACKed");check(store.rows().length()==1,"card missing");Thread.sleep(180);receiver.receive(packet,packet.length);check(ack.poll(3,TimeUnit.SECONDS)!=null,"replay ACK");check(store.rows().length()==1,"replay duplicated");store.delete("26.1.2.3",id,false);check(store.rows().length()==0&&store.has("26.1.2.3",id),"delete tombstone");Thread.sleep(180);receiver.receive(packet,packet.length);check(ack.poll(3,TimeUnit.SECONDS)!=null,"deleted replay ACK");check(store.rows().length()==0,"deleted replay restored");
   JSONObject forged=new JSONObject(message.toString()).put("id","00000000-0000-0000-0000-000000000002").put("text",card.text().replace("26.1.2.3:25565","26.9.9.9:25565"));Thread.sleep(180);byte[] bad=ChatWire.packet("26.1.2.3","26.4.5.6",forged.toString());receiver.receive(bad,bad.length);check(ack.poll(400,TimeUnit.MILLISECONDS)==null&&store.rows().length()==0,"forged card stored or ACKed");
   membership.set(false);Thread.sleep(180);receiver.receive(packet,packet.length);check(ack.poll(400,TimeUnit.MILLISECONDS)==null,"stale member ACKed");
  }finally{receiver.close();store.clear();}
  membership.set(true);BlockingQueue<byte[]> outgoing=new LinkedBlockingQueue<>();ChatEngine sender=new ChatEngine(context,"26.1.2.3",outgoing::offer,directory);
  try{
   rejects(()->sender.send("26.4.5.6",card.text().replace("26.1.2.3:25565","26.9.9.9:25565")));check(store.rows().length()==0,"forged outgoing stored");
   sender.send("26.4.5.6",card.text());byte[] wire=outgoing.poll(3,TimeUnit.SECONDS);check(wire!=null,"card send missing");String firstId=new JSONObject(ChatWire.parse(wire,wire.length,"26.4.5.6")[1]).getString("id");JSONObject ackBody=new JSONObject().put("v",1).put("id",firstId).put("ack",true);byte[] confirmation=ChatWire.packet("26.4.5.6","26.1.2.3",ackBody.toString());sender.receive(confirmation,confirmation.length);long until=android.os.SystemClock.elapsedRealtime()+2000;while(!store.rows().getJSONObject(0).optString("state").equals("delivered")&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(20);check(store.rows().getJSONObject(0).optString("state").equals("delivered"),"normal card ACK status");
   sender.send("26.4.5.6",GameCard.create("Minecraft","26.1.2.3",25566,"Host",now,true).text());byte[] update=outgoing.poll(3,TimeUnit.SECONDS);check(update!=null,"replacement not sent");String newId=new JSONObject(ChatWire.parse(update,update.length,"26.4.5.6")[1]).getString("id");check(!newId.equals(firstId)&&store.rows().length()==2,"update overwrote previous card");
  }finally{sender.close();store.clear();}
  GameCardUiChecks.run(instrumentation,context);
 }
}
