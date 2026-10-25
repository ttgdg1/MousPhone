# Windows layout mapping provenance

The Android mapper implements these exact Windows keyboard layouts, with **Caps Lock off**:

| App | Windows KLID | Microsoft layout DLL |
| --- | --- | --- |
| EN / US | 00000409 | kbdus.dll |
| RU | 00000419 | kbdru.dll |
| UK | 00000809 | kbduk.dll |
| DE | 00000407 | kbdgr.dll |
| FR | 0000040C | kbdfr.dll |
| ES | 0000040A | kbdsp.dll |

The generator `extract_windows_layouts.py` reads the **installed Windows DLLs' static KBDTABLES** through their `KbdLayerDescriptor` export. It does not install DLLs, call `LoadKeyboardLayout`, activate a layout, set keyboard state or inject input. Regenerate with 64-bit Python on Windows, from any folder:

```powershell
python tools/layouts/extract_windows_layouts.py
```

It writes `WindowsLayoutData.java`, the inverse character lookup used by the app; `tests/fixtures/windows-key-events.tsv`, the separate physical-key/dead-composition tables used by `CheckLayouts`; and `windows-layouts.json`, which retains the DLL SHA-256 hashes and full conversion data. No Windows DLL is shipped or required on Android.

For non-modifier text keys, PC Set-1 scan codes are converted to standard USB HID Keyboard/Keypad usages. Shift is HID bit 0x02; AltGr is **right Alt**, HID bit 0x40 (not left Alt). The tables include the ISO extra key, HID usage 0x64, used for UK backslash and several DE/FR/ES symbols. AltGr and Shift+AltGr are taken from Windows' Ctrl+Alt modification columns. The ordinary four text layers are used, never Ctrl-only control/shortcut layers.

Every dead-key character is a **finished sequence**: accent key followed by a base character; a literal accent is followed by Space. Each individual stroke requires a key-up report before the next. Sending the first accent alone would leave Windows' compose state pending and is not supported. These tables do not toggle Caps Lock or use locale/codepage-dependent Alt+numpad tricks.

Legacy French AZERTY (`0000040C`) has no Caps-Lock-off direct/dead sequence for **É, Ç, œ, æ**. Those characters are rejected before a text edit is sent. Common lowercase French letters (`éèàçù`), circumflex/diaeresis combinations, and grave-accent capitals (`ÀÈÙ`) are supported. German umlauts/ß and Spanish ñ plus accented vowels use the actual physical mappings. Not every language's character is available in every layout. This is physical HID typing, not arbitrary Unicode injection; decomposed combining input and emoji remain unsupported.

Primary references:

- [Microsoft keyboard layout samples and table design](https://github.com/microsoft/Windows-driver-samples/tree/main/input/layout)
- [Microsoft Windows keyboard layout identifiers and visualizations](https://learn.microsoft.com/en-us/globalization/windows-keyboard-layouts)
- [Microsoft keyboard/HID input model](https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/keyboard-and-mouse-hid-client-drivers)
- [Microsoft `ToUnicodeEx`, including dead-key state behavior](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-tounicodeex)

The generated lookup is independent of the UI language. Windows must already have the exact corresponding layout active in the target window. The app cannot query or acknowledge that state over its HID channel.
