package ru.bluenet.vpn;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
public final class ChatActivity extends Activity {
 @Override protected void attachBaseContext(Context c){super.attachBaseContext(UiPreferences.context(c));}
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);LinearLayout root=EasyUi.col(this);root.setBackgroundColor(EasyUi.bg(this));EasyUi.insets(root);
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.addView(EasyUi.icon(this,"‹",EasyUi.t(this,"Назад","Back"),this::finish),new LinearLayout.LayoutParams(EasyUi.dp(this,48),EasyUi.dp(this,48)));
  TextView title=EasyUi.text(this,EasyUi.t(this,"Чат сети","Network chat"),20);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);LinearLayout.LayoutParams heading=new LinearLayout.LayoutParams(0,-2,1);heading.leftMargin=EasyUi.dp(this,8);bar.addView(title,heading);
  Button menu=EasyUi.icon(this,"⋮",EasyUi.t(this,"Меню чата","Chat menu"),()->{});menu.setOnClickListener(v->{PopupMenu popup=new PopupMenu(this,menu);popup.getMenu().add(EasyUi.t(this,"Очистить историю","Clear history")).setOnMenuItemClickListener(item->{new AlertDialog.Builder(this).setMessage(EasyUi.t(this,"Удалить историю на этом телефоне?","Delete history on this phone?")).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{try{ChatStore.get(this).clear();}catch(Exception e){EasyUi.toast(this,"Не удалось очистить историю","Could not clear history");}}).show();return true;});popup.show();});bar.addView(menu,new LinearLayout.LayoutParams(EasyUi.dp(this,48),EasyUi.dp(this,48)));root.addView(bar);
  LinearLayout.LayoutParams chat=new LinearLayout.LayoutParams(-1,0,1);chat.topMargin=EasyUi.dp(this,12);root.addView(new ChatPanel(this,false),chat);setContentView(root);
 }
}
