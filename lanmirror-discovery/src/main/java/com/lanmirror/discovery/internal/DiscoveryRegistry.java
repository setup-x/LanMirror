package com.lanmirror.discovery.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure Java bounded discovery state. Tickets, not names alone, authorize late resolution results.
 */
public final class DiscoveryRegistry {
    public static final int MAX_RESULTS = 32, MAX_PENDING = 64;

    public static final class Entry {
        public final String id, name, host;
        public final int port, slots;

        public Entry(String id, String name, String host, int port, int slots) {
            if (!text(id, 128) || !text(name, 128) || !text(host, 256) || port < 1 || port > 65535 || slots < 1 || slots > 4)
                throw new IllegalArgumentException("Invalid discovery entry");
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.slots = slots;
        }
    }

    public static final class Ticket {
        public final String serviceKey;
        public final long generation;
        private final long serial;

        private Ticket(String key, long gen, long serial) {
            serviceKey = key;
            generation = gen;
            this.serial = serial;
        }
    }

    private static final class Record {
        final Ticket ticket;
        Entry entry;
        boolean pending = true;

        Record(Ticket ticket) {
            this.ticket = ticket;
        }
    }

    private final Map<String, Record> records = new LinkedHashMap<>();
    private long generation, serial;
    private int pending;
    private boolean active;

    public synchronized long begin() {
        generation++;
        active = true;
        records.clear();
        pending = 0;
        return generation;
    }

    public synchronized void stop() {
        generation++;
        active = false;
        records.clear();
        pending = 0;
    }

    /**
     * Duplicate discoveries do not enqueue duplicate resolutions; at most 64 are outstanding.
     */
    public synchronized Ticket found(String serviceKey) {
        if (!active || !text(serviceKey, 512) || records.containsKey(serviceKey) || pending >= MAX_PENDING
                || records.size() >= MAX_RESULTS + MAX_PENDING) return null;
        Ticket ticket = new Ticket(serviceKey, generation, ++serial);
        records.put(serviceKey, new Record(ticket));
        pending++;
        return ticket;
    }

    public synchronized boolean isCurrent(Ticket ticket) {
        if (ticket == null || !active || ticket.generation != generation) return false;
        Record r = records.get(ticket.serviceKey);
        return r != null && r.ticket == ticket;
    }

    /**
     * Result may be rejected when 32 distinct installation ids are already visible.
     */
    public synchronized boolean resolved(Ticket ticket, Entry entry) {
        if (!isCurrent(ticket) || entry == null) return false;
        Record r = records.get(ticket.serviceKey);
        if (!r.pending) return false;
        r.pending = false;
        pending--;
        if (!hasId(entry.id) && uniqueCount() >= MAX_RESULTS) {
            records.remove(ticket.serviceKey);
            return false;
        }
        r.entry = entry;
        return true;
    }

    public synchronized boolean failed(Ticket ticket) {
        if (!isCurrent(ticket)) return false;
        Record r = records.remove(ticket.serviceKey);
        if (r.pending) pending--;
        return true;
    }

    /**
     * Removal invalidates any old Ticket; finding the same name later creates a different Ticket.
     */
    public synchronized boolean lost(String serviceKey) {
        Record removed = records.remove(serviceKey);
        if (removed == null) return false;
        if (removed.pending) pending--;
        return true;
    }

    private boolean hasId(String id) {
        for (Record r : records.values()) if (r.entry != null && r.entry.id.equals(id)) return true;
        return false;
    }

    private int uniqueCount() {
        return byId().size();
    }

    private Map<String, Record> byId() {
        Map<String, Record> result = new LinkedHashMap<>();
        for (Record r : records.values())
            if (r.entry != null) {
                Record old = result.get(r.entry.id);
                if (old == null || r.ticket.serial > old.ticket.serial) result.put(r.entry.id, r);
            }
        return result;
    }

    /**
     * Snapshot and entries are immutable; newest service appearance wins per installation UUID.
     */
    public synchronized List<Entry> snapshot() {
        List<Entry> list = new ArrayList<>();
        for (Record r : byId().values()) list.add(r.entry);
        Collections.sort(list, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                int n = a.name.compareToIgnoreCase(b.name);
                return n != 0 ? n : a.id.compareTo(b.id);
            }
        });
        return Collections.unmodifiableList(list);
    }

    public synchronized int pendingCount() {
        return pending;
    }

    public synchronized int serviceCount() {
        return records.size();
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized boolean active() {
        return active;
    }

    private static boolean text(String value, int max) {
        return value != null && !value.trim().isEmpty() && value.length() <= max;
    }
}
