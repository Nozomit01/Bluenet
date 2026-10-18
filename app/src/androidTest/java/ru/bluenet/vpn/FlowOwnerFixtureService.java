package ru.bluenet.vpn;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.net.*;

/** Test-only second UID. The VPN's own UID is deliberately excluded and cannot be queried. */
public final class FlowOwnerFixtureService extends Service {
    private DatagramSocket socket;private String error="";
    @Override public IBinder onBind(Intent intent){
        return new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
            if(code!=1)return false;
            // Binder transactions execute on the service's worker pool, not its main thread.
            if(socket==null&&error.isEmpty())try{socket=new DatagramSocket(0,InetAddress.getByName("127.0.0.1"));socket.connect(InetAddress.getByName("127.0.0.1"),49997);}catch(Exception failed){error=failed.getClass().getSimpleName()+": "+failed.getMessage();if(socket!=null)socket.close();socket=null;}
            reply.writeInt(socket==null?-1:socket.getLocalPort());reply.writeInt(android.os.Process.myUid());reply.writeString(error);return true;
        }};
    }
    @Override public void onDestroy(){if(socket!=null)socket.close();super.onDestroy();}
}
