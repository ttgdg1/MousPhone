package io.pocketpad.remote;

import java.util.Arrays;
import java.util.List;

/** Regression checks for saved Windows layout order, independent from UI locale. */
public final class CheckHostLayouts {
    private static void same(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void rejectsEmpty(List<KeyboardMapper.HostLayout> layouts) {
        try {
            HostLayoutPreferences.encode(layouts);
            throw new AssertionError("An empty Windows layout list must not be saved");
        } catch (IllegalArgumentException expected) { /* Keep at least one usable layout. */ }
    }

    public static void main(String[] args) {
        List<KeyboardMapper.HostLayout> legacy = Arrays.asList(
            KeyboardMapper.HostLayout.RU, KeyboardMapper.HostLayout.US);
        same(legacy, HostLayoutPreferences.parse(null), "existing installs keep RU/US defaults");
        same(legacy, HostLayoutPreferences.parse(""), "missing preference recovers safely");
        same(legacy, HostLayoutPreferences.parse("unknown,UA,,removed"), "stale preference recovers safely");

        List<KeyboardMapper.HostLayout> windowsOrder = Arrays.asList(
            KeyboardMapper.HostLayout.DE, KeyboardMapper.HostLayout.US,
            KeyboardMapper.HostLayout.FR, KeyboardMapper.HostLayout.RU,
            KeyboardMapper.HostLayout.ES, KeyboardMapper.HostLayout.UK);
        same(windowsOrder, HostLayoutPreferences.parse(" DE, US,FR,RU,ES,UK,DE,removed "),
            "valid Windows order is preserved while stale names and duplicates are removed");
        same("DE,US,FR,RU,ES,UK", HostLayoutPreferences.encode(windowsOrder),
            "saved order is stable and readable");
        same(windowsOrder, HostLayoutPreferences.parse(HostLayoutPreferences.encode(windowsOrder)),
            "saved custom order survives app restart");
        same("DE,US", HostLayoutPreferences.encode(Arrays.asList(
            KeyboardMapper.HostLayout.DE, KeyboardMapper.HostLayout.DE, KeyboardMapper.HostLayout.US)),
            "duplicate persisted entries cannot skip the expected language");

        for (int i = 0; i < windowsOrder.size(); i++) {
            same(windowsOrder.get((i + 1) % windowsOrder.size()),
                HostLayoutPreferences.next(windowsOrder, windowsOrder.get(i)),
                "Win+Space prediction follows configured Windows order, including wraparound");
        }
        List<KeyboardMapper.HostLayout> one = HostLayoutPreferences.parse("FR");
        same(1, one.size(), "one configured Windows layout is supported");
        same(null, HostLayoutPreferences.next(one, KeyboardMapper.HostLayout.FR),
            "single-layout mode never predicts an unavailable language");
        same(null, HostLayoutPreferences.next(legacy, KeyboardMapper.HostLayout.DE),
            "calibration outside configured ring does not guess Win+Space outcome");
        same(null, HostLayoutPreferences.next(null, KeyboardMapper.HostLayout.RU),
            "unconfigured ring cannot switch");
        rejectsEmpty(null);
        rejectsEmpty(java.util.Collections.emptyList());

        same(6, KeyboardMapper.HostLayout.values().length, "only the six requested host layouts are offered");
        for (KeyboardMapper.HostLayout layout : KeyboardMapper.HostLayout.values()) {
            if (layout.name().equals("UA") || layout.name().equals("UKRAINIAN")) {
                throw new AssertionError("Unexpected layout in configured language choices");
            }
        }
        System.out.println("CheckHostLayouts passed: migration, saved Windows order, cycle and safe single-layout mode.");
    }
}
