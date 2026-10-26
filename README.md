# MousPhone

Android 9+ Bluetooth HID remote for Windows: touchpad, mouse, live keyboard input and editable shortcuts. **Nothing needs to be installed or run on the PC.** Remote control uses Bluetooth; no PC server, Wi-Fi connection or internet service is required.

## Languages and keyboard layouts

The app interface supports **Auto / Русский / English / Deutsch / Français / Español**. Open `⚙` and choose **App language**. Auto follows a supported phone language, with English as the fallback. This setting changes the interface only; the Windows layout and Android keyboard language remain separate.

Text input supports these exact standard Windows layouts:

| App label | Windows layout | KLID |
| --- | --- | --- |
| EN | English (United States), US | `00000409` |
| RU | Russian, ЙЦУКЕН | `00000419` |
| UK | English (United Kingdom) | `00000809` |
| DE | German, QWERTZ | `00000407` |
| FR | French, legacy AZERTY | `0000040C` |
| ES | Spanish, Spain | `0000040A` |

In `⚙` → **PC layouts and Win+Space order**, add, remove and reorder layouts to match the layouts **already used by Windows**, in the order displayed by Win+Space. Any pair works, for example **ES → EN**; lists of 3–6 layouts also work, for example **RU → DE → EN**. The initial list is RU → EN. One layout can be kept when no cycling is needed.

Hold the layout button to select the layout currently active in the target Windows window. This only calibrates the app; it sends no key to the PC. A short press sends Win+Space and advances the expected layout in your list, after key release and a 350 ms pause. With one layout, or when the current layout is outside the list, cycling is blocked rather than guessed. Windows does not confirm its active layout or provide its layout list to this HID app. After a PC-side language/window change, calibrate again. Keep **Caps Lock off** and select the Android IME language separately.

## Controls

- `⌨` opens/closes the Android keyboard. Editable quick shortcuts appear **above** the text field.
- Input row: **layout ⇄ — text — Backspace**. The local field contains the current sending fragment, not a copy of the remote document.
- Bottom panel: **left click — right click — editable shortcuts — Enter**. Its shortcuts and the shortcuts above input are stored independently.
- `⚙` can fully hide either panel without leaving an empty row. Saved shortcuts remain; show the panels again in the same settings.
- `＋` adds a shortcut; hold a shortcut to edit, reorder or delete it. Editors and special keys are also available in `☷`.
- One finger moves/taps for left click; two fingers scroll/tap for right click. Double-tap with movement on the second touch drags. `☷` also provides manual left-button hold, modifiers and special keys.

The MousPhone name, original icon, application ID `io.pocketpad.remote`, saved commands and previous settings are retained. The source folder remains `PocketPad` for compatibility.

## Text limitations

Bluetooth HID sends physical key presses; Windows chooses the resulting characters. This is **not arbitrary Unicode input**. A wrong PC layout produces wrong characters even if the app interface language is correct.

- The six tables provide 757 character mappings across layouts, including 124 complete two-key sequences for dead accents and literal accent + Space. Right Alt is used for AltGr. Every key press has a release; accents are completed before the next queued command.
- Examples include UK `£ €`, German `ä ö ü ß`, French `é è ê È` and Spanish `ñ ¿ á Á ü`. These mappings are layout-specific. Legacy French AZERTY with Caps Lock off cannot enter every French character this way: for example `É`, `Ç`, `œ` and `æ` are rejected. Other national variants and custom layouts are not implied by the language names.
- Emoji, unsupported symbols and combining Unicode sequences are rejected before any part of the changed fragment is sent. Mixed-language text may need separate fragments and layout changes.
- Layout changes, calibration and shortcuts start a new local fragment without deleting text on the PC. Keep the PC caret at the end of the current fragment for live edits. If the caret/window changed, calibrate again before editing.
- A lost connection halfway through a dead-key sequence can leave an accent pending in Windows. Check the target field and press Esc on the PC, or send Esc after reconnecting, before continuing. Delivery cannot be guaranteed across a radio disconnection.

## Build and checks

Use **JDK 17**, **Android SDK Platform 35**, **Build Tools 34.0.0** and the included **Gradle Wrapper 8.9**. From the project root:

```powershell
powershell -ExecutionPolicy Bypass -File tests/run-tests.ps1
.\gradlew.bat assembleDebug lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Version: `3.3-beta`, versionCode `11`, minSdk `28`. The bundled development signing key permits compatible beta updates; use your own signing key for a release.

Six JVM check suites have passed, including native-decoded layout fixtures, complete accent sequences and custom layout-order migration/cycling. All 139 translatable resource keys are present in each of the five interface languages. Exact build, lint and UI results are recorded in [CHECKS_RU.md](CHECKS_RU.md). Real Bluetooth HID pairing/input still requires a physical Android phone and Windows PC; emulator checks do not establish radio compatibility. `tools/layouts` contains developer-only fixture authoring tools; users do not need to run them.

For GitHub, upload the **contents** of `PocketPad`, including `.github`, so `settings.gradle` is at the repository root. **Actions → Build debug APK → Run workflow** runs checks and builds the APK; a successful run provides the **MousPhone-debug-apk** artifact. Full Windows instructions: [BUILD_RU.md](BUILD_RU.md).

---

## По-русски: первый запуск

**На ПК ничего устанавливать или запускать не нужно.** Сопрягите телефон с Windows в системных настройках Bluetooth, затем выберите компьютер через `ᛒ` в MousPhone.

1. В `⚙` выберите **язык интерфейса**: Авто, Русский, English, Deutsch, Français или Español. Он не меняет язык ввода на ПК.
2. В **«Раскладки ПК и порядок Win+Пробел»** укажите нужные раскладки из таблицы выше и их порядок в Windows. Всё настраивается на телефоне. Подходит любая пара, например **ES → EN**, или список из 3–6, например **RU → DE → EN**. Добавление и порядок доступны по нажатию на элементы списка.
3. Откройте нужное окно Windows, выключите **Caps Lock**, нажмите `⌨` в MousPhone. Удержанием кнопки раскладки слева укажите текущую раскладку этого окна.
4. Печатайте фрагментами. Короткое нажатие кнопки раскладки отправляет **Win+Пробел** и меняет ожидаемую раскладку по вашему списку после отпускания клавиш и паузы 350 мс. Это ожидание, а не подтверждение от Windows. Язык клавиатуры Android выбирается отдельно.
5. Если переключили раскладку/окно на ПК или переместили курсор в другое поле, снова выполните сверку. При одной раскладке переключение не отправляется; если текущая раскладка отсутствует в списке, сначала сверьте её или измените список.

Интерфейс переведён на пять языков; настройки языка, раскладок, скрытия панелей и два списка команд сохраняются независимо. Название **MousPhone** и исходная иконка сохранены.

HID передаёт клавиши, а не любой Unicode. Поддерживаются только указанные варианты раскладок и доступные в них символы; эмодзи и несовместимое изменение отклоняются целиком до отправки. Акценты DE/FR/ES передаются завершёнными последовательностями, AltGr — правым Alt. Для старой французской AZERTY при выключенном Caps Lock, например, **É, Ç, œ, æ не поддерживаются**. После обрыва связи посреди акцента перед продолжением может понадобиться Esc.

Сборка и загрузка исходников на GitHub: **[BUILD_RU.md](BUILD_RU.md)**. Проверки и их ограничения: **[CHECKS_RU.md](CHECKS_RU.md)**.
