package ru.bluenet.vpn;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic snapshots and invalid form submissions only: never contacts any network endpoint. */
public final class TroubleshootChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static TroubleshootModel.Observation observe(JSONObject state) {
        return TroubleshootModel.observe(state, TroubleshootModel.Permission.PREPARED, false, 123456);
    }
    private static JSONObject fixture() throws Exception {
        return new JSONObject("{\"phase\":\"connected\",\"generation\":9,\"snapshot\":{\"vip\":\"26.254.0.2\","
                + "\"networks\":[{\"network_id\":\"A\",\"name\":\"Synthetic network\"}],\"peers\":{"
                + "\"1\":{\"status\":\"Connected\",\"peer\":{\"name\":\"Synthetic peer\",\"vip\":\"26.1.2.3\",\"network_ids\":[\"A\"]}},"
                + "\"2\":{\"status\":\"Connected\",\"peer\":{\"name\":\"Other network\",\"vip\":\"26.2.3.4\",\"network_ids\":[\"B\"]}}}}}");
    }
    private static void rejects(String ip, String port) {
        boolean rejected = false;
        try { TroubleshootModel.Target.parse(ip, port); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Unsafe or invalid TCP target accepted");
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void onMain(Instrumentation instrumentation, Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("Troubleshooting UI check", failure.get());
    }
    private static List<View> descendants(View root) {
        List<View> result = new ArrayList<>(); result.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(descendants(group.getChildAt(i)));
        }
        return result;
    }

    public static void run(Instrumentation instrumentation, Context context) throws Exception {
        TroubleshootModel.Observation live = observe(fixture());
        require(live.live && live.networksKnown && live.peersKnown && live.networks.size() == 1,
                "Complete current snapshot not recognized");
        require(live.peers.size() == 1 && live.connectedPeers() == 1 && live.peers.get(0).ip.equals("26.1.2.3"),
                "Unrelated-network peer included in shared-network report");
        require(live.observedAt == 123456, "Observation time discarded");
        for (String phase : new String[]{"idle", "connecting", "stopping", "error", "nonsense"}) {
            TroubleshootModel.Observation old = observe(fixture().put("phase", phase));
            require(!old.live && !old.networksKnown && !old.peersKnown && old.peers.isEmpty(),
                    "Disconnected or unknown phase reused a stale peer snapshot");
        }
        TroubleshootModel.Observation missing = observe(new JSONObject().put("phase", "connected"));
        require(missing.live && !missing.networksKnown && !missing.peersKnown, "Missing data was presented as an empty successful result");
        JSONObject empty = fixture();
        empty.getJSONObject("snapshot").put("networks", new JSONArray()).put("peers", new JSONObject());
        TroubleshootModel.Observation noNetworks = observe(empty);
        require(noNetworks.networksKnown && noNetworks.networks.isEmpty(), "Known empty network list became unknown");
        empty = fixture(); empty.getJSONObject("snapshot").put("peers", new JSONObject());
        require(observe(empty).peersKnown && observe(empty).peers.isEmpty(), "Known empty peer list became unknown");
        JSONObject malformed = fixture();
        malformed.getJSONObject("snapshot").getJSONObject("peers").getJSONObject("1").getJSONObject("peer").remove("network_ids");
        require(!observe(malformed).peersKnown, "Malformed peer membership became a valid empty list");
        JSONArray tooMany = new JSONArray(); for (int i = 0; i < 201; i++) tooMany.put(new JSONObject().put("network_id", "N" + i));
        JSONObject oversized = fixture(); oversized.getJSONObject("snapshot").put("networks", tooMany);
        require(!observe(oversized).networksKnown, "Oversized snapshot not bounded");
        for (TroubleshootModel.Permission permission : TroubleshootModel.Permission.values()) {
            TroubleshootModel.Observation permissionOnly = TroubleshootModel.observe(null, permission, false, 1);
            require(!permissionOnly.live && permissionOnly.permission == permission, "Permission status incorrectly implied an active connection");
        }
        require(TroubleshootModel.observe(fixture(), TroubleshootModel.Permission.PREPARED, true, 1).blocked,
                "Blocking service state was lost");
        TroubleshootModel.Target target = TroubleshootModel.Target.parse(" 26.001.2.3 ", "25565");
        require(target.ip.equals("26.1.2.3") && target.port == 25565, "TCP endpoint normalization failed");
        for (String bad : new String[]{"8.8.8.8", "127.0.0.1", "localhost", "26.1.2.256", "26.1.2.3/path", "26.1.2", "26.1.2.3\nInjected", "::1"}) rejects(bad, "25565");
        for (String bad : new String[]{"0", "65536", "-1", "+80", "1.5", "1e2", "", "000001"}) rejects("26.1.2.3", bad);
        require(TroubleshootModel.portState("open") == TroubleshootModel.PortState.OPEN
                && TroubleshootModel.portState("closed") == TroubleshootModel.PortState.CLOSED
                && TroubleshootModel.portState("timeout") == TroubleshootModel.PortState.TIMEOUT
                && TroubleshootModel.portState(null) == TroubleshootModel.PortState.UNKNOWN
                && TroubleshootModel.portState("raw private error") == TroubleshootModel.PortState.UNKNOWN,
                "TCP outcome mapping inferred a cause or fabricated a timeout");
        require(TroubleshootModel.sameProbeContext(live, observe(fixture()), target), "Stable TCP context marked stale");
        JSONObject changed = fixture().put("generation", 10);
        require(!TroubleshootModel.sameProbeContext(live, observe(changed), target), "Restarted engine accepted an old result");
        changed = fixture(); changed.getJSONObject("snapshot").put("vip", "26.254.0.3");
        require(!TroubleshootModel.sameProbeContext(live, observe(changed), target), "Local IP change accepted an old result");
        changed = fixture(); changed.getJSONObject("snapshot").getJSONObject("peers").getJSONObject("1").put("status", "Disconnected");
        require(!TroubleshootModel.sameProbeContext(live, observe(changed), target), "Disconnected peer accepted an old result");
        changed = fixture(); changed.getJSONObject("snapshot").getJSONObject("peers").getJSONObject("1").getJSONObject("peer").put("vip", "26.1.2.4");
        require(!TroubleshootModel.sameProbeContext(live, observe(changed), target), "Peer address change accepted an old result");
        require(!TroubleshootModel.sameProbeContext(live, missing, target)
                && !TroubleshootModel.sameProbeContext(live, TroubleshootModel.observe(fixture(), TroubleshootModel.Permission.CONFIRMATION_REQUIRED, false, 2), target),
                "Unknown or revoked context accepted an old result");

        android.content.SharedPreferences preferences = context.getSharedPreferences("settings", 0);
        String oldLanguage = preferences.getString("language", "ru"), oldTheme = preferences.getString("theme", "system");
        try {
            for (String language : new String[]{"ru", "en"}) {
                String theme = language.equals("ru") ? "light" : "dark";
                preferences.edit().putString("language", language).putString("theme", theme).commit();
                TroubleshootActivity activity = (TroubleshootActivity) instrumentation.startActivitySync(
                        new Intent(context, TroubleshootActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    instrumentation.waitForIdleSync();
                    onMain(instrumentation, () -> {
                        try {
                            Future<?> task = (Future<?>) field(activity, "task");
                            if (task != null) task.cancel(true);
                            Field request = TroubleshootActivity.class.getDeclaredField("request"); request.setAccessible(true); request.setInt(activity, request.getInt(activity) + 1);
                            Method setBusy = TroubleshootActivity.class.getDeclaredMethod("setBusy", boolean.class); setBusy.setAccessible(true); setBusy.invoke(activity, false);
                            Method render = TroubleshootActivity.class.getDeclaredMethod("renderObservation", TroubleshootModel.Observation.class); render.setAccessible(true); render.invoke(activity, live);
                            Method select = TroubleshootActivity.class.getDeclaredMethod("selectScenario", int.class); select.setAccessible(true); select.invoke(activity, 1);
                            EditText ip = (EditText) field(activity, "ipInput"), port = (EditText) field(activity, "portInput");
                            Button probe = (Button) field(activity, "probeButton");
                            ip.setText("8.8.8.8"); port.setText("25565"); probe.performClick();
                            require(ip.getError() != null && !(Boolean) field(activity, "busy"), "Invalid IP started a network check");
                            ip.setText("26.1.2.3"); port.setText("0"); probe.performClick();
                            require(port.getError() != null && !(Boolean) field(activity, "busy"), "Invalid port started a network check");
                            ViewGroup root = (ViewGroup) field(activity, "root");
                            List<TextView> labels = new ArrayList<>(); List<Float> sizes = new ArrayList<>();
                            for (View view : descendants(root)) if (view instanceof TextView) { labels.add((TextView) view); sizes.add(((TextView) view).getTextSize()); }
                            for (float scale : new float[]{1f, 2f}) for (int height : new int[]{640, 240}) {
                                // EasyUi's fixed-size back glyph uses DIP, independently of accessibility font scaling.
                                for (int i = 0; i < labels.size(); i++) labels.get(i).setTextSize(TypedValue.COMPLEX_UNIT_PX,
                                        sizes.get(i) * (labels.get(i).getText().toString().equals("‹") ? 1f : scale));
                                int widthPx = EasyUi.dp(activity, 320), heightPx = EasyUi.dp(activity, height);
                                root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
                                root.layout(0, 0, widthPx, heightPx);
                                for (View view : descendants(root)) if (view instanceof Button && view.getVisibility() == View.VISIBLE) {
                                    Button button = (Button) view;
                                    require(button.getHeight() >= EasyUi.dp(activity, 48), "Troubleshooting action target smaller than 48dp");
                                    if (button.getLayout() != null) require(button.getLayout().getHeight() <= button.getHeight() - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom() + 1, "Troubleshooting button text clipped");
                                }
                                for (TextView label : labels) label.onPreDraw();
                                Bitmap image = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
                                try { root.draw(new Canvas(image)); try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "troubleshoot-" + language + "-" + scale + "-" + height + ".png"))) { require(image.compress(Bitmap.CompressFormat.PNG, 100, out), "Screenshot write failed"); } }
                                finally { image.recycle(); }
                            }
                        } catch (Exception error) { throw new RuntimeException(error); }
                    });
                } finally { onMain(instrumentation, activity::finish); instrumentation.waitForIdleSync(); }
            }
        } finally { preferences.edit().putString("language", oldLanguage).putString("theme", oldTheme).commit(); }
    }
}
