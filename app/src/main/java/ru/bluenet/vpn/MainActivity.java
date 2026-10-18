package ru.bluenet.vpn;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Native Android UI; network state always comes from the protocol engine. */
public class MainActivity extends Activity {
    private int BLUE, INK, MUTED, PALE, SURFACE, BORDER, HIGHLIGHT, FIELD, TAB;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout root,header,body,list,nav,connectionCard,trafficCard;
    private ScrollView mainScroll;
    private String peerQuery="";private boolean pendingJoin,bubblePermission;private EditText peerSearch;
    private TextView stateTitle,stateSubtitle,ipLabel,received,sent,count,title,tabCaption;
    private Button connect,join,create;
    private TextView[] tabs=new TextView[4];
    private JSONObject status=new JSONObject(), snapshot=new JSONObject();
    private String phase="idle",lastRows="",selectedNetwork="";
    private long lastOperation,lastPing,lastGeneration;
    private int tab;
    private boolean foreground;
    private String pendingDiagnostic;
    private boolean pendingCreate;private final Set<Long> userPings=new HashSet<>();private String[] pendingInvitation;
    private final Runnable poll=new Runnable(){public void run(){if(!foreground)return;refresh();handler.postDelayed(this,500);}};

    private String tr(int id){return getString(id);}
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(UiPreferences.context(base));}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        boolean dark=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        BLUE=dark?0xff79a8ff:0xff246bfd;INK=dark?0xffe7efff:0xff152c52;MUTED=dark?0xff9eb1cf:0xff7085a4;PALE=dark?0xff0b1424:0xfff3f7ff;
        SURFACE=dark?0xff152238:Color.WHITE;BORDER=dark?0xff2c4161:0xffe4edfd;HIGHLIGHT=dark?0xff223a5e:0xffe7f0ff;FIELD=dark?0xff1c2d47:0xfff2f7ff;TAB=dark?0xff243e64:0xffe5efff;
        if(saved!=null){pendingJoin=saved.getBoolean("join");bubblePermission=saved.getBoolean("bubble_permission");pendingCreate=saved.getBoolean("create");pendingDiagnostic=saved.getString("diagnostic");tab=saved.getInt("tab");selectedNetwork=saved.getString("network","");}
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(SURFACE);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);}
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|(dark?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        build();
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);}
    private void handleIntent(Intent intent){
        try{phase=new JSONObject(NativeBridge.status()).optString("phase","idle");}catch(Exception ignored){}
        if(intent.getData()!=null)try{phase=new JSONObject(NativeBridge.status()).optString("phase","idle");pendingInvitation=Invitation.parse(intent.getData().toString());getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);intent.setData(null);if(phase.equals("connected"))confirmInvitation();else if(phase.equals("connecting")||BlueVpnService.running())toast(tr(R.string.invitation_wait));else new AlertDialog.Builder(this).setTitle(tr(R.string.invitation_title)).setMessage(tr(R.string.invitation_connect)).setNegativeButton(tr(R.string.ui_53),(d,w)->{pendingInvitation=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}).setPositiveButton(tr(R.string.ui_15),(d,w)->{if(!phase.equals("connecting"))toggleVpn();}).show();}catch(Exception invalid){toast(tr(R.string.invitation_invalid));}
        if(intent.getBooleanExtra("open_chat",false)){intent.removeExtra("open_chat");startActivity(new Intent(this,ChatActivity.class));}
        if(getClass()==QuickConnectActivity.class&&intent.getBooleanExtra("tile_connect",false)){intent.removeExtra("tile_connect");if(!BlueVpnService.running()&&!phase.equals("stopping"))toggleVpn();}
    }
    private void confirmInvitation(){String[] invite=pendingInvitation;pendingInvitation=null;if(invite==null)return;
        new AlertDialog.Builder(this).setTitle(tr(R.string.invitation_title)).setMessage(invite[0]).setNegativeButton(tr(R.string.ui_53),null).setPositiveButton(tr(R.string.ui_69),(d,w)->sendAction("join",invite[0],invite[1].isEmpty()?null:invite[1],0)).setOnDismissListener(d->{java.util.Arrays.fill(invite,"");getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}).show();
    }
    @Override public void onResume(){super.onResume();foreground=true;if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("stay_awake",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(bubblePermission){bubblePermission=false;if(android.provider.Settings.canDrawOverlays(this)){startForegroundService(new Intent(this,QuickBubbleService.class));handler.postDelayed(this::refreshSettings,350);}}if(tab==2)refreshSettings();handler.removeCallbacks(poll);handler.post(poll);}
    @Override public void onPause(){foreground=false;handler.removeCallbacks(poll);super.onPause();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle out){out.putBoolean("join",pendingJoin);out.putBoolean("bubble_permission",bubblePermission);out.putBoolean("create",pendingCreate);out.putString("diagnostic",pendingDiagnostic);out.putInt("tab",tab);out.putString("network",selectedNetwork);super.onSaveInstanceState(out);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");if(bold)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable outline(int color,int radius,int stroke){GradientDrawable d=shape(color,radius);d.setStroke(dp(1),stroke);return d;}
    private Button button(String label,boolean filled){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(filled?Color.WHITE:BLUE);b.setBackground(filled?shape(0xff246bfd,16):outline(SURFACE,16,BORDER));b.setMinHeight(dp(50));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setStateListAnimator(null);b.setElevation(0);b.setTranslationZ(0);b.setMinWidth(0);b.setMinimumWidth(0);b.setIncludeFontPadding(false);return b;}
    private void space(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(16),dp(16),dp(16),dp(16));v.setBackground(outline(SURFACE,22,BORDER));return v;}
    private void addCard(LinearLayout c){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);body.addView(c,p);}
    private void build(){
        root=column();root.setBackgroundColor(PALE);setContentView(root);
        header=column();GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(19,72,187),Color.rgb(50,126,255)});header.setBackground(gradient);header.setPadding(dp(16),dp(8),dp(16),dp(8));root.addView(header);
        LinearLayout brand=row();TextView logo=text("◈",24,Color.WHITE,true);logo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,24);logo.setContentDescription(tr(R.string.ui_0));brand.addView(logo,new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout names=column();names.addView(text(tr(R.string.ui_1),16,Color.WHITE,true));TextView sub=text(tr(R.string.ui_2),10,Color.rgb(209,226,255),false);names.addView(sub);brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        TextView info=text("i",20,Color.WHITE,true);info.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,20);info.setGravity(Gravity.CENTER);info.setBackground(shape(Color.argb(28,255,255,255),24));info.setContentDescription(tr(R.string.ui_3));info.setOnClickListener(v->about());brand.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48)));header.addView(brand);
        title=text(tab==1?tr(R.string.ui_4):tab==2?tr(R.string.ui_5):EasyUi.t(this,"Моя сеть","My network"),20,Color.WHITE,true);header.addView(title);
        mainScroll=new ScrollView(this);mainScroll.setFillViewport(true);mainScroll.setClipToPadding(false);root.addView(mainScroll,new LinearLayout.LayoutParams(-1,0,1));
        body=column();body.setPadding(dp(16),dp(16),dp(16),dp(12));mainScroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        buildConnection();buildTraffic();
        tabCaption=text(tr(R.string.ui_8),11,MUTED,true);tabCaption.setLetterSpacing(.12f);body.addView(tabCaption);space(body,12);
        list=column();body.addView(list);
        nav=row();nav.setBackgroundColor(SURFACE);nav.setPadding(dp(6),dp(8),dp(6),dp(10));root.addView(nav);
        String[] labels={tr(R.string.ui_9),tr(R.string.ui_10),tr(R.string.ui_5),EasyUi.t(this,"Чат","Chat")};int[] icons={R.drawable.nav_networks,R.drawable.nav_devices,R.drawable.nav_settings,R.drawable.nav_chat};
        for(int i=0;i<4;i++){final int index=i;TextView item=text(labels[i],12,i==tab?BLUE:MUTED,true);tabs[i]=item;item.setContentDescription(labels[i]);item.setGravity(Gravity.CENTER);item.setSingleLine();item.setHorizontallyScrolling(false);item.setEllipsize(TextUtils.TruncateAt.END);item.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);item.setPadding(dp(2),dp(6),dp(2),dp(6));item.setMinHeight(dp(60));item.setCompoundDrawablesWithIntrinsicBounds(0,icons[i],0,0);item.setCompoundDrawablePadding(dp(4));item.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(i==tab?BLUE:MUTED));item.setBackground(shape(i==tab?TAB:SURFACE,14));item.setSelected(i==tab);item.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View view,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(view,info);info.setClassName(Button.class.getName());}});item.setOnClickListener(v->{if(index==3)startActivity(new Intent(this,ChatActivity.class));else selectTab(index);});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(2);p.rightMargin=dp(2);nav.addView(item,p);}

        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;WindowInsetsController controller=v.getWindowInsetsController();if(controller!=null)controller.setSystemBarsAppearance(((getResources().getConfiguration().uiMode&48)==32?0:WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS),WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS|WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}
            header.setPadding(dp(16),dp(6)+top,dp(16),dp(8));nav.setPadding(dp(6),dp(8),dp(6),dp(10)+bottom);return insets;
        });root.requestApplyInsets();renderRows();
    }
    private void buildConnection(){
        LinearLayout c=card(),line=row();connectionCard=c;TextView shield=text("◇",28,BLUE,true);shield.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,28);shield.setGravity(Gravity.CENTER);shield.setBackground(shape(HIGHLIGHT,14));shield.setContentDescription(tr(R.string.ui_11));line.addView(shield,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout labels=column();labels.setPadding(dp(10),0,0,0);stateTitle=text(tr(R.string.ui_12),18,INK,true);stateSubtitle=text(tr(R.string.ui_13),12,MUTED,false);stateSubtitle.setPadding(0,dp(4),0,0);labels.addView(stateTitle);labels.addView(stateSubtitle);line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));c.addView(line);space(c,12);
        ipLabel=text(tr(R.string.ui_14),13,BLUE,true);ipLabel.setPadding(dp(12),dp(8),dp(12),dp(8));ipLabel.setMinHeight(dp(48));ipLabel.setGravity(Gravity.CENTER_VERTICAL);ipLabel.setBackground(shape(FIELD,11));ipLabel.setTypeface(Typeface.create("monospace",Typeface.NORMAL));ipLabel.setOnClickListener(v->{String ip=snapshot.optString("vip","");if(!ip.isEmpty()&&!ip.equals("null"))copy(ip);});c.addView(ipLabel);space(c,10);
        connect=button(tr(R.string.ui_15),true);connect.setOnClickListener(v->toggleVpn());c.addView(connect,new LinearLayout.LayoutParams(-1,-2));addCard(c);
    }
    private void buildTraffic(){
        LinearLayout c=card(),metrics=row();trafficCard=c;String[] labels={tr(R.string.ui_16),tr(R.string.ui_17),tr(R.string.ui_18)};TextView[] values=new TextView[3];
        for(int i=0;i<3;i++){LinearLayout m=column();TextView label=text(labels[i],9,MUTED,true);label.setLetterSpacing(.06f);m.addView(label);values[i]=text(i==2?"0":tr(R.string.ui_19),17,INK,true);values[i].setPadding(0,dp(6),0,0);m.addView(values[i]);metrics.addView(m,new LinearLayout.LayoutParams(0,-2,1));}
        received=values[0];sent=values[1];count=values[2];c.addView(metrics);addCard(c);
    }
    private void selectTab(int value){tab=value;selectedNetwork="";lastRows="";title.setText(tab==1?tr(R.string.ui_4):tab==2?tr(R.string.ui_5):EasyUi.t(this,"Моя сеть","My network"));for(int i=0;i<4;i++){tabs[i].setBackground(shape(i==tab?TAB:SURFACE,14));tabs[i].setTextColor(i==tab?BLUE:MUTED);tabs[i].setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(i==tab?BLUE:MUTED));tabs[i].setSelected(i==tab);}renderRows();mainScroll.scrollTo(0,0);}
    private void refresh(){
        try{
            String raw=NativeBridge.status();if(raw==null)return;status=new JSONObject(raw);long generation=status.optLong("generation");if(generation!=lastGeneration){lastGeneration=generation;lastOperation=lastPing=0;userPings.clear();lastRows="";}phase=status.optString("phase","idle");snapshot=status.optJSONObject("snapshot");if(snapshot==null)snapshot=new JSONObject();
            QualityMonitor.observe(status);if(phase.equals("connected")&&pendingInvitation!=null)confirmInvitation();if(phase.equals("connected")&&pendingJoin){pendingJoin=false;networkDialog(false);}if(phase.equals("connected")&&pendingCreate){pendingCreate=false;networkDialog(true);}
            boolean retry=BlueVpnService.isRetryPending();boolean live=phase.equals("connected"),busy=phase.equals("connecting")||retry,stopping=phase.equals("stopping");
            String issue=BlueVpnService.issue();
            stateTitle.setText(live?tr(R.string.ui_20):busy?tr(R.string.ui_21):stopping?tr(R.string.ui_22):(phase.equals("error")||!issue.isEmpty())?tr(R.string.ui_23):tr(R.string.ui_12));
            stateTitle.setTextColor(live?BLUE:INK);
            JSONArray nets=snapshot.optJSONArray("networks");int n=nets==null?0:nets.length();
            stateSubtitle.setText(live?(n==0?tr(R.string.ui_24):tr(R.string.ui_25)+n):busy?(retry?tr(R.string.ui_26):tr(R.string.ui_27)):!issue.isEmpty()?tr(ErrorCatalog.text(issue)):phase.equals("error")?translateError(status.optString("error")):tr(R.string.ui_28));
            connect.setText(live||BlueVpnService.blocked()?tr(R.string.ui_29):busy?tr(R.string.ui_30):stopping?tr(R.string.ui_22):tr(R.string.ui_15));connect.setEnabled(!stopping);connect.setAlpha(stopping?.6f:1);
            if(BlueVpnService.blocked()&&!live&&!busy){stateTitle.setText(tr(R.string.traffic_blocked));stateSubtitle.setText(tr(R.string.kill_switch_hint));}
            String vip=snapshot.optString("vip","");boolean hasIp=!vip.isEmpty()&&!vip.equals("null");ipLabel.setText(tr(R.string.ui_31)+(hasIp?vip:"—"));ipLabel.setVisibility(hasIp?View.VISIBLE:View.GONE);
            JSONObject traffic=snapshot.optJSONObject("traffic");received.setText(bytes(traffic==null?0:traffic.optLong("received_bytes")));sent.setText(bytes(traffic==null?0:traffic.optLong("sent_bytes")));
            JSONObject peers=snapshot.optJSONObject("peers");int online=0;if(peers!=null){Iterator<String> keys=peers.keys();while(keys.hasNext()){JSONObject p=peers.optJSONObject(keys.next());if(p!=null&&p.optString("status").equals("Connected"))online++;}}count.setText(Integer.toString(online));
            String signature=tab+"/"+live+"/"+selectedNetwork+"/"+(nets==null?"":nets.toString())+"/"+(peers==null?"":peers.toString())+"/"+status.optJSONObject("ping");if(!signature.equals(lastRows)&&tab!=2&&!(tab==1&&peerSearch!=null&&peerSearch.hasFocus())){lastRows=signature;renderRows();}
            if(join!=null){join.setEnabled(!stopping);join.setAlpha(stopping?.5f:1);}if(create!=null){create.setEnabled(!stopping);create.setAlpha(stopping?.5f:1);}
            JSONObject op=status.optJSONObject("operation");if(op!=null&&op.optLong("id")>0&&op.optLong("id")!=lastOperation){lastOperation=op.optLong("id");String message=op.optBoolean("uncertain")?tr(R.string.ui_32):translateOperation(op.optString("message"),op.optBoolean("error"));if(op.optBoolean("error"))new AlertDialog.Builder(this).setTitle(tr(R.string.ui_33)).setMessage(message).setPositiveButton(tr(R.string.ui_34),null).show();else toast(message);}
            JSONObject ping=status.optJSONObject("ping");if(ping!=null&&ping.optLong("id")>0&&ping.optLong("id")!=lastPing){lastPing=ping.optLong("id");if(userPings.remove(lastPing)){if(ping.has("rtt_ms")&&!ping.isNull("rtt_ms"))toast(tr(R.string.ui_35)+String.format(getResources().getConfiguration().getLocales().get(0),tr(R.string.ui_36),ping.optDouble("rtt_ms")));else toast(tr(R.string.ui_37));}}
        }catch(Throwable error){stateTitle.setText(tr(R.string.ui_38));stateSubtitle.setText(tr(R.string.ui_39));}
    }
    private void renderRows(){
        list.removeAllViews();join=create=null;connectionCard.setVisibility(tab==0?View.VISIBLE:View.GONE);trafficCard.setVisibility(View.GONE);
        if(tab==2){tabCaption.setText(tr(R.string.ui_40));settings();return;}
        if(tab==1){tabCaption.setText(selectedNetwork.isEmpty()?tr(R.string.ui_41):tr(R.string.ui_42));renderPeers();return;}
        tabCaption.setText(EasyUi.t(this,phase.equals("connected")?"МОИ СЕТИ":"ПОДКЛЮЧИТЕСЬ, ЗАТЕМ ВЫБЕРИТЕ СЕТЬ",phase.equals("connected")?"MY NETWORKS":"CONNECT, THEN CHOOSE A NETWORK"));
        LinearLayout actions=row();
        join=button(EasyUi.t(this,"Войти в сеть","Join network"),true);join.setOnClickListener(v->{if(phase.equals("connected"))networkDialog(false);else{pendingJoin=true;pendingCreate=false;if(!phase.equals("connecting"))toggleVpn();}});actions.addView(join,new LinearLayout.LayoutParams(0,-2,1));join.setEnabled(!phase.equals("stopping"));
        create=button(tr(R.string.ui_46),false);create.setOnClickListener(v->{if(phase.equals("connected"))networkDialog(true);else{pendingCreate=true;pendingJoin=false;if(!phase.equals("connecting"))toggleVpn();}});LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(0,-2,1);actionParams.leftMargin=dp(8);actions.addView(create,actionParams);list.addView(actions);space(list,12);
        JSONArray networks=snapshot.optJSONArray("networks");
        if(networks==null||networks.length()==0){LinearLayout empty=card();TextView symbol=text("＋",32,BLUE,false);symbol.setGravity(Gravity.CENTER);empty.addView(symbol);TextView label=text(tr(R.string.ui_43),17,INK,true);label.setGravity(Gravity.CENTER);empty.addView(label);TextView hint=text(tr(R.string.ui_44),13,MUTED,false);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(8),0,0);empty.addView(hint);list.addView(empty);space(list,12);}
        else for(int i=0;i<Math.min(networks.length(),200);i++){JSONObject network=networks.optJSONObject(i);if(network!=null)networkRow(network);}
        Button quick=button(EasyUi.t(this,"Кружок поверх игры","Circle over the game"),false);quick.setOnClickListener(v->enableBubble());list.addView(quick);space(list,8);
        TextView hint=text(tr(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("vless",false)?R.string.vless_hint:R.string.ui_47),11,MUTED,false);hint.setGravity(Gravity.CENTER);space(list,12);list.addView(hint);

    }
    private void networkRow(JSONObject network){
        String name=network.optString("name"),id=network.optString("network_id");LinearLayout c=card(),line=row();TextView icon=text("◈",25,BLUE,true);icon.setGravity(Gravity.CENTER);icon.setBackground(shape(HIGHLIGHT,12));line.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout labels=column();labels.setPadding(dp(12),0,0,0);TextView label=text(name,16,INK,true);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);labels.addView(label);labels.addView(text(tr(R.string.ui_48)+peersIn(id),11,MUTED,false));line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView more=text("⋮",24,MUTED,false);more.setGravity(Gravity.CENTER);more.setContentDescription(tr(R.string.ui_49)+name);line.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));c.addView(line);
        View.OnClickListener show=v->{tab=1;selectedNetwork=id;title.setText(name);lastRows="";for(int k=0;k<4;k++){tabs[k].setTextColor(k==1?BLUE:MUTED);tabs[k].setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(k==1?BLUE:MUTED));tabs[k].setSelected(k==1);tabs[k].setBackground(shape(k==1?TAB:SURFACE,14));}renderRows();};c.setOnClickListener(show);more.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{tr(R.string.ui_50),tr(R.string.invitation_title),tr(R.string.ui_51)},(d,which)->{if(which==0)show.onClick(c);else if(which==1)startActivity(new Intent(this,FeatureActivity.class).putExtra("mode","invite").putExtra("network_name",name));else new AlertDialog.Builder(this).setTitle(tr(R.string.ui_52)).setMessage(name).setNegativeButton(tr(R.string.ui_53),null).setPositiveButton(tr(R.string.ui_54),(x,w)->sendAction("leave",id,null,0)).show();}).show());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);list.addView(c,p);
    }
    private int peersIn(String id){int count=0;JSONObject peers=snapshot.optJSONObject("peers");if(peers!=null){Iterator<String> keys=peers.keys();while(keys.hasNext()){JSONObject v=peers.optJSONObject(keys.next());JSONObject p=v==null?null:v.optJSONObject("peer");if(p!=null&&contains(p.optJSONArray("network_ids"),id))count++;}}return count;}
    private boolean contains(JSONArray a,String s){if(a==null)return false;for(int i=0;i<a.length();i++)if(s.equals(a.optString(i)))return true;return false;}
    private void renderPeers(){
        if(peerSearch==null){peerSearch=field(EasyUi.t(this,"Поиск: имя или IP","Search: name or IP"),false);peerSearch.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){peerQuery=s.toString().toLowerCase(Locale.ROOT);for(int i=0;i<list.getChildCount();i++){View v=list.getChildAt(i);if(v.getTag() instanceof String)v.setVisibility(((String)v.getTag()).contains(peerQuery)?View.VISIBLE:View.GONE);}}public void afterTextChanged(android.text.Editable e){}});}
        if(peerSearch.getParent()!=null)((android.view.ViewGroup)peerSearch.getParent()).removeView(peerSearch);list.addView(peerSearch);space(list,8);
        Button chat=button(EasyUi.t(this,"Открыть чат","Open chat"),true);chat.setOnClickListener(v->startActivity(new Intent(this,ChatActivity.class)));list.addView(chat);space(list,8);

        TextView stats=text(EasyUi.t(this,"Трафик: ","Traffic: ")+received.getText()+" ↓ · "+sent.getText()+" ↑",12,MUTED,false);list.addView(stats);space(list,8);Button games=button(tr(R.string.games_tools),false);games.setOnClickListener(v->openTools("games"));list.addView(games);space(list,12);
        JSONObject peers=snapshot.optJSONObject("peers");int shown=0;if(peers!=null){Iterator<String> keys=peers.keys();while(keys.hasNext()&&shown<200){JSONObject value=peers.optJSONObject(keys.next());JSONObject peer=value==null?null:value.optJSONObject("peer");if(peer==null||!selectedNetwork.isEmpty()&&!contains(peer.optJSONArray("network_ids"),selectedNetwork))continue;peerRow(peer,value);shown++;}}
        if(shown==0){LinearLayout c=card();c.addView(text(tr(R.string.ui_55),18,INK,true));space(c,7);c.addView(text(tr(R.string.ui_56),13,MUTED,false));list.addView(c);}
        if(shown==200){space(list,8);list.addView(text(tr(R.string.ui_57),12,MUTED,false));}
    }
    private void peerRow(JSONObject peer,JSONObject view){
        String name=peer.optString("name",tr(R.string.ui_58)),vip=peer.optString("vip"),state=view.optString("status"),path=view.optString("transport");long rid=peer.optLong("rid");
        LinearLayout c=card(),line=row();c.setTag((name+" "+vip).toLowerCase(Locale.ROOT));c.setVisibility(((String)c.getTag()).contains(peerQuery)?View.VISIBLE:View.GONE);StringBuilder initials=new StringBuilder();name.codePoints().limit(2).forEach(initials::appendCodePoint);TextView avatar=text(initials.toString().toUpperCase(getResources().getConfiguration().getLocales().get(0)),16,BLUE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(shape(HIGHLIGHT,14));line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout labels=column();labels.setPadding(dp(12),0,0,0);TextView label=text(name,15,INK,true);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);labels.addView(label);TextView ip=text(vip,12,MUTED,false);ip.setTypeface(Typeface.MONOSPACE);labels.addView(ip);line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView dot=text("●",15,state.equals("Connected")?Color.rgb(29,173,127):state.equals("Connecting")?BLUE:Color.rgb(184,197,217),false);dot.setContentDescription(peerState(state));line.addView(dot);
        QualityMonitor.Sample sample=phase.equals("connected")?QualityMonitor.sample(rid):null;
        String quality=sample==null?tr(R.string.signal_unknown):sample.milliseconds<0?tr(R.string.signal_no_reply):String.format(getResources().getConfiguration().getLocales().get(0),tr(R.string.ui_36),sample.milliseconds);
        SignalView signal=new SignalView(this,QualityMonitor.level(sample),BLUE,quality);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(28),dp(22));sp.leftMargin=dp(8);line.addView(signal,sp);signal.setOnClickListener(v->toast(quality+"\n"+tr(R.string.signal_hint)));c.addView(line);space(c,9);c.addView(text(peerState(state)+(state.equals("Connected")?" · "+transport(path):""),11,MUTED,false));
        c.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(name).setMessage(vip+"\n"+peerState(state)+(state.equals("Connected")?"\n"+transport(path):"")+"\n"+QualityMonitor.describe(this,rid)).setNegativeButton(tr(R.string.ui_59),null).setNeutralButton(tr(R.string.ui_60),(d,w)->copy(vip)).setPositiveButton(tr(R.string.ui_61),(d,w)->sendAction("ping","",null,rid)).show());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);list.addView(c,p);
    }
    private EditText field(String hint,boolean password){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setSingleLine();e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(outline(FIELD,12,BORDER));e.setInputType(InputType.TYPE_CLASS_TEXT|(password?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(password?256:255)});if(password){e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);}return e;}
    private void networkDialog(boolean make){
        LinearLayout content=column();content.setPadding(dp(24),dp(12),dp(24),0);content.addView(text(tr(R.string.ui_62),12,MUTED,true));space(content,6);EditText name=field(tr(R.string.ui_63),false);content.addView(name);space(content,14);content.addView(text(tr(R.string.ui_64),12,MUTED,true));space(content,6);EditText password=field(tr(R.string.ui_65),true);content.addView(password);space(content,8);CheckBox open=new CheckBox(this);open.setText(tr(R.string.ui_66));open.setTextColor(INK);if(!make)content.addView(open);open.setOnCheckedChangeListener((b,c)->{password.setEnabled(!c);if(c)password.getText().clear();});space(content,8);content.addView(text(tr(R.string.ui_67),11,MUTED,false));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(make?tr(R.string.ui_46):tr(R.string.ui_45)).setView(content).setNegativeButton(tr(R.string.ui_53),null).setPositiveButton(make?tr(R.string.ui_68):tr(R.string.ui_69),null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String n=name.getText().toString(),p=password.getText().toString();if(n.trim().isEmpty()||n.codePoints().anyMatch(Character::isISOControl)){name.setError(tr(R.string.ui_70));return;}if(!open.isChecked()&&(p.length()<6||p.length()>256||p.codePoints().anyMatch(Character::isISOControl))){password.setError(tr(R.string.ui_71));return;}if(sendAction(make?"create":"join",n,open.isChecked()?null:p,0)){password.getText().clear();dialog.dismiss();toast(make?tr(R.string.ui_72):tr(R.string.ui_73));}}));dialog.setOnDismissListener(d->password.getText().clear());dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private boolean sendAction(String action,String name,String password,long rid){try{JSONObject request=new JSONObject().put("action",action).put("name",name).put("rid",rid);if(password!=null)request.put("password",password);long id=NativeBridge.command(request.toString());if(action.equals("ping")){if(userPings.size()>=64)userPings.clear();userPings.add(id);}return true;}catch(Exception error){EventLog.add(this,ErrorCatalog.code(error.getMessage()));toast(translateError(error.getMessage()));return false;}}
    private void toggleVpn(){if(phase.equals("connected")||phase.equals("connecting")||BlueVpnService.isRetryPending()||BlueVpnService.blocked()){pendingCreate=false;pendingJoin=false;startService(new Intent(this,BlueVpnService.class).setAction(BlueVpnService.STOP));return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},28);Intent permission=VpnService.prepare(this);if(permission!=null)startActivityForResult(permission,26);else startVpn();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==26&&result==RESULT_OK)startVpn();else if(request==26){pendingCreate=false;pendingJoin=false;toast(tr(R.string.ui_74));}
        else if(request==29){
            String report=pendingDiagnostic;pendingDiagnostic=null;
            if(result==RESULT_OK&&data!=null&&data.getData()!=null&&report!=null){
                try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){
                    if(out==null)throw new java.io.IOException();out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));toast(tr(R.string.ui_75));
                }catch(Exception error){toast(tr(R.string.ui_76));}
            }
        }}
    private void startVpn(){try{startForegroundService(new Intent(this,BlueVpnService.class));}catch(Exception error){toast(tr(R.string.ui_77));}}
    private void enableBubble(){
        if(android.provider.Settings.canDrawOverlays(this)){startForegroundService(new Intent(this,QuickBubbleService.class));toast(EasyUi.t(this,"Кружок появился. Нажмите, чтобы открыть; перетащите, чтобы переместить.","Circle enabled. Tap to open; drag to move."));return;}
        new AlertDialog.Builder(this).setTitle(EasyUi.t(this,"Кружок поверх игры","Circle over your game")).setMessage(EasyUi.t(this,"Разрешите показ поверх других приложений. Кружок можно скрыть кнопкой × или через уведомление. Он не читает экран других приложений.","Allow display over other apps. Hide the circle with × or its notification. It does not read other apps’ screens.")).setNegativeButton(android.R.string.cancel,null).setPositiveButton(EasyUi.t(this,"Разрешить","Allow"),(d,w)->{bubblePermission=true;try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception e){bubblePermission=false;toast(EasyUi.t(this,"Откройте разрешение «Поверх других приложений» в настройках Android.","Open Display over other apps in Android settings."));}}).show();
    }
    private void refreshSettings(){if(mainScroll==null||list==null||tab!=2)return;int position=mainScroll.getScrollY();renderRows();mainScroll.post(()->{if(tab==2)mainScroll.scrollTo(0,position);});}
    private void settingsSection(String tag,String ru,String en){TextView heading=text(EasyUi.t(this,ru,en),18,BLUE,true);heading.setTag(tag);if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);p.bottomMargin=dp(12);list.addView(heading,p);}
    private void settings(){
        settingsSection("settings-appearance","Оформление","Appearance");
        Button language=button(tr(R.string.language)+" · "+(getSharedPreferences("settings",MODE_PRIVATE).getString("language","ru").equals("en")?"English":"Русский"),false);language.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(tr(R.string.language)).setSingleChoiceItems(new String[]{"Русский","English"},getSharedPreferences("settings",MODE_PRIVATE).getString("language","ru").equals("en")?1:0,(d,which)->{getSharedPreferences("settings",MODE_PRIVATE).edit().putString("language",which==1?"en":"ru").apply();d.dismiss();recreate();}).setNegativeButton(tr(R.string.ui_53),null).show());list.addView(language,new LinearLayout.LayoutParams(-1,-2));space(list,10);
        String currentTheme=getSharedPreferences("settings",MODE_PRIVATE).getString("theme","system");Button theme=button(tr(R.string.theme)+" · "+tr(currentTheme.equals("dark")?R.string.theme_dark:currentTheme.equals("light")?R.string.theme_light:R.string.theme_system),false);theme.setOnClickListener(v->{String selected=getSharedPreferences("settings",MODE_PRIVATE).getString("theme","system");new AlertDialog.Builder(this).setTitle(tr(R.string.theme)).setSingleChoiceItems(new String[]{tr(R.string.theme_system),tr(R.string.theme_light),tr(R.string.theme_dark)},selected.equals("dark")?2:selected.equals("light")?1:0,(d,which)->{getSharedPreferences("settings",MODE_PRIVATE).edit().putString("theme",new String[]{"system","light","dark"}[which]).apply();d.dismiss();recreate();}).setNegativeButton(tr(R.string.ui_53),null).show();});list.addView(theme,new LinearLayout.LayoutParams(-1,-2));space(list,8);
        settingsSection("settings-game","Во время игры","During game");
        boolean circleOn=QuickBubbleService.active()!=null;Button bubble=button(EasyUi.t(this,circleOn?"Скрыть кружок · включён":"Включить плавающий кружок",circleOn?"Hide circle · on":"Enable floating circle"),!circleOn);bubble.setOnClickListener(v->{if(QuickBubbleService.active()!=null)stopService(new Intent(this,QuickBubbleService.class));else enableBubble();handler.postDelayed(this::refreshSettings,350);});list.addView(bubble);space(list,10);
        Switch awake=new Switch(this);awake.setText(EasyUi.t(this,"Не гасить экран (приложение и кружок)","Keep screen on (app and circle)"));awake.setTextColor(INK);awake.setMinHeight(dp(48));awake.setChecked(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("stay_awake",false));awake.setOnCheckedChangeListener((b,on)->{getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("stay_awake",on).apply();if(on)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);});list.addView(awake);space(list,4);list.addView(text(EasyUi.t(this,"Работает, пока открыто приложение или кружок. Расходует больше батареи.","Applies while the app or circle is visible. Uses more battery."),11,MUTED,false));space(list,8);
        settingsSection("settings-connection","Подключение","Connection");
        vlessSettings();
        Button apps=button(tr(R.string.apps_vless),false);apps.setOnClickListener(v->openTools("apps"));list.addView(apps);space(list,8);
        Button invitations=button(tr(R.string.invitation_title),false);invitations.setOnClickListener(v->openTools("invite"));list.addView(invitations);space(list,8);
        LinearLayout protection=card();switchRow(protection,tr(R.string.kill_switch),tr(R.string.kill_switch_hint),"kill_switch",true);space(protection,10);protection.addView(text(tr(R.string.tile_hint),12,MUTED,false));list.addView(protection);space(list,12);
        SharedPreferences preferences=getSharedPreferences("settings",MODE_PRIVATE);LinearLayout c=card();c.addView(text(tr(R.string.ui_78),16,INK,true));space(c,8);TextView name=text(preferences.getString("device",Build.MODEL),14,BLUE,true);c.addView(name);space(c,6);c.addView(text(tr(R.string.ui_79),12,MUTED,false));c.setOnClickListener(v->{EditText input=field(tr(R.string.ui_78),false);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});input.setText(preferences.getString("device",Build.MODEL));LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),0);box.addView(input);new AlertDialog.Builder(this).setTitle(tr(R.string.ui_78)).setView(box).setNegativeButton(tr(R.string.ui_53),null).setPositiveButton(tr(R.string.ui_80),(d,w)->{String value=input.getText().toString().trim();if(value.isEmpty()||value.codePoints().anyMatch(Character::isISOControl)){toast(tr(R.string.ui_81));return;}preferences.edit().putString("device",value).apply();renderRows();toast(tr(R.string.ui_82));}).show();});list.addView(c);space(list,12);
        LinearLayout options=card();switchRow(options,tr(R.string.ui_83),tr(R.string.ui_84),"discovery",true);space(options,16);switchRow(options,tr(R.string.ui_85),tr(R.string.ui_86),"relay",false);space(options,16);switchRow(options,tr(R.string.ui_87),tr(R.string.ui_88),"reconnect",true);space(options,10);options.addView(text(tr(R.string.ui_89),11,MUTED,false));list.addView(options);space(list,12);
        settingsSection("settings-checks","Проверки и помощь","Checks and help");
        Button check=button(tr(R.string.diagnostics),true);check.setOnClickListener(v->showDiagnostics());list.addView(check,new LinearLayout.LayoutParams(-1,-2));space(list,10);
        Button log=button(tr(R.string.error_log),false);log.setOnClickListener(v->showLog());list.addView(log,new LinearLayout.LayoutParams(-1,-2));space(list,10);
        Button diagnostics=button(tr(R.string.ui_90),false);diagnostics.setOnClickListener(v->exportDiagnostic());list.addView(diagnostics,new LinearLayout.LayoutParams(-1,-2));space(list,8);list.addView(text(tr(R.string.ui_91),11,MUTED,false));space(list,12);
        Button updates=button(EasyUi.t(this,"История изменений · ","Release notes · ")+BuildConfig.VERSION_NAME,false);updates.setOnClickListener(v->startActivity(new Intent(this,ReleaseNotesActivity.class)));list.addView(updates);space(list,12);
        LinearLayout info=card();info.addView(text(tr(R.string.ui_92)+BuildConfig.VERSION_NAME,16,INK,true));space(info,6);info.addView(text(tr(R.string.ui_93),12,MUTED,false));info.setOnClickListener(v->about());list.addView(info);
    }
    private void switchRow(LinearLayout parent,String label,String hint,String key,boolean fallback){LinearLayout line=row(),labels=column();labels.addView(text(label,15,INK,true));TextView subtitle=text(hint,11,MUTED,false);subtitle.setPadding(0,dp(5),dp(6),0);labels.addView(subtitle);line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));Switch s=new Switch(this);s.setContentDescription(label);s.setChecked(getSharedPreferences("settings",MODE_PRIVATE).getBoolean(key,fallback));s.setOnCheckedChangeListener((b,on)->{if(liveOrBusy()&&!key.equals("reconnect")){s.setOnCheckedChangeListener(null);s.setChecked(!on);toast(tr(R.string.vless_needs_stop));renderRows();return;}getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean(key,on).apply();});line.addView(s);parent.addView(line);}
    private void vlessSettings(){
        LinearLayout c=card();switchRow(c,tr(R.string.vless_mode),tr(R.string.vless_hint),"vless",false);space(c,12);
        boolean exists=new java.io.File(getFilesDir(),"vless.enc").exists()||new java.io.File(getFilesDir(),"servers.enc").exists();String summary=tr(R.string.vless_profile_none);if(exists)try{int total=ServerCatalog.read(this).getJSONArray("servers").length();summary=EasyUi.t(this,"Серверов сохранено: ","Saved servers: ")+total;}catch(Exception e){summary=EasyUi.t(this,"Не удалось прочитать профили. Данные сохранены.","Cannot read profiles. Your data has been retained.");}TextView profiles=text(summary,12,MUTED,false);profiles.setTag("settings-server-summary");c.addView(profiles);space(c,10);
        Button load=button(tr(R.string.server_tools),false);load.setOnClickListener(v->openTools("servers"));c.addView(load);space(c,8);
        if(exists){Button remove=button(tr(R.string.vless_delete),false);remove.setOnClickListener(v->openTools("servers"));c.addView(remove);}
        list.addView(c);space(list,12);
    }
    private void openTools(String mode){startActivity(new Intent(this,FeatureActivity.class).putExtra("mode",mode));}
    private boolean liveOrBusy(){return BlueVpnService.running()||phase.equals("connected")||phase.equals("connecting")||phase.equals("stopping")||BlueVpnService.isRetryPending();}
    private JSONObject connectivityReport()throws Exception{
        android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);JSONObject result=new JSONObject();boolean internet=false,validated=false,vpn=false,wifi=false,cell=false;
        for(android.net.Network n:cm.getAllNetworks()){android.net.NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c==null)continue;
            vpn|=c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
            if(c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)){internet|=c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);validated|=c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);wifi|=c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);cell|=c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);}
        }
        return result.put("network_available",internet).put("internet_validated",validated).put("vpn_present",vpn).put("wifi",wifi).put("cellular",cell).put("vless_active",BlueVpnService.proxyActive()).put("vless_enabled",getSharedPreferences("settings",MODE_PRIVATE).getBoolean("vless",false));
    }
    private void showDiagnostics(){
        try{JSONObject report=new JSONObject(DiagnosticReport.create(new JSONObject(NativeBridge.status()),BuildConfig.VERSION_CODE,BuildConfig.VERSION_NAME)),net=connectivityReport();
            StringBuilder message=new StringBuilder(tr(net.optBoolean("internet_validated")?R.string.internet_ok:net.optBoolean("network_available")?R.string.internet_unverified:R.string.internet_absent));
            message.append("\n\n").append(getString(R.string.diag_profile,tr(new java.io.File(getFilesDir(),"identity.enc").exists()?R.string.present:R.string.missing)));
            message.append("\n\n").append(getString(R.string.diag_summary,report.optInt("network_count"),report.optInt("connected_peer_count"),report.optInt("direct_tcp_count"),report.optInt("direct_udp_count"),report.optInt("relay_count")));
            message.append("\n\n").append(tr(R.string.signal_hint));
            JSONObject quality=report.optJSONObject("quality");if(quality!=null&&quality.has("average_rtt_ms"))message.append("\n").append(tr(R.string.ui_35)).append(String.format(getResources().getConfiguration().getLocales().get(0),tr(R.string.ui_36),quality.optDouble("average_rtt_ms")));
            message.append("\n\n").append(QualityMonitor.describe(this,0));
            if(net.optBoolean("vpn_present")&&!phase.equals("connected"))message.append("\n\n").append(tr(R.string.other_vpn));
            new AlertDialog.Builder(this).setTitle(tr(R.string.diagnostics)).setMessage(message.toString()).setNegativeButton(tr(R.string.ui_59),null).setNeutralButton(tr(R.string.error_log),(d,w)->showLog()).setPositiveButton(tr(R.string.run_tests),(d,w)->testPeers()).show();
        }catch(Exception error){toast(tr(R.string.ui_94));}
    }
    private void testPeers(){
        if(!phase.equals("connected")){toast(tr(R.string.error_connect_first));return;}JSONObject peers=snapshot.optJSONObject("peers");ArrayList<Long> ids=new ArrayList<>();if(peers!=null){Iterator<String> it=peers.keys();while(it.hasNext()&&ids.size()<8){String key=it.next();JSONObject peer=peers.optJSONObject(key);if(peer!=null&&peer.optString("status").equals("Connected"))try{ids.add(Long.parseLong(key));}catch(NumberFormatException ignored){}}}
        if(ids.isEmpty()){toast(tr(R.string.diag_no_peers));return;}for(int i=0;i<ids.size();i++){long rid=ids.get(i);handler.postDelayed(()->{if(phase.equals("connected"))sendAction("ping","",null,rid);},i*400L);}toast(tr(R.string.diag_testing));
    }
    private void showLog(){
        JSONArray entries=EventLog.read(this);StringBuilder message=new StringBuilder();java.text.DateFormat time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM,getResources().getConfiguration().getLocales().get(0));
        for(int i=entries.length()-1;i>=0;i--){JSONObject row=entries.optJSONObject(i);if(row!=null)message.append(time.format(new Date(row.optLong("time")))).append(" · ").append(tr(ErrorCatalog.text(row.optString("code")))).append("\n\n");}
        new AlertDialog.Builder(this).setTitle(tr(R.string.error_log)).setMessage(message.length()==0?tr(R.string.log_empty):message.toString()).setNegativeButton(tr(R.string.ui_59),null).setNeutralButton(tr(R.string.ui_90),(d,w)->exportDiagnostic()).setPositiveButton(tr(R.string.clear_log),(d,w)->EventLog.clear(this)).show();
    }
    private void exportDiagnostic(){
        try{JSONObject report=new JSONObject(DiagnosticReport.create(new JSONObject(NativeBridge.status()),BuildConfig.VERSION_CODE,BuildConfig.VERSION_NAME));report.put("events",EventLog.read(this));report.put("connectivity",connectivityReport());pendingDiagnostic=report.toString(2);
            Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"BlueNetwork-diagnostics.json");startActivityForResult(save,29);
        }catch(Exception error){pendingDiagnostic=null;toast(tr(R.string.ui_94));}
    }
    private void about(){new AlertDialog.Builder(this).setTitle(tr(R.string.ui_95)).setMessage(getString(R.string.about)+tr(R.string.ui_96)+BuildConfig.VERSION_NAME+tr(R.string.ui_97)).setPositiveButton(tr(R.string.ui_34),null).setNeutralButton(tr(R.string.ui_98),(d,w)->{try{String license=new String(readAsset("OpenRad-LICENSE.txt"),java.nio.charset.StandardCharsets.UTF_8)+"\n\n"+new String(readAsset("ThirdPartyNotices.md"),java.nio.charset.StandardCharsets.UTF_8);new AlertDialog.Builder(this).setTitle(tr(R.string.ui_99)).setMessage(license).setPositiveButton(tr(R.string.ui_59),null).show();}catch(Exception ignored){toast(tr(R.string.ui_100));}}).show();}
    private byte[] readAsset(String name) throws java.io.IOException {try(java.io.InputStream in=getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private void copy(String value){ClipData clip=ClipData.newPlainText(tr(R.string.ui_101),value);if(Build.VERSION.SDK_INT>=33){PersistableBundle extra=new PersistableBundle();extra.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extra);}((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);toast(tr(R.string.ui_102));}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private String bytes(long count){if(count<1024)return count+tr(R.string.ui_103);if(count<1048576)return String.format(getResources().getConfiguration().getLocales().get(0),tr(R.string.ui_104),count/1024.);if(count<1073741824)return String.format(getResources().getConfiguration().getLocales().get(0),tr(R.string.ui_105),count/1048576.);return String.format(getResources().getConfiguration().getLocales().get(0),tr(R.string.ui_106),count/1073741824.);}
    private String peerState(String s){return switch(s){case "Connected"->tr(R.string.ui_107);case "Connecting"->tr(R.string.ui_108);case "Online"->tr(R.string.ui_109);case "Refused"->tr(R.string.ui_110);case "Failed"->tr(R.string.ui_111);case "Unavailable"->tr(R.string.ui_112);default->tr(R.string.ui_113);};}
    private String transport(String path){return switch(path){case "DirectTcp"->tr(R.string.ui_114);case "DirectUdp"->tr(R.string.ui_115);case "Relay"->tr(R.string.ui_116);default->"";};}
    private String translateOperation(String value,boolean error){if(error)return translateError(value);if(value.startsWith("Joined "))return tr(R.string.ui_117)+value.substring(7);if(value.startsWith("Created "))return tr(R.string.ui_118)+value.substring(8);if(value.startsWith("Left network"))return tr(R.string.ui_119);if(value.contains("waiting for administrator"))return tr(R.string.ui_120);return tr(R.string.ui_121);}
    private String translateError(String value){if(value==null||value.isEmpty()||value.equals("null"))return tr(R.string.ui_122);String s=value.toLowerCase(Locale.ROOT);if(s.contains("профиль"))return tr(R.string.error_profile);if(s.contains("сначала"))return tr(R.string.error_connect_first);if(s.contains("password")||s.contains("join refused")||s.contains("sh server proof"))return tr(R.string.ui_128);if(s.contains("permission")||s.contains("vpn-интерфейс"))return tr(R.string.ui_130);if(s.contains("queue")||s.contains("очеред"))return tr(R.string.ui_131);if(s.contains("timed out")||s.contains("timeout"))return tr(R.string.ui_132);if(s.contains("network operation refused"))return tr(R.string.ui_133);if(s.contains("network name"))return tr(R.string.ui_134);return tr(R.string.ui_135);}
}
