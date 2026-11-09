package com.example.clipboardsync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class PairingQrTest {

    @Test
    public void parsesDesktopPayload() {
        PairingQr.Info info = PairingQr.parse("clipsync://pair?v=1&n=MY%20PC&ip=192.168.1.23,10.0.0.5");
        assertNotNull(info);
        assertEquals("MY PC", info.name);
        assertEquals(Arrays.asList("192.168.1.23", "10.0.0.5"), info.ips);
    }

    @Test
    public void readsDesktopIdWhenPresent() {
        assertEquals("076c24fa828f", PairingQr.parse("clipsync://pair?v=1&n=PC&id=076c24fa828f&ip=192.168.1.2").id);
        assertNull(PairingQr.parse("clipsync://pair?v=1&n=PC&ip=192.168.1.2").id);
        assertNull(PairingQr.parse("clipsync://pair?v=1&n=PC&id=bad%20id&ip=192.168.1.2").id);
    }

    @Test
    public void desktopCodesAreNotPhones() {
        assertFalse(PairingQr.parse("clipsync://pair?v=1&n=PC&ip=192.168.1.2").isPhone);
    }

    @Test
    public void phonePayloadRoundTrips() {
        String payload = PairingQr.buildPhonePayload("Isaac's Galaxy S23", "phone123abc",
                Arrays.asList("192.168.1.40", "10.0.0.8"));
        assertEquals("clipsync://pair?v=1&r=p&n=Isaac%27s%20Galaxy%20S23&id=phone123abc&ip=192.168.1.40,10.0.0.8", payload);
        PairingQr.Info info = PairingQr.parse(payload);
        assertNotNull(info);
        assertTrue(info.isPhone);
        assertEquals("Isaac's Galaxy S23", info.name);
        assertEquals("phone123abc", info.id);
        assertEquals(Arrays.asList("192.168.1.40", "10.0.0.8"), info.ips);
    }

    @Test
    public void phonePayloadCleansNameAndSkipsBadId() {
        PairingQr.Info info = PairingQr.parse(PairingQr.buildPhonePayload("a|b & c", "bad id", Arrays.asList("192.168.1.40")));
        assertNotNull(info);
        assertEquals("a-b & c", info.name);
        assertNull(info.id);
        assertEquals("Phone", PairingQr.parse(PairingQr.buildPhonePayload("  ", null, Arrays.asList("192.168.1.40"))).name);
    }

    @Test
    public void decodesUnicodeAndPlusInName() {
        PairingQr.Info info = PairingQr.parse("clipsync://pair?v=1&n=Caf%C3%A9+PC&ip=192.168.0.2");
        assertNotNull(info);
        assertEquals("Café PC", info.name);
    }

    @Test
    public void defaultsNameAndStripsSeparator() {
        assertEquals("Desktop", PairingQr.parse("clipsync://pair?ip=192.168.0.2").name);
        assertEquals("a-b", PairingQr.parse("clipsync://pair?n=a%7Cb&ip=192.168.0.2").name);
    }

    @Test
    public void dropsInvalidAndDuplicateIps() {
        PairingQr.Info info = PairingQr.parse("clipsync://pair?n=x&ip=999.1.1.1,abc,192.168.1.5,192.168.1.5,1.2.3");
        assertNotNull(info);
        assertEquals(Collections.singletonList("192.168.1.5"), info.ips);
    }

    @Test
    public void rejectsOtherCodes() {
        assertNull(PairingQr.parse(null));
        assertNull(PairingQr.parse(""));
        assertNull(PairingQr.parse("https://example.com/?ip=192.168.1.5"));
        assertNull(PairingQr.parse("clipsync://pair"));
        assertNull(PairingQr.parse("clipsync://pairing?ip=192.168.1.5"));
        assertNull(PairingQr.parse("clipsync://pair?n=PC"));
        assertNull(PairingQr.parse("clipsync://pair?n=%ZZ&ip=bad"));
    }

    @Test
    public void malformedNameStillPairs() {
        PairingQr.Info info = PairingQr.parse("clipsync://pair?n=%ZZ&ip=192.168.1.5");
        assertNotNull(info);
        assertEquals("Desktop", info.name);
    }

    @Test
    public void prefersSameSubnet() {
        List<String> desktop = Arrays.asList("172.20.0.1", "10.0.0.5", "192.168.1.23");
        List<String> local = Arrays.asList("100.80.1.2", "192.168.1.40");
        assertEquals(Arrays.asList("192.168.1.23", "172.20.0.1", "10.0.0.5"),
                PairingQr.prioritizeSameSubnet(desktop, local));
    }

    @Test
    public void validatesIpv4() {
        assertTrue(PairingQr.isValidIpv4("0.0.0.0"));
        assertTrue(PairingQr.isValidIpv4("255.255.255.255"));
        assertFalse(PairingQr.isValidIpv4("256.1.1.1"));
        assertFalse(PairingQr.isValidIpv4("1.1.1."));
        assertFalse(PairingQr.isValidIpv4("1.1.1.1.1"));
        assertFalse(PairingQr.isValidIpv4("-1.1.1.1"));
    }
}
