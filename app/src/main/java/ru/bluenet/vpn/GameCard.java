package ru.bluenet.vpn;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
/** Human-readable chat payload; never a statement that a game server is reachable. */
final class GameCard {
 static final String PREFIX="[Blue Network game v1]\n";
 final String game,ip,author;final int port;final long updated;final boolean replacement;
 private GameCard(String g,String i,int p,String a,long t,boolean r){game=g;ip=i;port=p;author=a;updated=t;replacement=r;}
 String address(){return ip+":"+port;}
 static String field(String s,int max){if(s==null||s.isEmpty()||!s.equals(s.trim())||s.length()>max||s.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE))throw new IllegalArgumentException("card_field");return s;}
 static String ip(String value)throws Exception{byte[] bytes=NetworkProbe.address(value);if((bytes[0]&255)!=26)throw new IllegalArgumentException("card_ip");String canonical=(bytes[0]&255)+"."+(bytes[1]&255)+"."+(bytes[2]&255)+"."+(bytes[3]&255);if(!canonical.equals(value))throw new IllegalArgumentException("card_ip");return value;}
 static GameCard create(String game,String ip,int port,String author,long time,boolean replacement)throws Exception{field(game,48);field(author,48);ip(ip);if(port<1||port>65535||time<1577836800000L||time>System.currentTimeMillis()+300000L)throw new IllegalArgumentException("card_values");GameCard card=new GameCard(game,ip,port,author,time,replacement);String text=card.text();ChatEngine.clean(text);JSONObject packet=new JSONObject().put("v",1).put("id","00000000-0000-0000-0000-000000000000").put("text",text);if(packet.toString().getBytes(StandardCharsets.UTF_8).length>ChatWire.MAX)throw new IllegalArgumentException("card_size");return card;}
 String text(){return PREFIX+"Game: "+game+"\nAddress: "+address()+"\nHost: "+author+"\nUpdated: "+updated+"\n"+(replacement?"Replaces: previous card":"Host supplied; availability not checked");}
 static GameCard parse(String text){try{if(text==null||!text.startsWith(PREFIX))return null;String[] l=text.split("\n",-1);if(l.length!=6||!l[1].startsWith("Game: ")||!l[2].startsWith("Address: ")||!l[3].startsWith("Host: ")||!l[4].startsWith("Updated: ")||!(l[5].equals("Replaces: previous card")||l[5].equals("Host supplied; availability not checked")))return null;String address=l[2].substring(9);int colon=address.indexOf(':');String port=address.substring(colon+1);if(!port.matches("[1-9][0-9]{0,4}"))return null;return create(l[1].substring(6),address.substring(0,colon),Integer.parseInt(port),l[3].substring(6),timestamp(l[4].substring(9)),l[5].startsWith("Replaces:"));}catch(Exception e){return null;}}
 static long timestamp(String value){if(!value.matches("[0-9]{13}"))throw new IllegalArgumentException("card_time");return Long.parseLong(value);}
 static boolean allowed(String text,String sender){if(!text.startsWith(PREFIX))return true;GameCard c=parse(text);return c!=null&&c.ip.equals(sender);}
}
