package io.pocketpad.remote;

/** JVM-only checks: no Android SDK required. */
public final class CheckLiveText {
    private static void assertSame(Object want, Object got) {
        if (!want.equals(got)) throw new AssertionError("Expected: " + want + " ; got: " + got);
    }

    private static void accepted(String previous, String next, KeyboardMapper.HostLayout layout,
                                 int backspaces, String text) {
        LiveTextDiff.Update update = LiveTextDiff.plan(previous, next, layout);
        assertSame(true, update.accepted());
        assertSame(backspaces, update.backspaces);
        assertSame(text, update.text);
        // The edits must reconstruct the complete requested line, including deletions.
        String result = previous.substring(0, previous.length() - update.backspaces) + update.text;
        assertSame(next, result);
    }

    private static void rejected(String previous, String next, KeyboardMapper.HostLayout layout, int codePoint) {
        LiveTextDiff.Update update = LiveTextDiff.plan(previous, next, layout);
        assertSame(false, update.accepted());
        assertSame(codePoint, update.unsupportedCodePoint);
        // Rejection is atomic: no previous remote text is erased and no prefix is transmitted.
        assertSame(0, update.backspaces);
        assertSame("", update.text);
    }

    public static void main(String[] args) {
        KeyboardMapper.HostLayout us = KeyboardMapper.HostLayout.US;
        KeyboardMapper.HostLayout ru = KeyboardMapper.HostLayout.RU;
        accepted("", "hello, world!", us, 0, "hello, world!");
        accepted("", "Привет, мир!", ru, 0, "Привет, мир!");
        accepted("кот", "котик", ru, 0, "ик");
        accepted("abcdef", "abXYef", us, 4, "XYef");
        accepted("abc", "xyz", us, 3, "xyz");
        accepted("abc", "", us, 3, "");
        accepted("abc", "abc", us, 0, "");
        accepted("", "", ru, 0, "");
        accepted("привет", "прив", ru, 2, "");
        accepted("a.", "a,", us, 1, ",");
        accepted("а.", "а,", ru, 1, ",");
        rejected("", "hello", ru, 'h');
        rejected("", "Привет", us, 'П');
        rejected("abc", "abПривет", us, 'П');
        rejected("аб", "аб hello", ru, 'h');
        rejected("abc", "ab\uD83D\uDE00c", us, 0x1f600);
        rejected("аб", "а\uD83D\uDE00б", ru, 0x1f600);
        rejected("abc", "abé", us, 'é');
        rejected("аб", "а—б", ru, '—');
        // User corrects a rejected local edit; previous remains the last transmitted remote line.
        rejected("ab", "ab\uD83D\uDE00", us, 0x1f600);
        accepted("ab", "abc", us, 0, "c");
        rejected("кот", "hello", ru, 'h');
        accepted("кот", "коты", ru, 0, "ы");
        // Accent composition uses two physical keys but edits one host character.
        accepted("hola", "holá", KeyboardMapper.HostLayout.ES, 1, "á");
        accepted("áé", "á", KeyboardMapper.HostLayout.ES, 1, "");
        accepted("", "España: ¡sí! ¿qué?", KeyboardMapper.HostLayout.ES, 0, "España: ¡sí! ¿qué?");
        accepted("", "für Größe", KeyboardMapper.HostLayout.DE, 0, "für Größe");
        accepted("", "français, été, Noël", KeyboardMapper.HostLayout.FR, 0, "français, été, Noël");
        rejected("hola", "holá😀", KeyboardMapper.HostLayout.ES, 0x1f600);
        rejected("été", "Été", KeyboardMapper.HostLayout.FR, 'É');
        System.out.println("OK: layout-aware live input; atomic unsupported edits; correction and suffix reconciliation");
    }
}
