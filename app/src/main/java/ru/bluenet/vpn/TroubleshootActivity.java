package ru.bluenet.vpn;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Read-only guided checks; the only active test is one explicitly requested Radmin TCP probe. */
public final class TroubleshootActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Future<?> task;
    private int request, scenario;
    private boolean active, busy, cancelled;
    private LinearLayout root, observations, probeResult, progress;
    private TextView status;
    private Button friendScenario, gameScenario, refreshButton, probeButton;
    private EditText ipInput, portInput;
    private LinearLayout gameInputs;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiPreferences.context(base));
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scenario = savedInstanceState == null ? 0 : savedInstanceState.getInt("scenario", 0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(0xff1452d8);
        getWindow().setNavigationBarColor(EasyUi.bg(this));
        root = EasyUi.col(this);
        root.setTag("troubleshoot-root");
        root.setBackgroundColor(EasyUi.bg(this));
        EasyUi.insets(root);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(EasyUi.icon(this, "‹", t("Назад", "Back"), this::finish),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = EasyUi.text(this, t("Помощник подключения", "Connection assistant"), 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams heading = new LinearLayout.LayoutParams(0, -2, 1);
        heading.leftMargin = dp(8);
        toolbar.addView(title, heading);
        root.addView(toolbar);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = EasyUi.col(this);
        content.setPadding(0, dp(8), 0, dp(16));
        content.addView(EasyUi.text(this, t("Выберите проблему. Проверки показывают состояние на момент запроса; настройки подключения меняются на вкладке «Сети».",
                "Choose a problem. Checks show the state at the time of the request; connection settings are managed on Networks."), 14));
        friendScenario = EasyUi.secondary(this, t("Не вижу друга", "I cannot see my friend"), () -> selectScenario(0));
        gameScenario = EasyUi.secondary(this, t("Друг виден, игра не подключается", "Friend is visible, but the game cannot connect"), () -> selectScenario(1));
        content.addView(friendScenario, EasyUi.actionParams(this));
        content.addView(gameScenario, EasyUi.actionParams(this));
        progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar spinner = new ProgressBar(this);
        progress.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
        progress.addView(EasyUi.text(this, t("Проверка…", "Checking…"), 14));
        progress.setVisibility(View.GONE);
        content.addView(progress, EasyUi.actionParams(this));
        status = EasyUi.text(this, "", 13);
        status.setTag("troubleshoot-status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        refreshButton = EasyUi.button(this, t("Проверить состояние сети", "Check network state"), this::refresh);
        content.addView(refreshButton, EasyUi.actionParams(this));
        observations = EasyUi.col(this);
        content.addView(observations);
        gameInputs = EasyUi.col(this);
        TextView tcpTitle = EasyUi.text(this, t("Проверка игрового TCP-порта", "Game TCP port check"), 18);
        tcpTitle.setTypeface(Typeface.DEFAULT_BOLD);
        gameInputs.addView(tcpTitle);
        gameInputs.addView(EasyUi.text(this, t("Укажите VPN-адрес устройства с сервером и точный порт из игры. Для Java Minecraft часто используется 25565; у LAN-мира порт может быть другим.",
                "Enter the server device's VPN address and the exact port shown by the game. Java Minecraft often uses 25565; a LAN world may use a different port."), 14));
        ipInput = field(t("VPN-адрес сервера · 26.x.x.x", "Server VPN address · 26.x.x.x"), "troubleshoot-ip");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ipInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(15)});
        portInput = field(t("TCP-порт · 1–65535", "TCP port · 1–65535"), "troubleshoot-port");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
        gameInputs.addView(ipInput, EasyUi.actionParams(this));
        gameInputs.addView(portInput, EasyUi.actionParams(this));
        probeButton = EasyUi.button(this, t("Проверить TCP-порт", "Check TCP port"), this::probe);
        probeButton.setTag("troubleshoot-probe");
        gameInputs.addView(probeButton, EasyUi.actionParams(this));
        gameInputs.addView(EasyUi.text(this, t("Проверяется только TCP по указанному адресу и порту через сеть Radmin. Результат не проверяет UDP, Bedrock, версии игры или её настройки.",
                "Only TCP at the specified address and port is checked through Radmin. The result does not test UDP, Bedrock, game versions or game configuration."), 13));
        probeResult = EasyUi.col(this);
        probeResult.setTag("troubleshoot-probe-result");
        gameInputs.addView(probeResult);
        content.addView(gameInputs, EasyUi.actionParams(this));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
        updateScenario();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        if (cancelled) {
            status.setText(t("Проверка отменена при закрытии экрана. Повторите её при необходимости.",
                    "The check was cancelled when this screen was left. Run it again if needed."));
            cancelled = false;
        } else if (observations.getChildCount() == 0) refresh();
    }

    @Override protected void onStop() {
        active = false;
        request++;
        if (task != null) task.cancel(true);
        cancelled = busy;
        setBusy(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("scenario", scenario);
        super.onSaveInstanceState(state);
    }

    private EditText field(String hint, String tag) {
        EditText input = new EditText(this);
        input.setTag(tag);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setTextSize(15);
        input.setTextColor(EasyUi.ink(this));
        input.setHintTextColor(EasyUi.dark(this) ? 0xffaabbcc : 0xff63738a);
        input.setSingleLine();
        input.setMinimumHeight(dp(48));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(EasyUi.shape(this, EasyUi.dark(this) ? 0xff223651 : 0xffe8f0ff, 12));
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return input;
    }

    private void selectScenario(int value) {
        if (busy) return;
        scenario = value;
        updateScenario();
    }

    private void updateScenario() {
        gameInputs.setVisibility(scenario == 1 ? View.VISIBLE : View.GONE);
        friendScenario.setSelected(scenario == 0);
        gameScenario.setSelected(scenario == 1);
        friendScenario.setText((scenario == 0 ? "✓ " : "") + t("Не вижу друга", "I cannot see my friend"));
        gameScenario.setText((scenario == 1 ? "✓ " : "") + t("Друг виден, игра не подключается", "Friend is visible, but the game cannot connect"));
    }

    private TroubleshootModel.Observation capture() {
        TroubleshootModel.Permission permission;
        try {
            permission = VpnService.prepare(this) == null ? TroubleshootModel.Permission.PREPARED
                    : TroubleshootModel.Permission.CONFIRMATION_REQUIRED;
        } catch (Exception unavailable) { permission = TroubleshootModel.Permission.UNKNOWN; }
        JSONObject state = null;
        try { state = new JSONObject(NativeBridge.status()); } catch (Exception unavailable) { /* Unknown, no raw errors. */ }
        return TroubleshootModel.observe(state, permission, BlueVpnService.blocked(), System.currentTimeMillis());
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        for (View view : new View[]{friendScenario, gameScenario, refreshButton, probeButton, ipInput, portInput}) {
            view.setEnabled(!value);
            view.setAlpha(value ? .6f : 1f);
        }
    }

    private void refresh() {
        if (busy || !active) return;
        setBusy(true);
        status.setText(t("Получение текущего состояния…", "Reading current state…"));
        final int token = ++request;
        task = worker.submit(() -> {
            TroubleshootModel.Observation observed = capture();
            main.post(() -> {
                if (!active || token != request || isFinishing()) return;
                setBusy(false);
                renderObservation(observed);
            });
        });
    }

    private void renderObservation(TroubleshootModel.Observation observation) {
        observations.removeAllViews();
        status.setText(t("Состояние получено: ", "State observed: ") + when(observation.observedAt));
        String permission = observation.permission == TroubleshootModel.Permission.PREPARED
                ? t("Приложение подготовлено Android для VPN. Это не подтверждает активное подключение.", "Android has prepared this app for VPN use. This does not confirm an active connection.")
                : observation.permission == TroubleshootModel.Permission.CONFIRMATION_REQUIRED
                ? t("При подключении потребуется подтверждение Android. Откройте «Сети» и нажмите «Подключиться».", "Android confirmation is required before connecting. Open Networks and tap Connect.")
                : t("Не удалось определить готовность разрешения. Проверьте её при подключении на вкладке «Сети».", "VPN permission readiness could not be determined. Check it when connecting on Networks.");
        card(observations, t("1. Разрешение VPN", "1. VPN permission"), permission,
                "Android · VpnService.prepare", observation.observedAt);
        String phase;
        switch (observation.phase) {
            case "connected": phase = t("Движок сообщает о подключении.", "The engine reports a connection."); break;
            case "connecting": phase = t("Выполняется подключение. Дождитесь результата и повторите проверку.", "Connection is in progress. Wait for its result and check again."); break;
            case "stopping": phase = t("Выполняется отключение. Дождитесь его завершения.", "Disconnection is in progress. Wait for it to finish."); break;
            case "idle": phase = t("Движок отключён. Подключите VPN на вкладке «Сети».", "The engine is disconnected. Connect the VPN on Networks."); break;
            case "error": phase = t("Движок сообщает об ошибке. Проверьте состояние на вкладке «Сети» и откройте диагностику при необходимости.", "The engine reports an error. Check the Networks screen and open diagnostics if needed."); break;
            default: phase = t("Текущее состояние движка неизвестно. Повторите проверку.", "The current engine state is unknown. Run the check again.");
        }
        if (observation.blocked) phase += "\n" + t("Служба сообщает о блокировке трафика. Это не означает готовность сети к игре.", "The service reports that traffic is blocked. This does not indicate that the game network is ready.");
        card(observations, t("2. Подключение", "2. Connection"), phase,
                t("OpenRad: текущий статус; состояние службы VPN", "OpenRad: current status; VPN service state"), observation.observedAt);
        String networks;
        if (!observation.networksKnown) networks = t("Неизвестно: актуальный список сетей недоступен. При отключённом движке старый снимок не используется.", "Unknown: the current network list is unavailable. Old snapshots are not used while the engine is disconnected.");
        else if (observation.networks.isEmpty()) networks = t("Устройство не вошло ни в одну сеть. На вкладке «Сети» войдите в сеть друга или создайте свою.", "This device has not joined a network. Join your friend's network or create one on Networks.");
        else {
            StringBuilder names = new StringBuilder(t("Сетей: ", "Networks: ")).append(observation.networks.size());
            for (int i = 0; i < Math.min(8, observation.networkNames.size()); i++) names.append("\n• ").append(observation.networkNames.get(i).isEmpty() ? t("Название недоступно", "Name unavailable") : observation.networkNames.get(i));
            names.append("\n").append(t("Проверьте, что друг вошёл в ту же сеть. Его сети отдельно не проверяются.", "Confirm that your friend joined the same network. Their networks are not checked separately."));
            networks = names.toString();
        }
        card(observations, t("3. Сети этого устройства", "3. Networks on this device"), networks,
                t("OpenRad: список собственных сетей", "OpenRad: own network list"), observation.observedAt);
        String peers;
        if (!observation.peersKnown || observation.networks.isEmpty()) peers = t("Неизвестно: для проверки участников нужны актуальные данные подключения и выбранная общая сеть.", "Unknown: current connection data and a joined shared network are needed to check peers.");
        else if (observation.peers.isEmpty()) peers = t("В текущем списке нет участников общих сетей. Попросите друга проверить подключение и точное имя сети. Причина отсутствия участника не установлена.", "No peers in shared networks are present in the current list. Ask your friend to check their connection and the exact network name. The reason for their absence has not been established.");
        else {
            StringBuilder names = new StringBuilder(t("Участников в общих сетях: ", "Peers in shared networks: ")).append(observation.peers.size())
                    .append(t("; подключено: ", "; connected: ")).append(observation.connectedPeers());
            for (int i = 0; i < Math.min(8, observation.peers.size()); i++) {
                TroubleshootModel.Peer peer = observation.peers.get(i);
                names.append("\n• ").append(peer.name.isEmpty() ? peer.ip : peer.name + " · " + peer.ip)
                        .append(" · ").append(peerState(peer.status));
            }
            names.append("\n").append(t("Наличие участника не подтверждает доступность игры. Если друг виден, выберите второй сценарий и проверьте точный TCP-порт сервера.", "A listed peer does not confirm game availability. If your friend is visible, choose the second scenario and check the server's exact TCP port."));
            peers = names.toString();
        }
        card(observations, t("4. Участники общей сети", "4. Shared-network peers"), peers,
                t("OpenRad: список участников и пересечение сетей", "OpenRad: peer directory and shared network membership"), observation.observedAt);
    }

    private void probe() {
        if (busy || !active) return;
        final TroubleshootModel.Target target;
        try { target = TroubleshootModel.Target.parse(ipInput.getText().toString(), portInput.getText().toString()); }
        catch (IllegalArgumentException invalid) {
            if ("address".equals(invalid.getMessage())) ipInput.setError(t("Нужен числовой адрес 26.x.x.x", "Enter a numeric 26.x.x.x address"));
            else portInput.setError(t("Укажите порт от 1 до 65535", "Enter a port from 1 to 65535"));
            return;
        }
        ipInput.setError(null); portInput.setError(null);
        setBusy(true);
        status.setText(t("Проверяется TCP: ", "Checking TCP: ") + target.ip + ":" + target.port);
        probeResult.removeAllViews();
        final int token = ++request;
        task = worker.submit(() -> {
            TroubleshootModel.Observation before = capture();
            long started = SystemClock.elapsedRealtime();
            String result = null;
            if (before.live && !before.blocked && before.networksKnown && !before.networks.isEmpty()) try {
                // The app UID is excluded from VPN; ordinary Java sockets would use the wrong path.
                result = BlueVpnService.checkPort(target.ip, target.port);
            } catch (Exception unavailable) { /* Unknown, never a fabricated timeout or raw error. */ }
            if (Thread.currentThread().isInterrupted()) return;
            TroubleshootModel.Observation after = capture();
            TroubleshootModel.PortState portState = TroubleshootModel.portState(result);
            boolean stale = !TroubleshootModel.sameProbeContext(before, after, target);
            long duration = Math.max(0, SystemClock.elapsedRealtime() - started);
            main.post(() -> {
                if (!active || token != request || isFinishing()) return;
                setBusy(false);
                renderObservation(after);
                String outcome;
                switch (portState) {
                    case OPEN: outcome = t("TCP-порт открыт на момент проверки.", "The TCP port was open at the time of the check."); break;
                    case CLOSED: outcome = t("Получен TCP-ответ об отклонении подключения: порт закрыт на момент проверки.", "A TCP rejection response was received: the port was closed at the time of the check."); break;
                    case TIMEOUT: outcome = t("Нет ответа за время проверки. Причина не установлена: этот результат не различает сетевой сбой, фильтрацию и отсутствие игрового сервера.", "No response was received within the check period. The cause is unknown: this result does not distinguish a network failure, filtering or an unavailable game server."); break;
                    default: outcome = t("Неизвестно: проверку через текущий Radmin-туннель выполнить не удалось. Проверьте подключение и повторите запрос.", "Unknown: the check could not be completed through the current Radmin tunnel. Check the connection and try again.");
                }
                if (stale) outcome = t("Результат не подтверждает текущее состояние: контекст подключения изменился или его актуальность неизвестна. Повторите проверку.", "This result does not confirm the current state: the connection context changed or its validity is unknown. Run the check again.") + "\n\n" + outcome;
                outcome += "\n\n" + t("Проверенная пара: ", "Checked endpoint: ") + target.ip + ":" + target.port
                        + "\n" + t("Длительность: ", "Duration: ") + duration + " ms"
                        + "\n" + t("TCP не подтверждает готовность UDP/Bedrock или совместимость игры.", "TCP does not confirm UDP/Bedrock readiness or game compatibility.");
                card(probeResult, t("Результат TCP-проверки", "TCP check result"), outcome,
                        t("Radmin: проверка TCP по указанным адресу и порту", "Radmin: TCP probe of the specified address and port"), after.observedAt);
            });
        });
    }

    private void card(LinearLayout parent, String title, String message, String source, long time) {
        LinearLayout card = EasyUi.col(this);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(EasyUi.shape(this, EasyUi.dark(this) ? 0xff203451 : 0xffffffff, 16));
        TextView heading = EasyUi.text(this, title, 17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(heading);
        card.addView(EasyUi.text(this, message, 14));
        card.addView(EasyUi.text(this, t("Источник: ", "Source: ") + source + "\n" + when(time), 12));
        parent.addView(card, EasyUi.actionParams(this));
    }

    private String peerState(String state) {
        if (state.equals("Connected")) return t("подключён", "connected");
        if (state.equals("Connecting")) return t("подключается", "connecting");
        if (state.equals("Disconnected")) return t("отключён", "disconnected");
        return t("состояние неизвестно", "state unknown");
    }

    private String when(long time) {
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                java.text.DateFormat.MEDIUM, locale).format(new Date(time));
    }
    private String t(String ru, String en) { return EasyUi.t(this, ru, en); }
    private int dp(int value) { return EasyUi.dp(this, value); }
}
