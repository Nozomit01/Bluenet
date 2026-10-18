package ru.bluenet.vpn;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;

/** One real Android TUN, two app-private datagram endpoints; the Radmin engine retains its fd API. */
final class PacketMux implements AutoCloseable {
    private final ParcelFileDescriptor tun;private final FileDescriptor radmin=new FileDescriptor(),proxy=new FileDescriptor(),direct=new FileDescriptor();
    private final ProxyEngine engine=new ProxyEngine(),directEngine=new ProxyEngine();private volatile boolean closed;private Thread worker;private PacketPolicy policy;
    private ChatEngine chat;
    void chat(String peer,String text)throws Exception{ChatEngine c=chat;if(c==null)throw new IOException("connect_first");c.send(peer,text);}
    private final boolean combined;private final String vip;private boolean discovery;
    private final java.util.concurrent.ConcurrentHashMap<Integer,PortProbe> probes=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class PortProbe{final String dest;final int port,local;final long seq;final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);volatile String result="timeout";PortProbe(String d,int p,int l,long s){dest=d;port=p;local=l;seq=s;}}
    long probe()throws Exception{return engine.probe();}
    String serverId(){return engine.serverId;}
    PacketMux(ParcelFileDescriptor tun){this(tun,"26.254.0.2",true);}
    PacketMux(ParcelFileDescriptor tun,String address,boolean useProxy){this.tun=tun;vip=address;combined=useProxy;}
    String port(String dest,int port)throws Exception{
        NetworkProbe.address(dest);if(closed||probes.size()>=8)throw new IOException("probe_busy");
        java.security.SecureRandom random=new java.security.SecureRandom();PortProbe test;
        do{int local=49152+random.nextInt(16384);test=new PortProbe(dest,port,local,random.nextInt()&0xffffffffL);}while(probes.putIfAbsent(test.local,test)!=null);
        try{byte[] syn=NetworkProbe.tcp(vip,dest,test.local,port,test.seq,0,2);Os.write(radmin,syn,0,syn.length);test.done.await(3,java.util.concurrent.TimeUnit.SECONDS);return test.result;}
        finally{probes.remove(test.local);}
    }
    private boolean portReply(byte[] packet,int n){
        if(n<40||packet[9]!=6)return false;int offset=(packet[0]&15)*4;if(offset<20||offset+20>n)return false;
        PortProbe test=probes.get(NetworkProbe.u16(packet,offset+2));if(test==null)return false;
        try{if(!NetworkProbe.reply(packet,n,vip,test.dest,test.local,test.port,test.seq))return false;
            int flags=packet[offset+13]&255;if((flags&0x17)==0x12){test.result="open";byte[] reset=NetworkProbe.tcp(vip,test.dest,test.local,test.port,test.seq+1,NetworkProbe.u32(packet,offset+4)+1,0x14);Os.write(radmin,reset,0,reset.length);}
            else if((flags&4)!=0)test.result="closed";else return false;test.done.countDown();return true;
        }catch(Exception ignored){return false;}
    }
    int start(Context context,Runnable failed)throws Exception{
        FileDescriptor radEnd=new FileDescriptor(),proxyEnd=new FileDescriptor(),directEnd=new FileDescriptor();policy=combined?new PacketPolicy(context):null;discovery=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("discovery",true);
        try{
            synchronized(this){
                if(closed)throw new IOException("vless_engine");
                Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,radmin,radEnd);
                if(combined)Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,proxy,proxyEnd);
                if(combined&&policy.split)Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,direct,directEnd);
            }
            if(combined)engine.start(context,proxyEnd);if(closed)throw new IOException("vless_engine");
            if(combined&&policy.split){directEngine.start(context,directEnd,"",true);try(ParcelFileDescriptor d=ParcelFileDescriptor.dup(direct)){NativeBridge.nonblocking(d.getFd());}}
            try(ParcelFileDescriptor a=ParcelFileDescriptor.dup(radmin)){NativeBridge.nonblocking(a.getFd());}
            if(combined)try(ParcelFileDescriptor b=ParcelFileDescriptor.dup(proxy)){NativeBridge.nonblocking(b.getFd());}
            int fd;try(ParcelFileDescriptor detached=ParcelFileDescriptor.dup(radEnd)){NativeBridge.nonblocking(detached.getFd());fd=detached.detachFd();}
            chat=new ChatEngine(context,vip,bytes->Os.write(radmin,bytes,0,bytes.length));
            worker=new Thread(()->pump(failed),"bluenet-packet-mux");worker.start();return fd;
        }catch(Exception error){close();throw error;}finally{try{Os.close(radEnd);}catch(Exception ignored){}try{Os.close(proxyEnd);}catch(Exception ignored){}try{Os.close(directEnd);}catch(Exception ignored){}}
    }
    static boolean radminDestination(byte[] packet,int n){
        if(n<20||n>packet.length||packet[0]>>4!=4)return false;
        int d=packet[16]&255;
        return d==26||d>=224&&d<=239||d==255&&(packet[17]&255)==255&&(packet[18]&255)==255&&(packet[19]&255)==255;
    }
    private void pump(Runnable failed){
        byte[] packet=new byte[1500];FileDescriptor[] fds=!combined?new FileDescriptor[]{tun.getFileDescriptor(),radmin}:policy.split?new FileDescriptor[]{tun.getFileDescriptor(),radmin,proxy,direct}:new FileDescriptor[]{tun.getFileDescriptor(),radmin,proxy};StructPollfd[] poll=new StructPollfd[fds.length];
        for(int i=0;i<fds.length;i++){poll[i]=new StructPollfd();poll[i].fd=fds[i];poll[i].events=(short)OsConstants.POLLIN;}
        try{while(!closed){
            if(combined&&(!engine.alive()||policy.split&&!directEngine.alive()))throw new IOException("vless_engine");
            try{Os.poll(poll,200);}catch(ErrnoException error){if(error.errno==OsConstants.EINTR)continue;throw error;}
            for(int i=0;i<fds.length;i++){
                if((poll[i].revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0)throw new IOException("vless_engine");
                if((poll[i].revents&OsConstants.POLLIN)==0)continue;
                int n=Os.read(fds[i],packet,0,packet.length);if(n<=0)throw new IOException("vless_engine");
                // Other Android apps must not impersonate this chat via ordinary VPN sockets.
                if(i==0&&ChatWire.reserved(packet,n))continue;
                if(i==1&&(chat!=null&&chat.receive(packet,n)||portReply(packet,n)))continue;
                if(i==0&&!discovery&&radminDestination(packet,n)&&(packet[16]&255)!=26)continue;
                FileDescriptor dest=i==0?(!combined||radminDestination(packet,n)?radmin:policy.direct(packet,n)?direct:proxy):tun.getFileDescriptor();
                try{Os.write(dest,packet,0,n);}catch(ErrnoException error){if(error.errno!=OsConstants.EAGAIN)throw error;}
            }
        }}catch(Exception error){if(!closed)failed.run();}finally{close();}
    }
    public synchronized void close(){if(closed){engine.close();directEngine.close();return;}closed=true;if(chat!=null)chat.close();for(PortProbe p:probes.values())p.done.countDown();engine.close();directEngine.close();try{tun.close();}catch(Exception ignored){}try{Os.close(radmin);}catch(Exception ignored){}try{Os.close(proxy);}catch(Exception ignored){}try{Os.close(direct);}catch(Exception ignored){}}
}
