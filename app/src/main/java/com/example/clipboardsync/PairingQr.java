package com.example.clipboardsync;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the pairing QR code shown by the Clipboard Sync desktop app.
 *
 * Format: clipsync://pair?v=1&n=<url-encoded desktop name>&id=<desktop id>&ip=<ip1>,<ip2>,...
 * (id is optional: desktops without auto-reconnect don't send it)
 *
 * Plain Java (no Android classes) so it can be used from the camera thread and unit tested.
 */
public final class PairingQr {

    public static final String PREFIX = "clipsync://pair";
    public static final int PORT = 8888;

    private PairingQr() { }

    public static final class Info {
        public final String desktopName;
        public final String desktopId; // null if the desktop didn't include one
        public final List<String> ips;

        Info(String desktopName, String desktopId, List<String> ips) {
            this.desktopName = desktopName;
            this.desktopId = desktopId;
            this.ips = ips;
        }
    }

    /** Returns the pairing info, or null if this is not a valid Clipboard Sync pairing code. */
    public static Info parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int prefixLen = PREFIX.length();
        if (s.length() <= prefixLen
                || !s.regionMatches(true, 0, PREFIX, 0, prefixLen)
                || s.charAt(prefixLen) != '?') {
            return null;
        }

        String name = null;
        String id = null;
        List<String> ips = new ArrayList<>();
        for (String param : s.substring(prefixLen + 1).split("&")) {
            int eq = param.indexOf('=');
            if (eq <= 0) continue;
            String key = param.substring(0, eq);
            String value = decode(param.substring(eq + 1));
            if (value == null) continue;

            if (key.equals("n")) {
                name = value;
            } else if (key.equals("id")) {
                id = DeviceInfo.isValidId(value) ? value : null;
            } else if (key.equals("ip")) {
                for (String ip : value.split(",")) {
                    ip = ip.trim();
                    if (isValidIpv4(ip) && !ips.contains(ip)) ips.add(ip);
                }
            }
        }
        if (ips.isEmpty()) return null;
        return new Info(cleanName(name), id, Collections.unmodifiableList(ips));
    }

    /**
     * Orders the desktop's addresses so the ones on the same /24 subnet as one of this
     * phone's addresses are tried first (keeps the original order otherwise).
     */
    public static List<String> prioritizeSameSubnet(List<String> desktopIps, List<String> localIps) {
        List<String> same = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String ip : desktopIps) {
            boolean match = false;
            String prefix = subnetPrefix(ip);
            if (prefix != null) {
                for (String local : localIps) {
                    if (prefix.equals(subnetPrefix(local))) { match = true; break; }
                }
            }
            (match ? same : other).add(ip);
        }
        same.addAll(other);
        return same;
    }

    static boolean isValidIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') return false;
            }
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }

    private static String subnetPrefix(String ip) {
        if (!isValidIpv4(ip)) return null;
        return ip.substring(0, ip.lastIndexOf('.') + 1);
    }

    private static String cleanName(String name) {
        if (name == null) return "Desktop";
        // '|' is the separator used when saving devices, so it can't appear in a name
        String cleaned = name.replaceAll("\\p{Cntrl}", "").replace('|', '-').trim();
        if (cleaned.isEmpty()) return "Desktop";
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) { // Malformed % escape
            return null;
        }
    }
}
