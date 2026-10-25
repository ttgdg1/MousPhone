package io.pocketpad.remote;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** No network, PC agent or Internet needed; the phone acts as a Bluetooth HID peripheral. */
@SuppressWarnings("MissingPermission")
final class BluetoothHidController {
    interface Listener {
        void onStatus(String message, boolean ready);
        void onDevicesChanged();
    }

    private final Context context;
    private final Listener listener;
    private int lastStatusResource;
    private boolean lastStatusReady;
    private Object[] lastStatusArguments = new Object[0];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final class QueuedKey {
        final KeyboardMapper.Stroke stroke;
        final int afterReleaseMs;
        final Runnable completion;
        QueuedKey(KeyboardMapper.Stroke stroke, int afterReleaseMs, Runnable completion) {
            this.stroke = stroke;
            this.afterReleaseMs = afterReleaseMs;
            this.completion = completion;
        }
    }
    private final ArrayDeque<QueuedKey> keyQueue = new ArrayDeque<>();
    private final BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice host;
    private BluetoothDevice pendingHost;
    private boolean registered;
    private boolean registering;
    private boolean profileRequested;
    private boolean sendingKeys;
    private boolean closed;
    private int heldButtons;
    private boolean manualLeftHeld;
    private boolean gestureDragHeld;
    private Runnable pendingClickRelease;
    private int epoch;
    private int retryVersion;
    private int reconnectAttempts;
    private boolean reconnectWanted;
    private boolean foreground;
    private final PointerMotion motion = new PointerMotion();
    private boolean pointerActive;
    private long nextPointerTick;
    private final Runnable pointerTicker = new Runnable() {
        @Override public void run() {
            if (!pointerActive) return;
            flushPointer(false);
            if (pointerActive) {
                // Absolute deadlines avoid accumulating callback-time drift. After a
                // stall, skip missed frames rather than burst several reports.
                long now = android.os.SystemClock.uptimeMillis();
                nextPointerTick = Math.max(nextPointerTick + 16, now + 1);
                handler.postAtTime(this, nextPointerTick);
            }
        }
    };

    BluetoothHidController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    boolean hasBluetooth() { return adapter != null; }
    private void status(int resource, boolean ready, Object... arguments) {
        lastStatusResource = resource;
        lastStatusReady = ready;
        lastStatusArguments = arguments.clone();
        listener.onStatus(UiLanguage.getString(context, resource, arguments), ready);
    }
    void refreshLocalizedStatus() {
        if (lastStatusResource != 0)
            listener.onStatus(UiLanguage.getString(context, lastStatusResource, lastStatusArguments), lastStatusReady);
    }
    void setForeground(boolean value) {
        foreground = value;
        if (!value) retryVersion++; // no connection attempts while in background
        else if (reconnectWanted && pendingHost != null && host == null) {
            reconnectAttempts = 0;
            scheduleReconnect();
        }
    }
    void reconnect() {
        if (closed) return;
        reconnectWanted = true;
        reconnectAttempts = 0;
        retryVersion++;
        if (host != null) pendingHost = host;
        if (pendingHost == null) {
            status(R.string.status_choose_pc, false);
            start();
            return;
        }
        if (!registered || hid == null) start(); else connect(pendingHost);
    }
    private void scheduleReconnect() {
        if (!reconnectWanted || !foreground || pendingHost == null || closed || reconnectAttempts >= 3) return;
        final int marker = ++retryVersion;
        final long delay = new long[]{1300, 3500, 8000}[reconnectAttempts++];
        status(R.string.status_reconnecting, false, reconnectAttempts);
        handler.postDelayed(() -> {
            if (marker != retryVersion || !foreground || closed || !reconnectWanted || pendingHost == null) return;
            if (!registered || hid == null) start(); else connect(pendingHost);
        }, delay);
    }
    boolean isEnabled() {
        if (adapter == null || !hasPermission()) return false;
        return adapter.isEnabled();
    }
    boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    boolean isConnected() { return registered && hid != null && host != null; }

    void start() {
        if (closed || adapter == null || !hasPermission()) return;
        if (!adapter.isEnabled()) { status(R.string.status_enable_phone_bluetooth, false); return; }
        if (hid != null) {
            if (!registered && !registering) registerHid();
            listener.onDevicesChanged();
            return;
        }
        if (profileRequested) return; // prevent duplicate profile proxy requests
        profileRequested = true;
        status(R.string.status_initializing_hid, false);
        try {
            boolean ok = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE);
            if (!ok) {
                profileRequested = false;
                status(R.string.status_hid_unavailable, false);
            }
        } catch (RuntimeException error) {
            profileRequested = false;
            status(R.string.status_bluetooth_error, false, error.getMessage());
        }
    }

    List<BluetoothDevice> bondedDevices() {
        List<BluetoothDevice> results = new ArrayList<>();
        if (adapter == null || !hasPermission() || !isEnabled()) return results;
        try {
            Set<BluetoothDevice> bonds = adapter.getBondedDevices();
            if (bonds != null) results.addAll(bonds);
            results.sort(Comparator.comparing(d -> {
                String name = d.getName();
                return name == null ? "" : name.toLowerCase();
            }));
        } catch (SecurityException error) {
            status(R.string.status_nearby_permission, false);
        }
        return results;
    }

    void connect(BluetoothDevice device) {
        if (device == null || closed) return;
        pendingHost = device;
        retryVersion++;
        reconnectWanted = true;
        if (!registered || hid == null) {
            status(R.string.status_waiting_hid, false);
            start();
            return;
        }
        try {
            if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                reconnectAttempts = 0;
                retryVersion++;
                status(R.string.status_connected, true, displayName(device));
            } else {
                status(R.string.status_connecting_to, false, displayName(device));
                if (!hid.connect(device)) {
                    status(R.string.status_connection_rejected, false);
                    scheduleReconnect();
                }
            }
        } catch (RuntimeException e) {
            status(R.string.status_connection_failed, false, e.getMessage());
        }
    }

    void disconnect() {
        if (hid != null && host != null) {
            try { hid.disconnect(host); } catch (RuntimeException ignored) {}
        }
        resetInput();
        host = null;
        pendingHost = null;
        reconnectWanted = false;
        retryVersion++;
        status(R.string.status_disconnected, false);
    }

    void pointerDown() {
        if (!isConnected()) return;
        handler.removeCallbacks(pointerTicker);
        pointerActive = true;
        nextPointerTick = android.os.SystemClock.uptimeMillis() + 16;
        handler.postAtTime(pointerTicker, nextPointerTick);
    }

    void pointerUp() {
        pointerActive = false;
        handler.removeCallbacks(pointerTicker);
        flushPointer(true);
    }

    void mouseMove(float dx, float dy) {
        if (!isConnected()) return;
        // Accumulate touch events; send evenly rather than in bursts at the
        // rate delivered by the Android UI event dispatcher.
        motion.add(dx, dy);
        if (!pointerActive) flushPointer(true);
    }

    private void flushPointer(boolean finish) {
        if (!isConnected()) {
            motion.clear();
            return;
        }
        // Smooth single-frame spikes without discarding any accumulated distance.
        // Normal updates stay responsive; pointerUp flushes the remaining distance.
        int[] delta = motion.take(finish);
        int x = delta[0], y = delta[1];
        while (x != 0 || y != 0) {
            int cx = Math.max(-127, Math.min(127, x));
            int cy = Math.max(-127, Math.min(127, y));
            sendMouse(cx, cy, 0, heldButtons);
            x -= cx;
            y -= cy;
        }
    }

    void scroll(int ticks) {
        if (!isConnected()) return;
        while (ticks != 0) {
            // HID wheel: positive up, negative down.
            int step = Math.max(-127, Math.min(127, ticks));
            sendMouse(0, 0, step, heldButtons);
            ticks -= step;
        }
    }

    void setLeftHeld(boolean held) {
        if (manualLeftHeld == held) return;
        finishPendingClick();
        manualLeftHeld = held;
        updateHeldButtons();
    }

    void setGestureDragHeld(boolean held) {
        if (gestureDragHeld == held) return;
        // The first tap may still have a timed click-release pending. Finish it
        // BEFORE the second press, or the timer would cancel the drag halfway.
        finishPendingClick();
        gestureDragHeld = held;
        updateHeldButtons();
    }

    private void updateHeldButtons() {
        heldButtons = (heldButtons & ~1) | ((manualLeftHeld || gestureDragHeld) ? 1 : 0);
        sendMouse(0, 0, 0, heldButtons);
    }

    private void finishPendingClick() {
        if (pendingClickRelease == null) return;
        handler.removeCallbacks(pendingClickRelease);
        pendingClickRelease = null;
        sendMouse(0, 0, 0, heldButtons);
    }

    void click(int button) {
        if (!isConnected()) return;
        finishPendingClick();
        final int expectedEpoch = epoch;
        sendMouse(0, 0, 0, heldButtons | button);
        pendingClickRelease = () -> {
            pendingClickRelease = null;
            if (epoch == expectedEpoch) sendMouse(0, 0, 0, heldButtons);
        };
        handler.postDelayed(pendingClickRelease, 55);
    }

    private void sendMouse(int x, int y, int wheel, int buttons) {
        if (!isConnected()) return;
        try { hid.sendReport(host, HidReports.MOUSE_ID, HidReports.mouse(buttons, x, y, wheel)); }
        catch (RuntimeException ignored) {}
    }

    void key(int modifiers, int code) {
        if (!isConnected()) return;
        keyQueue.add(new QueuedKey(new KeyboardMapper.Stroke(code, modifiers), 18, null));
        pumpKeys();
    }

    boolean isKeyboardBusy() { return sendingKeys || !keyQueue.isEmpty(); }

    boolean switchLanguage(Runnable completion) {
        if (!isConnected() || isKeyboardBusy()) return false;
        // Explicit key-up releases Win too. Keep the keyboard busy while Windows
        // processes the shortcut so new text cannot race the layout change.
        keyQueue.add(new QueuedKey(new KeyboardMapper.Stroke(0x2C, KeyboardMapper.META),
            350, completion));
        pumpKeys();
        return true;
    }

    boolean sendText(String text, KeyboardMapper.HostLayout layout) {
        if (!isConnected()) return false;
        // Atomic validation: an unsupported symbol must not partially enqueue text.
        ArrayList<QueuedKey> strokes = new ArrayList<>();
        for (char ch : text.toCharArray()) {
            KeyboardMapper.Stroke[] sequence = KeyboardMapper.strokesForChar(ch, layout);
            if (sequence == null) return false;
            for (KeyboardMapper.Stroke stroke : sequence)
                strokes.add(new QueuedKey(stroke, 18, null));
        }
        keyQueue.addAll(strokes);
        pumpKeys();
        return true;
    }

    private void pumpKeys() {
        if (sendingKeys || !isConnected()) return;
        QueuedKey queued = keyQueue.poll();
        if (queued == null) return;
        KeyboardMapper.Stroke stroke = queued.stroke;
        sendingKeys = true;
        final int expectedEpoch = epoch;
        sendKeyboard(stroke.modifiers, stroke.key);
        handler.postDelayed(() -> {
            if (epoch != expectedEpoch) return;
            sendKeyboard(0, 0);
            handler.postDelayed(() -> {
                if (epoch != expectedEpoch) return;
                sendingKeys = false;
                if (queued.completion != null) queued.completion.run();
                pumpKeys();
            }, queued.afterReleaseMs);
        }, 28);
    }

    private void sendKeyboard(int modifiers, int key) {
        if (!isConnected()) return;
        try { hid.sendReport(host, HidReports.KEYBOARD_ID, HidReports.keyboard(modifiers, key)); }
        catch (RuntimeException ignored) {}
    }

    private void resetInput() {
        pointerActive = false;
        handler.removeCallbacks(pointerTicker);
        motion.clear();
        if (pendingClickRelease != null) {
            handler.removeCallbacks(pendingClickRelease);
            pendingClickRelease = null;
        }
        epoch++;
        keyQueue.clear();
        sendingKeys = false;
        heldButtons = 0;
        manualLeftHeld = false;
        gestureDragHeld = false;
    }

    static String displayName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? device.getAddress() : name;
        } catch (SecurityException e) { return "Bluetooth"; }
    }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (closed || profile != BluetoothProfile.HID_DEVICE) return;
            profileRequested = false;
            hid = (BluetoothHidDevice) proxy;
            registerHid();
        }
        @Override public void onServiceDisconnected(int profile) {
            hid = null;
            profileRequested = false;
            registering = false;
            registered = false;
            host = null;
            resetInput();
            status(R.string.status_hid_service_disconnected, false);
            scheduleReconnect();
        }
    };

    private void registerHid() {
        if (hid == null || registered || registering || closed) return;
        registering = true;
        try {
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "MousPhone", "Bluetooth mouse and keyboard", "MousPhone",
                    BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR);
                boolean accepted = hid.registerApp(sdp, null, null, context.getMainExecutor(), callback);
                if (!accepted) {
                    registering = false;
                    status(R.string.status_hid_registration_failed, false);
                } else status(R.string.status_registering_hid, false);
            } catch (RuntimeException e) {
                registering = false;
                status(R.string.status_hid_not_supported, false, e.getMessage());
            }
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            registering = false;
            if (!registered) {
                host = null;
                resetInput();
                status(R.string.status_hid_background, false);
                scheduleReconnect();
            } else {
                if (pluggedDevice != null) pendingHost = pluggedDevice;
                status(R.string.status_hid_ready, false);
                listener.onDevicesChanged();
                if (pendingHost != null) connect(pendingHost);
            }
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                pendingHost = device;
                reconnectWanted = true;
                reconnectAttempts = 0;
                retryVersion++;
                status(R.string.status_connected, true, displayName(device));
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (host != null && host.equals(device)) {
                    resetInput();
                    host = null;
                }
                if (pendingHost != null && pendingHost.equals(device)) {
                    if (reconnectWanted) scheduleReconnect();
                    else status(R.string.status_disconnected, false);
                }
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status(R.string.status_connecting, false);
            }
            listener.onDevicesChanged();
        }
        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hid == null) return;
            try {
                if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && id == HidReports.MOUSE_ID) {
                    hid.replyReport(device, type, id, HidReports.mouse(0, 0, 0, 0));
                } else if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && id == HidReports.KEYBOARD_ID) {
                    hid.replyReport(device, type, id, HidReports.keyboard(0, 0));
                } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == HidReports.KEYBOARD_ID) {
                    hid.replyReport(device, type, id, new byte[] {0});
                } else {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                }
            } catch (RuntimeException ignored) {}
        }
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (hid == null) return;
            try {
                // Host may send keyboard LED state (CapsLock, NumLock, etc.).
                if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == HidReports.KEYBOARD_ID)
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                else
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            } catch (RuntimeException ignored) {}
        }
    };

    void close() {
        closed = true;
        retryVersion++;
        if (hid != null) {
            // Explicitly release held keys/buttons before unregistering.
            if (isConnected()) {
                sendKeyboard(0, 0);
                sendMouse(0, 0, 0, 0);
            }
            resetInput();
            try { hid.unregisterApp(); } catch (RuntimeException ignored) {}
            if (adapter != null) {
                try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); }
                catch (RuntimeException ignored) {}
            }
            hid = null;
        }
    }
}
