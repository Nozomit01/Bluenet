package ru.bluenet.vpn;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Secondary tools screen; every network operation runs outside the UI thread. */
public final class FeatureActivity extends Activity {
    private LinearLayout content;private boolean busy;private String mode;private int ink,background,blue;
    private Button backButton;private LinearLayout busyProgress;private TextView progressLabel;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private String t(String ru,String en){return getSharedPreferences("settings",MODE_PRIVATE).getString("language","ru").equals("en")?en:ru;}
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(UiPreferences.context(base));}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);mode=getIntent().getStringExtra("mode");if(mode==null)mode="servers";
        boolean dark=(getResources().getConfiguration().uiMode&48)==32;ink=dark?0xffe7efff:0xff152c52;background=dark?0xff0b1424:0xfff3f7ff;blue=dark?0xff79a8ff:0xff246bfd;
        getWindow().setStatusBarColor(0xff246bfd);getWindow().setNavigationBarColor(background);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);render();
    }
    @Override public void onDestroy(){worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
    private TextView label(String text){TextView v=new TextView(this);v.setText(text);v.setTextColor(ink);v.setTextSize(15);v.setPadding(0,EasyUi.dp(this,10),0,EasyUi.dp(this,10));content.addView(v);return v;}
    private Button button(String text,Runnable action){Button b=EasyUi.secondary(this,text,()->{if(!busy)action.run();});b.setEnabled(!busy);content.addView(b,EasyUi.actionParams(this));return b;}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private boolean stopped(){if(BlueVpnService.running()){toast(t("Сначала отключите VPN","Disconnect VPN first"));return false;}return true;}
    private EditText input(String hint,boolean secret){EditText v=new EditText(this);v.setTextColor(ink);v.setHintTextColor(EasyUi.dark(this)?0xffaabbcc:0xff63738a);v.setHint(hint);v.setSingleLine();v.setTextSize(15);v.setMinHeight(EasyUi.dp(this,48));v.setPadding(EasyUi.dp(this,12),EasyUi.dp(this,10),EasyUi.dp(this,12),EasyUi.dp(this,10));v.setBackground(EasyUi.shape(this,EasyUi.dark(this)?0xff223651:0xffe8f0ff,12));v.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));v.setSaveEnabled(false);v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);v.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(secret?32768:255)});return v;}
    private void render(){
        LinearLayout root=EasyUi.col(this);root.setBackgroundColor(background);EasyUi.insets(root);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);backButton=EasyUi.icon(this,"‹",t("Назад","Back"),this::finish);toolbar.addView(backButton,new LinearLayout.LayoutParams(EasyUi.dp(this,48),EasyUi.dp(this,48)));
        String heading=mode.equals("servers")?t("Серверы VLESS","VLESS servers"):mode.equals("apps")?t("Приложения","Apps"):mode.equals("invite")?t("Приглашение в сеть","Network invitation"):t("Игры и избранное","Games and favorites");TextView title=EasyUi.text(this,heading,20);title.setTypeface(Typeface.DEFAULT_BOLD);LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(0,-2,1);headingParams.leftMargin=EasyUi.dp(this,8);toolbar.addView(title,headingParams);root.addView(toolbar);
        busyProgress=new LinearLayout(this);busyProgress.setGravity(Gravity.CENTER_VERTICAL);busyProgress.setPadding(0,EasyUi.dp(this,10),0,EasyUi.dp(this,10));ProgressBar progress=new ProgressBar(this);busyProgress.addView(progress,new LinearLayout.LayoutParams(EasyUi.dp(this,32),EasyUi.dp(this,32)));progressLabel=EasyUi.text(this,t("Выполняется… Можно вернуться назад.","Working… You can go back."),14);progressLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(0,-2,1);progressParams.leftMargin=EasyUi.dp(this,8);busyProgress.addView(progressLabel,progressParams);busyProgress.setVisibility(busy?View.VISIBLE:View.GONE);root.addView(busyProgress);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=EasyUi.col(this);content.setPadding(0,EasyUi.dp(this,8),0,EasyUi.dp(this,16));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);root.requestApplyInsets();
        if(mode.equals("servers"))servers();else if(mode.equals("apps"))apps();else if(mode.equals("invite"))invite();else games();
    }
    private void setBusy(boolean value){busy=value;busyProgress.setVisibility(value?View.VISIBLE:View.GONE);enableControls(content,!value);}
    private void enableControls(View view,boolean enabled){if(view instanceof Button||view instanceof EditText)view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)enableControls(group.getChildAt(i),enabled);}}
    private interface Task{void run()throws Exception;}
    private void work(Task task){if(busy)return;setBusy(true);worker.submit(()->{boolean ok;try{task.run();ok=true;}catch(Exception error){ok=false;EventLog.add(this,"tool_failed");}final boolean done=ok;main.post(()->{if(isFinishing()||isDestroyed())return;busy=false;render();toast(done?t("Готово","Done"):t("Не удалось. Проверьте интернет, формат ссылки и поддержку параметров сервера.","Failed. Check internet, link format and supported server parameters."));});});}
    private void servers(){
        label(t("Поддерживаются ссылки vless:// и HTTPS-подписки: обычный или Base64-список, до 24 серверов. Другие протоколы пропускаются.","Supports vless:// links and HTTPS subscriptions: plain or Base64 lists, up to 24 servers. Other protocols are skipped."));
        button(t("Импортировать ссылку или подписку","Import link or subscription"),()->{if(stopped())importDialog();});
        try{
            JSONObject catalog=ServerCatalog.read(this);JSONArray entries=catalog.getJSONArray("servers");
            if(!catalog.optString("subscription").isEmpty()){
                label(t("Обновлено: ","Updated: ")+java.text.DateFormat.getDateTimeInstance().format(new Date(catalog.optLong("updated"))));
                button(t("Обновить подписку сейчас","Refresh subscription now"),()->{if(stopped())work(()->{String url=ServerCatalog.read(this).getString("subscription");ServerCatalog.importData(this,url,ServerCatalog.parse(ServerCatalog.download(url)));});});
                toggle(t("Обновлять подписку раз в сутки","Refresh subscription daily"),"subscription_auto",true,()->SubscriptionJob.schedule(this));
            }
            toggle(t("Автовыбор проверенного сервера и резерв при обрыве","Choose fastest tested server and fail over"),"server_auto",true,null);
            label(t("Проверка проходит через VLESS до HTTPS www.gstatic.com. Время включает установку туннеля, TLS и HTTP, это не ping. После трёх сбоев подряд — переключение на резерв.","Checks use VLESS to HTTPS www.gstatic.com. Time includes tunnel setup, TLS and HTTP; it is not ping. Three consecutive failures trigger failover."));
            if(entries.length()>0)button(t("Проверить все серверы","Test all servers"),()->{if(stopped())work(()->{JSONArray list=ServerCatalog.read(this).getJSONArray("servers");for(int i=0;i<list.length()&&!Thread.currentThread().isInterrupted();i++){JSONObject s=list.getJSONObject(i);long ms;try{ms=ProxyEngine.test(this,s.getString("link"));}catch(Exception e){ms=-1;}ServerCatalog.result(this,s.getString("id"),ms);int count=i+1;main.post(()->{if(!isFinishing()&&!isDestroyed())progressLabel.setText(t("Проверено: ","Tested: ")+count+"/"+list.length());});}});});
            for(int i=0;i<entries.length();i++){JSONObject s=entries.getJSONObject(i);String id=s.getString("id");long ms=s.optLong("latency",-2);
                String quality=ms==-2?t("Не проверен","Not tested"):ms<0?t("Проверка не прошла","Probe failed"):ms+" ms";
                button((id.equals(catalog.optString("selected"))?"✓ ":"")+s.getString("name")+" · "+quality,()->{if(stopped())try{ServerCatalog.select(this,id);render();}catch(Exception e){toast(t("Ошибка профиля","Profile error"));}});
            }
            if(entries.length()>0)button(t("Удалить все VLESS-профили","Delete all VLESS profiles"),()->{if(!stopped())return;new AlertDialog.Builder(this).setMessage(t("Удалить серверы и ссылку подписки?","Delete servers and subscription URL?"))
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{ServerCatalog.clear(this);getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("vless",false).apply();((android.app.job.JobScheduler)getSystemService(JOB_SCHEDULER_SERVICE)).cancel(264);render();}).show();});
        }catch(Exception error){label(t("Не удалось прочитать защищённый список. Данные сохранены для восстановления.","Cannot read encrypted catalog. Data retained for recovery."));}
    }
    private void importDialog(){
        EditText link=input("vless:// … / https:// …",true);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(EasyUi.dp(this,24),EasyUi.dp(this,12),EasyUi.dp(this,24),0);box.addView(link);
        CheckBox show=new CheckBox(this);show.setText(t("Показать ссылку","Show link"));box.addView(show);show.setOnCheckedChangeListener((v,on)->link.setTransformationMethod(on?null:android.text.method.PasswordTransformationMethod.getInstance()));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(t("Импорт VLESS","Import VLESS")).setView(box).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String value=link.getText().toString().trim();try{
            if(value.startsWith("https://"))ServerCatalog.subscriptionUrl(value);else VlessProfile.outbound(value);
            link.getText().clear();dialog.dismiss();work(()->{JSONArray entries=value.startsWith("https://")?ServerCatalog.parse(ServerCatalog.download(value)):ServerCatalog.parse(value);ServerCatalog.importData(this,value.startsWith("https://")?value:"",entries);SubscriptionJob.schedule(this);});
        }catch(Exception error){link.setError(t("Нужна ссылка vless:// или HTTPS-подписка","Use vless:// or an HTTPS subscription"));}}));dialog.setOnDismissListener(d->link.getText().clear());dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void toggle(String title,String key,boolean fallback,Runnable changed){Switch s=new Switch(this);s.setText(title);s.setTextColor(ink);s.setMinHeight(EasyUi.dp(this,48));s.setPadding(0,EasyUi.dp(this,8),0,EasyUi.dp(this,8));s.setChecked(getSharedPreferences("settings",MODE_PRIVATE).getBoolean(key,fallback));content.addView(s);s.setOnCheckedChangeListener((v,on)->{if(!stopped()){s.setOnCheckedChangeListener(null);s.setChecked(!on);render();return;}getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean(key,on).apply();if(changed!=null)changed.run();});}
    private void apps(){
        label(t("Radmin доступен всем приложениям. Отмеченные приложения используют VLESS для интернета; остальные — прямое соединение. DNS и неопознанные потоки идут через VLESS. Общий UID означает общий выбор.","Radmin stays available to all apps. Selected apps use VLESS for internet; others connect directly. DNS and unidentified flows use VLESS. Apps sharing a UID share the choice."));
        if(Build.VERSION.SDK_INT<29){label(t("Выбор приложений требует Android 10 или новее.","App selection requires Android 10 or newer."));return;}
        toggle(t("Использовать выбор приложений","Enable app selection"),"per_app",false,null);
        Set<String> selected=new HashSet<>(getSharedPreferences("settings",MODE_PRIVATE).getStringSet("vless_apps",Collections.emptySet()));
        List<ResolveInfo> apps=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
        apps.sort(Comparator.comparing(a->a.loadLabel(getPackageManager()).toString(),String.CASE_INSENSITIVE_ORDER));Set<String> seen=new HashSet<>();
        for(ResolveInfo info:apps){String name=info.activityInfo.packageName;if(name.equals(getPackageName())||!seen.add(name))continue;
            CheckBox check=new CheckBox(this);check.setText(info.loadLabel(getPackageManager())+"\n"+name);check.setTextColor(ink);check.setChecked(selected.contains(name));content.addView(check);
            check.setOnCheckedChangeListener((v,on)->{if(!stopped()){check.setOnCheckedChangeListener(null);check.setChecked(!on);render();return;}if(on)selected.add(name);else selected.remove(name);getSharedPreferences("settings",MODE_PRIVATE).edit().putStringSet("vless_apps",new HashSet<>(selected)).apply();});
        }
        label(t("Если ничего не выбрано, весь интернет идёт через VLESS. Настройка применяется при следующем подключении.","If no apps are selected, all internet uses VLESS. Applied on next connection."));
    }
    private void games(){
        label(t("Проверка TCP-порта идёт внутри сети Radmin. Java Minecraft обычно использует 25565; порт мира по LAN может меняться. UDP/Bedrock этой проверкой не проверяется.","TCP port checks run inside Radmin. Java Minecraft usually uses 25565; a LAN world's port can change. This check does not test UDP/Bedrock."));
        try{JSONObject state=new JSONObject(NativeBridge.status()),snapshot=state.optJSONObject("snapshot"),peers=snapshot==null?null:snapshot.optJSONObject("peers");
            if(peers==null){label(t("Подключите Radmin, чтобы увидеть устройства.","Connect Radmin to see devices."));return;}
            Set<String> favorites=getSharedPreferences("favorites",MODE_PRIVATE).getStringSet("peers",Collections.emptySet());List<String> keys=new ArrayList<>();Iterator<String> it=peers.keys();while(it.hasNext()&&keys.size()<200)keys.add(it.next());keys.sort(Comparator.comparing(k->!favorites.contains(k)));
            for(String key:keys){JSONObject view=peers.optJSONObject(key),peer=view==null?null:view.optJSONObject("peer");if(peer==null)continue;String ip=peer.optString("vip"),name=peer.optString("name");label((favorites.contains(key)?"★ ":"")+name+" · "+ip);
                button(favorites.contains(key)?t("Убрать из избранного","Remove favorite"):t("В избранное","Add favorite"),()->{Set<String> next=new HashSet<>(getSharedPreferences("favorites",MODE_PRIVATE).getStringSet("peers",Collections.emptySet()));if(!next.remove(key)&&next.size()<200)next.add(key);getSharedPreferences("favorites",MODE_PRIVATE).edit().putStringSet("peers",next).apply();render();});
                button(t("Копировать IP","Copy IP"),()->copy(ip));button(t("Проверить игровой порт","Check game port"),()->portDialog(ip));
            }
        }catch(Exception e){label(t("Устройства недоступны","Devices unavailable"));}
    }
    private void portDialog(String ip){EditText field=input(t("Порт","Port"),false);field.setInputType(InputType.TYPE_CLASS_NUMBER);field.setText("25565");
        AlertDialog d=new AlertDialog.Builder(this).setTitle(ip).setView(field).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{try{int port=Integer.parseInt(field.getText().toString());if(port<1||port>65535)throw new IllegalArgumentException();d.dismiss();work(()->{String result=BlueVpnService.checkPort(ip,port);main.post(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle(ip+":"+port).setMessage(result.equals("open")?t("TCP-порт открыт","TCP port is open"):result.equals("closed")?t("TCP-порт закрыт","TCP port is closed"):t("Нет ответа: проверьте порт, сервер игры и брандмауэр.","No reply: check port, game server and firewall.")).setPositiveButton(android.R.string.ok,null).show();});});}catch(Exception e){field.setError("1–65535");}}));d.show();}
    private void invite(){
        label(t("Введите название существующей сети и, при необходимости, пароль. QR-код с паролем даёт доступ в сеть. Приложение не хранит пароль приглашения.","Enter an existing network name and optional password. A QR code containing the password grants access. The invitation password is not stored."));
        EditText name=input(t("Название сети","Network name"),false),password=input(t("Пароль (можно оставить пустым)","Password (optional)"),true);content.addView(name,EasyUi.actionParams(this));content.addView(password,EasyUi.actionParams(this));
        if(getIntent().getStringExtra("network_name")!=null)name.setText(getIntent().getStringExtra("network_name"));
        button(t("Создать QR-код","Create QR code"),()->{try{String value=Invitation.make(name.getText().toString(),password.getText().toString());password.getText().clear();
            com.google.zxing.common.BitMatrix matrix=new com.google.zxing.MultiFormatWriter().encode(value,com.google.zxing.BarcodeFormat.QR_CODE,640,640,Collections.singletonMap(com.google.zxing.EncodeHintType.CHARACTER_SET,"UTF-8"));
            Bitmap bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);int[] pixels=new int[640*640];for(int y=0;y<640;y++)for(int x=0;x<640;x++)pixels[y*640+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;bitmap.setPixels(pixels,0,640,0,0,640,640);
            ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setImageBitmap(bitmap);image.setContentDescription(t("QR-код приглашения","Invitation QR code"));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(t("Приглашение","Invitation")).setView(image).setNegativeButton(android.R.string.cancel,null).setPositiveButton(t("Копировать приглашение","Copy invitation"),(d,w)->copy(value)).create();dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }catch(Exception e){toast(t("Проверьте имя и пароль (от 6 символов).","Check name and password (at least 6 characters)."));}});
        label(t("Друг сканирует QR-код системной камерой и открывает ссылку в «Синей сети», либо вставляет приглашение кнопкой ниже.","A friend scans with the system camera and opens the link in Blue Network, or pastes the invitation below."));
        button(t("Вставить приглашение","Paste invitation"),()->{EditText link=input("bluenet://join#…",true);AlertDialog d=new AlertDialog.Builder(this).setView(link).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{try{Invitation.parse(link.getText().toString());String value=link.getText().toString();link.getText().clear();d.dismiss();startActivity(new Intent(this,MainActivity.class).setData(android.net.Uri.parse(value)));finish();}catch(Exception e){link.setError(t("Неверное приглашение","Invalid invitation"));}}));d.setOnDismissListener(v->link.getText().clear());d.show();if(d.getWindow()!=null)d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);});
    }
    private void copy(String value){ClipData clip=ClipData.newPlainText("Blue Network",value);if(Build.VERSION.SDK_INT>=33){PersistableBundle p=new PersistableBundle();p.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(p);}((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);toast(t("Скопировано","Copied"));}
}
