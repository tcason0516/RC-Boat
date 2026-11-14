package com.rcboat.racing;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Best times and speeds, kept per course, class, engine and hull (rule book I-II-D: records
 * for every LSG class except LSG36 Offshore). Saved by MainActivity as a single string.
 */
final class Records {
    private final HashMap<String, Float> best = new HashMap<>();
    /** Set when a record changes, so the app knows to save. */
    volatile boolean dirty;

    /** Lower is better for times; higher for speeds. Returns true for a new record. */
    synchronized boolean offer(String key, float value, boolean higherIsBetter) {
        Float old = best.get(key);
        boolean better = old == null || (higherIsBetter ? value > old : value < old);
        if (better) { best.put(key, value); dirty = true; }
        return better && old != null;
    }

    synchronized Float get(String key) { return best.get(key); }

    synchronized String serialize() {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Float> e : best.entrySet()) {
            if (s.length() > 0) s.append(';');
            s.append(e.getKey()).append('=').append(String.format(Locale.US, "%.3f", e.getValue()));
        }
        return s.toString();
    }

    synchronized void load(String data) {
        best.clear();
        if (data == null || data.isEmpty()) return;
        for (String part : data.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try { best.put(part.substring(0, eq), Float.parseFloat(part.substring(eq + 1))); }
            catch (NumberFormatException ignored) { }
        }
    }
}
