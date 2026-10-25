package io.pocketpad.remote;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Character-to-HID mapping for an explicitly selected, matching Windows layout. */
final class KeyboardMapper {
    static final int CTRL = 0x01;
    static final int SHIFT = 0x02;
    static final int ALT = 0x04;
    static final int META = 0x08;
    /** USB HID right Alt; Windows interprets this as AltGr in these layouts. */
    static final int ALT_GR = 0x40;

    enum HostLayout {
        US("EN", "English (US)", "00000409"),
        RU("RU", "Русская (ЙЦУКЕН)", "00000419"),
        UK("UK", "English (UK)", "00000809"),
        DE("DE", "Deutsch (QWERTZ)", "00000407"),
        FR("FR", "Français (AZERTY)", "0000040C"),
        ES("ES", "Español (España)", "0000040A");

        final String label;
        final String description;
        final String klid;
        HostLayout(String label, String description, String klid) {
            this.label = label;
            this.description = description;
            this.klid = klid;
        }
        static HostLayout fromPreference(String name) {
            for (HostLayout layout : values()) if (layout.name().equals(name)) return layout;
            return RU;
        }
    }

    static final class Stroke {
        final int key;
        final int modifiers;
        Stroke(int key, int modifiers) { this.key = key; this.modifiers = modifiers; }
    }

    private static final Map<HostLayout, Map<Character, Stroke[]>> TABLES = createTables();

    /** Direct single-key lookup. Text input must use strokesForChar for accents. */
    static Stroke forChar(char ch, HostLayout layout) {
        Stroke[] sequence = table(layout).get(ch);
        return sequence != null && sequence.length == 1 ? sequence[0] : null;
    }

    /**
     * One BMP character -> one or two complete physical key presses.
     * Each Stroke needs its own key-down AND release. Spanish á uses dead acute,
     * release, a, release. A literal dead accent uses accent + Space so it cannot
     * leak into the next command. Caps Lock must be off. No Unicode injection.
     */
    static Stroke[] strokesForChar(char ch, HostLayout layout) {
        Stroke[] sequence = table(layout).get(ch);
        return sequence == null ? null : sequence.clone();
    }

    private static Map<Character, Stroke[]> table(HostLayout layout) {
        Map<Character, Stroke[]> table = TABLES.get(layout);
        return table == null ? Collections.emptyMap() : table;
    }

    private static Map<HostLayout, Map<Character, Stroke[]>> createTables() {
        Map<HostLayout, Map<Character, Stroke[]>> tables = new EnumMap<>(HostLayout.class);
        for (HostLayout layout : HostLayout.values()) {
            Map<Character, Stroke[]> table = new HashMap<>();
            for (String entry : WindowsLayoutData.forLayout(layout).split(";")) {
                if (entry.isEmpty()) continue;
                String[] pair = entry.split("=");
                char character = (char) Integer.parseInt(pair[0], 16);
                String[] encoded = pair[1].split(",");
                Stroke[] strokes = new Stroke[encoded.length];
                for (int i = 0; i < encoded.length; i++) {
                    int value = Integer.parseInt(encoded[i], 16);
                    strokes[i] = new Stroke(value >> 8, value & 0xff);
                }
                table.put(character, strokes);
            }
            tables.put(layout, Collections.unmodifiableMap(table));
        }
        return Collections.unmodifiableMap(tables);
    }

    private KeyboardMapper() {}
}
