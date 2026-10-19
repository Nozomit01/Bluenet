package ru.bluenet.vpn;

/** Pure, monotonic-clock reducer. Durations are initial usability hypotheses, not guarantees. */
final class ConnectionAnnouncements {
    static final long LOSS_DELAY_MS=5000, SETTLE_MS=1500, BURST_WINDOW_MS=60000;
    enum Event { CONNECTED, LOST, RESTORED, UNSTABLE_CONNECTED, UNSTABLE_DISCONNECTED }
    private boolean initialized, online, armed, lossSpoken, attempted;
    private final long[] edges=new long[4];
    private int edgeCount;
    private long lastUnstable=Long.MIN_VALUE, deadline=-1;
    private Event pending;

    void reset(){initialized=false;online=false;armed=false;lossSpoken=false;attempted=false;edgeCount=0;lastUnstable=Long.MIN_VALUE;cancel();}
    private void cancel(){pending=null;deadline=-1;}
    long deadline(){return deadline;}
    void update(String phase,boolean retry,boolean intentionalStop,long now){
        boolean connected="connected".equals(phase);
        if(intentionalStop||"stopping".equals(phase)||("idle".equals(phase)&&!retry)){reset();initialized=true;online=connected;return;}
        if(!initialized){initialized=true;online=connected;armed=connected;return;}
        if("connecting".equals(phase)||retry)attempted=true;
        if(online==connected)return;
        online=connected;cancel();
        if(edgeCount<edges.length)edges[edgeCount++]=now;
        else {System.arraycopy(edges,1,edges,0,edges.length-1);edges[edges.length-1]=now;}
        boolean burst=edgeCount==edges.length&&now-edges[0]<=BURST_WINDOW_MS;
        if(burst&&(lastUnstable==Long.MIN_VALUE||now-lastUnstable>=BURST_WINDOW_MS)){
            // Cooldown only limits instability summaries; durable loss/recovery remain reportable.
            pending=online?Event.UNSTABLE_CONNECTED:Event.UNSTABLE_DISCONNECTED;
        }else if(online){
            if(lossSpoken)pending=Event.RESTORED;
            else if(attempted&&!armed)pending=Event.CONNECTED;
        }else if(armed&&!lossSpoken)pending=Event.LOST;
        if(online)armed=true;
        if(pending!=null)deadline=now+(online?SETTLE_MS:LOSS_DELAY_MS);
    }
    Event poll(long now){
        if(pending==null||now<deadline)return null;
        Event result=pending;cancel();
        if(result==Event.UNSTABLE_CONNECTED||result==Event.UNSTABLE_DISCONNECTED)lastUnstable=now;
        if(result==Event.LOST||result==Event.UNSTABLE_DISCONNECTED)lossSpoken=true;
        if(result==Event.CONNECTED||result==Event.RESTORED||result==Event.UNSTABLE_CONNECTED){lossSpoken=false;attempted=false;}
        return result;
    }
}
