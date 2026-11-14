package com.lanmirror.app;

/** Pure Java address assembly. Dots are generated, never typed by the user. */
public final class Ipv4 {
    private Ipv4() { }
    public static String join(String[] parts) {
        if (parts == null || parts.length != 4) throw new IllegalArgumentException("IPv4 needs four parts");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) result.append('.');
            result.append(parts[i] == null ? "" : parts[i].trim());
        }
        return result.toString();
    }
    public static boolean isValid(String address) {
        if (address == null) return false;
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
