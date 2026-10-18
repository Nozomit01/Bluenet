package ru.bluenet.vpn;
import android.content.Context;
import android.graphics.*;
import android.view.View;
final class SignalView extends View {
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private int level=-1,color=0xff246bfd;private String description;
 SignalView(Context c,int value,int tint,String text){super(c);set(value,tint,text);}
 void set(int value,int tint,String text){level=value;color=tint;description=text;setContentDescription(text);invalidate();}
 @Override protected void onDraw(Canvas c){super.onDraw(c);float gap=getWidth()/10f,w=(getWidth()-3*gap)/4f;for(int i=0;i<4;i++){paint.setColor(i<level?color:0xff8092ae);paint.setAlpha(i<level?255:85);float h=getHeight()*(.25f+.23f*i);c.drawRoundRect(i*(w+gap),getHeight()-h,i*(w+gap)+w,getHeight(),2,2,paint);}if(level==0){paint.setAlpha(255);paint.setColor(0xffe97575);paint.setStrokeWidth(2);c.drawLine(0,getHeight(),getWidth(),0,paint);}}
}
