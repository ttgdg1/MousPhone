package io.pocketpad.remote;

/** Standard Bluetooth HID Report protocol: mouse (id 1) + boot-style keyboard (id 2). */
final class HidReports {
    static final int MOUSE_ID = 1;
    static final int KEYBOARD_ID = 2;

    // HID Report Descriptor. Byte literals use (byte) cast for unsigned > 0x7f.
    static final byte[] DESCRIPTOR = new byte[] {
        0x05, 0x01,       // Usage Page: Generic Desktop
        0x09, 0x02,       // Usage: Mouse
        (byte) 0xA1, 0x01, // Collection: Application
        (byte) 0x85, 0x01, // Report ID: 1
        0x09, 0x01,       // Usage: Pointer
        (byte) 0xA1, 0x00, // Collection: Physical
        0x05, 0x09,       // Usage Page: Buttons
        0x19, 0x01, 0x29, 0x03, // 3 buttons
        0x15, 0x00, 0x25, 0x01, // Logical min 0 max 1
        (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02, // buttons, 3 bits
        (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x03, // padding, 5 bits
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, // X,Y,Wheel
        0x15, (byte) 0x81, 0x25, 0x7F, // -127 .. 127
        0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06, // 3 signed relative axes
        (byte) 0xC0, (byte) 0xC0,

        0x05, 0x01, 0x09, 0x06, // Generic desktop, keyboard
        (byte) 0xA1, 0x01, (byte) 0x85, 0x02, // Application, ID 2
        0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, // modifiers E0-E7
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08,
        (byte) 0x81, 0x02, // modifier bitfield
        (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x03, // reserved byte
        (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
        (byte) 0x81, 0x00, // 6 key codes
        (byte) 0x95, 0x05, 0x75, 0x01, 0x05, 0x08,
        0x19, 0x01, 0x29, 0x05, (byte) 0x91, 0x02, // LED output
        (byte) 0x95, 0x01, 0x75, 0x03, (byte) 0x91, 0x03, // output padding
        (byte) 0xC0
    };

    static byte[] mouse(int buttons, int dx, int dy, int wheel) {
        return new byte[] {
            (byte)(buttons & 7), (byte) clamp(dx), (byte) clamp(dy), (byte) clamp(wheel)
        };
    }

    static byte[] keyboard(int modifiers, int key) {
        return new byte[] {(byte) modifiers, 0, (byte) key, 0, 0, 0, 0, 0};
    }

    private static int clamp(int value) { return Math.max(-127, Math.min(127, value)); }
    private HidReports() {}
}
