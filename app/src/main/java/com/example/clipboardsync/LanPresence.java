package com.example.clipboardsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps this phone and its saved desktops in touch when IP addresses change, with no user action.
 *
 *  - Announces this phone on UDP port 8889 every 3s while the app is open (every 30s in the background),
 *    and immediately whenever Wi-Fi connects or the phone's address changes.
 *  - Listens for desktop announcements and reports them, so saved desktop IPs can be updated.
 *
 * One instance per app process (like the TCP receiver on port 8888).
 */
final class LanPresence {

    static final int PORT = 8889;
    private static final long FOREGROUND_INTERVAL_MS = 3000;
    private static final long BACKGROUND_INTERVAL_MS = 30000;

    interface Listener {
        /** A desktop announced itself (called on a background thread). */
        void onDesktopSeen(String id, String name, String ip);

        /** This phone's own IP addresses changed (called on a background thread). */
        void onLocalAddressesChanged();

        /** Saved desktop IPs: announcements are also sent straight to them in case broadcasts are filtered. */
        List<String> knownDesktopIps();
    }

    private static LanPresence instance;

    private final Context appContext;
    private final String myId;
    private final String myName;
    private final Listener listener;

    private volatile DatagramSocket socket;
    private volatile boolean foreground = false;
    private WifiManager.MulticastLock multicastLock;

    private final Object wake = new Object();
    private boolean wakeRequested = false; // Guarded by wake

    private LanPresence(Context appContext, String myId, String myName, Listener listener) {
        this.appContext = appContext;
        this.myId = myId;
        this.myName = myName;
        this.listener = listener;
    }

    static synchronized void start(Context context, String myId, String myName, Listener listener) {
        if (instance != null) return;
        instance = new LanPresence(context.getApplicationContext(), myId, myName, listener);
        instance.startInternal();
    }

    /** Announce right away (e.g. after pairing, or the network changed). */
    static void poke() {
        LanPresence p = instance;
        if (p != null) p.wakeUp();
    }

    /** Announce often while the app is on screen, rarely in the background (saves battery). */
    static void setForeground(boolean isForeground) {
        LanPresence p = instance;
        if (p == null) return;
        p.foreground = isForeground;
        p.updateMulticastLock(isForeground);
        if (isForeground) p.wakeUp();
    }

    private void startInternal() {
        registerNetworkCallback();
        Thread t = new Thread(this::run, "lan-presence");
        t.setDaemon(true);
        t.start();
    }

    private void wakeUp() {
        synchronized (wake) {
            wakeRequested = true;
            wake.notifyAll();
        }
    }

    // Some phones drop broadcast packets unless this is held; only held while the app is on screen
    private synchronized void updateMulticastLock(boolean hold) {
        try {
            if (multicastLock == null) {
                WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) return;
                multicastLock = wifi.createMulticastLock("ClipboardSync:presence");
                multicastLock.setReferenceCounted(false);
            }
            if (hold) multicastLock.acquire();
            else if (multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) { }
    }

    // Instant reaction to Wi-Fi connecting / dropping / getting a new address
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = appContext.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Also Wi-Fi without internet
                    .build();
            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { wakeUp(); }
                @Override public void onLost(Network network) { wakeUp(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) { wakeUp(); }
            });
        } catch (Exception ignored) {
            // Not fatal: the periodic check below still notices address changes
        }
    }

    private void run() {
        openSocket();
        List<String> lastIps = localIpv4Addresses();
        while (true) {
            try {
                List<String> ips = localIpv4Addresses();
                if (!ips.equals(lastIps)) {
                    lastIps = ips;
                    listener.onLocalAddressesChanged();
                }
                announce();
                synchronized (wake) {
                    if (!wakeRequested) wake.wait(foreground ? FOREGROUND_INTERVAL_MS : BACKGROUND_INTERVAL_MS);
                    wakeRequested = false;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // Never let a network hiccup stop auto-reconnect
                try { Thread.sleep(FOREGROUND_INTERVAL_MS); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void openSocket() {
        try {
            DatagramSocket s = new DatagramSocket(PORT);
            s.setBroadcast(true);
            socket = s;
            Thread rx = new Thread(this::receiveLoop, "lan-presence-rx");
            rx.setDaemon(true);
            rx.start();
        } catch (IOException e) {
            // Port busy: we can still announce ourselves so desktops find us
            try {
                DatagramSocket s = new DatagramSocket();
                s.setBroadcast(true);
                socket = s;
            } catch (IOException ignored) { }
        }
    }

    private void announce() {
        DatagramSocket s = socket;
        if (s == null) return;
        byte[] data = PresenceMessage.encode(PresenceMessage.ROLE_PHONE, myId, myName).getBytes(StandardCharsets.UTF_8);

        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                        if (ia.getBroadcast() != null) targets.add(ia.getBroadcast()); // e.g. 192.168.1.255
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (String ip : listener.knownDesktopIps()) {
                targets.add(InetAddress.getByName(ip)); // IP literals: no DNS lookup
            }
        } catch (Exception ignored) { }

        for (InetAddress target : targets) {
            try {
                s.send(new DatagramPacket(data, data.length, target, PORT));
            } catch (Exception ignored) { } // No route on that network right now
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[512];
        while (true) {
            DatagramSocket s = socket;
            if (s == null || s.isClosed()) return;
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                s.receive(packet);
            } catch (IOException e) {
                continue;
            }
            try {
                if (!(packet.getAddress() instanceof Inet4Address)) continue;
                PresenceMessage msg = PresenceMessage.parse(
                        new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8));
                if (msg == null || msg.role != PresenceMessage.ROLE_DESKTOP || msg.id.equals(myId)) continue;
                listener.onDesktopSeen(msg.id, msg.name, packet.getAddress().getHostAddress());
            } catch (Exception ignored) { }
        }
    }

    static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) result.add(addr.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return result;
    }
}
