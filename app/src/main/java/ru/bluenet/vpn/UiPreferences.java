package ru.bluenet.vpn;
import android.content.*;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;
final class UiPreferences {
    static Context context(Context base){
        android.content.SharedPreferences p=base.getSharedPreferences("settings",Context.MODE_PRIVATE);
        Configuration c=new Configuration(base.getResources().getConfiguration());
        String lang=p.getString("language","ru");c.setLocales(new LocaleList(Locale.forLanguageTag(lang.equals("en")?"en":"ru")));
        String theme=p.getString("theme","system");
        if(!theme.equals("system"))c.uiMode=(c.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(theme.equals("dark")?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(c);
    }
    static String text(Context c,int id){return context(c).getString(id);}
}
