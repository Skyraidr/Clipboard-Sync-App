package com.example.clipboardsync;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds and parses Clipboard Sync pairing QR codes (shown by the desktop app and by phones).
 *
 * Format: clipsync://pair?v=1[&r=p]&n=<url-encoded name>&id=<device id>&ip=<ip1>,<ip2>,...
 *  - r=p marks a phone's code; desktop codes have no r
 *  - id is optional: desktops without auto-reconnect don't send it
 *
 * Plain Java (no Android classes) so it can be used from the camera thread and unit tested.
 */
public final class PairingQr {

    public static final String PREFIX = "clipsync://pair";
    public static final int PORT = 8888;

    private PairingQr() { }

    public static final class Info {
        public final String name;
        public final String id;        // null if the code didn't include one
        public final boolean isPhone;  // false = desktop
        public final List<String> ips;

        Info(String name, String id, boolean isPhone, List<String> ips) {
            this.name = name;
            this.id = id;
            this.isPhone = isPhone;
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
        boolean isPhone = false;
        List<String> ips = new ArrayList<>();
        for (String param : s.substring(prefixLen + 1).split("&")) {
            int eq = param.indexOf('=');
            if (eq <= 0) continue;
            String key = param.substring(0, eq);
            String value = decode(param.substring(eq + 1));
            if (value == null) continue;

            if (key.equals("n")) {
                name = value;
            } else if (key.equals("r")) {
                isPhone = value.equals("p");
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
        return new Info(cleanName(name, isPhone), id, isPhone, Collections.unmodifiableList(ips));
    }

    /** The content of this phone's own pairing QR code. */
    public static String buildPhonePayload(String name, String id, List<String> ips) {
        String encodedName;
        try {
            // Spaces as %20 (not '+') so every URI decoder reads the name the same way
            encodedName = URLEncoder.encode(cleanName(name, true), "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            encodedName = "Phone";
        }
        StringBuilder sb = new StringBuilder(PREFIX).append("?v=1&r=p&n=").append(encodedName);
        if (DeviceInfo.isValidId(id)) sb.append("&id=").append(id);
        sb.append("&ip=");
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ips.get(i));
        }
        return sb.toString();
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

    private static String cleanName(String name, boolean isPhone) {
        String fallback = isPhone ? "Phone" : "Desktop";
        if (name == null) return fallback;
        // '|' is the separator used when saving devices, so it can't appear in a name
        String cleaned = name.replaceAll("\\p{Cntrl}", "").replace('|', '-').trim();
        if (cleaned.isEmpty()) return fallback;
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
