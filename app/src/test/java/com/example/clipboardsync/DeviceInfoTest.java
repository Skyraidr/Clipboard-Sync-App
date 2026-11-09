package com.example.clipboardsync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class DeviceInfoTest {

    @Test
    public void serializesWithAndWithoutId() {
        assertEquals("PC|192.168.1.2", new DeviceInfo("PC", "192.168.1.2").serialize());
        assertEquals("PC|192.168.1.2|abc123", new DeviceInfo("PC", "192.168.1.2", "abc123").serialize());
    }

    @Test
    public void parsesOldAndNewFormats() {
        DeviceInfo old = DeviceInfo.parse("PC|192.168.1.2");
        assertEquals("PC", old.name);
        assertEquals("192.168.1.2", old.ip);
        assertNull(old.id);
        assertEquals("abc123", DeviceInfo.parse("PC|192.168.1.2|abc123").id);
        assertNull(DeviceInfo.parse("PC|192.168.1.2|bad id!").id);
        assertNull(DeviceInfo.parse("garbage"));
    }

    @Test
    public void followsKnownDesktopToNewIp() {
        List<DeviceInfo> list = new ArrayList<>();
        list.add(new DeviceInfo("Laptop", "192.168.1.2", "desk1"));
        DeviceInfo.SeenResult r = DeviceInfo.applyPeerSeen(list, "desk1", "DESKTOP-X", "192.168.1.9");
        assertNotNull(r);
        assertEquals("192.168.1.2", r.oldIp);
        assertEquals("192.168.1.9", list.get(0).ip);
        assertEquals("Laptop", list.get(0).name); // user's name kept
        assertNull(DeviceInfo.applyPeerSeen(list, "desk1", "DESKTOP-X", "192.168.1.9")); // no change
    }

    @Test
    public void learnsIdForManuallyAddedDeviceAtSameIp() {
        List<DeviceInfo> list = new ArrayList<>();
        list.add(new DeviceInfo("PC", "192.168.1.2"));
        DeviceInfo.SeenResult r = DeviceInfo.applyPeerSeen(list, "desk1", "DESKTOP-X", "192.168.1.2");
        assertNotNull(r);
        assertNull(r.oldIp);
        assertEquals("desk1", list.get(0).id);
    }

    @Test
    public void learnsIdByNameForDevicePairedBeforeIds() {
        List<DeviceInfo> list = new ArrayList<>();
        list.add(new DeviceInfo("DESKTOP-X", "192.168.1.2"));
        DeviceInfo.SeenResult r = DeviceInfo.applyPeerSeen(list, "desk1", "desktop-x", "10.0.0.7");
        assertNotNull(r);
        assertEquals("192.168.1.2", r.oldIp);
        assertEquals("10.0.0.7", list.get(0).ip);
        assertEquals("desk1", list.get(0).id);
    }

    @Test
    public void ignoresUnknownDesktopsAndDevicesWithOtherIds() {
        List<DeviceInfo> list = new ArrayList<>();
        list.add(new DeviceInfo("DESKTOP-X", "192.168.1.2", "other"));
        assertNull(DeviceInfo.applyPeerSeen(list, "desk1", "DESKTOP-X", "192.168.1.2"));
        assertNull(DeviceInfo.applyPeerSeen(list, "bad id", "DESKTOP-X", "192.168.1.5"));
        assertEquals("192.168.1.2", list.get(0).ip);
        assertEquals("other", list.get(0).id);
    }

    @Test
    public void upsertPairedMatchesByIdThenIpThenName() {
        List<DeviceInfo> list = new ArrayList<>();
        DeviceInfo byId = new DeviceInfo("Mine", "192.168.1.2", "desk1");
        byId.selected = false;
        list.add(byId);
        assertSame(byId, DeviceInfo.upsertPaired(list, "DESKTOP-X", "192.168.1.50", "desk1"));
        assertEquals("192.168.1.50", byId.ip);
        assertEquals("Mine", byId.name);
        assertTrue(byId.selected);

        DeviceInfo manual = new DeviceInfo("Work PC", "10.0.0.3");
        list.add(manual);
        assertSame(manual, DeviceInfo.upsertPaired(list, "WORK-1", "10.0.0.3", "desk2"));
        assertEquals("desk2", manual.id);

        DeviceInfo added = DeviceInfo.upsertPaired(list, "NEW-PC", "10.0.0.9", "desk3");
        assertEquals(3, list.size());
        assertEquals("NEW-PC|10.0.0.9|desk3", added.serialize());

        // A different desktop now at an address that used to belong to desk1: must not overwrite desk1
        DeviceInfo other = DeviceInfo.upsertPaired(list, "OTHER", "192.168.1.50", "desk4");
        assertEquals(4, list.size());
        assertEquals("desk1", byId.id);
        assertEquals("desk4", other.id);
    }
}
