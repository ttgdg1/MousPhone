package io.pocketpad.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Independent forward decoder from Windows KBDTABLES, not the app inverse map. */
public final class CheckLayouts {
    private static final int SHIFT = 2, ALT_GR = 64;

    private static final class Event {
        final char character;
        final boolean dead;
        Event(char character, boolean dead) { this.character = character; this.dead = dead; }
    }
    private static final class Fixture {
        final Map<Integer, Event> keys = new HashMap<>();
        final Map<Long, Character> compositions = new HashMap<>();

        String decode(KeyboardMapper.Stroke[] sequence) {
            StringBuilder output = new StringBuilder();
            char pendingAccent = 0;
            for (KeyboardMapper.Stroke stroke : sequence) {
                Event event = keys.get((stroke.modifiers << 8) | stroke.key);
                check(event != null, "unknown physical key " + stroke.key + "/" + stroke.modifiers);
                check(stroke.key >= 4 && stroke.key <= 0x65, "usage outside HID descriptor");
                check(stroke.modifiers == 0 || stroke.modifiers == SHIFT ||
                        stroke.modifiers == ALT_GR || stroke.modifiers == (SHIFT | ALT_GR),
                        "unexpected text modifier (shortcut injection)");
                if (event.dead) {
                    check(pendingAccent == 0, "unsupported chained dead key");
                    pendingAccent = event.character;
                } else if (pendingAccent != 0) {
                    Character combined = compositions.get(((long) pendingAccent << 16) | event.character);
                    check(combined != null, "missing dead-key composition");
                    output.append(combined.charValue());
                    pendingAccent = 0;
                } else { output.append(event.character); }
                byte[] down = HidReports.keyboard(stroke.modifiers, stroke.key);
                check(down.length == 8 && (down[0] & 255) == stroke.modifiers &&
                        (down[2] & 255) == stroke.key, "HID report bytes");
            }
            check(pendingAccent == 0, "dead accent left pending after character");
            return output.toString();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Map<KeyboardMapper.HostLayout, Fixture> readFixtures() throws Exception {
        Map<KeyboardMapper.HostLayout, Fixture> fixtures = new EnumMap<>(KeyboardMapper.HostLayout.class);
        for (KeyboardMapper.HostLayout layout : KeyboardMapper.HostLayout.values()) fixtures.put(layout, new Fixture());
        for (String line : Files.readAllLines(Path.of("tests", "fixtures", "windows-key-events.tsv"), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.split("\t");
            Fixture fixture = fixtures.get(KeyboardMapper.HostLayout.valueOf(columns[0]));
            int a = Integer.parseInt(columns[2], 16), b = Integer.parseInt(columns[3], 16);
            int c = Integer.parseInt(columns[4], 16), d = Integer.parseInt(columns[5], 16);
            if (columns[1].equals("KEY")) {
                fixture.keys.put((b << 8) | a, new Event((char)c, d != 0));
            } else if (columns[1].equals("COMPOSE") && d == 0) {
                fixture.compositions.put(((long)a << 16) | b, (char)c);
            }
        }
        return fixtures;
    }

    private static void expect(KeyboardMapper.HostLayout layout, char ch, int... encoded) {
        KeyboardMapper.Stroke[] strokes = KeyboardMapper.strokesForChar(ch, layout);
        check(strokes != null && strokes.length * 2 == encoded.length, layout + " sequence length for " + ch);
        for (int i = 0; i < strokes.length; i++) {
            check(strokes[i].key == encoded[i * 2] && strokes[i].modifiers == encoded[i * 2 + 1],
                    layout + " physical fixture for " + ch + " at " + i);
        }
    }

    public static void main(String[] args) throws Exception {
        check(KeyboardMapper.HostLayout.values().length == 6, "exactly six supported layouts");
        check(KeyboardMapper.HostLayout.fromPreference("UK") == KeyboardMapper.HostLayout.UK, "UK preference");
        check(KeyboardMapper.HostLayout.fromPreference("DE") == KeyboardMapper.HostLayout.DE, "DE preference");
        check(KeyboardMapper.HostLayout.fromPreference("bad") == KeyboardMapper.HostLayout.RU, "old/invalid preference migration");
        check(KeyboardMapper.HostLayout.fromPreference(null) == KeyboardMapper.HostLayout.RU, "null preference migration");
        Map<KeyboardMapper.HostLayout, Fixture> fixtures = readFixtures();
        int verified = 0, composed = 0;
        for (KeyboardMapper.HostLayout layout : KeyboardMapper.HostLayout.values()) {
            int count = 0;
            for (int value = 0; value <= Character.MAX_VALUE; value++) {
                char ch = (char) value;
                KeyboardMapper.Stroke[] sequence = KeyboardMapper.strokesForChar(ch, layout);
                if (sequence == null) continue;
                check(sequence.length >= 1 && sequence.length <= 2, "bounded sequence");
                char expected = ch == '\n' ? '\r' : ch;
                String output = fixtures.get(layout).decode(sequence);
                check(output.length() == 1 && output.charAt(0) == expected,
                        layout + " U+" + Integer.toHexString(ch) + " decodes incorrectly: " + output);
                KeyboardMapper.Stroke direct = KeyboardMapper.forChar(ch, layout);
                check((direct != null) == (sequence.length == 1), "direct API must not expose unfinished accents");
                if (sequence.length == 2) composed++;
                count++;
            }
            check(count >= 98, layout + " missing basic mappings");
            if (layout != KeyboardMapper.HostLayout.RU) {
                for (char ch = 32; ch <= 126; ch++) {
                    check(KeyboardMapper.strokesForChar(ch, layout) != null, layout + " ASCII missing " + ch);
                }
            }
            for (char invalid : new char[]{'\0','\u001b','\u0301','\uD83D','\uDE00','\u212A','\u2014'}) {
                check(KeyboardMapper.strokesForChar(invalid, layout) == null, "unsupported scalar accepted");
            }
            verified += count;
            System.out.println("OK: " + layout + " " + count + " character sequences decode to Windows fixture");
        }
        expect(KeyboardMapper.HostLayout.UK, '£', 0x20,SHIFT);
        expect(KeyboardMapper.HostLayout.UK, '€', 0x21,ALT_GR);
        expect(KeyboardMapper.HostLayout.UK, '\\', 0x64,0);
        expect(KeyboardMapper.HostLayout.UK, '@', 0x34,SHIFT);
        expect(KeyboardMapper.HostLayout.DE, 'z', 0x1c,0);
        expect(KeyboardMapper.HostLayout.DE, 'y', 0x1d,0);
        expect(KeyboardMapper.HostLayout.DE, 'ä', 0x34,0);
        expect(KeyboardMapper.HostLayout.DE, '@', 0x14,ALT_GR);
        expect(KeyboardMapper.HostLayout.DE, 'é', 0x2e,0,0x08,0);
        expect(KeyboardMapper.HostLayout.DE, '^', 0x35,0,0x2c,0);
        expect(KeyboardMapper.HostLayout.FR, 'a', 0x14,0);
        expect(KeyboardMapper.HostLayout.FR, 'm', 0x33,0);
        expect(KeyboardMapper.HostLayout.FR, 'é', 0x1f,0);
        expect(KeyboardMapper.HostLayout.FR, 'ê', 0x2f,0,0x08,0);
        expect(KeyboardMapper.HostLayout.FR, 'È', 0x24,ALT_GR,0x08,SHIFT);
        expect(KeyboardMapper.HostLayout.ES, 'ñ', 0x33,0);
        expect(KeyboardMapper.HostLayout.ES, '¿', 0x2e,SHIFT);
        expect(KeyboardMapper.HostLayout.ES, 'á', 0x34,0,0x04,0);
        expect(KeyboardMapper.HostLayout.ES, 'Á', 0x34,0,0x04,SHIFT);
        expect(KeyboardMapper.HostLayout.ES, 'ü', 0x34,SHIFT,0x18,0);
        check(KeyboardMapper.strokesForChar('É', KeyboardMapper.HostLayout.FR) == null &&
                KeyboardMapper.strokesForChar('Ç', KeyboardMapper.HostLayout.FR) == null,
                "legacy French must not fake unavailable Caps-Lock-off keys");
        KeyboardMapper.Stroke[] original = KeyboardMapper.strokesForChar('é', KeyboardMapper.HostLayout.DE);
        original[0] = new KeyboardMapper.Stroke(4, 0);
        expect(KeyboardMapper.HostLayout.DE, 'é', 0x2e,0,0x08,0);
        System.out.println("OK: " + verified + " native-decoded characters, " + composed +
                " finished dead-key sequences; AltGr/ISO keys and independent physical fixtures");
    }
}
