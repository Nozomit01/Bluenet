package ru.bluenet.vpn;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
/** Real local dialogs with per-owner synthetic state; never connects or sends to a real peer. */
final class GameCardUiChecks {
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static void main(Instrumentation i,Runnable r){java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();i.runOnMainSync(()->{try{r.run();}catch(Throwable t){error.set(t);}});if(error.get()!=null)throw new AssertionError("card UI",error.get());}
 static final class Fixture implements GameCardUi.Boundary {
  String ip="26.1.2.3",peer="26.4.5.6",sentPeer="",sentText="";boolean member=true,permission=true,throwToken,throwSecurity;int sends,shows;
  public String ownIp(){return ip;}public JSONObject member(String candidate){return member&&peer.equals(candidate)?new JSONObject():null;}
  public void send(String target,String text){sends++;sentPeer=target;sentText=text;}
  public boolean overlays(Context c){return permission;}
  public void show(AlertDialog d){shows++;if(throwToken)throw new WindowManager.BadTokenException("synthetic revoke race");if(throwSecurity)throw new SecurityException("synthetic revoke race");d.show();}
 }
 static void click(Instrumentation i,GameCardUi.Owner owner,int which){main(i,()->owner.lastDialog().getButton(which).performClick());i.waitForIdleSync();}
 static void visibleButtons(AlertDialog dialog){
  Rect frame=new Rect();dialog.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
  for(int which:new int[]{-1,-2}){Button button=dialog.getButton(which);check(button!=null&&button.isShown()&&button.isEnabled(),"dialog button unavailable: "+which);Rect visible=new Rect();check(button.getGlobalVisibleRect(visible)&&visible.width()==button.getWidth()&&visible.height()==button.getHeight(),"dialog button clipped: "+which+" visible="+visible+" size="+button.getWidth()+"x"+button.getHeight());int[] location=new int[2];button.getLocationOnScreen(location);Rect bounds=new Rect(location[0],location[1],location[0]+button.getWidth(),location[1]+button.getHeight());check(frame.contains(bounds),"dialog button outside visible window: "+which+" bounds="+bounds+" frame="+frame);int target=EasyUi.dp(dialog.getContext(),48);check(button.getWidth()>=target&&button.getHeight()>=target,"dialog button tap target below 48dp: "+which);check(button.getLayout()!=null&&button.getLayout().getLineCount()==1&&button.getLayout().getLineWidth(0)<=button.getWidth()-button.getCompoundPaddingLeft()-button.getCompoundPaddingRight(),"dialog button label clipped: "+which);}
 }
 static void tapCancel(Instrumentation i,GameCardUi.Owner owner){
  AlertDialog[] dialog=new AlertDialog[1];main(i,()->dialog[0]=owner.lastDialog());
  // Idle UI work does not imply that WindowManager has focused the new dialog yet.
  long deadline=android.os.SystemClock.uptimeMillis()+5000,focusedSince=0;boolean[] ready=new boolean[1];
  while(android.os.SystemClock.uptimeMillis()<deadline){
   i.waitForIdleSync();main(i,()->{View decor=dialog[0].getWindow().getDecorView();ready[0]=owner.lastDialog()==dialog[0]&&dialog[0].isShowing()&&decor.isShown()&&decor.isAttachedToWindow()&&decor.hasWindowFocus();});
   long now=android.os.SystemClock.uptimeMillis();if(ready[0]){if(focusedSince==0)focusedSince=now;if(now-focusedSince>=150)break;}else focusedSince=0;android.os.SystemClock.sleep(25);
  }
  check(ready[0]&&focusedSince!=0&&android.os.SystemClock.uptimeMillis()-focusedSince>=150,"Cancel dialog did not acquire stable input focus within 5s");
  float[] point=new float[2];main(i,()->{check(dialog[0].getWindow().getDecorView().hasWindowFocus(),"Cancel dialog lost input focus before pointer snapshot");visibleButtons(dialog[0]);Button cancel=dialog[0].getButton(-2);int[] location=new int[2];cancel.getLocationOnScreen(location);point[0]=location[0]+cancel.getWidth()/2f;point[1]=location[1]+cancel.getHeight()/2f;});
  main(i,()->check(dialog[0].isShowing()&&dialog[0].getWindow().getDecorView().isShown()&&dialog[0].getWindow().getDecorView().hasWindowFocus(),"Cancel dialog lost input focus before pointer tap"));
  long time=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,point[0],point[1],0),up=android.view.MotionEvent.obtain(time,time+50,android.view.MotionEvent.ACTION_UP,point[0],point[1],0);down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{i.sendPointerSync(down);i.sendPointerSync(up);}finally{down.recycle();up.recycle();}i.waitForIdleSync();main(i,()->check(!dialog[0].isShowing(),"visible Cancel tap did not dismiss dialog"));
 }
 static List<EditText> inputs(View v){ArrayList<EditText> result=new ArrayList<>();if(v instanceof EditText)result.add((EditText)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++)result.addAll(inputs(g.getChildAt(n)));}return result;}
 static void screenshot(Context c,AlertDialog d,String name){View v=d.getWindow().getDecorView();check(v.getWidth()>0&&v.getHeight()>0,"dialog not laid out");Bitmap b=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);try{v.draw(new Canvas(b));try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}catch(IOException e){throw new RuntimeException(e);}finally{b.recycle();}}
 static void run(Instrumentation instrumentation,Context context)throws Exception {
  SharedPreferences prefs=context.getSharedPreferences("settings",0);String oldLang=prefs.getString("language","ru"),oldTheme=prefs.getString("theme","system");
  try{for(String language:new String[]{"ru","en"}){String theme=language.equals("ru")?"light":"dark";prefs.edit().putString("language",language).putString("theme",theme).commit();ChatActivity activity=(ChatActivity)instrumentation.startActivitySync(new Intent(context,ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   try{instrumentation.waitForIdleSync();for(float scale:new float[]{1f,2f}){Fixture fixture=new Fixture();GameCardUi.Owner[] ref=new GameCardUi.Owner[1];GameCard card=GameCard.create("Minecraft","26.1.2.3",25565,"Fixture host",System.currentTimeMillis(),false);
    AlertDialog[] composeDialog=new AlertDialog[1];
    main(instrumentation,()->{android.view.ContextThemeWrapper themed=new android.view.ContextThemeWrapper(activity,theme.equals("dark")?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);Configuration config=new Configuration(activity.getResources().getConfiguration());config.fontScale=scale;themed.applyOverrideConfiguration(config);ref[0]=new GameCardUi.Owner(themed,fixture);GameCardUi.compose(ref[0],fixture.peer,null,()->{});composeDialog[0]=ref[0].lastDialog();check(composeDialog[0]!=null&&composeDialog[0].isShowing(),"compose not shown");});
    // Dialog dispatches OnShowListener asynchronously. Let it install the custom Preview click first.
    instrumentation.waitForIdleSync();
    main(instrumentation,()->{visibleButtons(composeDialog[0]);List<EditText> fields=inputs(composeDialog[0].getWindow().getDecorView());check(fields.size()==2,"manual game/port missing");fields.get(0).setText("Minecraft");fields.get(1).setText("25565");composeDialog[0].getButton(-1).performClick();});
    instrumentation.waitForIdleSync();main(instrumentation,()->{
     AlertDialog preview=ref[0].lastDialog();check(preview!=null&&preview!=composeDialog[0]&&preview.isShowing(),"Preview must be a new dialog after the compose OnShow callback");check(fixture.sends==0,"preview sent automatically");String artifact="gamecard-preview-"+language+"-"+String.valueOf(scale).replace('.','_');
     visibleButtons(preview);screenshot(context,preview,artifact+".png");
     String body=((TextView)preview.findViewById(android.R.id.message)).getText().toString();String author=ref[0].context.getSharedPreferences("settings",0).getString("device",android.os.Build.MODEL).trim();
     String configuredLanguage=ref[0].context.getSharedPreferences("settings",0).getString("language","ru");
     try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),artifact+".txt"))){out.write(("expectedLanguage="+language+"\nactualPreference="+configuredLanguage+"\nexpectedAuthor="+author+"\nauthorLength="+author.length()+"\npreviewBody:\n"+body).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(IOException e){throw new RuntimeException(e);}
     check(body.contains(fixture.peer),"preview missing selected recipient "+fixture.peer);
     check(body.contains("26.1.2.3:25565"),"preview missing published own IP:port");
     check(body.contains("Minecraft"),"preview missing game title");
     check(body.contains("Android"),"preview missing Android-only limitation");
     check(body.contains(author),"preview missing author; expectedLength="+author.length());
     check(body.contains(language.equals("ru")?"Время со слов автора":"Host-provided time"),"preview timestamp label does not match requested language "+language+"; preference="+configuredLanguage);
    });
    tapCancel(instrumentation,ref[0]);check(fixture.sends==0,"cancel sent");
    main(instrumentation,()->{GameCardUi.preview(ref[0],fixture.peer,card,()->{});fixture.ip="26.9.9.9";});click(instrumentation,ref[0],-1);check(fixture.sends==0,"changed IP sent");
    main(instrumentation,()->{fixture.ip=card.ip;GameCardUi.preview(ref[0],fixture.peer,card,()->{});fixture.member=false;});click(instrumentation,ref[0],-1);check(fixture.sends==0,"stale recipient sent");
    main(instrumentation,()->{fixture.member=true;GameCardUi.preview(ref[0],fixture.peer,card,()->{});});click(instrumentation,ref[0],-1);check(fixture.sends==1&&fixture.sentPeer.equals(fixture.peer)&&GameCard.parse(fixture.sentText)!=null,"explicit single recipient send");
    main(instrumentation,()->{try{JSONObject row=new JSONObject().put("peer",card.ip).put("name","Authenticated fixture host").put("text",card.text()).put("out",false);check(GameCardUi.history(ref[0].context,row).contains(card.address()),"card history missing address");GameCardUi.show(ref[0],row,fixture.peer,()->{});}catch(Exception e){throw new RuntimeException(e);}});
    instrumentation.waitForIdleSync();main(instrumentation,()->{visibleButtons(ref[0].lastDialog());screenshot(context,ref[0].lastDialog(),"gamecard-detail-"+language+"-"+String.valueOf(scale).replace('.','_')+".png");AlertDialog visible=ref[0].lastDialog();ref[0].close();check(!visible.isShowing()&&ref[0].count()==0,"owner close leaked dialog");GameCardUi.preview(ref[0],fixture.peer,card,()->{});check(ref[0].count()==0,"closed owner reopened");});
   }
   main(instrumentation,()->{try{Fixture fixture=new Fixture();GameCard card=GameCard.create("Game","26.1.2.3",1234,"Host",System.currentTimeMillis(),false);LinearLayout holder=new LinearLayout(activity);ChatPanel panel=new ChatPanel(activity,true);GameCardUi.Owner owner=new GameCardUi.Owner(activity,fixture);java.lang.reflect.Field field=ChatPanel.class.getDeclaredField("cardOwner");field.setAccessible(true);field.set(panel,owner);holder.addView(panel);activity.setContentView(holder);GameCardUi.preview(owner,fixture.peer,card,()->{});AlertDialog dialog=owner.lastDialog();check(dialog!=null&&dialog.isShowing(),"detach fixture not shown");holder.removeView(panel);check(owner.closed()&&!dialog.isShowing()&&owner.count()==0,"panel detach leaked dialog");
    Context serviceContext=new android.view.ContextThemeWrapper(context,android.R.style.Theme_Material_Light_NoActionBar);Fixture denied=new Fixture();denied.permission=false;GameCardUi.Owner noPermission=new GameCardUi.Owner(serviceContext,denied);noPermission.builder().setMessage("denied").show();check(denied.shows==0&&noPermission.count()==0,"service context fell back to app dialog");for(boolean security:new boolean[]{false,true}){Fixture race=new Fixture();race.throwToken=!security;race.throwSecurity=security;GameCardUi.Owner revoked=new GameCardUi.Owner(serviceContext,race);revoked.builder().setMessage("race").show();check(race.shows==1&&revoked.count()==0,"revoke race leaked/crashed");revoked.close();}noPermission.close();
   }catch(Exception e){throw new RuntimeException(e);}});
   }finally{main(instrumentation,activity::finish);}
  }}finally{prefs.edit().putString("language",oldLang).putString("theme",oldTheme).commit();}
 }
}
