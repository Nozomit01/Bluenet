package ru.bluenet.vpn;
import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

/** OS-scheduled daily refresh; cancellation closes work by bounded network timeouts. */
public final class SubscriptionJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private Future<?> task;private volatile int generation;
    static void schedule(Context c){
        JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(c.getSharedPreferences("settings",MODE_PRIVATE).getBoolean("subscription_auto",true))
            scheduler.schedule(new JobInfo.Builder(264,new ComponentName(c,SubscriptionJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(86400000L).setPersisted(true).build());
        else scheduler.cancel(264);
    }
    @Override public boolean onStartJob(JobParameters params){int token=++generation;task=worker.submit(()->{
        boolean retry=false;try{String url=ServerCatalog.read(this).optString("subscription");if(!url.isEmpty()){
            org.json.JSONArray entries=ServerCatalog.parse(ServerCatalog.download(url));ServerCatalog.refreshIfCurrent(this,url,entries);
        }}catch(Exception e){retry=true;EventLog.add(this,"subscription_failed");}
        final boolean again=retry;new android.os.Handler(getMainLooper()).post(()->{if(generation==token)jobFinished(params,again);});
    });return true;}
    @Override public boolean onStopJob(JobParameters params){generation++;if(task!=null)task.cancel(true);return true;}
    @Override public void onDestroy(){generation++;worker.shutdownNow();super.onDestroy();}
}
