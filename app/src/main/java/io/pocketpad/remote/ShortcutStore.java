package io.pocketpad.remote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Customisable combinations saved locally, no network or account. */
final class ShortcutStore {
    static final class Item {
        String label;
        int modifiers;
        int key;
        Item(String label, int modifiers, int key) {
            this.label = label; this.modifiers = modifiers; this.key = key;
        }
    }
    private final SharedPreferences preferences;
    private final ArrayList<Item> items = new ArrayList<>();
    private final boolean dock;
    ShortcutStore(Context context) {
        this(context, false);
    }
    static ShortcutStore forDock(Context context) {
        return new ShortcutStore(context, true);
    }
    private ShortcutStore(Context context, boolean dock) {
        this.dock = dock;
        preferences = context.getSharedPreferences(
            dock ? "pocketpad_dock_shortcuts" : "pocketpad_shortcuts", Context.MODE_PRIVATE);
        String json = preferences.getString("items", null);
        if (json == null) { defaults(); return; }
        try {
            JSONArray source = new JSONArray(json);
            for (int i = 0; i < source.length(); i++) {
                JSONObject object = source.getJSONObject(i);
                int key = object.getInt("key");
                int modifiers = object.getInt("modifiers");
                String label = object.getString("label");
                if (key >= 4 && key <= 0xE7 && modifiers >= 0 && modifiers <= 15 &&
                    label.length() > 0 && label.length() <= 40) {
                    items.add(new Item(label, modifiers, key));
                }
            }
        } catch (Exception e) { items.clear(); defaults(); }
    }
    List<Item> all() { return items; }
    void add(Item item) { items.add(item); save(); }
    void update(int index, Item item) { items.set(index, item); save(); }
    void delete(int index) { items.remove(index); save(); }
    void move(int index, int direction) {
        int target = index + direction;
        if (target < 0 || target >= items.size()) return;
        Item item = items.remove(index);
        items.add(target, item);
        save();
    }
    private void defaults() {
        if (dock) {
            items.add(new Item("Ctrl+C", KeyboardMapper.CTRL, 0x06));
            items.add(new Item("Ctrl+V", KeyboardMapper.CTRL, 0x19));
            items.add(new Item("Alt+Tab", KeyboardMapper.ALT, 0x2B));
            save();
            return;
        }
        items.add(new Item("Alt+Tab", KeyboardMapper.ALT, 0x2B));
        items.add(new Item("Ctrl+C", KeyboardMapper.CTRL, 0x06));
        items.add(new Item("Ctrl+V", KeyboardMapper.CTRL, 0x19));
        items.add(new Item("Win+D", KeyboardMapper.META, 0x07));
        items.add(new Item("F10", 0, 0x43));
        items.add(new Item("Esc", 0, 0x29));
        save();
    }
    private void save() {
        JSONArray json = new JSONArray();
        for (Item item : items) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("label", item.label);
                obj.put("modifiers", item.modifiers);
                obj.put("key", item.key);
                json.put(obj);
            } catch (Exception ignored) {}
        }
        preferences.edit().putString("items", json.toString()).apply();
    }
}
