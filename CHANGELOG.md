# История изменений / Release notes

Изменения перечислены от новых версий к предыдущим. Даты указаны только для подтверждённых выпусков.

## 0.5.3 — 2026-10-18

### Русский

**Редакционное обновление истории изменений**

- История изменений приведена к единому официальному стилю на русском и английском языках.
- Уточнены формулировки в истории изменений и сопроводительной документации с сохранением сведений о версиях, возможностях и ограничениях.

### English

**Release history editorial update**

- Standardized the Russian and English release history in a consistent formal style.
- Revised wording in the release history and accompanying documentation while preserving information about versions, capabilities and limitations.

## 0.5.2 — 2026-10-18

### Русский

**Оптимизация интерфейса и навигации**

- Оптимизирована компоновка главного экрана: основные действия перенесены выше, области нажатия значков увеличены.
- Настройки сгруппированы по назначению. Добавлено отображение выбранной темы и текущего состояния подключения.
- Унифицировано синее оформление экранов серверов, приглашений и игровых инструментов.
- Добавлено отображение хода проверок. Кнопка «Назад» остаётся доступной во время их выполнения.
- Уточнены элементы выбора получателя и сообщения об отсутствии подключения в чате.
- При обновлении истории чата сохраняется положение прокрутки, если открыты предыдущие сообщения.

### English

**Interface and navigation improvements**

- Optimized the home screen layout by moving primary actions higher and increasing icon touch target sizes.
- Grouped settings by purpose and added display of the selected theme and current connection state.
- Standardized the blue appearance of server, invitation and game tool screens.
- Added progress indicators for checks. The Back button remains available while checks are running.
- Clarified recipient selection controls and disconnected-state guidance in chat.
- Preserved the chat history scroll position during updates when earlier messages are being viewed.

## 0.5.1

### Русский

**Исправления компоновки интерфейса**

- Исправлено размещение текста и кнопок на главном экране и экране чата.
- Скорректированы отступы и переносы текста при увеличенном системном размере шрифта.
- Добавлена история обновлений на русском и английском языках, доступная в приложении без подключения к интернету.

### English

**Interface layout fixes**

- Corrected text and button placement on the home and chat screens.
- Adjusted spacing and text wrapping for larger system font sizes.
- Added in-app update history in Russian and English, available without an internet connection.

## 0.5.0

### Русский

**Текстовый чат и плавающая панель управления**

- Упрощена структура главного экрана. Добавлен поиск устройств по имени и IP-адресу.
- Добавлен личный текстовый чат между обновлёнными Android-клиентами «Синей сети» с подтверждением доставки, зашифрованной историей и быстрыми фразами. Чат несовместим с Windows-клиентом Radmin.
- Добавлена плавающая панель с быстрыми действиями и чатом поверх игрового экрана. Для её отображения требуется разрешение Android «Поверх других приложений».
- Добавлены копирование собственного VPN-адреса из плавающей панели и режим «Не гасить экран».

### English

**Text chat and floating control panel**

- Simplified the home screen structure and added device search by name or IP address.
- Added private text chat between updated Blue Network Android clients, including delivery confirmations, encrypted history and quick phrases. Chat is not compatible with the Windows Radmin client.
- Added a floating panel with quick actions and chat over the game screen. Android's Display over other apps permission is required.
- Added copying of the device's VPN address from the floating panel and a Keep screen on option.

## 0.4.0 — 2026-10-14

### Русский

**Подписки VLESS и сетевые инструменты**

- Добавлены HTTPS-подписки VLESS с поддержкой до 24 серверов, ежедневным обновлением, проверкой времени отклика и ограниченным переключением на резервный сервер.
- Добавлен выбор приложений, использующих VLESS, на Android 10 и более поздних версиях.
- В объединённом режиме обеспечено сохранение блокирующего туннеля при сбоях, пока работает VPN-служба. Принудительная остановка приложения средствами Android и ручное отключение VPN снимают защиту.
- Добавлены избранные устройства, проверка игрового TCP-порта, QR-приглашения и плитка быстрых настроек Android.
- Добавлена история задержки и доли проверочных запросов без ответа за последние 10 минут.

### English

**VLESS subscriptions and network tools**

- Added HTTPS VLESS subscriptions supporting up to 24 servers, daily refresh, response-time checks and bounded failover.
- Added per-app VLESS selection on Android 10 and later.
- In combined mode, the blocking tunnel is retained during failures while the VPN service is running. Android force-stop and manual VPN disconnection remove this protection.
- Added favorite devices, game TCP port checks, QR invitations and an Android Quick Settings tile.
- Added a ten-minute history of latency and the proportion of unanswered probes.

## 0.3.0 — 2026-10-14

### Русский

**Объединённый режим Radmin и VLESS**

- Добавлена встроенная поддержка VLESS для одновременного использования сети Radmin и интернет-VPN в одном туннеле, без отдельного запуска Happ.
- Добавлено создание сети с телефона без Windows-шлюза.
- Добавлены диагностика подключения, приватный журнал ошибок и индикатор качества VPN на основе измеренной задержки.
- Добавлены русский и английский языки, а также светлая, ночная и системная темы.

### English

**Combined Radmin and VLESS mode**

- Added built-in VLESS support for simultaneous use of the Radmin network and internet VPN in one tunnel, without running Happ separately.
- Added network creation from the phone without a Windows gateway.
- Added connection diagnostics, a private error log and a VPN quality indicator based on measured latency.
- Added Russian and English language options, together with light, dark and system themes.

## 0.2.0 — 2026-10-13

### Русский

**Автоматическое переподключение и защита данных**

- Добавлено автоматическое переподключение с ограниченным числом попыток после сетевого сбоя или смены сети.
- Добавлен экспорт диагностики без идентификаторов и секретных данных, выполняемый только по действию пользователя.
- Усилены проверки отправителя ARP, обработка повреждённых профилей и защита от повторной регистрации при неоднозначном ответе.
- Добавлена защита парольных диалогов от снимков экрана и сохранения введённых данных.

### English

**Automatic reconnection and data protection**

- Added automatic reconnection with a limited number of attempts after network failures or network changes.
- Added user-initiated diagnostics export without identifiers or secret data.
- Strengthened ARP sender validation, corrupt profile handling and protection against repeated registration after an ambiguous response.
- Added protection of password dialogs against screenshots and retention of entered credentials.

## 0.1.0

### Русский

**Первоначальный выпуск для Android**

- Выпущен прямой клиент сетей Radmin VPN на основе OpenRad с русским интерфейсом в синих цветах.
- Реализованы прямые подключения и подключение через ретранслятор, работа с сетями, список участников и измерение задержки.
- Обеспечено зашифрованное хранение идентичности устройства с использованием Android Keystore.
- Пользователем подтверждён сценарий совместной игры в Minecraft с Windows-клиентом Radmin VPN.

### English

**Initial Android release**

- Released a direct Radmin VPN network client based on OpenRad, with a Russian interface in blue.
- Implemented direct and relay connections, network operations, a peer list and latency measurements.
- Implemented encrypted storage of device identity using Android Keystore.
- A user confirmed a Minecraft multiplayer scenario with the Windows Radmin VPN client.

