package ru.bluenet.vpn;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
/** Bounded IPv4/UDP chat carried exclusively inside the authenticated Radmin tunnel. */
final class ChatWire {
 static final int PORT=49826,MAX=900;
 static byte[] packet(String src,String dst,String text)throws Exception{
  byte[] data=text.getBytes(StandardCharsets.UTF_8);if(data.length>MAX)throw new IllegalArgumentException();
  byte[] p=new byte[28+data.length];p[0]=0x45;NetworkProbe.put16(p,2,p.length);p[8]=64;p[9]=17;
  System.arraycopy(NetworkProbe.address(src),0,p,12,4);System.arraycopy(NetworkProbe.address(dst),0,p,16,4);
  NetworkProbe.put16(p,20,PORT);NetworkProbe.put16(p,22,PORT);NetworkProbe.put16(p,24,8+data.length);System.arraycopy(data,0,p,28,data.length);
  int check=NetworkProbe.sum(p,20,8+data.length,pseudo(p,8+data.length));NetworkProbe.put16(p,26,check==0?65535:check);
  NetworkProbe.put16(p,10,NetworkProbe.sum(p,0,20,0));return p;
 }
 static boolean reserved(byte[] p,int n){if(n<28||n>p.length||(p[0]&255)>>4!=4||p[9]!=17||(p[16]&255)!=26)return false;int off=(p[0]&15)*4;return off>=20&&off+8<=n&&NetworkProbe.u16(p,off)==PORT&&NetworkProbe.u16(p,off+2)==PORT;}
 static int pseudo(byte[] p,int len){int sum=17+len;for(int i=12;i<20;i+=2)sum+=NetworkProbe.u16(p,i);return sum;}
 static String[] parse(byte[] p,int n,String own)throws Exception{
  if(n<28||n>p.length||n>28+MAX||p[0]!=0x45||p[9]!=17||NetworkProbe.u16(p,2)!=n||(NetworkProbe.u16(p,6)&0xbfff)!=0||NetworkProbe.u16(p,20)!=PORT||NetworkProbe.u16(p,22)!=PORT||NetworkProbe.u16(p,24)!=n-20||NetworkProbe.u16(p,26)==0)return null;
  if(!Arrays.equals(Arrays.copyOfRange(p,16,20),NetworkProbe.address(own))||NetworkProbe.sum(p,0,20,0)!=0||NetworkProbe.sum(p,20,n-20,pseudo(p,n-20))!=0)return null;
  String src=(p[12]&255)+"."+(p[13]&255)+"."+(p[14]&255)+"."+(p[15]&255);NetworkProbe.address(src);
  String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(p,28,n-28)).toString();return new String[]{src,body};
 }
}
