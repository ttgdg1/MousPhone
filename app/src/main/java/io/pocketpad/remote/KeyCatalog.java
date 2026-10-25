package io.pocketpad.remote;

import java.util.ArrayList;
import java.util.List;

/** Computer keys are USB HID usages, not Android key codes. */
final class KeyCatalog {
    static final class Entry {
        final String label;
        final int code;
        Entry(String label, int code) { this.label = label; this.code = code; }
        @Override public String toString() { return label; }
    }
    static List<Entry> entries() {
        ArrayList<Entry> all = new ArrayList<>();
        for (int i=0; i<12; i++) all.add(new Entry("F"+(i+1), 0x3A+i));
        for (char c='A';c<='Z';c++) all.add(new Entry(String.valueOf(c), 0x04+c-'A'));
        for (int n=1;n<=9;n++) all.add(new Entry(String.valueOf(n), 0x1E+n-1));
        all.add(new Entry("0", 0x27));
        all.add(new Entry("Esc", 0x29));
        all.add(new Entry("Tab", 0x2B));
        all.add(new Entry("Enter", 0x28));
        all.add(new Entry("Space", 0x2C));
        all.add(new Entry("Backspace", 0x2A));
        all.add(new Entry("Delete", 0x4C));
        all.add(new Entry("Insert", 0x49));
        all.add(new Entry("Home", 0x4A));
        all.add(new Entry("End", 0x4D));
        all.add(new Entry("Page Up", 0x4B));
        all.add(new Entry("Page Down", 0x4E));
        all.add(new Entry("←", 0x50));
        all.add(new Entry("→", 0x4F));
        all.add(new Entry("↑", 0x52));
        all.add(new Entry("↓", 0x51));
        all.add(new Entry("Print Screen", 0x46));
        all.add(new Entry("Menu", 0x65));
        return all;
    }
    private KeyCatalog() {}
}
