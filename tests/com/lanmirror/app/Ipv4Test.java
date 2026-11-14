package com.lanmirror.app;

public final class Ipv4Test {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Check " + checks); }
    public static void main(String[] args) {
        check("192.168.1.23".equals(Ipv4.join(new String[] {"192", "168", "1", "23"})));
        check(Ipv4.isValid(Ipv4.join(new String[] {"192", "168", "1", "23"})));
        check("10.0.0.1".equals(Ipv4.join(new String[] {"10", "0", "0", "1"})));
        check("192.168.1.23".equals(Ipv4.join(new String[] {" 192 ", "168", "1", "23"})));
        check(Ipv4.isValid("0.0.0.0")); check(Ipv4.isValid("255.255.255.255"));
        check(!Ipv4.isValid(Ipv4.join(new String[] {"192", "", "1", "23"})));
        check(!Ipv4.isValid(Ipv4.join(new String[] {"192", "168", "1", "256"})));
        check(!Ipv4.isValid("192.168.1")); check(!Ipv4.isValid("192.168.1.23.4"));
        check(!Ipv4.isValid("192.168.1.23:7236")); check(!Ipv4.isValid("192.168.1.23 (wlan0)"));
        check(!Ipv4.isValid("192.168.-1.23")); check(!Ipv4.isValid("192.168.a.23"));
        check(!Ipv4.isValid("１９２.168.1.23")); check(!Ipv4.isValid(null));
        try { Ipv4.join(new String[] {"1", "2"}); throw new AssertionError(); } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("PASS: " + checks + " IPv4 assertions; four numeric parts generate all three dots");
    }
}
