package ru.bluenet.vpn;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic state/clock checks; never enables TalkBack, a VPN, or haptics. */
final class AccessibilityChecks {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void reducerChecks(){
        ConnectionAnnouncements r=new ConnectionAnnouncements();
        r.update("connected",false,false,0);
        require(r.poll(100000)==null,"Initial connected baseline must be silent");
        r.update("error",true,false,100001);
        require(r.poll(105000)==null,"Loss announced before five seconds");
        require(r.poll(105001)==ConnectionAnnouncements.Event.LOST,"Stable loss missing");
        r.update("connected",false,false,106000);
        require(r.poll(107499)==null,"Recovery must settle");
        require(r.poll(107500)==ConnectionAnnouncements.Event.RESTORED,"Recovery after reported loss missing");
        require(r.poll(200000)==null&&r.deadline()<0,"No repeated or endless timers");

        r.reset();r.update("connected",false,false,0);
        r.update("error",true,false,100);r.update("connected",false,false,500);
        require(r.poll(10000)==null,"Short outage must not produce loss or recovery");
        r.update("error",true,false,11000);r.reset();
        require(r.poll(90000)==null,"Pause/reset must cancel pending loss");
        r.update("error",true,false,90001);r.update("connected",false,false,91000);
        require(r.poll(100000)==null,"Resume baseline must not invent restoration");

        r.reset();r.update("connected",false,false,0);r.update("stopping",false,true,100);
        r.update("idle",false,false,200);
        require(r.poll(9000)==null,"Intentional stop is not an outage");
        r.update("connecting",false,false,10000);r.update("connected",false,false,11000);
        require(r.poll(12500)==ConnectionAnnouncements.Event.CONNECTED,"Fresh connection is not restoration");
        r.update("idle",false,false,13000);
        require(r.poll(20000)==null,"External tile/overlay stop must be silent");

        r.reset();r.update("connected",false,false,0);
        for(int i=1;i<=4;i++)r.update(i%2==1?"error":"connected",i%2==1,false,i*100);
        require(r.poll(1899)==null,"Burst must settle before speaking");
        require(r.poll(1900)==ConnectionAnnouncements.Event.UNSTABLE_CONNECTED,"Flaps must merge into one current-state notice");
        for(int i=5;i<=10;i++)r.update(i%2==1?"error":"connected",i%2==1,false,i*1000);
        require(r.poll(50000)==null&&r.deadline()<0,"Instability must be capped at one notice per minute");
        r.update("error",true,false,51000);
        require(r.poll(56000)==ConnectionAnnouncements.Event.LOST,"Cooldown must not hide a durable new loss");
        r.update("connected",false,false,57000);
        require(r.poll(58500)==ConnectionAnnouncements.Event.RESTORED,"Cooldown must not hide recovery after spoken loss");
        r.update("error",true,false,63000);r.update("connected",false,false,63100);
        r.update("error",true,false,63200);r.update("connected",false,false,63300);
        require(r.poll(64800)==ConnectionAnnouncements.Event.UNSTABLE_CONNECTED,"New burst after cooldown can be announced");

        r.reset();r.update("connected",false,false,0);r.update("error",true,false,10);
        r.update("connected",false,false,5009);
        require(r.poll(5010)==null,"Obsolete pending loss must be cancelled");
    }
    /** Actual MainActivity helper, driven with synthetic snapshots; no tile or VPN action is sent. */
    private static void stopIntentChecks(Instrumentation instrumentation,Context context){
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try{
            instrumentation.runOnMainSync(()->{try{
                Field foreground=MainActivity.class.getDeclaredField("foreground");foreground.setAccessible(true);foreground.setBoolean(activity,false);
                Field handler=MainActivity.class.getDeclaredField("handler");handler.setAccessible(true);
                Field poll=MainActivity.class.getDeclaredField("poll");poll.setAccessible(true);
                ((Handler)handler.get(activity)).removeCallbacks((Runnable)poll.get(activity));
                Field stop=MainActivity.class.getDeclaredField("intentionalStop");stop.setAccessible(true);
                Field generation=MainActivity.class.getDeclaredField("lastGeneration");generation.setAccessible(true);
                Method observe=MainActivity.class.getDeclaredMethod("observeStopIntent",long.class,String.class);observe.setAccessible(true);
                generation.setLong(activity,9);stop.setBoolean(activity,true);
                observe.invoke(activity,9L,"stopping");
                require(stop.getBoolean(activity),"In-flight manual stop intent must survive stopping in the same session");
                observe.invoke(activity,9L,"error");
                require(stop.getBoolean(activity),"Same-session error must not prematurely clear an in-flight manual stop");
                observe.invoke(activity,9L,"idle");
                require(!stop.getBoolean(activity),"Confirmed idle must clear manual stop intent");
                for(String phase:new String[]{"connected","error"}){
                    stop.setBoolean(activity,true);
                    observe.invoke(activity,10L,phase);
                    require(!stop.getBoolean(activity),"New-session "+phase+" must clear stale stop intent even if idle was skipped");
                }
                // After the cleared flag, a later unexpected failure must still be reportable.
                ConnectionAnnouncements reducer=new ConnectionAnnouncements();
                reducer.update("connected",false,stop.getBoolean(activity),0);
                reducer.update("error",true,stop.getBoolean(activity),1000);
                require(reducer.poll(6000)==ConnectionAnnouncements.Event.LOST,"New session loss must not be suppressed by old stop intent");
                reducer.update("connected",false,stop.getBoolean(activity),7000);
                require(reducer.poll(8500)==ConnectionAnnouncements.Event.RESTORED,"New session recovery after spoken loss must remain reportable");
            }catch(Throwable problem){failure.set(problem);}});
        }finally{instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();}
        if(failure.get()!=null)throw new AssertionError("Stop intent snapshot checks failed",failure.get());
    }
    public static void run(Instrumentation instrumentation,Context context){
        reducerChecks();
        stopIntentChecks(instrumentation,context);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{
            TextView title=new TextView(context);
            ConnectionAccessibility controller=new ConnectionAccessibility(context,title);
            int[] changes={0};
            title.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){changes[0]++;}public void afterTextChanged(Editable e){}});
            try{
                controller.resume();controller.update("connected",false,false,"Connected");
                require(title.getText().toString().equals("✓ Connected"),"Visible non-color status missing");
                require("Connected".contentEquals(title.getContentDescription()),"Screen reader must receive semantic label without glyph");
                for(int i=0;i<20;i++)controller.update("connected",false,false,"Connected");
                require(changes[0]==1,"Polling must not repeatedly replace title text");
                require(controller.getAnnouncementView().getAccessibilityLiveRegion()==View.ACCESSIBILITY_LIVE_REGION_NONE,"Unfocused/unattached views must never speak");
                require(controller.getAnnouncementView().getText().length()==0,"No unavailable announcements");
                controller.pause();controller.update("error",true,false,"Error");
                require(controller.getAnnouncementView().getText().length()==0,"Paused controller cannot announce");
                require(ConnectionAccessibility.message(context,ConnectionAnnouncements.Event.LOST).length()>0,"Localized loss text missing");
            }finally{controller.release();}
        }catch(Throwable problem){failure.set(problem);}});
        if(failure.get()!=null)throw new AssertionError("Accessibility checks failed",failure.get());
    }
}
