package ru.bluenet.vpn;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Readable release history, including at narrow widths and large system font sizes. */
public final class ReleaseNotesActivity extends Activity {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiPreferences.context(base));
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = EasyUi.dark(this);
        getWindow().setStatusBarColor(0xff1452d8);
        getWindow().setNavigationBarColor(EasyUi.bg(this));
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = EasyUi.col(this);
        root.setBackgroundColor(EasyUi.bg(this));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = EasyUi.col(this);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        Button back = EasyUi.button(this, t("‹ Назад", "‹ Back"), this::finish);
        back.setStateListAnimator(null);
        back.setElevation(0);
        back.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.addView(back, new LinearLayout.LayoutParams(-1, -2));
        TextView title = EasyUi.text(this, t("История изменений", "Release notes"), 28);
        title.setTypeface(null, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(14);
        content.addView(title, titleParams);
        TextView introduction = EasyUi.text(this,
                t("Изменения по версиям приложения. Доступны без подключения к интернету.", "Changes by application version. Available without an internet connection."), 15);
        content.addView(introduction);
        try {
            for (ReleaseNotes.Entry release : ReleaseNotes.load(this)) addRelease(content, release, dark);
        } catch (Exception exception) {
            content.addView(EasyUi.text(this,
                    t("Не удалось открыть историю обновлений.", "Could not open update history."), 17));
        }
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestApplyInsets();
    }

    private void addRelease(LinearLayout content, ReleaseNotes.Entry release, boolean dark) {
        LinearLayout card = EasyUi.col(this);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackground(EasyUi.shape(this, dark ? 0xff1d304c : Color.WHITE, 20));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(16);
        content.addView(card, cardParams);
        TextView version = EasyUi.text(this,
                t("Версия ", "Version ") + release.version, 22);
        version.setTypeface(null, Typeface.BOLD);
        version.setTextColor(dark ? 0xff90b8ff : 0xff1452d8);
        if (Build.VERSION.SDK_INT >= 28) version.setAccessibilityHeading(true);
        card.addView(version);
        if (!release.date.isEmpty()) card.addView(EasyUi.text(this, release.date, 14));
        TextView title = EasyUi.text(this, release.title, 18);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        for (String change : release.changes) {
            TextView item = EasyUi.text(this, "•  " + change, 16);
            item.setLineSpacing(dp(3), 1);
            card.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private int dp(int value) { return EasyUi.dp(this, value); }
    private String t(String ru, String en) { return EasyUi.t(this, ru, en); }
}
