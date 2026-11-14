package com.lanmirror.core.api;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Codec-owner-only bounded PTS -> FIFO send stamps. Repeated PTS never overwrites an older stamp.
 */
public final class FrameTimestampTracker {
    private final int limit;
    private final LinkedHashMap<Long, ArrayDeque<Long>> times = new LinkedHashMap<>();
    private int count;

    public FrameTimestampTracker(int limit) {
        if (limit < 1 || limit > 4096)
            throw new IllegalArgumentException("Invalid timestamp limit");
        this.limit = limit;
    }

    public void add(long pts, long sentNs) {
        ArrayDeque<Long> values = times.get(pts);
        if (values == null) {
            values = new ArrayDeque<>();
            times.put(pts, values);
        }
        values.addLast(sentNs);
        count++;
        while (count > limit) {
            Iterator<Map.Entry<Long, ArrayDeque<Long>>> entries = times.entrySet().iterator();
            Map.Entry<Long, ArrayDeque<Long>> oldest = entries.next();
            oldest.getValue().removeFirst();
            count--;
            if (oldest.getValue().isEmpty()) entries.remove();
        }
    }

    public Long remove(long pts) {
        ArrayDeque<Long> values = times.get(pts);
        if (values == null) return null;
        Long sent = values.removeFirst();
        count--;
        if (values.isEmpty()) times.remove(pts);
        return sent;
    }

    public int size() {
        return count;
    }

    public void clear() {
        times.clear();
        count = 0;
    }
}
