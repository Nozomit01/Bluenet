package ru.bluenet.vpn;
import java.util.Locale;
final class ErrorCatalog {
 static String code(String raw){String s=raw==null?"":raw.toLowerCase(Locale.ROOT);
  if(s.contains("vless_profile"))return "vless_profile";if(s.contains("vless"))return "vless_engine";
  if(s.contains("профил")||s.contains("profile")||s.contains("keystore"))return "profile";
  if(s.contains("permission")||s.contains("разреш")||s.contains("android не"))return "permission";
  if(s.contains("password")||s.contains("парол")||s.contains("proof")||s.contains("authentication"))return "auth";
  if(s.contains("timeout")||s.contains("timed out"))return "timeout";
  if(s.contains("queue")||s.contains("очеред"))return "queue";
  if(s.contains("connect")||s.contains("network")||s.contains("socket")||s.contains("broken pipe")||s.contains("eof"))return "network";
  return "engine";
 }
 static int text(String code){return switch(code){case "profile"->R.string.error_profile;case "permission"->R.string.error_permission;case "auth"->R.string.error_auth;case "timeout"->R.string.error_timeout;case "queue"->R.string.error_queue;case "network"->R.string.error_network;case "vless_profile"->R.string.error_vless_profile;case "vless_engine"->R.string.error_vless_engine;case "vless_network"->R.string.error_vless_network;case "connected"->R.string.event_connected;case "disconnected"->R.string.event_disconnected;case "reconnecting"->R.string.event_reconnecting;case "subscription_failed"->R.string.subscription_failed;case "server_switched"->R.string.server_switched;case "tool_failed"->R.string.tool_failed;default->R.string.error_engine;};}
}
