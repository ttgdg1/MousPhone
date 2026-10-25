package io.pocketpad.remote;

/** Deterministic, device-free logic shared by IME live-input and JVM tests. */
final class LiveTextDiff {
    static final class Update {
        final int backspaces;
        final String text;
        final int unsupportedCodePoint;
        Update(int backspaces, String text, int unsupportedCodePoint) {
            this.backspaces = backspaces;
            this.text = text;
            this.unsupportedCodePoint = unsupportedCodePoint;
        }
        boolean accepted() { return unsupportedCodePoint < 0; }
    }

    /** Validate the entire edit before deleting or sending anything on the host. */
    static Update plan(String previous, String next, KeyboardMapper.HostLayout layout) {
        for (int i = 0; i < next.length();) {
            int codePoint = next.codePointAt(i);
            if (codePoint > Character.MAX_VALUE ||
                    KeyboardMapper.strokesForChar((char) codePoint, layout) == null) {
                return new Update(0, "", codePoint);
            }
            i += Character.charCount(codePoint);
        }
        int shared = commonPrefix(previous, next);
        return new Update(previous.length() - shared, next.substring(shared), -1);
    }

    /** The HID cursor is kept at the end of the local buffer, so rewrite the changed suffix. */
    static int commonPrefix(String left, String right) {
        int n = Math.min(left.length(), right.length());
        int i = 0;
        while (i < n && left.charAt(i) == right.charAt(i)) i++;
        return i;
    }
    private LiveTextDiff() {}
}
