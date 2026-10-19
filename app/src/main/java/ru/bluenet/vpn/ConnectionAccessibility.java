package ru.bluenet.vpn;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

/** Foreground-only status decoration and polite, coalesced screen-reader updates. Main thread only. */
final class ConnectionAccessibility {
    static final String VIBRATION_PREF="connection_status_vibration";
    private final Context context;
    private final TextView title,announcements;
    private final AccessibilityManager accessibility;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ConnectionAnnouncements reducer=new ConnectionAnnouncements();
    private boolean resumed,released,eligible,vibrationEnabled;
    private String phase="idle";
    private boolean retry,intentional;
    private final Runnable dispatch=this::dispatch;
    private final AccessibilityManager.AccessibilityStateChangeListener stateListener=enabled->availabilityChanged();
    private final AccessibilityManager.TouchExplorationStateChangeListener touchListener=enabled->availabilityChanged();
    private final ViewTreeObserver.OnWindowFocusChangeListener focusListener=focused->availabilityChanged();
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener=this::availabilityChanged;

    ConnectionAccessibility(Context context,TextView title){
        this.context=context;this.title=title;
        announcements=EasyUi.text(context,"",13);
        announcements.setPadding(0,0,0,0);
        announcements.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        announcements.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        accessibility=(AccessibilityManager)context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if(accessibility!=null){accessibility.addAccessibilityStateChangeListener(stateListener);accessibility.addTouchExplorationStateChangeListener(touchListener);}
        title.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
        title.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }
    TextView getAnnouncementView(){return announcements;}
    void setVibrationEnabled(boolean enabled){vibrationEnabled=enabled;}
    void resume(){if(released)return;resumed=true;availabilityChanged();}
    void pause(){resumed=false;deactivate();}
    void release(){if(released)return;pause();released=true;
        if(accessibility!=null){accessibility.removeAccessibilityStateChangeListener(stateListener);accessibility.removeTouchExplorationStateChangeListener(touchListener);}
        if(title.getViewTreeObserver().isAlive()){title.getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);title.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);}
    }
    void update(String phase,boolean retryPending,boolean intentionalStop,String baseLabel){
        if(released)return;
        String nextPhase=phase==null?"idle":phase;
        boolean changed=!this.phase.equals(nextPhase)||retry!=retryPending||intentional!=intentionalStop;
        this.phase=nextPhase;retry=retryPending;intentional=intentionalStop;
        String plain=baseLabel==null?"":baseLabel;
        String symbol="connected".equals(this.phase)?"✓ ":"stopping".equals(this.phase)?"■ ":"connecting".equals(this.phase)||retry?"… ":"error".equals(this.phase)?"! ":"○ ";
        String decorated=symbol+plain;
        if(!decorated.contentEquals(title.getText()))title.setText(decorated);
        if(!plain.contentEquals(title.getContentDescription()==null?"":title.getContentDescription()))title.setContentDescription(plain);
        availabilityChanged();
        if(!eligible)return;
        // Remove a previously delivered explanatory line as soon as its state becomes stale.
        if(changed&&!announcements.getText().toString().isEmpty())clearAnnouncement();
        reducer.update(this.phase,retry,intentional,SystemClock.uptimeMillis());schedule();
    }
    private boolean available(){return !released&&resumed&&title.isShown()&&title.hasWindowFocus()&&accessibility!=null&&accessibility.isEnabled()&&accessibility.isTouchExplorationEnabled();}
    private void availabilityChanged(){
        boolean active=available();
        if(!active){if(eligible)deactivate();return;}
        if(!eligible){eligible=true;reducer.reset();} // The next fresh update establishes the silent baseline.
    }
    private void clearAnnouncement(){announcements.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);announcements.setText("");}
    private void deactivate(){eligible=false;handler.removeCallbacks(dispatch);reducer.reset();clearAnnouncement();}
    private void schedule(){handler.removeCallbacks(dispatch);long deadline=reducer.deadline();if(deadline>=0)handler.postAtTime(dispatch,deadline);}
    private void dispatch(){
        availabilityChanged();if(!eligible)return;
        ConnectionAnnouncements.Event event=reducer.poll(SystemClock.uptimeMillis());
        if(event!=null){announcements.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);announcements.setText(message(context,event));vibrate(event);}
        schedule();
    }
    static String message(Context context,ConnectionAnnouncements.Event event){
        switch(event){
            case CONNECTED:return EasyUi.t(context,"Соединение установлено","Connection established");
            case LOST:return EasyUi.t(context,"Соединение потеряно","Connection lost");
            case RESTORED:return EasyUi.t(context,"Соединение восстановлено","Connection restored");
            case UNSTABLE_CONNECTED:return EasyUi.t(context,"Соединение нестабильно. Сейчас подключено","Connection unstable. Currently connected");
            default:return EasyUi.t(context,"Соединение нестабильно. Сейчас не подключено","Connection unstable. Currently disconnected");
        }
    }
    private void vibrate(ConnectionAnnouncements.Event event){
        if(!vibrationEnabled||!available())return;
        try{
            if(Settings.System.getInt(context.getContentResolver(),Settings.System.HAPTIC_FEEDBACK_ENABLED,0)==0)return;
            AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            NotificationManager notifications=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if(audio==null||audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL||notifications==null||notifications.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL)return;
            Vibrator vibrator=(Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
            if(vibrator==null||!vibrator.hasVibrator())return;
            long[] pattern=event==ConnectionAnnouncements.Event.LOST||event==ConnectionAnnouncements.Event.UNSTABLE_DISCONNECTED?new long[]{0,60,100,60}:new long[]{0,40};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern,-1),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
        }catch(SecurityException ignored){/* Optional permission or system policy may disallow haptics. */}
    }
}
