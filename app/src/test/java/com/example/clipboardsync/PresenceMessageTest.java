package com.example.clipboardsync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PresenceMessageTest {

    @Test
    public void roundTrips() {
        String msg = PresenceMessage.encode(PresenceMessage.ROLE_PHONE, "phone123", "Pixel | 8\n");
        assertEquals("CLIPSYNC1|P|phone123|Pixel - 8", msg);
        PresenceMessage parsed = PresenceMessage.parse(msg);
        assertEquals(PresenceMessage.ROLE_PHONE, parsed.role);
        assertEquals("phone123", parsed.id);
        assertEquals("Pixel - 8", parsed.name);
    }

    @Test
    public void readsDesktopBeacon() {
        PresenceMessage m = PresenceMessage.parse("CLIPSYNC1|D|076c24fa828f|DESKTOP-ABC");
        assertEquals(PresenceMessage.ROLE_DESKTOP, m.role);
        assertEquals("076c24fa828f", m.id);
        assertEquals("DESKTOP-ABC", m.name);
        assertEquals("Desktop", PresenceMessage.parse("CLIPSYNC1|D|abc|").name);
    }

    @Test
    public void rejectsJunk() {
        assertNull(PresenceMessage.parse(null));
        assertNull(PresenceMessage.parse("hello"));
        assertNull(PresenceMessage.parse("CLIPSYNC1|D|abc"));
        assertNull(PresenceMessage.parse("CLIPSYNC2|D|abc|x"));
        assertNull(PresenceMessage.parse("CLIPSYNC1|X|abc|x"));
        assertNull(PresenceMessage.parse("CLIPSYNC1|DD|abc|x"));
        assertNull(PresenceMessage.parse("CLIPSYNC1|D|bad id|x"));
    }
}
