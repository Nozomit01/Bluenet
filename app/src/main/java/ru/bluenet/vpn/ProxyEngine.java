package ru.bluenet.vpn;
import android.content.Context;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.ServerSocket;
import org.json.JSONObject;

/** App-private control socket, authenticated loopback SOCKS, no plaintext config files or raw logs. */
final class ProxyEngine implements AutoCloseable {
    private volatile boolean closed;private long lastErrorAt=-10000;private java.lang.Process process;private LocalSocket control;private File socket;
    private int socksPort;private String socksUser,socksPassword;String serverId="";
    void start(Context context,FileDescriptor virtualTun)throws Exception{
        start(context,virtualTun,ServerCatalog.active(context),false);
    }
    void start(Context context,FileDescriptor virtualTun,String secret,boolean direct)throws Exception{
        if(!direct){VlessProfile.outbound(secret);serverId=ServerCatalog.entry(secret).getString("id");}
        int port;try(ServerSocket reserve=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=reserve.getLocalPort();}
        String user=java.util.UUID.randomUUID().toString(),password=java.util.UUID.randomUUID().toString();
        socksPort=port;socksUser=user;socksPassword=password;
        socket=new File(context.getFilesDir(),"proxy-"+java.util.UUID.randomUUID()+".sock");
        JSONObject request=new JSONObject().put("xray",direct?VlessProfile.directConfiguration(port,user,password):VlessProfile.configuration(secret,port,user,password)).put("socket",socket.getAbsolutePath()).put("proxy","socks5://"+user+":"+password+"@127.0.0.1:"+port);
        ProcessBuilder builder=new ProcessBuilder(context.getApplicationInfo().nativeLibraryDir+"/libbluenet_proxy.so");
        builder.environment().put("GOMEMLIMIT","128MiB");builder.environment().put("GOMAXPROCS","2");
        builder.environment().put("SSL_CERT_DIR","/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts");
        if(closed)throw new IOException("vless_engine");process=builder.start();if(closed){process.destroy();throw new IOException("vless_engine");}discard(context,process.getInputStream());discard(context,process.getErrorStream());
        byte[] config=request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(OutputStream out=process.getOutputStream()){out.write(config);out.write('\n');}finally{java.util.Arrays.fill(config,(byte)0);}
        long until=SystemClock.elapsedRealtime()+12000;
        while(!closed&&!socket.exists()&&process.isAlive()&&SystemClock.elapsedRealtime()<until)Thread.sleep(30);
        if(closed||!socket.exists())throw new IOException("vless_engine");
        // bind() creates the pathname before listen(); file existence alone is not readiness.
        while(true){
            if(closed||!process.isAlive()||SystemClock.elapsedRealtime()>=until)throw new IOException("vless_engine");
            LocalSocket candidate=new LocalSocket();
            try{candidate.connect(new LocalSocketAddress(socket.getAbsolutePath(),LocalSocketAddress.Namespace.FILESYSTEM));control=candidate;break;}
            catch(IOException notListening){candidate.close();Thread.sleep(30);}
        }
        control.setSoTimeout(12000);
        control.setFileDescriptorsForSend(new FileDescriptor[]{virtualTun});control.getOutputStream().write(1);control.setFileDescriptorsForSend(null);
        byte[] ready=new byte[6];int n=0;while(n<ready.length){int got=control.getInputStream().read(ready,n,ready.length-n);if(got<0)throw new IOException("vless_engine");n+=got;}
        if(!new String(ready,java.nio.charset.StandardCharsets.US_ASCII).equals("READY\n"))throw new IOException("vless_engine");control.setSoTimeout(0);
    }
    boolean alive(){return process!=null&&process.isAlive();}
    /** A successful probe requires authenticated SOCKS, the VLESS tunnel, valid TLS and HTTP 204. */
    long probe()throws Exception{
        long begin=SystemClock.elapsedRealtime();
        try(java.net.Socket raw=new java.net.Socket()){
            java.util.concurrent.ScheduledFuture<?> timeout=NetworkDeadline.after(()->{try{raw.close();}catch(IOException ignored){}},20);
            try{
            raw.connect(new java.net.InetSocketAddress("127.0.0.1",socksPort),3000);raw.setSoTimeout(8000);
            DataInputStream in=new DataInputStream(raw.getInputStream());OutputStream out=raw.getOutputStream();
            out.write(new byte[]{5,1,2});if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=2)throw new IOException("socks_auth");
            byte[] u=socksUser.getBytes(java.nio.charset.StandardCharsets.US_ASCII),p=socksPassword.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(1);out.write(u.length);out.write(u);out.write(p.length);out.write(p);if(in.readUnsignedByte()!=1||in.readUnsignedByte()!=0)throw new IOException("socks_auth");
            byte[] host="www.gstatic.com".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(new byte[]{5,1,0,3});out.write(host.length);out.write(host);out.write(new byte[]{1,(byte)187});
            if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=0||in.readUnsignedByte()!=0)throw new IOException("vless_network");
            int type=in.readUnsignedByte(),length=type==1?4:type==4?16:type==3?in.readUnsignedByte():-1;if(length<0)throw new IOException("socks_reply");in.readFully(new byte[length+2]);
            javax.net.ssl.SSLSocketFactory factory=(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault();
            try(javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)factory.createSocket(raw,"www.gstatic.com",443,true)){
                javax.net.ssl.SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.setSoTimeout(8000);tls.startHandshake();
                tls.getOutputStream().write("GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                ByteArrayOutputStream line=new ByteArrayOutputStream();int b;while((b=tls.getInputStream().read())!=-1&&b!='\n'){if(line.size()>256)throw new IOException("probe_http");line.write(b);}
                if(!line.toString("US-ASCII").matches("HTTP/1\\.[01] 204(?: .*)?\\r?"))throw new IOException("probe_http");
            }
            }finally{timeout.cancel(false);}
        }
        return SystemClock.elapsedRealtime()-begin;
    }
    static long test(Context context,String link)throws Exception{
        FileDescriptor a=new FileDescriptor(),b=new FileDescriptor();ProxyEngine temporary=new ProxyEngine();
        try{android.system.Os.socketpair(android.system.OsConstants.AF_UNIX,android.system.OsConstants.SOCK_DGRAM,0,a,b);temporary.start(context,b,link,false);return temporary.probe();}
        finally{temporary.close();try{android.system.Os.close(a);}catch(Exception ignored){}try{android.system.Os.close(b);}catch(Exception ignored){}}
    }
    private void discard(Context context,InputStream in){
        Context app=context.getApplicationContext();Thread t=new Thread(()->{byte[] b=new byte[2048],line=new byte[4096];int length=0;
            try(InputStream stream=in){int n;while((n=stream.read(b))!=-1){for(int i=0;i<n;i++){
                if(b[i]=='\n'){String text=new String(line,0,length,java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
                    if(text.contains("[error]")||text.contains("failed to")||text.contains("timed out")||text.contains("connection refused"))noteError(app);
                    java.util.Arrays.fill(line,(byte)0);length=0;
                }else if(length<line.length)line[length++]=b[i];
            }java.util.Arrays.fill(b,(byte)0);}}
            catch(IOException ignored){}finally{java.util.Arrays.fill(b,(byte)0);java.util.Arrays.fill(line,(byte)0);}
        });t.setDaemon(true);t.start();
    }
    private synchronized void noteError(Context app){long now=SystemClock.elapsedRealtime();if(now-lastErrorAt<10000)return;lastErrorAt=now;EventLog.add(app,"vless_network");}
    public void close(){closed=true;try{if(control!=null)control.close();}catch(IOException ignored){}if(process!=null)process.destroy();if(socket!=null)socket.delete();}
}
