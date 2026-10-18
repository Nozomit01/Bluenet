package ru.bluenet.vpn;
import java.net.InetAddress;
import java.util.Arrays;
/** Minimal TCP SYN probe through the authenticated Radmin IP path, not the physical network. */
final class NetworkProbe {
    static byte[] address(String ip)throws Exception{
        if(!ip.matches("26\\.(?:[0-9]{1,3}\\.){2}[0-9]{1,3}"))throw new IllegalArgumentException();
        String[] parts=ip.split("\\.");for(String part:parts)if(Integer.parseInt(part)>255)throw new IllegalArgumentException();return InetAddress.getByName(ip).getAddress();
    }
    static int u16(byte[] p,int at){return (p[at]&255)*256+(p[at+1]&255);}
    static long u32(byte[] p,int at){return ((long)u16(p,at)<<16)|u16(p,at+2);}
    static void put16(byte[] p,int at,int value){p[at]=(byte)(value>>8);p[at+1]=(byte)value;}
    static void put32(byte[] p,int at,long value){put16(p,at,(int)(value>>16));put16(p,at+2,(int)value);}
    static int sum(byte[] p,int from,int length,int initial){int sum=initial;for(int i=from;i<from+length;i+=2)sum+=((p[i]&255)<<8)+(i+1<from+length?p[i+1]&255:0);while((sum>>16)!=0)sum=(sum&65535)+(sum>>16);return (~sum)&65535;}
    static byte[] tcp(String source,String dest,int sp,int dp,long seq,long ack,int flags)throws Exception{
        if(sp<1||dp<1||sp>65535||dp>65535)throw new IllegalArgumentException();byte[] p=new byte[40];p[0]=0x45;put16(p,2,40);put16(p,4,(int)seq);p[6]=0x40;p[8]=64;p[9]=6;
        System.arraycopy(address(source),0,p,12,4);System.arraycopy(address(dest),0,p,16,4);put16(p,10,sum(p,0,20,0));
        put16(p,20,sp);put16(p,22,dp);put32(p,24,seq);put32(p,28,ack);p[32]=0x50;p[33]=(byte)flags;put16(p,34,4096);
        int pseudo=6+20;for(int i=12;i<20;i+=2)pseudo+=u16(p,i);put16(p,36,sum(p,20,20,pseudo));return p;
    }
    static boolean reply(byte[] p,int n,String source,String dest,int sp,int dp,long seq)throws Exception{
        if(n<40||n>p.length||(p[0]&255)>>4!=4||p[9]!=6||(p[6]&63)!=0||p[7]!=0)return false;
        int offset=(p[0]&15)*4,total=u16(p,2);if(offset<20||offset+20>n||total>n||total<offset+20||(p[offset+12]&255)>>4<5)return false;
        if(!Arrays.equals(Arrays.copyOfRange(p,12,16),address(dest))||!Arrays.equals(Arrays.copyOfRange(p,16,20),address(source)))return false;
        if(u16(p,offset)!=dp||u16(p,offset+2)!=sp||u32(p,offset+8)!=((seq+1)&0xffffffffL))return false;
        int pseudo=6+total-offset;for(int i=12;i<20;i+=2)pseudo+=u16(p,i);
        return ((p[offset+12]&255)>>4)*4<=total-offset&&sum(p,0,offset,0)==0&&sum(p,offset,total-offset,pseudo)==0;
    }
}
