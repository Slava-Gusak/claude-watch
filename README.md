# claude-watch

Лимиты Claude и статус Claude Desktop / Claude Code на часах с Wear OS (Samsung Galaxy Watch FE).

```
 ПК (агент + хуки Claude Code) ──POST /pc──►  VPS relay  ◄──GET /status── часы (приложение + тайл)
                                              │
                               GET /api/oauth/usage (свой вход в Claude)
```

## 1. VPS

```bash
curl -fsSL https://raw.githubusercontent.com/Slava-Gusak/claude-watch/main/relay/install.sh | sudo bash
```

Скрипт выведет ссылку `https://mine.slava-gusak.ru:<порт>/admin?key=…`. Открой её на телефоне:

1. **Войти в Claude** → подтверди доступ → скопируй код → вставь → «Сохранить код».
2. **Показать код для часов** → 6 цифр.

Обновление relay: та же команда (токены и вход сохраняются). Логи: `journalctl -u claude-watch -f`.

## 2. Часы

APK: <https://github.com/Slava-Gusak/claude-watch/releases/latest/download/claude-watch.apk>

Установка без ПК, с телефона:

1. Часы: **Настройки → О часах → ПО → 5 раз по «Версия ПО»** → появятся «Параметры разработчика».
2. **Параметры разработчика → Отладка ADB → вкл**, **Отладка по Wi-Fi → вкл** (часы и телефон в одной Wi-Fi сети;
   если Wi-Fi нет — раздай с телефона точку доступа и подключи к ней часы).
3. На телефоне установи **Bugjaeger** или **Wear Installer 2** (Google Play), скачай APK по ссылке выше.
4. В «Отладке по Wi-Fi» на часах: **Связать новое устройство** → введи IP:порт и код в приложении на телефоне → установи APK.
5. Открой «Claude лимиты» на часах → введи 6 цифр с /admin.
6. Тайл: долгое нажатие на циферблат → свайп к тайлам → «+» → «Claude лимиты».

Экраны: **1** — лимиты (внешнее кольцо — 5 часов, внутреннее — неделя) и Claude Desktop, **свайп** → **2** — Claude Code,
**3** — обновить / отвязать.

## 3. ПК (когда будешь рядом)

Двойной клик по `pc/install.bat`. Он спросит строку из блока «Для ПК» на странице /admin, добавит хуки в
`~/.claude/settings.json` (с бэкапом `settings.json.bak-claudewatch`) и поставит агент в автозапуск
(задача планировщика `ClaudeWatchAgent`).

Без агента «в работе» определяется по тому, растёт ли 5-часовой лимит.

Удаление агента: `Unregister-ScheduledTask ClaudeWatchAgent`, удалить `%LOCALAPPDATA%\ClaudeWatch`,
вернуть `settings.json` из бэкапа.

## Заметки

* Ключ подписи `wear/signing/claude-watch.jks` лежит в репозитории специально — чтобы новые сборки
  ставились поверх старых. Это личное приложение не из магазина.
* Токены Claude хранятся только на VPS (`/opt/claude-watch/state.json`, права 600).
