package ru.bluenet.vpn;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.*;
import java.net.*;
import java.util.*;

/** Unknown/fragmented flows and DNS stay on VLESS; a selected app never falls back to direct. */
final class PacketPolicy {
    private final ConnectivityManager connectivity;private final Set<Integer> selected=new HashSet<>();
    private final android.content.pm.PackageManager packages;private final Set<String> selectedNames;
    private final Map<String,Decision> cache=new LinkedHashMap<>();final boolean split;
    private static final class Decision{final boolean direct;final long at=SystemClock.elapsedRealtime();Decision(boolean d){direct=d;}}
    PacketPolicy(Context c){
        connectivity=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        packages=c.getPackageManager();
        android.content.SharedPreferences p=c.getSharedPreferences("settings",Context.MODE_PRIVATE);
        selectedNames=new HashSet<>(p.getStringSet("vless_apps",Collections.emptySet()));
        split=Build.VERSION.SDK_INT>=29&&p.getBoolean("per_app",false)&&!p.getStringSet("vless_apps",Collections.emptySet()).isEmpty();
        if(split)for(String name:p.getStringSet("vless_apps",Collections.emptySet()))try{selected.add(c.getPackageManager().getApplicationInfo(name,0).uid);}catch(Exception ignored){}
    }
    static InetSocketAddress[] endpoints(byte[] p,int n)throws Exception{
        if(n>p.length||n<20)throw new IllegalArgumentException();int version=(p[0]&255)>>4,offset,proto;byte[] source,dest;
        if(version==4){offset=(p[0]&15)*4;if(offset<20||n<offset+4||(p[6]&63)!=0||p[7]!=0)throw new IllegalArgumentException();proto=p[9]&255;source=Arrays.copyOfRange(p,12,16);dest=Arrays.copyOfRange(p,16,20);}
        else if(version==6){offset=40;if(n<44)throw new IllegalArgumentException();proto=p[6]&255;source=Arrays.copyOfRange(p,8,24);dest=Arrays.copyOfRange(p,24,40);}
        else throw new IllegalArgumentException();
        if(proto!=6&&proto!=17)throw new IllegalArgumentException();int sp=(p[offset]&255)*256+(p[offset+1]&255),dp=(p[offset+2]&255)*256+(p[offset+3]&255);
        if(sp==0||dp==0||dp==53||dp==853)throw new IllegalArgumentException();
        return new InetSocketAddress[]{new InetSocketAddress(InetAddress.getByAddress(source),sp),new InetSocketAddress(InetAddress.getByAddress(dest),dp)};
    }
    boolean direct(byte[] p,int n){
        if(android.os.Build.VERSION.SDK_INT<29||!split||selected.isEmpty())return false;
        try{
            InetSocketAddress[] ep=endpoints(p,n);int proto=((p[0]&255)>>4)==4?p[9]&255:p[6]&255;
            String key=proto+"/"+ep[0]+"/"+ep[1];Decision old=cache.get(key);if(old!=null&&SystemClock.elapsedRealtime()-old.at<15000)return old.direct;
            int uid=connectivity.getConnectionOwnerUid(proto,ep[0],ep[1]);boolean direct=false;
            if(uid>=0&&!selected.contains(uid)){
                // An app reinstall can change its UID without ending the VPN session.
                String[] names=packages.getPackagesForUid(uid);
                if(names!=null&&names.length>0){direct=true;for(String name:names)if(selectedNames.contains(name)){direct=false;break;}}
            }
            // Revalidate every direct packet: a five-tuple can be reused by another app/UID.
            if(cache.size()>=512)cache.clear();if(!direct)cache.put(key,new Decision(false));return direct;
        }catch(Exception ignored){return false;}
    }
}
