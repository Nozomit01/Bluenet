package ru.bluenet.vpn;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import org.json.JSONObject;

/** The foreground service owns the protocol engine independently of the Activity. */
public final class BlueVpnService extends VpnService {
    public static final String STOP = "ru.bluenet.vpn.STOP";
    private static final int NOTIFICATION = 26;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private volatile long generation;
    private IdentityStore identity;
    private volatile PacketMux mux;
    private long probeAt, probeId, probeGeneration;private int probeIndex;
    private String loggedPhase="";private long loggedOperation;
    private final ReconnectPolicy retries = new ReconnectPolicy();
    private boolean requested, retryQueued, networkRestart;
    private Network physical;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private static volatile boolean retryPending;
    private static volatile String connectionIssue="";
    private static volatile boolean proxyActive;public static boolean proxyActive(){return proxyActive;}
    private static volatile boolean running,blocked;public static boolean running(){return running;}public static boolean blocked(){return blocked;}
    private ParcelFileDescriptor guard;private volatile boolean probeBusy;private int healthFailures,failovers;private long healthAt;private boolean failoverExhausted;
    private static volatile BlueVpnService instance;
    static String checkPort(String ip,int port)throws Exception{BlueVpnService s=instance;PacketMux m=s==null?null:s.mux;if(m==null)throw new IllegalStateException("connect_first");return m.port(ip,port);}
    static void chat(String peer,String text)throws Exception{BlueVpnService s=instance;PacketMux m=s==null?null:s.mux;if(m==null)throw new IllegalStateException("connect_first");m.chat(peer,text);}
    private final java.util.concurrent.ExecutorService healthWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    public static String issue(){return connectionIssue;}
    public static boolean isRetryPending() { return retryPending; }
    private String tr(int id){return UiPreferences.text(this,id);}
    private boolean combined(){return getSharedPreferences("settings",MODE_PRIVATE).getBoolean("vless",false);}
    private boolean autoReconnect() { return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("reconnect", true); }
    private boolean hasProfile() { return new java.io.File(getFilesDir(), "identity.enc").exists(); }
    private final Runnable reconnect = () -> {
        retryQueued = false; retryPending = false;
        if (!requested || stopping || !autoReconnect() || VpnService.prepare(this) != null) { requestStop(); return; }
        if (!hasInternet()) { scheduleRetry(true); return; }
        try { startEngine(); } catch (Exception error) { finishService(); }
    };
    private boolean hasInternet() {
        if (connectivity == null) return false;
        for (Network n : connectivity.getAllNetworks()) {
            NetworkCapabilities c = connectivity.getNetworkCapabilities(n);
            if (c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return true;
        }
        return false;
    }
    private void watchNetworks() {
        if (networkCallback != null) return;
        connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { main.post(() -> {
                Network previous = physical; physical = n;
                if (requested && autoReconnect() && previous != null && !previous.equals(n)) restartForNetwork();
            }); }
            @Override public void onLost(Network n) { main.post(() -> {
                if (n.equals(physical)) { physical = null; if (requested && autoReconnect()) restartForNetwork(); }
            }); }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback);
    }
    private void restartForNetwork() {
        try {
            JSONObject state = new JSONObject(NativeBridge.status());
            if (state.optString("phase").equals("connected") && state.optLong("generation") == generation) {
                networkRestart = true; NativeBridge.stop();
            }
        } catch (Exception ignored) {}
    }
    private void startEngine() throws Exception {
        connectionIssue="";
        if(combined()){
            VlessProfile.outbound(ServerCatalog.active(this));
            if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("server_auto",true))ServerCatalog.fastest(this);
            if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("kill_switch",true)&&guard==null){guard=vpnBuilder("26.255.255.254",true).establish();if(guard==null)throw new IllegalStateException("VPN interface is unavailable");blocked=true;}
        }
        SharedPreferences preferences = getSharedPreferences("settings", MODE_PRIVATE);
        if (!NativeBridge.start(this, preferences.getString("device", Build.MODEL), preferences.getBoolean("relay", false))) throw new IllegalStateException("VPN не запущен");
        generation = new JSONObject(NativeBridge.status()).optLong("generation");
        main.removeCallbacks(notifyState); main.postDelayed(notifyState, 1000);
    }
    private void scheduleRetry(boolean eligible) {
        if (retryQueued) return;
        long delay = retries.nextDelay(requested, autoReconnect(), hasProfile(), eligible);
        if (delay < 0 || VpnService.prepare(this) != null) { finishService(); return; }
        retryQueued = true; retryPending = true;EventLog.add(this,"reconnecting");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(UiPreferences.context(this).getString(R.string.notify_retry,delay/1000)));
        main.postDelayed(reconnect, delay);
    }
    private void finishService() {
        if(requested&&!stopping&&guard!=null&&combined()&&getSharedPreferences("settings",MODE_PRIVATE).getBoolean("kill_switch",true)){
            retryQueued=false;retryPending=false;blocked=true;closeMux();main.removeCallbacks(reconnect);main.removeCallbacks(notifyState);NativeBridge.stop();
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(UiPreferences.context(this).getString(R.string.traffic_blocked)));return;
        }
        requested = false; stopping = true; retryQueued = false; retryPending = false;
        running=false;blocked=false;closeGuard();
        main.removeCallbacks(reconnect); main.removeCallbacks(notifyState);
        closeMux();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private final Runnable notifyState = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            try {
                JSONObject state = new JSONObject(NativeBridge.status());
                String phase = state.optString("phase", "idle");
                observe(state);
                if (phase.equals("connected")) retries.connected(SystemClock.elapsedRealtime());
                if (phase.equals("idle") || phase.equals("error")) { engineEnded(generation); return; }
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(tr(phase.equals("connected")?R.string.ui_20:R.string.notify_connecting)));
            } catch (Exception ignored) {}
            main.postDelayed(this, 1000);
        }
    };
    private synchronized void closeMux(){proxyActive=false;PacketMux old=mux;mux=null;if(old!=null)old.close();}
    private synchronized void closeGuard(){if(guard!=null){try{guard.close();}catch(Exception ignored){}guard=null;}}
    private synchronized void retainGuard(ParcelFileDescriptor descriptor)throws Exception{
        if(stopping){descriptor.close();throw new IllegalStateException("VPN setup cancelled");}
        if(!combined()||!getSharedPreferences("settings",MODE_PRIVATE).getBoolean("kill_switch",true))return;
        ParcelFileDescriptor next=ParcelFileDescriptor.dup(descriptor.getFileDescriptor());closeGuard();guard=next;blocked=true;
    }
    private void proxyFault(){
        if(stopping||!requested)return;connectionIssue="vless_network";blocked=guard!=null;EventLog.add(this,"vless_network");
        if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("server_auto",true))try{PacketMux current=mux;if(current!=null)ServerCatalog.result(this,current.serverId(),-1);if(ServerCatalog.next(this))EventLog.add(this,"server_switched");}catch(Exception ignored){}
        failoverExhausted=++failovers>5;networkRestart=!failoverExhausted;NativeBridge.stop();
    }
    private void observe(JSONObject state){
        if(state.optString("phase").equals("connected")&&mux!=null&&combined()){
            blocked=false;
            long now=SystemClock.elapsedRealtime();PacketMux current=mux;long gen=generation;
            if(!probeBusy&&now-healthAt>=30000&&getSharedPreferences("settings",MODE_PRIVATE).getBoolean("server_auto",true)){
                probeBusy=true;healthAt=now;healthWorker.submit(()->{long ms;try{ms=current.probe();}catch(Exception failure){ms=-1;}final boolean healthy=ms>=0;try{ServerCatalog.result(this,current.serverId(),ms);}catch(Exception ignored){}
                    main.post(()->{probeBusy=false;if(stopping||mux!=current||generation!=gen)return;if(healthy)healthFailures=0;else if(++healthFailures>=3){healthFailures=0;proxyFault();}});
                });
            }
        }
        QualityMonitor.observe(state);String phase=state.optString("phase");
        if(!phase.equals(loggedPhase)){loggedPhase=phase;if(phase.equals("error"))EventLog.add(this,ErrorCatalog.code(state.optString("error")));else if(phase.equals("connected"))EventLog.add(this,"connected");else if(phase.equals("idle"))EventLog.add(this,"disconnected");}
        long gen=state.optLong("generation");if(gen!=probeGeneration){probeGeneration=gen;loggedOperation=probeId=0;probeAt=0;probeIndex=0;}
        JSONObject operation=state.optJSONObject("operation");if(operation!=null&&operation.optLong("id")!=loggedOperation){loggedOperation=operation.optLong("id");if(operation.optBoolean("error"))EventLog.add(this,ErrorCatalog.code(operation.optString("message")));}
        if(!phase.equals("connected")||SystemClock.elapsedRealtime()-probeAt<5000)return;
        JSONObject snapshot=state.optJSONObject("snapshot"),peers=snapshot==null?null:snapshot.optJSONObject("peers");if(peers==null)return;
        java.util.ArrayList<Long> available=new java.util.ArrayList<>();java.util.Iterator<String> keys=peers.keys();
        while(keys.hasNext()&&available.size()<200){String key=keys.next();JSONObject peer=peers.optJSONObject(key);if(peer!=null&&peer.optString("status").equals("Connected"))try{available.add(Long.parseLong(key));}catch(NumberFormatException ignored){}}
        if(!available.isEmpty()){java.util.Collections.sort(available);long rid=available.get(probeIndex++%available.size());try{probeId=NativeBridge.command(new JSONObject().put("action","ping").put("rid",rid).toString());probeAt=SystemClock.elapsedRealtime();}catch(Exception ignored){}}
    }
    @Override public void onCreate() {
        super.onCreate();instance=this; identity = new IdentityStore(this);
        NotificationChannel channel = new NotificationChannel("vpn", tr(R.string.notify_vpn), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(tr(R.string.ui_7));
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BlueVpnService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "vpn").setSmallIcon(R.drawable.ic_notification).setContentTitle(tr(R.string.app_name))
            .setContentText(text).setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null, tr(R.string.ui_29), stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { connectionIssue="";requestStop(); return START_NOT_STICKY; }
        try {
            String phase = new JSONObject(NativeBridge.status()).optString("phase", "idle");
            if (phase.equals("connecting") || phase.equals("connected") || phase.equals("stopping")) return START_NOT_STICKY;
            stopping = false; requested = true;running=true;healthFailures=failovers=0;failoverExhausted=false; retries.reset(); retryQueued = false; retryPending = false; networkRestart = false; main.removeCallbacks(reconnect);
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(tr(R.string.notify_connecting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION, notification(tr(R.string.notify_connecting)));
            watchNetworks(); startEngine();
        } catch (Exception error) { connectionIssue=ErrorCatalog.code(error.getMessage());EventLog.add(this,connectionIssue);requestStop(); }
        return START_NOT_STICKY;
    }
    private Builder vpnBuilder(String vip,boolean useProxy)throws Exception{
        Builder builder = new Builder().setSession(tr(R.string.app_name)+" · Radmin VPN").setMtu(1500)
            .addAddress(vip, 8).addRoute("26.0.0.0", 8).allowFamily(android.system.OsConstants.AF_INET6).setBlocking(false);
        if (getSharedPreferences("settings",MODE_PRIVATE).getBoolean("discovery",true)) {
            builder.addRoute("255.255.255.255",32).addRoute("224.0.0.0",4);
        }
        if(useProxy){
            builder.addRoute("0.0.0.0",0).addAddress("fd00:26::2",128).addRoute("::",0).addDnsServer("1.1.1.1");
        }
        // Exclude the engine UID so its control/direct/relay sockets can never loop back into this VPN.
        builder.addDisallowedApplication(getPackageName());
        builder.setConfigureIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        return builder;
    }
    /** Called only from a native worker after Android VPN consent. Ownership transfers to Rust. */
    public int createTun(String vip) throws Exception {
        final boolean useProxy=combined();
        if (stopping || VpnService.prepare(this) != null) throw new IllegalStateException("VPN permission was revoked");
        Builder builder=vpnBuilder(vip,useProxy);
        ParcelFileDescriptor descriptor = builder.establish();
        if(descriptor==null)throw new IllegalStateException("VPN interface is unavailable");
        if(stopping){descriptor.close();throw new IllegalStateException("VPN permission was revoked");}
        retainGuard(descriptor);
        PacketMux next=new PacketMux(descriptor,vip,useProxy);mux=next;
        try{int fd=next.start(this,()->main.post(()->{if(mux!=next||stopping)return;if(useProxy){connectionIssue="vless_engine";EventLog.add(this,"vless_engine");proxyFault();}else{networkRestart=true;NativeBridge.stop();}}));synchronized(this){if(stopping||mux!=next){try(ParcelFileDescriptor abandoned=ParcelFileDescriptor.adoptFd(fd)){}throw new IllegalStateException("VPN setup cancelled");}proxyActive=useProxy;blocked=false;return fd;}}
        catch(Exception error){boolean canceled=stopping||mux!=next;next.close();if(mux==next)mux=null;if(!canceled){connectionIssue=ErrorCatalog.code(error.getMessage());if(!connectionIssue.startsWith("vless"))connectionIssue="vless_engine";EventLog.add(this,connectionIssue);}throw error;}

    }
    public String loadIdentity() throws Exception {
        String value = identity.read();
        if (value.isEmpty() && getSharedPreferences("registration", MODE_PRIVATE).getBoolean("pending", false)) throw new IllegalStateException("Результат регистрации неизвестен; автоматическая повторная регистрация остановлена");
        return value;
    }
    public void beginRegistration() {
        if (!getSharedPreferences("registration", MODE_PRIVATE).edit().putBoolean("pending", true).commit()) throw new IllegalStateException("Не удалось защитить регистрацию");
    }
    public void saveIdentity(String value) throws Exception { identity.write(value); getSharedPreferences("registration", MODE_PRIVATE).edit().putBoolean("pending", false).commit(); }
    public void engineEnded(long endedGeneration) { main.post(() -> {
        if (generation != endedGeneration || stopping || retryQueued) return;
        main.removeCallbacks(notifyState);
        try {
            JSONObject state = new JSONObject(NativeBridge.status());
            observe(state);closeMux();blocked=guard!=null;
            boolean eligible = !failoverExhausted&&(networkRestart || (state.optString("phase").equals("error") && state.optBoolean("retryable", false)));
            networkRestart = false; scheduleRetry(eligible);
        } catch (Exception error) { finishService(); }
    }); }
    private void requestStop() { requested=false;finishService(); NativeBridge.stop(); }
    @Override public void onRevoke() { EventLog.add(this,"permission");requestStop(); super.onRevoke(); }
    @Override public void onDestroy() {
        stopping = true; requested = false;running=false;blocked=false; retryPending = false;closeMux();closeGuard();healthWorker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (networkCallback != null) { try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {} networkCallback = null; }
        if(instance==this)instance=null;NativeBridge.stop(); super.onDestroy();
    }
}
