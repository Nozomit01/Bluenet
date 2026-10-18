package ru.bluenet.vpn;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
final class EasyUi {
 static String t(Context c,String ru,String en){return c.getSharedPreferences("settings",0).getString("language","ru").equals("en")?en:ru;}
 static int dp(Context c,int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
 static boolean dark(Context c){String s=c.getSharedPreferences("settings",0).getString("theme","system");return s.equals("dark")||s.equals("system")&&(c.getResources().getConfiguration().uiMode&48)==32;}
 static int ink(Context c){return dark(c)?0xffe7efff:0xff152c52;}static int bg(Context c){return dark(c)?0xff152238:0xfff3f7ff;}
 static GradientDrawable shape(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
 static LinearLayout col(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
 static TextView text(Context c,String s,int size){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(ink(c));t.setPadding(dp(c,4),dp(c,6),dp(c,4),dp(c,6));return t;}
 static Button button(Context c,String s,Runnable action){Button b=new Button(c);b.setText(s);b.setTextSize(14);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setGravity(android.view.Gravity.CENTER);b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));b.setIncludeFontPadding(false);b.setStateListAnimator(null);b.setElevation(0);b.setTranslationZ(0);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),shape(c,0xff246bfd,14),null));b.setOnClickListener(v->action.run());return b;}
 static Button secondary(Context c,String s,Runnable action){Button b=button(c,s,action);b.setTextColor(dark(c)?0xff9cbfff:0xff246bfd);GradientDrawable background=shape(c,dark(c)?0xff203451:0xffe8f0ff,14);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22246bfd),background,null));return b;}
 static Button icon(Context c,String glyph,String description,Runnable action){Button b=button(c,glyph,action);b.setContentDescription(description);b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,22);b.setMaxLines(1);b.setHorizontallyScrolling(false);b.setPadding(0,0,0,0);return b;}
 static LinearLayout.LayoutParams actionParams(Context c){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,8);return p;}
 static void insets(android.view.View root){root.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());top=bars.top;bottom=Math.max(bars.bottom,insets.getInsets(android.view.WindowInsets.Type.ime()).bottom);}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(dp(v.getContext(),16),top+dp(v.getContext(),8),dp(v.getContext(),16),bottom+dp(v.getContext(),8));return insets;});}
 static void toast(Context c,String ru,String en){Toast.makeText(c,t(c,ru,en),Toast.LENGTH_LONG).show();}
 static void copyIp(Context c){try{String ip=new org.json.JSONObject(NativeBridge.status()).getJSONObject("snapshot").getString("vip");NetworkProbe.address(ip);((ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("VPN IP",ip));toast(c,"IP скопирован","IP copied");}catch(Exception e){toast(c,"Сначала подключите сеть","Connect first");}}
}
