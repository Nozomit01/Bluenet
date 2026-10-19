package ru.bluenet.vpn;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Regression coverage for narrow displays and accessibility text sizing.
 * Uses only synthetic state: no VPN connection or message is sent.
 */
final class LayoutChecks {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void onMain(Instrumentation instrumentation,Runnable task){java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();instrumentation.runOnMainSync(()->{try{task.run();}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new AssertionError("Layout check failed",failure.get());}
    private static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void awaitMainForeground(Instrumentation instrumentation,MainActivity activity,boolean expected){
        long deadline=SystemClock.uptimeMillis()+5000;
        boolean[] ready={false};
        do{
            onMain(instrumentation,()->{try{ready[0]=((Boolean)field(activity,"foreground"))==expected&&(!expected||activity.hasWindowFocus())&&!activity.isFinishing();}catch(Exception e){throw new RuntimeException(e);}});
            if(ready[0])return;
            SystemClock.sleep(25);
        }while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("MainActivity did not "+(expected?"resume with window focus":"pause for the tool")+" within five seconds");
    }
    private static List<View> descendants(View root){List<View> all=new ArrayList<>();all.add(root);if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)all.addAll(descendants(group.getChildAt(i)));}return all;}
    private static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    private static Rect bounds(ViewGroup root,View child){Rect box=new Rect(0,0,child.getWidth(),child.getHeight());root.offsetDescendantRectToMyCoords(child,box);return box;}
    private static void screenshot(Context c,View view,String name)throws Exception{for(View descendant:descendants(view))if(descendant instanceof TextView)((TextView)descendant).onPreDraw();Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);try{view.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(c.getFilesDir(),"layout-"+name+".png"))){require(image.compress(Bitmap.CompressFormat.PNG,100,out),"Screenshot encoding failed");}}finally{image.recycle();}}
    private static void assertTextHeight(TextView label,String description){if(label.getLayout()!=null)require(label.getLayout().getHeight()<=label.getHeight()-label.getCompoundPaddingTop()-label.getCompoundPaddingBottom()+1,description+" text clipped vertically");}

    public static void run(Instrumentation instrumentation,Context context)throws Exception {
        android.content.SharedPreferences preferences=context.getSharedPreferences("settings",0);
        String oldLanguage=preferences.getString("language","ru"),oldTheme=preferences.getString("theme","system");
        boolean oldAwake=preferences.getBoolean("stay_awake",false);
        try {
            for(String language:new String[]{"ru","en"}){
                String theme=language.equals("ru")?"light":"dark";
                preferences.edit().putString("language",language).putString("theme",theme).commit();
                List<ReleaseNotes.Entry> releases=ReleaseNotes.load(context);
                String[] versions={"0.6.0","0.5.3","0.5.2","0.5.1","0.5.0","0.4.0","0.3.0","0.2.0","0.1.0"};
                require(releases.size()==versions.length,"Offline release history must include every published version");
                for(int i=0;i<versions.length;i++){
                    ReleaseNotes.Entry entry=releases.get(i);require(entry.version.equals(versions[i]),"Release history order/version mismatch");
                    require(!entry.title.trim().isEmpty()&&!entry.changes.isEmpty(),"Release notes have empty localized content");
                    boolean cyrillic=false;for(char letter:(entry.title+entry.changes.toString()).toCharArray())if(letter>='А'&&letter<='я'||letter=='ё'||letter=='Ё')cyrillic=true;
                    require(cyrillic==language.equals("ru"),"Release notes language does not match preference");
                }
                MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    instrumentation.waitForIdleSync();
                    onMain(instrumentation,()->{try{
                        Field foreground=MainActivity.class.getDeclaredField("foreground");foreground.setAccessible(true);foreground.setBoolean(activity,false);
                        ((Handler)field(activity,"handler")).removeCallbacksAndMessages(null);
                        ViewGroup root=(ViewGroup)field(activity,"root");ViewGroup nav=(ViewGroup)field(activity,"nav");
                        List<TextView> texts=new ArrayList<>();List<Float> original=new ArrayList<>();for(View view:descendants(root))if(view instanceof TextView){texts.add((TextView)view);original.add(((TextView)view).getTextSize());}
                        for(float scale:new float[]{1f,1.5f,2f}){
                            for(int i=0;i<texts.size();i++)texts.get(i).setTextSize(TypedValue.COMPLEX_UNIT_PX,original.get(i)*scale);
                            layout(root,dp(activity,320),dp(activity,640));
                            require(nav.getChildCount()==4,"Expected four navigation destinations");
                            for(int i=0;i<nav.getChildCount();i++){
                                View item=nav.getChildAt(i);require(item.getWidth()>=dp(activity,48)&&item.getHeight()>=dp(activity,48),"Navigation target too small");
                                List<TextView> labels=new ArrayList<>();for(View candidate:descendants(item))if(candidate instanceof TextView&&!((TextView)candidate).getText().toString().isEmpty())labels.add((TextView)candidate);
                                require(!labels.isEmpty(),"Navigation destination has no text label");
                                for(TextView label:labels){require(label.getLayout()!=null&&label.getLayout().getLineCount()==1,"Navigation label wraps at "+language+"/"+scale);assertTextHeight(label,"Navigation "+label.getText());require(label.getContentDescription()!=null||item.getContentDescription()!=null,"Ellipsized navigation requires full accessible label");if(scale==1f&&activity.getResources().getConfiguration().fontScale<=1.05f)require(label.getLayout().getEllipsisCount(0)==0,"Default-size navigation label shortened: "+label.getText());}
                            }
                            for(View view:descendants(root))if(view instanceof Button&&view.getVisibility()==View.VISIBLE)assertTextHeight((TextView)view,"Button "+((TextView)view).getText()+" "+language+"/"+scale);
                            screenshot(context,root,"home-"+language+"-"+theme+"-"+String.valueOf(scale).replace('.','_'));
                        }
                    }catch(Exception e){throw new RuntimeException(e);}});
                    final int[] savedScroll={0};
                    onMain(instrumentation,()->{try{
                        java.lang.reflect.Method select=MainActivity.class.getDeclaredMethod("selectTab",int.class);select.setAccessible(true);select.invoke(activity,2);
                        ViewGroup root=(ViewGroup)field(activity,"root");layout(root,dp(activity,320),dp(activity,640));
                        for(String tag:new String[]{"settings-appearance","settings-game","settings-connection","settings-checks"})require(root.findViewWithTag(tag)!=null,"Missing settings section "+tag);
                        ScrollView mainScroll=(ScrollView)field(activity,"mainScroll");mainScroll.scrollTo(0,dp(activity,180));savedScroll[0]=mainScroll.getScrollY();require(savedScroll[0]>0,"Settings fixture must be scrolled before return");
                    }catch(Exception e){throw new RuntimeException(e);}});
                    Instrumentation.ActivityMonitor toolMonitor=instrumentation.addMonitor(FeatureActivity.class.getName(),null,false);
                    FeatureActivity feature;
                    try{
                        // Match openTools(): the tool must sit above this MainActivity in its task.
                        onMain(instrumentation,()->activity.startActivity(new Intent(activity,FeatureActivity.class).putExtra("mode","servers")));
                        android.app.Activity launched=instrumentation.waitForMonitorWithTimeout(toolMonitor,5000);
                        require(launched instanceof FeatureActivity,"Tool did not launch within five seconds");
                        feature=(FeatureActivity)launched;
                    }finally{instrumentation.removeMonitor(toolMonitor);}
                    try{
                        instrumentation.waitForIdleSync();
                        require(feature.getTaskId()==activity.getTaskId(),"Tool must return to the same MainActivity task");
                        awaitMainForeground(instrumentation,activity,false);
                        preferences.edit().putBoolean("stay_awake",!oldAwake).commit();
                        onMain(instrumentation,()->{try{
                            Field busy=FeatureActivity.class.getDeclaredField("busy");busy.setAccessible(true);busy.setBoolean(feature,true);
                            View back=(View)field(feature,"backButton");require(back.isEnabled()&&back.performClick(),"Tool Back must remain usable while busy");require(feature.isFinishing(),"Busy tool Back did not close the screen");
                        }catch(Exception e){throw new RuntimeException(e);}});
                        // finish()/an idle queue do not guarantee that the underlying Activity resumed.
                        awaitMainForeground(instrumentation,activity,true);
                        onMain(instrumentation,()->{try{
                            ViewGroup root=(ViewGroup)field(activity,"root");Switch awake=null;for(View view:descendants(root))if(view instanceof Switch){String value=((Switch)view).getText().toString();if(value.contains("Не гасить экран")||value.contains("Keep screen on"))awake=(Switch)view;}
                            require(awake!=null&&awake.isChecked()!=oldAwake,"Settings did not refresh after returning from a tool");
                            require(((ScrollView)field(activity,"mainScroll")).getScrollY()==savedScroll[0],"Returning from a tool reset settings scroll position");
                            screenshot(context,root,"settings-"+language+"-"+theme);
                        }catch(Exception e){throw new RuntimeException(e);}});
                    }finally{onMain(instrumentation,feature::finish);preferences.edit().putBoolean("stay_awake",oldAwake).commit();}
                }finally{onMain(instrumentation,activity::finish);instrumentation.waitForIdleSync();}
                for(float scale:new float[]{1f,1.5f,2f})for(boolean compact:new boolean[]{false,true})for(int heightDp:compact?new int[]{400}:new int[]{400,240}){
                    onMain(instrumentation,()->{try{
                        Configuration config=new Configuration(UiPreferences.context(context).getResources().getConfiguration());config.fontScale=scale;
                        Context themed=new ContextThemeWrapper(context.createConfigurationContext(config),theme.equals("dark")?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
                        ChatPanel panel=new ChatPanel(themed,compact);
                        ((TextView)field(panel,"history")).setText(language.equals("ru")?"17:40 · Тестовый участник\nПривет! Запускай игру.\n\n17:41 · Вы → Тестовый участник\nСейчас подключусь.\nДоставлено":"17:40 · Test peer\nHello! Start the game.\n\n17:41 · You → Test peer\nJoining now.\nDelivered");
                        ((EditText)field(panel,"input")).setText(language.equals("ru")?"Сообщение\nВторая строка":"Message\nSecond line");
                        ViewGroup root;
                        if(compact){ScrollView outer=new ScrollView(themed);outer.addView(panel);root=outer;}else root=panel;
                        layout(root,dp(themed,320),dp(themed,heightDp));
                        EditText input=null;TextView send=null,quick=null;
                        for(View view:descendants(panel)){
                            if(view instanceof EditText)input=(EditText)view;
                            if(view instanceof TextView&&view.getVisibility()==View.VISIBLE){TextView text=(TextView)view;String description=String.valueOf(text.getContentDescription()),value=text.getText().toString();if(description.equals("Отправить")||description.equals("Send")||value.equals("Отправить")||value.equals("Send"))send=text;if(value.contains("Быстр")||value.contains("Quick phrase")||description.equals("Быстрые фразы")||description.equals("Quick phrases"))quick=text;}
                        }
                        require(input!=null&&send!=null&&quick!=null,"Chat must expose message, send and quick phrase controls");
                        Rect inputBox=bounds(panel,input),sendBox=bounds(panel,send),quickBox=bounds(panel,quick);
                        require(!Rect.intersects(inputBox,sendBox)&&!Rect.intersects(quickBox,sendBox)&&!Rect.intersects(inputBox,quickBox),"Chat controls overlap "+language+"/"+scale+"/"+compact+"/"+heightDp);
                        require(sendBox.width()>=dp(themed,48)&&sendBox.height()>=dp(themed,48),"Send target smaller than48dp");
                        for(Rect box:new Rect[]{inputBox,sendBox,quickBox})require(box.left>=0&&box.right<=panel.getWidth()&&box.top>=0&&box.bottom<=panel.getHeight(),"Chat controls leave panel bounds");
                        require(quickBox.width()>=dp(themed,48)&&quickBox.height()>=dp(themed,48),"Quick phrase target smaller than48dp");
                        if(!compact)require(sendBox.bottom<=dp(themed,heightDp),"Keyboard-height chat hides composer");
                        assertTextHeight(send,"Send control");assertTextHeight(quick,"Quick phrase control");
                        if(heightDp==400||scale==2f)screenshot(context,root,"chat-"+language+"-"+theme+"-"+String.valueOf(scale).replace('.','_')+(compact?"-compact":"")+(heightDp==240?"-height240":""));
                    }catch(Exception e){throw new RuntimeException(e);}});
                }
                ReleaseNotesActivity notes=(ReleaseNotesActivity)instrumentation.startActivitySync(new Intent(context,ReleaseNotesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try{instrumentation.waitForIdleSync();onMain(instrumentation,()->{try{
                    ViewGroup content=notes.findViewById(android.R.id.content);View root=content.getChildAt(0);
                    for(View view:descendants(root))if(view instanceof TextView){TextView text=(TextView)view;text.setTextSize(TypedValue.COMPLEX_UNIT_PX,text.getTextSize()*1.5f);}
                    layout(root,dp(notes,320),dp(notes,640));
                    boolean oldest=false;for(View view:descendants(root))if(view instanceof TextView){TextView text=(TextView)view;if(text.getText().toString().contains("0.1.0"))oldest=true;assertTextHeight(text,"Release notes "+text.getText());}
                    require(oldest,"Oldest release is absent from rendered offline page");
                    screenshot(context,root,"notes-"+language+"-"+theme);
                }catch(Exception e){throw new RuntimeException(e);}});}finally{onMain(instrumentation,notes::finish);instrumentation.waitForIdleSync();}
            }
        }finally{preferences.edit().putString("language",oldLanguage).putString("theme",oldTheme).putBoolean("stay_awake",oldAwake).commit();}
    }
}
