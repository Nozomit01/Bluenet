package ru.bluenet.vpn;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;

/** Disposable-emulator checks. No Radmin server is contacted and no real profile is used. */
public final class RuntimeSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle results=new Bundle(); File profile=null; boolean ownsProfile=false;
        try {
            if(Build.VERSION.SDK_INT<35)throw new AssertionError("These smoke checks require the disposable API 35+ emulator");
            check(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"),"Smoke tests require a disposable emulator");
            Context context=getTargetContext();profile=new File(context.getFilesDir(),"identity.enc");
            check(!new File(context.getFilesDir(),"vless.enc").exists()&&!new File(context.getFilesDir(),"servers.enc").exists(),"Existing VLESS profile/catalog must not be overwritten");
            check(!profile.exists()&&!new File(profile+".bak").exists()&&!new File(profile+".new").exists(),"Refusing to overwrite an existing profile");ownsProfile=true;
            JSONObject initial=new JSONObject(NativeBridge.status());check(initial.optString("phase").equals("idle"),"Native engine must initially be idle");
            results.putString("jni_load","PASS");
            String synthetic="{\"format\":\"openrad-identity-v1\",\"rid\":42,\"vip\":\"26.254.0.2\",\"node_name\":\"Тест Android\",\"address\":\"00000000000000000000000000000000\",\"credential\":\"736d6f6b652d736563726574\",\"server_address\":\"127.0.0.1\"}";
            IdentityStore store=new IdentityStore(context);store.write(synthetic);check(new IdentityStore(context).read().equals(synthetic),"Keystore persistence failed");
            byte[] first=Files.readAllBytes(profile.toPath());store.write(synthetic);byte[] second=Files.readAllBytes(profile.toPath());
            check(!Arrays.equals(first,second),"GCM IV was reused");check(!new String(second,java.nio.charset.StandardCharsets.ISO_8859_1).contains("openrad-identity-v1"),"Plaintext credential reached storage");
            results.putString("keystore_roundtrip","PASS");
            BlueVpnService callbacks=new BlueVpnService();Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(callbacks,context);callbacks.onCreate();
            // A valid synthetic profile points only at loopback: verify native-worker
            // callbacks and honest failure state without registration or live credentials.
            check(NativeBridge.start(callbacks,"Тест Android",false),"Native worker failed to start");
            long until=SystemClock.elapsedRealtime()+45000;JSONObject state;
            do {Thread.sleep(100);state=new JSONObject(NativeBridge.status());}while(state.optString("phase").equals("connecting")&&SystemClock.elapsedRealtime()<until);
            check(state.optString("phase").equals("error"),"An unavailable server must produce an error, never connected state");
            check(!state.optString("error").isEmpty(),"Failure reason missing");NativeBridge.stop();
            results.putString("jni_worker_failure_lifecycle","PASS");
            try(ParcelFileDescriptor tun=ParcelFileDescriptor.adoptFd(callbacks.createTun("26.254.0.2"))) {
                check(tun.getFd()>=0,"VpnService failed to establish TUN");
                int flags=Os.fcntlInt(tun.getFileDescriptor(),OsConstants.F_GETFL,0);check((flags&OsConstants.O_NONBLOCK)!=0,"TUN must be nonblocking");
            }
            results.putString("vpn_tun_ownership","PASS");
            ReconnectPolicy policy=new ReconnectPolicy();
            check(policy.nextDelay(true,true,false,true)<0,"Registration must never be retried automatically");
            check(policy.nextDelay(true,true,true,false)<0,"Permanent errors must not be retried");
            long[] delays={2000,4000,8000,16000,30000};for(long expected:delays)check(policy.nextDelay(true,true,true,true)==expected,"Retry backoff mismatch");
            check(policy.nextDelay(true,true,true,true)<0,"Retries must be bounded");
            policy.connected(1000);policy.connected(62000);check(policy.nextDelay(true,true,true,true)==2000,"Healthy session must reset retry budget");
            check(policy.nextDelay(false,true,true,true)<0,"User stop must cancel retry");
            check(policy.nextDelay(true,false,true,true)<0,"Disabled setting must cancel retry");
            results.putString("bounded_reconnect_policy","PASS");
            JSONObject privateState=new JSONObject("{\"phase\":\"connected\",\"error\":\"TOPSECRET\",\"credential\":\"TOPSECRET\",\"snapshot\":{\"vip\":\"26.1.2.3\",\"networks\":[{\"name\":\"SECRETNETWORK\"}],\"peers\":{\"777\":{\"name\":\"SECRETPEER\",\"status\":\"Connected\",\"transport\":\"DirectTcp\",\"password\":\"TOPSECRET\"}}}}");
            String diagnostic=DiagnosticReport.create(privateState,2,"0.2.0");
            check(!diagnostic.contains("TOPSECRET")&&!diagnostic.contains("SECRETNETWORK")&&!diagnostic.contains("SECRETPEER")&&!diagnostic.contains("26.1.2.3")&&!diagnostic.contains("777"),"Diagnostics leaked identifiers or secrets");
            check(new JSONObject(diagnostic).getInt("direct_tcp_count")==1,"Diagnostics omitted public counters");
            results.putString("diagnostics_redaction","PASS");
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(profile)){out.write(new byte[131073]);}
            boolean oversizedRejected=false;try{store.read();}catch(IllegalStateException expected){oversizedRejected=true;}
            check(oversizedRejected,"Oversized encrypted profile was accepted");
            store.write(synthetic); // Restore fixture only; never a user's identity.
            byte[] tampered=Files.readAllBytes(profile.toPath());tampered[tampered.length-1]^=1;Files.write(profile.toPath(),tampered);
            boolean tamperRejected=false;try{store.read();}catch(Exception expected){tamperRejected=true;}
            check(tamperRejected&&Arrays.equals(Files.readAllBytes(profile.toPath()),tampered),"Tampered profile must be rejected and preserved");
            store.write(synthetic);
            callbacks.beginRegistration();
            check(context.getSharedPreferences("registration",Context.MODE_PRIVATE).getBoolean("pending",false),"Registration guard was not committed");
            callbacks.saveIdentity(synthetic);
            check(!context.getSharedPreferences("registration",Context.MODE_PRIVATE).getBoolean("pending",true),"Successful profile save did not clear registration guard");
            results.putString("profile_bounds_and_registration_guard","PASS");
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("language","en").putString("theme","dark").commit();
            Context english=UiPreferences.context(context);
            check(english.getString(R.string.ui_46).equals("Create a network"),"English resources are incomplete");
            check((english.getResources().getConfiguration().uiMode&48)==32,"Dark theme preference was not applied");
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("theme","light").commit();
            check((UiPreferences.context(context).getResources().getConfiguration().uiMode&48)==16,"Light theme preference was not applied");
            results.putString("language_theme_persistence","PASS");
            String vless="vless://00000000-0000-4000-8000-000000000001@127.0.0.1:49999?encryption=none&type=tcp&security=none";
            check(VlessProfile.outbound(vless).getString("protocol").equals("vless"),"VLESS share import failed");
            for(String bad:new String[]{vless+"&allowInsecure=1",vless.replace("security=none","security=unknown"),vless.replace("00000000-0000-4000-8000-000000000001","NOT-A-UUID"),vless.replace("type=tcp","type=kcp")}){
                boolean rejected=false;try{VlessProfile.outbound(bad);}catch(Exception expected){rejected=true;}check(rejected,"Unsafe or unsupported VLESS parameters were accepted");
            }
            String pbk=android.util.Base64.encodeToString(new byte[32],android.util.Base64.URL_SAFE|android.util.Base64.NO_PADDING|android.util.Base64.NO_WRAP);
            check(VlessProfile.outbound(vless.replace("security=none","security=reality")+"&sni=example.com&pbk="+pbk+"&sid=0123&fp=chrome&flow=xtls-rprx-vision").getJSONObject("streamSettings").has("realitySettings"),"Reality parameters were lost");
            results.putString("vless_import_validation","PASS");
            byte[] routing=new byte[40];routing[0]=0x45;routing[16]=26;check(PacketMux.radminDestination(routing,40),"Radmin VIPs must stay on OpenRad");
            routing[16]=(byte)192;check(!PacketMux.radminDestination(routing,40),"Internet must use VLESS");routing[16]=(byte)224;check(PacketMux.radminDestination(routing,40),"LAN discovery must stay on Radmin");routing[0]=0x60;check(!PacketMux.radminDestination(routing,40),"IPv6 must use the proxy");check(!PacketMux.radminDestination(routing,0),"Truncated packet was accepted");
            results.putString("combined_route_isolation","PASS");
            EventLog.clear(context);EventLog.add(context,"TOPSECRET://26.1.2.3");for(int i=0;i<150;i++)EventLog.add(context,"timeout");
            long logUntil=SystemClock.elapsedRealtime()+15000;org.json.JSONArray events;
            do{Thread.sleep(100);events=EventLog.read(context);}while(events.length()==0&&SystemClock.elapsedRealtime()<logUntil);
            check(events.length()>0&&events.length()<=128&&!events.toString().contains("TOPSECRET")&&!events.toString().contains("26.1.2.3"),"Error log leaked data or exceeded its bound");
            results.putString("bounded_private_error_log","PASS");
            check(!new File(context.getFilesDir(),"vless.enc").exists(),"Refusing to overwrite an existing VLESS profile");
            new IdentityStore(context,"vless.enc","bluenet.vless.v1").write(vless);
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("vless",true).commit();
            BlueVpnService combined=new BlueVpnService();attach.invoke(combined,context);combined.onCreate();
            try(ParcelFileDescriptor virtual=ParcelFileDescriptor.adoptFd(combined.createTun("26.254.0.2"))){check(virtual.getFd()>=0,"Combined TUN did not return the Radmin endpoint");}
            combined.onDestroy();context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("vless",false).commit();new File(context.getFilesDir(),"vless.enc").delete();
            results.putString("android_xray_process_fd_and_mux","PASS");
            // Subscriptions: plain/Base64, deduplication, unsupported entries and bounded failures.
            String list=vless+"#Synthetic%20server\n"+vless+"#Duplicate\nss://unsupported\n";
            check(ServerCatalog.parse(list).length()==1,"Subscription deduplication failed");
            check(ServerCatalog.parse(android.util.Base64.encodeToString(list.getBytes(java.nio.charset.StandardCharsets.UTF_8),android.util.Base64.NO_WRAP)).length()==1,"Base64 subscription import failed");
            for(String bad:new String[]{"https://example.invalid/", "x".repeat(262145)}){boolean rejected=false;try{ServerCatalog.parse(bad);}catch(Exception expected){rejected=true;}check(rejected,"Invalid/oversized subscription accepted");}
            boolean insecure=false;try{ServerCatalog.subscriptionUrl("http://example.invalid/sub");}catch(Exception expected){insecure=true;}check(insecure,"Cleartext subscription accepted");
            results.putString("subscription_formats_and_bounds","PASS");
            org.json.JSONArray candidates=ServerCatalog.parse(vless+"#One\n"+vless.replace(":49999",":49998")+"#Two");
            ServerCatalog.importData(context,"https://example.invalid/sub/SECRET",candidates);
            check(ServerCatalog.active(context).equals(candidates.getJSONObject(0).getString("link")),"Catalog selection failed");
            byte[] encrypted=Files.readAllBytes(new File(context.getFilesDir(),"servers.enc").toPath());
            check(!new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1).contains("SECRET"),"Subscription URL reached plaintext storage");
            ServerCatalog.result(context,candidates.getJSONObject(0).getString("id"),200);ServerCatalog.result(context,candidates.getJSONObject(1).getString("id"),80);ServerCatalog.fastest(context);
            check(ServerCatalog.active(context).equals(candidates.getJSONObject(1).getString("link")),"Fastest measured server not selected");
            check(ServerCatalog.next(context)&&ServerCatalog.active(context).equals(candidates.getJSONObject(0).getString("link")),"Failover did not change server");
            SubscriptionJob.schedule(context);check(((android.app.job.JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(264)!=null,"Daily refresh not scheduled");
            ((android.app.job.JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(264);
            results.putString("encrypted_catalog_selection_and_failover","PASS");
            String invitation=Invitation.make("Сеть + & #","secret+/=&");String[] decoded=Invitation.parse(invitation);
            check(decoded[0].equals("Сеть + & #")&&decoded[1].equals("secret+/=&"),"Invitation escaping failed");
            boolean invalidInvite=false;try{Invitation.parse("bluenet://join?name=bad#name=bad&password=");}catch(Exception expected){invalidInvite=true;}check(invalidInvite,"Unexpected invitation query accepted");
            com.google.zxing.common.BitMatrix qr=new com.google.zxing.MultiFormatWriter().encode(invitation,com.google.zxing.BarcodeFormat.QR_CODE,320,320,java.util.Collections.singletonMap(com.google.zxing.EncodeHintType.CHARACTER_SET,"UTF-8"));
            int[] pixels=new int[320*320];for(int y=0;y<320;y++)for(int x=0;x<320;x++)pixels[y*320+x]=qr.get(x,y)?0xff000000:0xffffffff;
            com.google.zxing.BinaryBitmap bits=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(320,320,pixels)));
            check(new com.google.zxing.MultiFormatReader().decode(bits).getText().equals(invitation),"QR code is not decodable");
            results.putString("invitation_qr_roundtrip","PASS");
            byte[] flow=NetworkProbe.tcp("26.254.0.2","26.254.0.3",50000,25565,10,0,2);
            java.net.InetSocketAddress[] endpoints=PacketPolicy.endpoints(flow,flow.length);check(endpoints[1].getPort()==25565,"Flow parsing lost destination port");
            flow[6]=0x20;boolean fragment=false;try{PacketPolicy.endpoints(flow,flow.length);}catch(Exception expected){fragment=true;}check(fragment,"Fragment must never bypass VLESS");
            flow=NetworkProbe.tcp("26.254.0.2","26.254.0.3",50000,53,10,0,2);boolean dns=false;try{PacketPolicy.endpoints(flow,flow.length);}catch(Exception expected){dns=true;}check(dns,"DNS must never bypass VLESS");
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("per_app",true).putStringSet("vless_apps",java.util.Collections.singleton(context.getPackageName())).putBoolean("vless",true).commit();
            BlueVpnService split=new BlueVpnService();attach.invoke(split,context);split.onCreate();
            try(ParcelFileDescriptor virtual=ParcelFileDescriptor.adoptFd(split.createTun("26.254.0.2"))){check(virtual.getFd()>=0,"Split dispatcher failed to start both proxy engines");
                java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<IBinder> binder=new java.util.concurrent.atomic.AtomicReference<>();
                android.content.ServiceConnection connection=new android.content.ServiceConnection(){public void onServiceConnected(android.content.ComponentName name,IBinder service){binder.set(service);ready.countDown();}public void onServiceDisconnected(android.content.ComponentName name){}};
                android.content.Intent fixture=new android.content.Intent().setComponent(new android.content.ComponentName(context.getPackageName()+".test",FlowOwnerFixtureService.class.getName()));
                check(context.bindService(fixture,connection,Context.BIND_AUTO_CREATE),"Could not bind second-UID flow fixture");
                try{
                    check(ready.await(30,java.util.concurrent.TimeUnit.SECONDS)&&binder.get()!=null,"Second-UID fixture did not start");
                    Parcel request=Parcel.obtain(),response=Parcel.obtain();int port,uid;String fixtureError;try{check(binder.get().transact(1,request,response,0),"Fixture binder failed");port=response.readInt();uid=response.readInt();fixtureError=response.readString();}finally{request.recycle();response.recycle();}
                    check(port>0&&uid!=android.os.Process.myUid(),"Flow fixture: port="+port+", uid="+uid+", parent="+android.os.Process.myUid()+", error="+fixtureError);
                    byte[] packet=new byte[28];packet[0]=0x45;packet[9]=17;packet[12]=packet[16]=127;packet[15]=packet[19]=1;NetworkProbe.put16(packet,20,port);NetworkProbe.put16(packet,22,49997);
                    int observed=((android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE)).getConnectionOwnerUid(17,new java.net.InetSocketAddress("127.0.0.1",port),new java.net.InetSocketAddress("127.0.0.1",49997));
                    check(observed==uid,"Android did not expose the captured fixture UID: expected "+uid+", observed "+observed);
                    context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putStringSet("vless_apps",java.util.Collections.singleton(context.getPackageName()+".test")).commit();
                    check(!new PacketPolicy(context).direct(packet,28),"Selected app was routed directly");
                    context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putStringSet("vless_apps",java.util.Collections.singleton("android")).commit();
                    check(new PacketPolicy(context).direct(packet,28),"Unselected app was not routed directly");
                }finally{context.unbindService(connection);}
            }
            java.lang.reflect.Field guard=BlueVpnService.class.getDeclaredField("guard");guard.setAccessible(true);ParcelFileDescriptor retained=(ParcelFileDescriptor)guard.get(split);
            Method closeMux=BlueVpnService.class.getDeclaredMethod("closeMux");closeMux.setAccessible(true);closeMux.invoke(split);
            check(retained!=null&&retained.getFileDescriptor().valid(),"Kill switch lost TUN after proxy dispatcher teardown");
            split.onDestroy();check(guard.get(split)==null,"Explicit destroy retained a VPN descriptor");
            results.putString("per_app_dispatch_and_fail_closed_tun","PASS");
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("vless",false).putBoolean("per_app",false).commit();
            BlueVpnService portService=new BlueVpnService();attach.invoke(portService,context);portService.onCreate();
            try(ParcelFileDescriptor virtual=ParcelFileDescriptor.adoptFd(portService.createTun("26.254.0.2"))){
                // Fake Service instances are not the framework-bound singleton. Exercise their owned mux.
                java.lang.reflect.Field ownedMux=BlueVpnService.class.getDeclaredField("mux");ownedMux.setAccessible(true);PacketMux portMux=(PacketMux)ownedMux.get(portService);
                java.util.concurrent.FutureTask<String> portTest=new java.util.concurrent.FutureTask<>(()->portMux.port("26.254.0.3",25565));new Thread(portTest).start();
                byte[] syn=new byte[1500];int n=0;long deadline=SystemClock.elapsedRealtime()+2500;
                while(n==0&&SystemClock.elapsedRealtime()<deadline){try{n=Os.read(virtual.getFileDescriptor(),syn,0,syn.length);if(n!=40||syn[9]!=6||NetworkProbe.u16(syn,22)!=25565||(syn[33]&255)!=2)n=0;}catch(android.system.ErrnoException e){if(e.errno!=OsConstants.EAGAIN)throw e;Thread.sleep(20);}}
                if(n!=40&&portTest.isDone())portTest.get();
                check(n==40,"Port probe did not travel through the Radmin endpoint");int local=NetworkProbe.u16(syn,20);long sequence=NetworkProbe.u32(syn,24);
                byte[] reply=NetworkProbe.tcp("26.254.0.3","26.254.0.2",25565,local,300,sequence+1,0x12);
                check(NetworkProbe.reply(reply,40,"26.254.0.2","26.254.0.3",local,25565,sequence),"TCP checksum/correlation rejected valid SYN-ACK");
                reply[28]^=1;check(!NetworkProbe.reply(reply,40,"26.254.0.2","26.254.0.3",local,25565,sequence),"Uncorrelated SYN-ACK accepted");reply[28]^=1;
                Os.write(virtual.getFileDescriptor(),reply,0,reply.length);check(portTest.get(4,java.util.concurrent.TimeUnit.SECONDS).equals("open"),"Authenticated path port check failed");
            }finally{portService.onDestroy();}
            results.putString("game_port_virtual_path_and_tcp_correlation","PASS");
            QualityMonitor.observe(new JSONObject().put("generation",9999).put("ping",new JSONObject().put("id",1).put("peer",42).put("rtt_ms",20)));
            QualityMonitor.observe(new JSONObject().put("generation",9999).put("ping",new JSONObject().put("id",2).put("peer",42).put("rtt_ms",40)));
            QualityMonitor.observe(new JSONObject().put("generation",9999).put("ping",new JSONObject().put("id",3).put("peer",42).put("rtt_ms",JSONObject.NULL)));
            JSONObject quality=QualityMonitor.stats(42);check(quality.getInt("replies")==2&&quality.getInt("timeouts")==1&&quality.getDouble("jitter_ms")==20&&quality.getDouble("probe_loss_percent")>33,"Probe history metrics incorrect");
            results.putString("probe_loss_and_rtt_history","PASS");
            for(String mode:new String[]{"servers","apps","games","invite"}){
                FeatureActivity feature=(FeatureActivity)startActivitySync(new android.content.Intent(context,FeatureActivity.class).putExtra("mode",mode).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();Thread.sleep(200);
                runOnMainSync(()->{try{android.view.View view=feature.findViewById(android.R.id.content);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(context.getFilesDir(),"tool-"+mode+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();feature.finish();}catch(Exception e){throw new RuntimeException(e);}});
            }
            ServerCatalog.clear(context);ServerCatalog.refreshIfCurrent(context,"https://example.invalid/sub/SECRET",candidates);check(ServerCatalog.read(context).getJSONArray("servers").length()==0,"Canceled refresh restored deleted subscription");
            results.putString("tool_screens_launch","PASS");
            // Render actual app views with synthetic state, avoiding emulator System UI overlays.
            context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("theme","dark").putString("language","en").commit();
            android.content.Intent launch=new android.content.Intent(context,MainActivity.class).putExtra("tile_connect",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity=(MainActivity)startActivitySync(launch);waitForIdleSync();check(!BlueVpnService.running(),"Exported launcher extras started VPN without an internal action");
            runOnMainSync(()->{try{Method settings=MainActivity.class.getDeclaredMethod("selectTab",int.class);settings.setAccessible(true);settings.invoke(activity,2);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();Thread.sleep(300);
            runOnMainSync(()->{
                try{android.view.View view=activity.findViewById(android.R.id.content);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
                    try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(context.getFilesDir(),"ui-night-en.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
                }catch(Exception e){throw new RuntimeException(e);}
            });
            runOnMainSync(()->{try{Method select=MainActivity.class.getDeclaredMethod("selectTab",int.class);select.setAccessible(true);select.invoke(activity,0);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();Thread.sleep(300);
            runOnMainSync(()->{try{android.view.View v=activity.findViewById(android.R.id.content);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(v.getWidth(),v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(bitmap));try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(context.getFilesDir(),"home-night-en.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();activity.finish();}catch(Exception e){throw new RuntimeException(e);}});
            results.putString("english_dark_activity_launch","PASS");
            try {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();PacketMux canceled=new PacketMux(pipe[0]);canceled.close();
                boolean canceledRejected=false;try{canceled.start(context,()->{});}catch(Exception expected){canceledRejected=true;}finally{canceled.close();pipe[1].close();}
                check(canceledRejected,"Canceled dispatcher must not start or allocate a new engine");
            }catch(Exception error){throw new AssertionError("Early mux cancellation failed",error);}
            results.putString("mux_early_cancel","PASS");
            
            // Chat framing: checksum, address binding, fragmentation, size and UTF-8 boundaries.
            String chatText="Привет, играем?";byte[] chatPacket=ChatWire.packet("26.1.2.3","26.4.5.6",chatText);
            check(ChatWire.reserved(chatPacket,chatPacket.length),"VPN apps could inject into reserved chat port");byte[] ordinaryInternet=chatPacket.clone();ordinaryInternet[16]=8;check(!ChatWire.reserved(ordinaryInternet,ordinaryInternet.length),"Internet UDP port incorrectly reserved");byte[] otherSource=chatPacket.clone();NetworkProbe.put16(otherSource,20,12345);check(!ChatWire.reserved(otherSource,otherSource.length),"Unrelated source UDP port incorrectly reserved");
            check(ChatWire.parse(chatPacket,chatPacket.length,"26.4.5.6")[1].equals(chatText),"UDP chat checksum/UTF-8 roundtrip");
            check(ChatWire.parse(chatPacket,chatPacket.length,"26.4.5.7")==null,"Chat accepted another recipient");
            byte[] damaged=chatPacket.clone();damaged[damaged.length-1]^=1;check(ChatWire.parse(damaged,damaged.length,"26.4.5.6")==null,"Chat accepted altered payload");
            damaged=chatPacket.clone();damaged[6]=0x20;check(ChatWire.parse(damaged,damaged.length,"26.4.5.6")==null,"Fragmented chat accepted");
            boolean chatTooLarge=false;try{ChatWire.packet("26.1.2.3","26.4.5.6","X".repeat(901));}catch(IllegalArgumentException e){chatTooLarge=true;}check(chatTooLarge,"Oversized chat accepted");
            results.putString("chat_wire_validation","PASS");
            JSONObject chatState=new JSONObject("{\"phase\":\"connected\",\"snapshot\":{\"networks\":[{\"network_id\":\"A\"}],\"peers\":{\"1\":{\"status\":\"Connected\",\"peer\":{\"vip\":\"26.1.2.3\",\"network_ids\":[\"B\"]}}}}}");
            check(ChatEngine.member(chatState,"26.1.2.3")==null,"Chat accepted peer without shared membership");
            chatState.getJSONObject("snapshot").getJSONObject("peers").getJSONObject("1").getJSONObject("peer").put("network_ids",new org.json.JSONArray().put("A"));
            check(ChatEngine.member(chatState,"26.1.2.3")!=null,"Shared member missing");chatState.put("phase","idle");check(ChatEngine.member(chatState,"26.1.2.3")==null,"Disconnected peer accepted");
            results.putString("chat_membership_boundary","PASS");
            ChatStore chatStore=ChatStore.get(context);chatStore.clear();ChatEngine[] pair=new ChatEngine[2];
            ChatEngine.Directory directory=ip->{try{return new JSONObject().put("name","Test peer");}catch(Exception e){return null;}};
            pair[0]=new ChatEngine(context,"26.1.2.3",packet->{pair[1].receive(packet,packet.length);},directory);
            pair[1]=new ChatEngine(context,"26.4.5.6",packet->{pair[0].receive(packet,packet.length);},directory);
            try{pair[0].send("26.4.5.6",chatText);long chatUntil=SystemClock.elapsedRealtime()+8000;boolean delivered=false;do{Thread.sleep(100);org.json.JSONArray rows=chatStore.rows();for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("state").equals("delivered"))delivered=true;}while(!delivered&&SystemClock.elapsedRealtime()<chatUntil);check(delivered,"Two engines did not confirm delivery");check(chatStore.rows().length()==2,"Chat duplicated received history");}finally{pair[0].close();pair[1].close();}
            java.util.concurrent.ArrayBlockingQueue<byte[]> droppedPackets=new java.util.concurrent.ArrayBlockingQueue<>(8);ChatEngine silent=new ChatEngine(context,"26.1.2.3",packet->{droppedPackets.offer(packet);},directory);try{silent.send("26.4.5.6","No ACK");byte[] dropped=droppedPackets.poll(4,java.util.concurrent.TimeUnit.SECONDS);check(dropped!=null,"Chat send not attempted");String outgoingId=new JSONObject(ChatWire.parse(dropped,dropped.length,"26.4.5.6")[1]).getString("id");byte[] wrongAck=ChatWire.packet("26.9.9.9","26.1.2.3",new JSONObject().put("v",1).put("id",outgoingId).put("ack",true).toString());silent.receive(wrongAck,wrongAck.length);Thread.sleep(300);org.json.JSONArray pendingRows=chatStore.rows();check(!pendingRows.getJSONObject(pendingRows.length()-1).optString("state").equals("delivered"),"Wrong peer confirmed delivery");Thread.sleep(7400);org.json.JSONArray rows=chatStore.rows();check(rows.getJSONObject(rows.length()-1).getString("state").equals("failed"),"Unacknowledged chat falsely delivered");}finally{silent.close();}
            results.putString("chat_delivery_confirmation","PASS");
            for(int i=0;i<55;i++)chatStore.add("26.4.5.6","Test","id"+i,"PRIVATE_CHAT_CONTENT_"+i,false);
            check(chatStore.rows().length()<=48,"Chat history unbounded");byte[] chatCipher=Files.readAllBytes(new File(context.getFilesDir(),"chat.enc").toPath());check(!new String(chatCipher,java.nio.charset.StandardCharsets.ISO_8859_1).contains("PRIVATE_CHAT_CONTENT"),"Plaintext chat stored");
            chatStore.clear();check(chatStore.rows().length()==0,"Clear chat failed");results.putString("chat_encrypted_history_bounds","PASS");
            ChatActivity chatActivity=(ChatActivity)startActivitySync(new android.content.Intent(context,ChatActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();Thread.sleep(300);runOnMainSync(()->{try{android.view.View v=chatActivity.findViewById(android.R.id.content);android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(v.getWidth(),v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(bitmap));try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(context.getFilesDir(),"chat-night-en.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();chatActivity.finish();}catch(Exception e){throw new RuntimeException(e);}});results.putString("chat_activity_launch","PASS");
            check(android.provider.Settings.canDrawOverlays(context),"Disposable emulator must grant overlay app-op");
            // Start from a visible Activity as required by Android FGS background restrictions.
            MainActivity overlayHost=(MainActivity)startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            runOnMainSync(()->context.startForegroundService(new android.content.Intent(context,QuickBubbleService.class)));long bubbleUntil=SystemClock.elapsedRealtime()+10000;while(QuickBubbleService.active()==null&&SystemClock.elapsedRealtime()<bubbleUntil)Thread.sleep(100);check(QuickBubbleService.active()!=null,"Bubble service missing");
            runOnMainSync(()->{try{Method show=QuickBubbleService.class.getDeclaredMethod("show",boolean.class);show.setAccessible(true);show.invoke(QuickBubbleService.active(),true);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();Thread.sleep(400);
            runOnMainSync(()->{try{java.lang.reflect.Field window=QuickBubbleService.class.getDeclaredField("window");window.setAccessible(true);android.view.View v=(android.view.View)window.get(QuickBubbleService.active());check(v!=null&&v.isAttachedToWindow(),"Expanded overlay missing");android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(v.getWidth(),v.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);v.draw(new android.graphics.Canvas(bitmap));try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(context.getFilesDir(),"bubble-night-en.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();Method show=QuickBubbleService.class.getDeclaredMethod("show",boolean.class);show.setAccessible(true);show.invoke(QuickBubbleService.active(),false);}catch(Exception e){throw new RuntimeException(e);}});
            runOnMainSync(()->{context.stopService(new android.content.Intent(context,QuickBubbleService.class));overlayHost.finish();});waitForIdleSync();Thread.sleep(300);check(QuickBubbleService.active()==null,"Overlay failed to stop");results.putString("floating_overlay_lifecycle","PASS");

            LayoutChecks.run(this,context);results.putString("adaptive_layout_and_release_history","PASS");
            results.putInt("tests_passed",28);finish(Activity.RESULT_OK,results);
        } catch(Throwable error) {
            NativeBridge.stop();results.putString("failure",error.getClass().getSimpleName()+": "+error.getMessage());java.io.StringWriter trace=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(trace));results.putString("failure_stack",trace.toString());finish(Activity.RESULT_CANCELED,results);
        } finally {
            if(ownsProfile&&profile!=null){profile.delete();new File(profile+".bak").delete();new File(profile+".new").delete();}
        }
    }
}
