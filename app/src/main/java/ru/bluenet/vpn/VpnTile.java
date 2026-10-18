package ru.bluenet.vpn;
import android.service.quicksettings.*;
import android.content.Intent;
import android.app.PendingIntent;
import android.net.VpnService;
import android.os.Build;
public final class VpnTile extends TileService {
    @Override public void onStartListening(){Tile t=getQsTile();if(t==null)return;t.setLabel(UiPreferences.text(this,R.string.app_name));t.setState(BlueVpnService.running()?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);if(Build.VERSION.SDK_INT>=29)t.setSubtitle(UiPreferences.text(this,BlueVpnService.blocked()?R.string.traffic_blocked:BlueVpnService.running()?R.string.ui_20:R.string.ui_12));t.updateTile();}
    @Override public void onClick(){super.onClick();unlockAndRun(()->{
        if(BlueVpnService.running())startService(new Intent(this,BlueVpnService.class).setAction(BlueVpnService.STOP));
        else{
            String phase="idle";try{phase=new org.json.JSONObject(NativeBridge.status()).optString("phase","idle");}catch(Exception ignored){}
            boolean transitioning=phase.matches("stopping|connecting|connected");
            if(!transitioning&&VpnService.prepare(this)==null)startForegroundService(new Intent(this,BlueVpnService.class));
            else{Intent open=new Intent(this,QuickConnectActivity.class).putExtra("tile_connect",!transitioning).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,264,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));else startActivity(open);
            }
        }onStartListening();
    });}
}
