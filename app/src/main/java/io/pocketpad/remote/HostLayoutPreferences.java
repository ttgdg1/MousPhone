package io.pocketpad.remote;

import java.util.ArrayList;
import java.util.List;

/** Ordered expected Windows layouts; independent from the app interface locale. */
final class HostLayoutPreferences {
    static List<KeyboardMapper.HostLayout> parse(String stored) {
        ArrayList<KeyboardMapper.HostLayout> layouts = new ArrayList<>();
        if (stored != null) for (String name : stored.split(",")) {
            try {
                KeyboardMapper.HostLayout layout = KeyboardMapper.HostLayout.valueOf(name.trim());
                if (!layouts.contains(layout)) layouts.add(layout);
            } catch (IllegalArgumentException ignored) { /* Ignore stale or unknown preferences. */ }
        }
        if (layouts.isEmpty()) {
            layouts.add(KeyboardMapper.HostLayout.RU);
            layouts.add(KeyboardMapper.HostLayout.US);
        }
        return layouts;
    }

    static String encode(List<KeyboardMapper.HostLayout> layouts) {
        if (layouts == null || layouts.isEmpty()) throw new IllegalArgumentException("At least one layout is required");
        ArrayList<String> names = new ArrayList<>();
        for (KeyboardMapper.HostLayout layout : layouts) {
            if (layout == null) throw new IllegalArgumentException("Null layout");
            if (!names.contains(layout.name())) names.add(layout.name());
        }
        return String.join(",", names);
    }

    static KeyboardMapper.HostLayout next(List<KeyboardMapper.HostLayout> layouts,
                                          KeyboardMapper.HostLayout current) {
        if (layouts == null || layouts.size() < 2) return null;
        int index = layouts.indexOf(current);
        return index < 0 ? null : layouts.get((index + 1) % layouts.size());
    }

    private HostLayoutPreferences() {}
}
