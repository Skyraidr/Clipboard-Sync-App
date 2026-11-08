package com.example.clipboardsync;

import java.util.List;

/**
 * A saved target device (normally a desktop). Plain Java so the matching rules can be unit tested.
 *
 * Saved as "name|ip" or "name|ip|id". The id is the desktop's permanent id: it comes from the QR code,
 * or is learned from the desktop's presence beacons, and lets us follow the desktop when its IP changes.
 */
final class DeviceInfo {
    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    // volatile: updated on the main thread, read by background send threads
    volatile String name;
    volatile String ip;
    volatile String id;
    volatile boolean selected = true;

    DeviceInfo(String name, String ip) {
        this(name, ip, null);
    }

    DeviceInfo(String name, String ip, String id) {
        this.name = name;
        this.ip = ip;
        this.id = isValidId(id) ? id : null;
    }

    String serialize() {
        return id == null ? name + "|" + ip : name + "|" + ip + "|" + id;
    }

    /** Returns null for unreadable entries. Entries saved by older versions have no id. */
    static DeviceInfo parse(String saved) {
        if (saved == null) return null;
        String[] parts = saved.split("\\|");
        if (parts.length < 2) return null;
        return new DeviceInfo(parts[0], parts[1], parts.length >= 3 ? parts[2] : null);
    }

    static boolean isValidId(String id) {
        return id != null && id.matches(ID_PATTERN);
    }

    /** What changed when a desktop announced itself. */
    static final class SeenResult {
        final DeviceInfo device;
        final String oldIp; // Non-null when the desktop moved to a new IP

        SeenResult(DeviceInfo device, String oldIp) {
            this.device = device;
            this.oldIp = oldIp;
        }
    }

    /**
     * A desktop announced (id, name) at ip. Updates the matching saved device in place.
     * Returns what changed (so the caller can save), or null if nothing did / the desktop isn't saved.
     */
    static SeenResult applyDesktopSeen(List<DeviceInfo> devices, String id, String name, String ip) {
        if (!isValidId(id) || ip == null) return null;

        // 1. Known by id: follow it to its new address
        for (DeviceInfo d : devices) {
            if (id.equals(d.id)) {
                if (ip.equals(d.ip)) return null;
                String oldIp = d.ip;
                d.ip = ip;
                return new SeenResult(d, oldIp);
            }
        }
        // 2. Saved without an id (added manually / older version) at this exact address: learn its id
        for (DeviceInfo d : devices) {
            if (d.id == null && ip.equals(d.ip)) {
                d.id = id;
                return new SeenResult(d, null);
            }
        }
        // 3. Saved without an id under the same name (e.g. paired by QR before ids existed): learn id + address
        for (DeviceInfo d : devices) {
            if (d.id == null && name != null && d.name.equalsIgnoreCase(name)) {
                d.id = id;
                if (ip.equals(d.ip)) return new SeenResult(d, null);
                String oldIp = d.ip;
                d.ip = ip;
                return new SeenResult(d, oldIp);
            }
        }
        return null;
    }

    /**
     * Saves a desktop we just paired with by QR code, updating an existing entry instead of adding a duplicate.
     * An entry matched by id or address keeps the name the user gave it.
     */
    static DeviceInfo upsertPaired(List<DeviceInfo> devices, String name, String ip, String id) {
        String validId = isValidId(id) ? id : null;
        DeviceInfo match = null;
        if (validId != null) {
            for (DeviceInfo d : devices) {
                if (validId.equals(d.id)) { match = d; break; }
            }
        }
        if (match == null) {
            for (DeviceInfo d : devices) {
                if (d.ip.equals(ip) && (d.id == null || validId == null || d.id.equals(validId))) { match = d; break; }
            }
        }
        if (match == null) {
            for (DeviceInfo d : devices) {
                // Same desktop, new address (e.g. DHCP changed it)
                if (d.name.equalsIgnoreCase(name) && (d.id == null || validId == null || d.id.equals(validId))) { match = d; break; }
            }
        }

        if (match == null) {
            match = new DeviceInfo(name, ip, validId);
            devices.add(match);
        } else {
            match.ip = ip;
            if (validId != null) match.id = validId;
        }
        match.selected = true;
        return match;
    }
}
