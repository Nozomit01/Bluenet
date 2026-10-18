package ru.bluenet.vpn;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Offline, versioned release history shipped with the APK. */
final class ReleaseNotes {
    static final class Entry {
        final String version, date, title;
        final List<String> changes;
        Entry(String version, String date, String title, List<String> changes) {
            this.version = version;
            this.date = date;
            this.title = title;
            this.changes = Collections.unmodifiableList(changes);
        }
    }

    static List<Entry> load(Context context) throws Exception {
        String language = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("language", "ru").equals("en") ? "en" : "ru";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open("release-notes.json")) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 65536) throw new IllegalArgumentException("release notes size");
                bytes.write(buffer, 0, count);
            }
        }
        JSONArray releases = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()))
                .getJSONArray("releases");
        if (releases.length() > 100) throw new IllegalArgumentException("release notes count");
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            JSONObject localized = release.getJSONObject(language);
            JSONArray items = localized.getJSONArray("changes");
            List<String> changes = new ArrayList<>();
            for (int j = 0; j < items.length(); j++) changes.add(items.getString(j));
            result.add(new Entry(release.getString("version"), release.optString("date", ""),
                    localized.getString("title"), changes));
        }
        return Collections.unmodifiableList(result);
    }

    private ReleaseNotes() {}
}
