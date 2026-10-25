package io.pocketpad.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One touch surface, optional Steam-Link-style controls, and live HID keyboard. */
public final class MainActivity extends Activity implements BluetoothHidController.Listener {
    private static final int REQ_BT_PERMS = 10;
    private static final int REQ_ENABLE = 11;
    private static final int REQ_DISCOVER = 12;
    private static final int BG = 0xFF0C101A;
    private static final int PANEL = 0xFF182131;
    private static final int BTN = 0xFF283448;
    private static final int BLUE = 0xFF73AFFF;
    private static final int WHITE = 0xFFF3F7FF;
    private static final int MUTED = 0xFFA1AEC1;
    private static final int GREEN = 0xFF75E0AB;

    private Context uiContext;
    private BluetoothHidController controller;
    private ShortcutStore shortcutStore;
    private ShortcutStore dockShortcutStore;
    private FrameLayout surface;
    private View floatingPanel;
    private TouchpadView touchpad;
    private TextView statusView;
    private LinearLayout deviceList;
    private LinearLayout quickButtons;
    private LinearLayout dockButtons;
    private View quickAccessPanel;
    private View bottomPanel;
    private LinearLayout modifierRow;
    private Button holdButton;
    private TextView speedText;
    private EditText liveField;
    private Button hostLayoutButton;
    private TextView liveFeedback;
    private KeyboardMapper.HostLayout hostLayout;
    private boolean menuOpen, settingsOpen, devicesOpen, keyboardOpen, immersive;
    private boolean leftHeld;
    private boolean settingText;
    private boolean switchingLanguage;
    private boolean hideQuickCommands;
    private boolean hideBottomPanel;
    private String liveBuffer = "";
    private String remoteBuffer = "";
    private String statusMessage = "";
    private boolean statusConnected;
    private float sensitivity;
    private int stickyModifiers;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        uiContext = UiLanguage.wrap(base);
    }

    @Override public Resources getResources() {
        return uiContext == null ? super.getResources() : uiContext.getResources();
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusMessage = getString(R.string.status_preparing);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        shortcutStore = new ShortcutStore(this);
        dockShortcutStore = ShortcutStore.forDock(this);
        sensitivity = getPreferences(MODE_PRIVATE).getFloat("sensitivity", 1.35f);
        hideQuickCommands = getPreferences(MODE_PRIVATE).getBoolean("hideQuickCommands", false);
        hideBottomPanel = getPreferences(MODE_PRIVATE).getBoolean("hideBottomPanel", false);
        hostLayout = KeyboardMapper.HostLayout.fromPreference(
            getPreferences(MODE_PRIVATE).getString("hostLayout", "RU"));
        controller = new BluetoothHidController(this, this);
        buildUi();
        checkBluetooth();
    }

    @Override protected void onResume() {
        super.onResume();
        if (controller != null) {
            controller.setForeground(true);
            if (controller.hasPermission() && controller.isEnabled()) {
                controller.start(); refreshDevices();
            }
        }
    }
    @Override protected void onPause() {
        if (touchpad != null) touchpad.cancelActiveGesture();
        if (controller != null) controller.setForeground(false);
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (controller != null) controller.close();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        uiContext = UiLanguage.wrap(getBaseContext());
        refreshLocalizedConnectionStatus();
        buildUi();
    }

    private void checkBluetooth() {
        if (!controller.hasBluetooth()) { onStatus(getString(R.string.status_bluetooth_unavailable), false); return; }
        if (!controller.hasPermission() || !advertisePermission()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[] {
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQ_BT_PERMS);
            return;
        }
        if (!controller.isEnabled()) {
            onStatus(getString(R.string.status_enable_bluetooth), false);
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            } catch (SecurityException e) {
                onStatus(getString(R.string.status_allow_bluetooth), false);
            }
            return;
        }
        controller.start();
        refreshDevices();
    }
    private boolean advertisePermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(
            Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_BT_PERMS) {
            if (controller.hasPermission() && advertisePermission()) checkBluetooth();
            else onStatus(getString(R.string.status_allow_bluetooth), false);
        }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ENABLE) {
            if (res == RESULT_OK || controller.isEnabled()) checkBluetooth();
            else onStatus(getString(R.string.status_enable_bluetooth), false);
        } else if (req == REQ_DISCOVER) checkBluetooth();
    }

    private void buildUi() {
        // Never recreate HID service during orientation/visual or locale changes.
        if (touchpad != null) touchpad.cancelActiveGesture();
        if (liveField != null) liveBuffer = liveField.getText().toString();
        liveField = null; statusView = null; deviceList = null;
        hostLayoutButton = null; liveFeedback = null;
        quickAccessPanel = null; bottomPanel = null;
        modifierRow = null; quickButtons = null; dockButtons = null; holdButton = null; speedText = null;
        if (immersive) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(dp(7), dp(5), dp(7), dp(5));
        root.setFocusableInTouchMode(true);
        buildToolbar(root);
        surface = new FrameLayout(this);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        touchpad = new TouchpadView(this, controller);
        touchpad.setSensitivity(sensitivity);
        surface.addView(touchpad, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout footer = vertical();
        if (keyboardOpen) {
            buildQuickAccess(footer);
            buildLiveInput(footer);
        }
        buildMouseDock(footer);
        applyPanelVisibility();
        root.addView(footer);
        setContentView(root);
        // The panel floats OVER the pad instead of stealing its space.
        if (menuOpen) showFloating("keys");
        else if (settingsOpen) showFloating("settings");
        else if (devicesOpen) showFloating("devices");
        else floatingPanel = null;
        if (keyboardOpen && liveField != null) showImeDelayed();
        else root.requestFocus();
    }

    private void buildToolbar(LinearLayout root) {
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text(getString(R.string.app_name), 18, WHITE, true);
        bar.addView(logo, new LinearLayout.LayoutParams(-2, dp(45)));
        Button bt = button("ᛒ", BLUE, PANEL);
        bt.setTextSize(21);
        bt.setContentDescription(getString(R.string.bluetooth_devices));
        bt.setOnClickListener(v -> togglePanel("devices"));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(43), dp(42));
        bp.leftMargin = dp(7);
        bar.addView(bt, bp);
        statusView = text(statusMessage, 10, statusConnected ? GREEN : MUTED, false);
        statusView.setSingleLine();
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setOnClickListener(v -> togglePanel("devices"));
        bar.addView(statusView, new LinearLayout.LayoutParams(0, dp(43), 1));
        Button kb = button("⌨", BLUE, BTN);
        kb.setTextSize(20);
        kb.setContentDescription(getString(R.string.open_keyboard));
        kb.setOnClickListener(v -> toggleKeyboard());
        bar.addView(kb, new LinearLayout.LayoutParams(dp(42), dp(42)));
        Button speed = button("⚙", WHITE, BTN);
        speed.setTextSize(20);
        speed.setContentDescription(getString(R.string.open_settings));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(42), dp(42));
        sp.leftMargin = dp(4);
        bar.addView(speed, sp);
        speed.setOnClickListener(v -> togglePanel("settings"));
        Button menu = button("☷", BLUE, BTN);
        menu.setTextSize(21);
        menu.setContentDescription(getString(R.string.open_keys));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(43), dp(42));
        mp.leftMargin = dp(4);
        bar.addView(menu, mp);
        menu.setOnClickListener(v -> togglePanel("keys"));
        root.addView(bar);
    }

    private void buildMouseDock(LinearLayout root) {
        LinearLayout dock = horizontal();
        bottomPanel = dock;
        Button left = button(getString(R.string.mouse_left), WHITE, BTN);
        left.setContentDescription(getString(R.string.mouse_left_click));
        left.setOnClickListener(v -> controller.click(1));
        dock.addView(left, new LinearLayout.LayoutParams(dp(51), dp(45)));
        Button right = button(getString(R.string.mouse_right), WHITE, BTN);
        right.setContentDescription(getString(R.string.mouse_right_click));
        right.setOnClickListener(v -> controller.click(2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(51), dp(45));
        rp.leftMargin = dp(4);
        dock.addView(right, rp);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        dockButtons = horizontal();
        scroller.addView(dockButtons);
        LinearLayout.LayoutParams shortcuts = new LinearLayout.LayoutParams(0, dp(45), 1);
        shortcuts.leftMargin = dp(5);
        shortcuts.rightMargin = dp(5);
        dock.addView(scroller, shortcuts);
        Button enter = button(getString(R.string.key_enter), WHITE, BTN);
        enter.setOnClickListener(v -> sendEnter());
        dock.addView(enter, new LinearLayout.LayoutParams(dp(57), dp(45)));
        root.addView(dock, margins(0, 4, 0, 0));
        updateQuickButtons();
    }

    private void buildQuickAccess(LinearLayout root) {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        quickAccessPanel = scroller;
        scroller.setHorizontalScrollBarEnabled(false);
        quickButtons = horizontal();
        scroller.addView(quickButtons);
        root.addView(scroller, margins(0, 4, 0, 0));
    }

    private void applyPanelVisibility() {
        // GONE removes each complete row and its margins from layout. The live
        // input, touchpad and Bluetooth controller stay in place while toggling.
        if (quickAccessPanel != null)
            quickAccessPanel.setVisibility(hideQuickCommands ? View.GONE : View.VISIBLE);
        if (bottomPanel != null)
            bottomPanel.setVisibility(hideBottomPanel ? View.GONE : View.VISIBLE);
    }

    private void toggleKeyboard() {
        resetLiveInput();
        if (keyboardOpen) {
            hideIme();
            keyboardOpen = false;
            liveBuffer = "";
            remoteBuffer = "";
        } else {
            keyboardOpen = true;
            liveBuffer = "";
            remoteBuffer = ""; // new remote input session; no old text is resent
            closePanels();
        }
        buildUi();
    }

    private void buildLiveInput(LinearLayout root) {
        LinearLayout line = horizontal();
        line.setGravity(Gravity.CENTER_VERTICAL);
        hostLayoutButton = button(hostLayout.label + " ⇄", BLUE, BTN);
        hostLayoutButton.setTextSize(12);
        hostLayoutButton.setContentDescription(getString(R.string.layout_button_hint));
        hostLayoutButton.setEnabled(!switchingLanguage);
        hostLayoutButton.setOnClickListener(v -> switchHostLanguage());
        hostLayoutButton.setOnLongClickListener(v -> { chooseHostLayout(); return true; });
        LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(dp(58), dp(44));
        languageParams.rightMargin = dp(5);
        line.addView(hostLayoutButton, languageParams);
        EditText input = new EditText(this);
        liveField = input;
        input.setSingleLine(true);
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setHint(getString(R.string.input_hint));
        input.setTextSize(13);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(bg(PANEL, 10));
        // Disable speculative autocomplete because Bluetooth HID transmits key strokes.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setText(liveBuffer);
        input.setSelection(liveBuffer.length());
        input.setEnabled(controller.isConnected() && !switchingLanguage);
        input.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                input.post(() -> input.setSelection(input.length()));
            }
            return false;
        });
        input.setOnEditorActionListener((v, id, event) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (event != null &&
                event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendEnter();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (settingText) return;
                String next = value.toString();
                liveBuffer = next;
                if (switchingLanguage) {
                    updateLiveFeedback(null);
                    return;
                }
                if (!controller.isConnected()) {
                    updateLiveFeedback(getString(R.string.input_disconnected));
                    return;
                }
                LiveTextDiff.Update update = LiveTextDiff.plan(remoteBuffer, next, hostLayout);
                if (!update.accepted()) {
                    updateLiveFeedback(unsupportedMessage(update.unsupportedCodePoint));
                    return;
                }
                updateLiveFeedback(null);
                // Reconcile any IME composition/autocorrection against the remote
                // line by rewriting only the changed suffix. No second Send tap.
                for (int i=0; i<update.backspaces; i++) controller.key(0, 0x2A);
                if (controller.sendText(update.text, hostLayout)) remoteBuffer = next;
            }
        });
        line.addView(input, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button backspace = button("⌫", BLUE, BTN);
        backspace.setTextSize(23);
        backspace.setContentDescription(getString(R.string.backspace_hint));
        backspace.setOnClickListener(v -> sendBackspace());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(47), dp(44));
        dp.leftMargin = dp(5);
        line.addView(backspace, dp);
        root.addView(line, margins(0, 4, 0, 3));
        liveFeedback = text("", 11, MUTED, false);
        root.addView(liveFeedback);
        LiveTextDiff.Update current = LiveTextDiff.plan(remoteBuffer, liveBuffer, hostLayout);
        updateLiveFeedback(current.accepted() ? null : unsupportedMessage(current.unsupportedCodePoint));
    }

    private String unsupportedMessage(int codePoint) {
        String symbol = new String(Character.toChars(codePoint));
        return getString(R.string.input_unsupported, symbol,
            String.format(Locale.US, "U+%04X", codePoint), hostLayout.label);
    }

    private void updateLiveFeedback(String error) {
        if (liveFeedback == null) return;
        liveFeedback.setText(error != null ? error : switchingLanguage ? getString(R.string.switching_layout)
            : controller.isConnected() ? getString(R.string.input_ready, hostLayout.label)
            : getString(R.string.connect_bluetooth_first));
        liveFeedback.setTextColor(error == null ? MUTED : 0xFFFFBE7A);
    }

    private void resetLiveInput() {
        // A session/layout boundary must never erase or replay text on the PC.
        settingText = true;
        if (liveField != null) {
            liveField.clearComposingText();
            liveField.setText("");
        }
        liveBuffer = "";
        remoteBuffer = "";
        settingText = false;
        updateLiveFeedback(null);
    }

    private void chooseHostLayout() {
        if (!keyboardIdle()) return;
        KeyboardMapper.HostLayout[] layouts = KeyboardMapper.HostLayout.values();
        String[] labels = new String[layouts.length];
        for (int i = 0; i < layouts.length; i++)
            labels[i] = layouts[i].label + " — " + layoutName(layouts[i]);
        new AlertDialog.Builder(this)
            .setTitle(R.string.choose_current_layout)
            .setSingleChoiceItems(labels, hostLayout.ordinal(), (dialog, which) -> {
                if (!keyboardIdle()) { dialog.dismiss(); return; }
                if (layouts[which] != hostLayout) {
                    hostLayout = layouts[which];
                    getPreferences(MODE_PRIVATE).edit().putString("hostLayout", hostLayout.name()).apply();
                    if (hostLayoutButton != null) hostLayoutButton.setText(hostLayout.label + " ⇄");
                }
                // Reconfirming the same layout can also mean a new Windows
                // caret/window. Do not keep a fragment from the previous field.
                resetLiveInput();
                toast(getString(R.string.layout_calibrated, layoutName(hostLayout)));
                dialog.dismiss();
                if (settingsOpen) showFloating("settings");
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void switchHostLanguage() {
        if (!controller.isConnected()) { toast(getString(R.string.connect_first)); return; }
        if (controller.isKeyboardBusy() || switchingLanguage) {
            toast(getString(R.string.wait_keyboard)); return;
        }
        List<KeyboardMapper.HostLayout> layouts = orderedLayouts();
        if (!layouts.contains(hostLayout)) {
            toast(getString(R.string.layout_not_in_cycle));
            return;
        }
        final KeyboardMapper.HostLayout next = HostLayoutPreferences.next(layouts, hostLayout);
        if (next == null) {
            toast(getString(R.string.single_layout));
            return;
        }
        stickyModifiers = 0;
        repaintModifiers();
        resetLiveInput();
        switchingLanguage = true;
        if (liveField != null) liveField.setEnabled(false);
        if (hostLayoutButton != null) hostLayoutButton.setEnabled(false);
        updateLiveFeedback(null);
        boolean queued = controller.switchLanguage(() -> {
            hostLayout = next; // Expected layout: HID cannot read the Windows state.
            getPreferences(MODE_PRIVATE).edit().putString("hostLayout", hostLayout.name()).apply();
            switchingLanguage = false;
            resetLiveInput();
            if (hostLayoutButton != null) {
                hostLayoutButton.setText(hostLayout.label + " ⇄");
                hostLayoutButton.setEnabled(true);
            }
            if (liveField != null) liveField.setEnabled(controller.isConnected());
            if (keyboardOpen && liveField != null) showImeDelayed();
        });
        if (!queued) {
            switchingLanguage = false;
            if (hostLayoutButton != null) hostLayoutButton.setEnabled(true);
            if (liveField != null) liveField.setEnabled(controller.isConnected());
            updateLiveFeedback(null);
        }
    }

    private void sendBackspace() {
        if (!controller.isConnected()) { toast(getString(R.string.connect_first)); return; }
        if (switchingLanguage) return;
        if (keyboardOpen && liveField != null && liveField.length() > 0) {
            Editable value = liveField.getText();
            int end = value.length();
            int start = Character.offsetByCodePoints(value, end, -1);
            value.delete(start, end); // Reconciles against the remote text via TextWatcher.
            liveField.setSelection(value.length());
        } else pressKey(0x2A);
    }

    private void showImeDelayed() {
        EditText field = liveField;
        field.postDelayed(() -> {
            if (field != liveField || !keyboardOpen || !field.isEnabled()) return;
            field.requestFocus();
            ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 190);
    }
    private void hideIme() {
        if (liveField == null) return;
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(liveField.getWindowToken(), 0);
        liveField.clearFocus();
    }

    private void closePanels() {
        menuOpen = false; settingsOpen = false; devicesOpen = false;
    }
    private void togglePanel(String which) {
        boolean wasOpen = "keys".equals(which) ? menuOpen :
            "settings".equals(which) ? settingsOpen : devicesOpen;
        closePanels();
        if (!wasOpen) {
            if ("keys".equals(which)) menuOpen = true;
            else if ("settings".equals(which)) settingsOpen = true;
            else devicesOpen = true;
            // Hide the IME while the command overlay is in use.
            if (keyboardOpen && liveField != null) hideIme();
            showFloating(which);
        } else removeFloating();
    }
    private void removeFloating() {
        if (surface != null && floatingPanel != null) surface.removeView(floatingPanel);
        floatingPanel = null;
    }
    private void showFloating(String which) {
        if (surface == null) return;
        removeFloating();
        LinearLayout card = vertical();
        card.setPadding(dp(10), dp(9), dp(10), dp(11));
        card.setBackground(bg(PANEL, 15));
        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        String title = "keys".equals(which) ? getString(R.string.title_keys) :
            "settings".equals(which) ? getString(R.string.title_settings) : getString(R.string.title_bluetooth);
        head.addView(text(title, 13, WHITE, true), new LinearLayout.LayoutParams(0, dp(36), 1));
        Button close = button("✕", WHITE, BTN);
        close.setOnClickListener(v -> { closePanels(); removeFloating(); });
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(36)));
        card.addView(head);
        if ("keys".equals(which)) buildKeysPanel(card);
        else if ("settings".equals(which)) buildSettingsPanel(card);
        else buildDevicesPanel(card);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(card);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        lp.topMargin = dp(5);
        // Scroll instead of growing over the entire touchpad in portrait.
        int max = getResources().getDisplayMetrics().heightPixels * 63 / 100;
        lp.height = Math.min(max, calculatePanelHeight(which));
        surface.addView(scroll, lp);
        scroll.setClipToOutline(true);
        floatingPanel = scroll;
    }
    private int calculatePanelHeight(String name) {
        return dp("keys".equals(name) ? 405 : "devices".equals(name) ? 320 : 265);
    }

    private void buildKeysPanel(LinearLayout card) {
        card.addView(text("F1–F12", 11, MUTED, true), margins(0, 5, 0, 5));
        for (int row=0; row<2; row++) {
            LinearLayout line = horizontal();
            for (int col=0; col<6; col++) {
                final int key = 0x3A + row*6 + col;
                Button b = button("F"+(row*6+col+1), WHITE, BTN);
                b.setTextSize(11);
                b.setOnClickListener(v -> pressKey(key));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(41), 1);
                if (col > 0) lp.leftMargin = dp(4);
                line.addView(b, lp);
            }
            card.addView(line, margins(0, 0, 0, 4));
        }
        card.addView(text(getString(R.string.modifiers_heading), 10, MUTED, true), margins(0, 9, 0, 5));
        modifierRow = horizontal();
        String[] mods = {"Ctrl", "Alt", "Shift", "Win"};
        int[] bits = {KeyboardMapper.CTRL, KeyboardMapper.ALT,
            KeyboardMapper.SHIFT, KeyboardMapper.META};
        for (int i=0;i<4;i++) {
            final int bit = bits[i];
            Button b = button(mods[i], WHITE, BTN);
            b.setTag(bit);
            b.setOnClickListener(v -> { stickyModifiers ^= bit; repaintModifiers(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(39), 1);
            if (i > 0) lp.leftMargin = dp(4);
            modifierRow.addView(b,lp);
        }
        card.addView(modifierRow);
        repaintModifiers();
        card.addView(text(getString(R.string.special_keys), 10, MUTED, true), margins(0, 8, 0, 5));
        LinearLayout nav = horizontal();
        String[] names = {"Esc", "Tab", getString(R.string.key_space), getString(R.string.key_delete), "↑", "↓", "←", "→"};
        int[] codes = {0x29, 0x2B, 0x2C, 0x4C, 0x52, 0x51, 0x50, 0x4F};
        for (int i=0; i<names.length; i++) {
            if (i == 4) { card.addView(nav, margins(0, 0, 0, 4)); nav = horizontal(); }
            final int code = codes[i];
            Button b = button(names[i], WHITE, BTN);
            b.setOnClickListener(v -> pressKey(code));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(39), 1);
            if (i % 4 > 0) lp.leftMargin = dp(4);
            nav.addView(b, lp);
        }
        card.addView(nav);
        LinearLayout actions = horizontal();
        holdButton = button(leftHeld ? getString(R.string.release_left) : getString(R.string.hold_left), BLUE, BTN);
        holdButton.setOnClickListener(v -> toggleHold());
        actions.addView(holdButton, new LinearLayout.LayoutParams(0, dp(39), 1));
        Button rotate = button(getString(R.string.rotate), WHITE, BTN);
        rotate.setOnClickListener(v -> setRequestedOrientation(
            getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(39), 1);
        rp.leftMargin = dp(5);
        actions.addView(rotate,rp);
        Button full = button(immersive ? getString(R.string.normal_screen) : getString(R.string.full_screen_short), WHITE, BTN);
        full.setOnClickListener(v -> { immersive = !immersive; buildUi(); });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(39), 1);
        fp.leftMargin = dp(5);
        actions.addView(full, fp);
        card.addView(actions, margins(0, 8, 0, 4));
        LinearLayout title = horizontal();
        title.addView(text(getString(R.string.quick_commands_heading), 10, MUTED, true),
            new LinearLayout.LayoutParams(0, dp(33), 1));
        Button edit = button(getString(R.string.edit_gear), BLUE, BTN);
        edit.setOnClickListener(v -> showShortcutManager(shortcutStore));
        title.addView(edit, new LinearLayout.LayoutParams(dp(110), dp(33)));
        card.addView(title);
        Button editDock = button(getString(R.string.configure_dock), BLUE, BTN);
        editDock.setOnClickListener(v -> showShortcutManager(dockShortcutStore));
        card.addView(editDock, margins(0, 5, 0, 3, 38));
        card.addView(text(getString(R.string.shortcut_help),
            11, MUTED, false));
    }
    private void repaintModifiers() {
        if (modifierRow == null) return;
        for (int i=0; i<modifierRow.getChildCount(); i++) {
            View b = modifierRow.getChildAt(i);
            int bit = (Integer)b.getTag();
            b.setBackground(bg((stickyModifiers & bit) != 0 ? 0xFF285C9A : BTN, 10));
        }
    }
    private void updateQuickButtons() {
        fillShortcutButtons(quickButtons, shortcutStore, 40);
        fillShortcutButtons(dockButtons, dockShortcutStore, 45);
    }

    private void fillShortcutButtons(LinearLayout target, ShortcutStore store, int height) {
        if (target == null) return;
        target.removeAllViews();
        List<ShortcutStore.Item> all = store.all();
        for (int i=0; i<all.size(); i++) {
            final int index = i;
            ShortcutStore.Item item = all.get(i);
            Button b = button(item.label, WHITE, BTN);
            b.setOnClickListener(v -> sendKey(item.modifiers, item.key));
            b.setTextSize(12);
            b.setMinWidth(dp(62));
            b.setContentDescription(getString(R.string.shortcut_hold_hint, item.label));
            b.setOnLongClickListener(v -> { shortcutOptions(store, index); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(height));
            lp.rightMargin = dp(5);
            target.addView(b,lp);
        }
        Button add = button("＋", BLUE, BTN);
        add.setContentDescription(store == dockShortcutStore ? getString(R.string.add_dock_shortcut)
            : getString(R.string.add_top_shortcut));
        add.setOnClickListener(v -> editShortcut(store, -1));
        target.addView(add, new LinearLayout.LayoutParams(dp(40), dp(height)));
    }
    private void showShortcutManager(ShortcutStore store) {
        List<ShortcutStore.Item> all = store.all();
        String[] names = new String[all.size()+1];
        for (int i=0; i<all.size();i++) names[i] = all.get(i).label + "   ✎";
        names[all.size()] = getString(R.string.add_button);
        new AlertDialog.Builder(this).setTitle(store == dockShortcutStore ? getString(R.string.bottom_panel) : getString(R.string.top_shortcuts))
            .setItems(names, (dialog, index) -> {
                if (index == all.size()) editShortcut(store, -1);
                else shortcutOptions(store, index);
            }).setNegativeButton(getString(R.string.close), null).show();
    }
    private void shortcutOptions(ShortcutStore store, int index) {
        if (index < 0 || index >= store.all().size()) return;
        String[] options = {getString(R.string.edit), getString(R.string.move_left), getString(R.string.move_right), getString(R.string.delete)};
        new AlertDialog.Builder(this).setTitle(store.all().get(index).label)
            .setItems(options, (dialog, chosen) -> {
                if (chosen == 0) editShortcut(store, index);
                else if (chosen == 3) {
                    new AlertDialog.Builder(this).setMessage(getString(R.string.delete_button_question))
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setPositiveButton(getString(R.string.delete), (d,w)-> { store.delete(index); updateQuickButtons(); })
                        .show();
                } else {
                    store.move(index, chosen == 1 ? -1 : 1);
                    updateQuickButtons();
                }
            }).show();
    }

    private void editShortcut(ShortcutStore store, int index) {
        ShortcutStore.Item original = index >= 0 ? store.all().get(index) : null;
        LinearLayout fields = vertical();
        fields.setPadding(dp(18), dp(10), dp(18), dp(5));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(getString(R.string.shortcut_name_hint));
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        if (original != null) name.setText(original.label);
        fields.addView(name);
        List<KeyCatalog.Entry> catalog = KeyCatalog.entries();
        Spinner spinner = new Spinner(this);
        List<String> catalogNames = new ArrayList<>();
        for (KeyCatalog.Entry entry : catalog) catalogNames.add(keyLabel(entry.code, entry.label));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, catalogNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (original != null) {
            for (int i=0; i<catalog.size();i++) if (catalog.get(i).code == original.key) {
                spinner.setSelection(i); break;
            }
        }
        fields.addView(text(getString(R.string.computer_key), 12, MUTED, true), margins(0, 11, 0, 3));
        fields.addView(spinner);
        fields.addView(text(getString(R.string.modifiers_label), 12, MUTED, true), margins(0, 11, 0, 2));
        String[] modNames = {"Ctrl", "Shift", "Alt", "Win"};
        int[] modBits = {KeyboardMapper.CTRL, KeyboardMapper.SHIFT,
            KeyboardMapper.ALT, KeyboardMapper.META};
        CheckBox[] checks = new CheckBox[4];
        for(int i=0;i<4;i++) {
            checks[i] = new CheckBox(this);
            checks[i].setText(modNames[i]);
            checks[i].setChecked(original != null && (original.modifiers & modBits[i]) != 0);
            checks[i].setTextColor(WHITE);
            fields.addView(checks[i]);
        }
        // A light theme here keeps native Spinner/EditText/Dialog readable on all ROMs.
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(index >= 0 ? getString(R.string.edit_shortcut) : getString(R.string.new_button))
            .setView(fields)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty() || label.length() > 40) {
                name.setError(getString(R.string.name_length_error)); return;
            }
            int mask = 0;
            for(int i=0;i<4;i++) if (checks[i].isChecked()) mask |= modBits[i];
            ShortcutStore.Item changed = new ShortcutStore.Item(label, mask,
                catalog.get(spinner.getSelectedItemPosition()).code);
            if (index < 0) store.add(changed); else store.update(index,changed);
            updateQuickButtons();
            dialog.dismiss();
        }));
        dialog.show();
    }


    private void buildDevicesPanel(LinearLayout card) {
        LinearLayout row = horizontal();
        Button discover = button(getString(R.string.pairing), WHITE, BTN);
        discover.setOnClickListener(v -> requestDiscoverable());
        row.addView(discover, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button system = button(getString(R.string.bluetooth_settings), WHITE, BTN);
        system.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(42), 1);
        sp.leftMargin=dp(5);
        row.addView(system, sp);
        card.addView(row, margins(0, 6, 0, 7));
        card.addView(text(getString(R.string.choose_paired_pc), 12, MUTED, false),
            margins(0, 4, 0, 4));
        deviceList = vertical();
        card.addView(deviceList);
        refreshDevices();
        LinearLayout buttons = horizontal();
        Button refresh = button(getString(R.string.reconnect), BLUE, BTN);
        refresh.setOnClickListener(v -> { controller.reconnect(); refreshDevices(); });
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button disconnect = button(getString(R.string.disconnect), WHITE, 0xFF422936);
        disconnect.setOnClickListener(v -> {
            controller.disconnect(); leftHeld = false;
            if (holdButton != null) holdButton.setText(getString(R.string.hold_left));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
        lp.leftMargin=dp(5);
        buttons.addView(disconnect, lp);
        card.addView(buttons, margins(0, 7, 0, 0));
    }
    private void requestDiscoverable() {
        if (!advertisePermission()) { checkBluetooth(); return; }
        if (!controller.isEnabled()) { checkBluetooth(); return; }
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
        try { startActivityForResult(intent, REQ_DISCOVER); }
        catch (SecurityException e) { toast(getString(R.string.allow_pairing)); }
        catch (RuntimeException e) { toast(getString(R.string.pairing_failed)); }
    }
    private void refreshDevices() {
        if (deviceList == null || controller == null) return;
        deviceList.removeAllViews();
        List<BluetoothDevice> devices = controller.bondedDevices();
        if (devices.isEmpty()) {
            deviceList.addView(text(getString(R.string.no_paired_devices),
                12, MUTED, false));
            return;
        }
        for (BluetoothDevice d : devices) {
            Button choice = button("▣  " + BluetoothHidController.displayName(d), WHITE, BTN);
            choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            choice.setSingleLine(true);
            choice.setEllipsize(TextUtils.TruncateAt.END);
            choice.setTextSize(13);
            choice.setOnClickListener(v -> controller.connect(d));
            deviceList.addView(choice, margins(0, 3, 0, 2, 48));
        }
    }
    private void buildSettingsPanel(LinearLayout card) {
        String languageCode = UiLanguage.selected(this);
        int languageIndex = java.util.Arrays.asList(UiLanguage.CODES).indexOf(languageCode);
        String languageName = languageIndex <= 0 ? getString(R.string.language_auto) : UiLanguage.NATIVE_NAMES[languageIndex];
        Button appLanguage = button(getString(R.string.app_language, languageName), BLUE, BTN);
        appLanguage.setOnClickListener(v -> chooseAppLanguage());
        card.addView(appLanguage, margins(0, 3, 0, 4, 44));
        card.addView(text(getString(R.string.app_language_help), 11, MUTED, false), margins(0, 0, 0, 7));
        CheckBox quickVisibility = new CheckBox(this);
        quickVisibility.setText(getString(R.string.hide_quick_commands));
        quickVisibility.setTextColor(WHITE);
        quickVisibility.setTextSize(13);
        quickVisibility.setChecked(hideQuickCommands);
        quickVisibility.setOnCheckedChangeListener((button, checked) -> {
            hideQuickCommands = checked;
            getPreferences(MODE_PRIVATE).edit().putBoolean("hideQuickCommands", checked).apply();
            applyPanelVisibility();
        });
        card.addView(quickVisibility, margins(0, 3, 0, 0, 42));
        CheckBox bottomVisibility = new CheckBox(this);
        bottomVisibility.setText(getString(R.string.hide_bottom_panel));
        bottomVisibility.setTextColor(WHITE);
        bottomVisibility.setTextSize(13);
        bottomVisibility.setChecked(hideBottomPanel);
        bottomVisibility.setOnCheckedChangeListener((button, checked) -> {
            hideBottomPanel = checked;
            getPreferences(MODE_PRIVATE).edit().putBoolean("hideBottomPanel", checked).apply();
            applyPanelVisibility();
        });
        card.addView(bottomVisibility, margins(0, 0, 0, 0, 42));
        card.addView(text(getString(R.string.panel_visibility_help),
            11, MUTED, false), margins(0, 0, 0, 8));
        speedText = text(getString(R.string.pointer_speed, sensitivity),
            13, WHITE, true);
        card.addView(speedText, margins(0, 9, 0, 6));
        SeekBar slider = new SeekBar(this);
        slider.setMax(250);
        slider.setProgress(Math.round((sensitivity - 0.5f)*100));
        slider.setContentDescription(getString(R.string.pointer_speed_hint));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    sensitivity = 0.5f + progress/100f;
                    if (touchpad != null) touchpad.setSensitivity(sensitivity);
                    getPreferences(MODE_PRIVATE).edit().putFloat("sensitivity", sensitivity).apply();
                    if (speedText != null) speedText.setText(getString(R.string.pointer_speed, sensitivity));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        card.addView(slider);
        LinearLayout ends = horizontal();
        ends.addView(text(getString(R.string.speed_slow), 11, MUTED, false),
            new LinearLayout.LayoutParams(0, dp(24), 1));
        TextView max = text(getString(R.string.speed_fast), 11, MUTED, false);
        max.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        ends.addView(max, new LinearLayout.LayoutParams(0, dp(24), 1));
        card.addView(ends);
        card.addView(text(getString(R.string.speed_help), 12, MUTED, false),
            margins(0, 6, 0, 8));
        Button layouts = button(getString(R.string.layout_order_button), BLUE, BTN);
        layouts.setOnClickListener(v -> showLayoutOrder());
        card.addView(layouts, margins(0, 5, 0, 4, 44));
        card.addView(text(getString(R.string.layout_order_summary, layoutOrderSummary()), 12, WHITE, false), margins(0, 0, 0, 6));
        Button layout = button(getString(R.string.calibrate_layout, hostLayout.label), BLUE, BTN);
        layout.setOnClickListener(v -> {
            closePanels(); removeFloating(); chooseHostLayout();
        });
        card.addView(layout, margins(0, 0, 0, 5, 42));
        card.addView(text(getString(R.string.layout_help), 12, MUTED, false), margins(0, 0, 0, 8));
        Button orientation = button(getString(R.string.auto_rotate), WHITE, BTN);
        orientation.setOnClickListener(v -> {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            toast(getString(R.string.rotation_help));
        });
        card.addView(orientation, margins(0, 0, 0, 5, 42));
        Button full = button(immersive ? getString(R.string.exit_full_screen) : getString(R.string.full_screen), BLUE, BTN);
        full.setOnClickListener(v -> { immersive = !immersive; buildUi(); });
        card.addView(full, margins(0, 0, 0, 5, 42));
    }


    private String keyLabel(int code, String fallback) {
        switch (code) {
            case 0x28: return getString(R.string.key_enter);
            case 0x2A: return getString(R.string.key_backspace);
            case 0x2C: return getString(R.string.key_space);
            case 0x46: return getString(R.string.key_print_screen);
            case 0x49: return getString(R.string.key_insert);
            case 0x4A: return getString(R.string.key_home);
            case 0x4B: return getString(R.string.key_page_up);
            case 0x4C: return getString(R.string.key_delete);
            case 0x4D: return getString(R.string.key_end);
            case 0x4E: return getString(R.string.key_page_down);
            case 0x65: return getString(R.string.key_menu);
            default: return fallback;
        }
    }

    private boolean keyboardIdle() {
        if (switchingLanguage || controller.isKeyboardBusy()) {
            toast(getString(R.string.wait_keyboard));
            return false;
        }
        return true;
    }

    private String layoutName(KeyboardMapper.HostLayout layout) {
        switch (layout) {
            case US: return getString(R.string.layout_us);
            case RU: return getString(R.string.layout_ru);
            case UK: return getString(R.string.layout_uk);
            case DE: return getString(R.string.layout_de);
            case FR: return getString(R.string.layout_fr);
            case ES: return getString(R.string.layout_es);
            default: return layout.description;
        }
    }

    private List<KeyboardMapper.HostLayout> orderedLayouts() {
        return HostLayoutPreferences.parse(getPreferences(MODE_PRIVATE).getString("hostLayoutOrder", null));
    }

    private void saveLayoutOrder(List<KeyboardMapper.HostLayout> layouts) {
        getPreferences(MODE_PRIVATE).edit().putString("hostLayoutOrder", HostLayoutPreferences.encode(layouts)).apply();
        if (settingsOpen) showFloating("settings");
    }

    private String layoutOrderSummary() {
        StringBuilder names = new StringBuilder();
        for (KeyboardMapper.HostLayout layout : orderedLayouts()) {
            if (names.length() != 0) names.append(" → ");
            names.append(layout.label);
        }
        return names.toString();
    }

    private void chooseAppLanguage() {
        if (!keyboardIdle()) return;
        String[] choices = UiLanguage.NATIVE_NAMES.clone();
        choices[0] = getString(R.string.language_auto);
        int selected = java.util.Arrays.asList(UiLanguage.CODES).indexOf(UiLanguage.selected(this));
        new AlertDialog.Builder(this).setTitle(R.string.choose_app_language)
            .setSingleChoiceItems(choices, selected, (dialog, index) -> {
                if (!keyboardIdle()) { dialog.dismiss(); return; }
                String code = UiLanguage.CODES[index];
                if (!code.equals(UiLanguage.selected(this))) {
                    UiLanguage.select(this, code);
                    uiContext = UiLanguage.wrap(getBaseContext());
                    // Preserve the active HID service and all saved control state.
                    refreshLocalizedConnectionStatus();
                    buildUi();
                }
                dialog.dismiss();
            }).setNegativeButton(R.string.cancel, null).show();
    }

    private void refreshLocalizedConnectionStatus() {
        if (controller == null) return;
        // Permission/radio errors originate in this Activity, before the HID
        // controller has a cached status. Translate those as well, without
        // requesting permissions or enabling Bluetooth during a locale change.
        if (!controller.hasBluetooth()) onStatus(getString(R.string.status_bluetooth_unavailable), false);
        else if (!controller.hasPermission() || !advertisePermission())
            onStatus(getString(R.string.status_allow_bluetooth), false);
        else if (!controller.isEnabled()) onStatus(getString(R.string.status_enable_bluetooth), false);
        else controller.refreshLocalizedStatus();
    }

    private void showLayoutOrder() {
        if (!keyboardIdle()) return;
        List<KeyboardMapper.HostLayout> layouts = orderedLayouts();
        String[] names = new String[layouts.size() + 1];
        for (int i = 0; i < layouts.size(); i++)
            names[i] = (i + 1) + ". " + layouts.get(i).label + " — " + layoutName(layouts.get(i));
        names[layouts.size()] = getString(R.string.add_layout);
        new AlertDialog.Builder(this).setTitle(R.string.layout_order_title)
            .setItems(names, (dialog, which) -> {
                if (!keyboardIdle()) return;
                if (which == layouts.size()) addLayout();
                else layoutOrderOptions(layouts, which);
            })
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.layout_help_button, (d, w) ->
                new AlertDialog.Builder(this).setMessage(R.string.layout_order_help)
                    .setPositiveButton(R.string.close, (ignored, button) -> showLayoutOrder()).show())
            .show();
    }

    private void layoutOrderOptions(List<KeyboardMapper.HostLayout> layouts, int index) {
        String[] actions = {getString(R.string.move_up), getString(R.string.move_down), getString(R.string.remove_layout)};
        new AlertDialog.Builder(this).setTitle(layoutName(layouts.get(index)))
            .setItems(actions, (dialog, action) -> {
                if (!keyboardIdle()) return;
                if (action == 2) {
                    if (layouts.size() == 1) { toast(getString(R.string.keep_one_layout)); showLayoutOrder(); return; }
                    layouts.remove(index);
                } else {
                    int target = action == 0 ? index - 1 : index + 1;
                    if (target >= 0 && target < layouts.size()) java.util.Collections.swap(layouts, index, target);
                }
                saveLayoutOrder(layouts);
                showLayoutOrder();
            }).setNegativeButton(R.string.cancel, (d, w) -> showLayoutOrder()).show();
    }

    private void addLayout() {
        List<KeyboardMapper.HostLayout> current = orderedLayouts();
        List<KeyboardMapper.HostLayout> available = new ArrayList<>();
        for (KeyboardMapper.HostLayout layout : KeyboardMapper.HostLayout.values())
            if (!current.contains(layout)) available.add(layout);
        if (available.isEmpty()) { showLayoutOrder(); return; }
        String[] names = new String[available.size()];
        for (int i = 0; i < names.length; i++) names[i] = layoutName(available.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.add_layout)
            .setItems(names, (dialog, index) -> {
                if (!keyboardIdle()) return;
                current.add(available.get(index));
                saveLayoutOrder(current);
                showLayoutOrder();
            }).setNegativeButton(R.string.cancel, (d, w) -> showLayoutOrder()).show();
    }


    private void sendEnter() {
        if (!controller.isConnected()) { toast(getString(R.string.connect_first)); return; }
        if (switchingLanguage) return;
        LiveTextDiff.Update pending = LiveTextDiff.plan(remoteBuffer, liveBuffer, hostLayout);
        if (keyboardOpen && !pending.accepted()) {
            updateLiveFeedback(unsupportedMessage(pending.unsupportedCodePoint));
            toast(getString(R.string.fix_unsent_character));
            return;
        }
        controller.key(stickyModifiers, 0x28);
        stickyModifiers = 0;
        repaintModifiers();
        // New line in remote editor means a new local input session.
        if (keyboardOpen && liveField != null) {
            resetLiveInput();
        }
    }
    private void pressKey(int code) {
        if (!controller.isConnected()) { toast(getString(R.string.connect_first)); return; }
        if (switchingLanguage) return;
        resetLiveInput();
        controller.key(stickyModifiers, code);
        stickyModifiers = 0;
        repaintModifiers();
    }
    private void sendKey(int modifiers, int code) {
        if (!controller.isConnected()) { toast(getString(R.string.connect_first)); return; }
        if (switchingLanguage) return;
        resetLiveInput();
        stickyModifiers = 0;
        repaintModifiers();
        controller.key(modifiers, code);
    }
    private void toggleHold() {
        if (!controller.isConnected()) { toast(getString(R.string.connect_first)); return; }
        leftHeld = !leftHeld;
        controller.setLeftHeld(leftHeld);
        if (holdButton != null) holdButton.setText(leftHeld ? getString(R.string.release_left) : getString(R.string.hold_left));
    }
    @Override public void onStatus(String message, boolean connected) {
        runOnUiThread(() -> {
            boolean connectionChanged = statusConnected != connected;
            statusMessage = message; statusConnected = connected;
            if (!connected) switchingLanguage = false;
            if (connectionChanged) resetLiveInput();
            if (hostLayoutButton != null) hostLayoutButton.setEnabled(!switchingLanguage);
            if (liveField != null) liveField.setEnabled(connected && !switchingLanguage);
            LiveTextDiff.Update current = LiveTextDiff.plan(remoteBuffer, liveBuffer, hostLayout);
            updateLiveFeedback(current.accepted() ? null : unsupportedMessage(current.unsupportedCodePoint));
            if (connectionChanged && connected && keyboardOpen && liveField != null) showImeDelayed();
            if (statusView != null) {
                statusView.setText(message);
                statusView.setTextColor(connected ? GREEN : MUTED);
            }
            if (connected && devicesOpen) {
                closePanels(); removeFloating();
            }
            if (!connected) {
                leftHeld = false;
                if (holdButton != null) holdButton.setText(getString(R.string.hold_left));
            }
        });
    }
    @Override public void onDevicesChanged() { runOnUiThread(this::refreshDevices); }
    @Override public void onBackPressed() {
        if (menuOpen || settingsOpen || devicesOpen) { closePanels(); removeFloating(); }
        else if (keyboardOpen) toggleKeyboard();
        else if (immersive) { immersive = false; buildUi(); }
        else super.onBackPressed();
    }

    private LinearLayout vertical() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }
    private LinearLayout horizontal() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        return ll;
    }
    private GradientDrawable bg(int fill, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        return g;
    }
    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String s, int color, int fill) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(color);
        b.setTextSize(12); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setPadding(dp(5), 0, dp(5), 0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setBackground(bg(fill,10));
        return b;
    }
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(dp(l),dp(t),dp(r),dp(b));
        return p;
    }
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b,int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(height));
        p.setMargins(dp(l),dp(t),dp(r),dp(b));
        return p;
    }
    private int dp(float v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
}
