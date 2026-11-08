package com.example.clipboardsync;

/**
 * The tiny UDP "I'm here" message both apps broadcast so they can find each other after an IP change:
 *
 *   CLIPSYNC1|&lt;D=desktop or P=phone&gt;|&lt;permanent device id&gt;|&lt;name&gt;
 *
 * The sender's IP is not in the message: the receiver takes it from the packet itself.
 */
final class PresenceMessage {
    static final String MAGIC = "CLIPSYNC1";
    static final char ROLE_DESKTOP = 'D';
    static final char ROLE_PHONE = 'P';

    final char role;
    final String id;
    final String name;

    private PresenceMessage(char role, String id, String name) {
        this.role = role;
        this.id = id;
        this.name = name;
    }

    static String encode(char role, String id, String name) {
        return MAGIC + "|" + role + "|" + id + "|" + cleanName(name, role == ROLE_DESKTOP ? "Desktop" : "Phone");
    }

    /** Returns null for anything that isn't a valid message. */
    static PresenceMessage parse(String msg) {
        if (msg == null) return null;
        String[] f = msg.split("\\|", -1);
        if (f.length < 4 || !MAGIC.equals(f[0]) || f[1].length() != 1) return null;
        char role = f[1].charAt(0);
        if (role != ROLE_DESKTOP && role != ROLE_PHONE) return null;
        if (!DeviceInfo.isValidId(f[2])) return null;
        return new PresenceMessage(role, f[2], cleanName(f[3], role == ROLE_DESKTOP ? "Desktop" : "Phone"));
    }

    /** Removes characters that would break the message or the saved device list ('|'). */
    static String cleanName(String raw, String fallback) {
        if (raw == null) return fallback;
        String name = raw.replaceAll("\\p{Cntrl}", "").replace('|', '-').trim();
        if (name.isEmpty()) return fallback;
        return name.length() > 60 ? name.substring(0, 60) : name;
    }
}
