package io.pocketpad.remote;

import java.util.HashMap;
import java.util.Map;

/** Independent physical-key fixtures for standard Windows US and Russian (00000419). */
public final class CheckHid {
    private static final int SHIFT = 0x02;

    private static void eq(int actual, int expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static int id(int key, int modifiers) { return (modifiers << 8) | key; }

    private static void row(Map<Integer, Character> layout, int[] keys, String plain, String shifted) {
        if (keys.length != plain.length() || keys.length != shifted.length())
            throw new AssertionError("Invalid fixture row");
        for (int i = 0; i < keys.length; i++) {
            layout.put(id(keys[i], 0), plain.charAt(i));
            layout.put(id(keys[i], SHIFT), shifted.charAt(i));
        }
    }

    private static Map<Integer, Character> usFixture() {
        Map<Integer, Character> result = new HashMap<>();
        int[] alphabet = new int[26];
        for (int i = 0; i < alphabet.length; i++) alphabet[i] = 0x04 + i;
        row(result, alphabet, "abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        row(result, new int[]{0x1e,0x1f,0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27},
            "1234567890", "!@#$%^&*()");
        row(result, new int[]{0x2d,0x2e,0x2f,0x30,0x31,0x33,0x34,0x35,0x36,0x37,0x38},
            "-=[]\\;'`,./", "_+{}|:\"~<>?");
        common(result);
        return result;
    }

    private static Map<Integer, Character> ruFixture() {
        Map<Integer, Character> result = new HashMap<>();
        row(result, new int[]{0x35}, "ё", "Ё");
        row(result, new int[]{0x14,0x1a,0x08,0x15,0x17,0x1c,0x18,0x0c,0x12,0x13,0x2f,0x30},
            "йцукенгшщзхъ", "ЙЦУКЕНГШЩЗХЪ");
        row(result, new int[]{0x04,0x16,0x07,0x09,0x0a,0x0b,0x0d,0x0e,0x0f,0x33,0x34},
            "фывапролджэ", "ФЫВАПРОЛДЖЭ");
        row(result, new int[]{0x1d,0x1b,0x06,0x19,0x05,0x11,0x10,0x36,0x37},
            "ячсмитьбю", "ЯЧСМИТЬБЮ");
        row(result, new int[]{0x1e,0x1f,0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27},
            "1234567890", "!\"№;%:?*()");
        row(result, new int[]{0x2d,0x2e,0x31,0x38}, "-=\\.", "_+/,");
        common(result);
        return result;
    }

    private static void common(Map<Integer, Character> result) {
        result.put(id(0x28, 0), '\n');
        result.put(id(0x2b, 0), '\t');
        result.put(id(0x2c, 0), ' ');
    }

    private static void roundTrip(char ch, KeyboardMapper.HostLayout layout, Map<Integer, Character> fixture) {
        KeyboardMapper.Stroke stroke = KeyboardMapper.forChar(ch, layout);
        if (stroke == null) throw new AssertionError(layout + " rejected " + ch);
        Character output = fixture.get(id(stroke.key, stroke.modifiers));
        if (output == null || output != ch)
            throw new AssertionError(layout + " input " + ch + " would emit " + output +
                " at key=" + stroke.key + ", modifiers=" + stroke.modifiers);
    }

    private static void rejected(char ch, KeyboardMapper.HostLayout layout) {
        if (KeyboardMapper.forChar(ch, layout) != null)
            throw new AssertionError(layout + " should reject U+" + Integer.toHexString(ch));
    }

    public static void main(String[] args) {
        eq(HidReports.mouse(1, 300, -300, 2).length, 4, "mouse size");
        eq(HidReports.mouse(1, 300, -300, 2)[1], 127, "mouse X clamped");
        eq(HidReports.mouse(1, 300, -300, 2)[2], -127, "mouse Y clamped");
        eq(HidReports.keyboard(0, 4).length, 8, "keyboard size");
        eq(HidReports.DESCRIPTOR.length, 119, "HID descriptor bytes");

        Map<Integer, Character> us = usFixture(), ru = ruFixture();
        for (char ch = 32; ch <= 126; ch++) roundTrip(ch, KeyboardMapper.HostLayout.US, us);
        String russian = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ";
        for (char ch : russian.toCharArray()) {
            roundTrip(ch, KeyboardMapper.HostLayout.RU, ru);
            rejected(ch, KeyboardMapper.HostLayout.US);
        }
        for (char ch : " 1234567890-_=+\\/.,!\"№;%:?*()\t\n".toCharArray())
            roundTrip(ch, KeyboardMapper.HostLayout.RU, ru);
        for (char ch = 'a'; ch <= 'z'; ch++) rejected(ch, KeyboardMapper.HostLayout.RU);
        for (char ch = 'A'; ch <= 'Z'; ch++) rejected(ch, KeyboardMapper.HostLayout.RU);
        for (char ch : "@#$^&[]{}|'`~<>".toCharArray()) rejected(ch, KeyboardMapper.HostLayout.RU);
        for (KeyboardMapper.HostLayout layout : KeyboardMapper.HostLayout.values()) {
            roundTrip('\t', layout, layout == KeyboardMapper.HostLayout.US ? us : ru);
            roundTrip('\n', layout, layout == KeyboardMapper.HostLayout.US ? us : ru);
            eq(KeyboardMapper.forChar('\r', layout).key, 0x28, "CR Enter");
            rejected('\uD83D', layout);
            rejected('\uDE00', layout);
            if (layout == KeyboardMapper.HostLayout.US || layout == KeyboardMapper.HostLayout.RU)
                rejected('é', layout);
            rejected('K', layout);
            rejected('—', layout);
            rejected('\0', layout);
        }
        eq(KeyboardMapper.forChar(',', KeyboardMapper.HostLayout.RU).key, 0x38, "RU comma physical key");
        eq(KeyboardMapper.forChar(',', KeyboardMapper.HostLayout.RU).modifiers, SHIFT, "RU comma shifted");
        eq(KeyboardMapper.forChar('б', KeyboardMapper.HostLayout.RU).key, 0x36, "RU be separate from comma");
        eq(KeyboardMapper.forChar('.', KeyboardMapper.HostLayout.RU).key, 0x38, "RU period physical key");
        eq(KeyboardMapper.forChar('.', KeyboardMapper.HostLayout.RU).modifiers, 0, "RU period plain");
        int f10 = KeyCatalog.entries().stream().filter(e -> e.label.equals("F10")).findFirst().orElseThrow().code;
        eq(f10, 0x43, "F10 key usage");
        int del = KeyCatalog.entries().stream().filter(e -> e.label.equals("Delete")).findFirst().orElseThrow().code;
        eq(del, 0x4c, "Delete key usage");
        System.out.println("OK: HID reports; all US ASCII + RU letters/punctuation decode under independent fixtures");
    }
}
