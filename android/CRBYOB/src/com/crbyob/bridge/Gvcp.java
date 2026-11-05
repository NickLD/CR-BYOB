package com.crbyob.bridge;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** GigE-Vision GVCP discovery responder on UDP 3956, plus the 1 s keepalive ACKs. */
public final class Gvcp {
    public static final int PORT = 3956;
    private static final int KEY = 0x42, CMD_DISCOVERY = 2, ACK_DISCOVERY = 3, PAYLOAD_LEN = 0xF8;

    public static final class Cfg {
        public byte[] mac = new byte[6];
        public String mask = "255.255.255.0", gateway = "0.0.0.0";
        public String manufacturer = "Orbbec", model = "Scan Bridge", version = "1.3.1", mfgInfo = "",
                sn = "CRBYOB001", userName = "";
        public int pid = 0x0C01;
        public double keepalive = 1.0;
        public java.util.function.Supplier<String> advertise = () -> "all";
    }

    /** Interface kind: "p2p" (Wi-Fi Direct), "wifi" (wlan*), "eth" (wired eth*, usb*, rndis*), "other". */
    static String kind(String ifname) {
        if (ifname == null) return "other";
        if (ifname.startsWith("p2p")) return "p2p";
        if (ifname.startsWith("wlan")) return "wifi";
        if (ifname.startsWith("eth") || ifname.startsWith("usb") || ifname.startsWith("rndis")) return "eth";
        return "other";
    }

    private String policy() {
        try {
            String a = cfg.advertise.get();
            return a == null ? "all" : a;
        } catch (Exception e) {
            return "all";
        }
    }

    /** "advertise" policy: all | p2p | wifi | eth, and the legacy "lan" = wifi + eth ("local" is handled in choose). */
    static boolean allowedKind(String policy, String kind) {
        switch (policy) {
            case "p2p": return kind.equals("p2p");
            case "wifi": return kind.equals("wifi");
            case "eth": return kind.equals("eth");
            case "lan": return kind.equals("wifi") || kind.equals("eth");
            default: return true;
        }
    }

    private static int rank(String kind) {
        switch (kind) { case "p2p": return 0; case "eth": return 1; case "wifi": return 2; default: return 3; }
    }

    /**
     * The interface to answer a peer on, or null if the policy forbids every candidate. When several
     * local interfaces share the peer's subnet (USB-C Ethernet and Wi-Fi on the same LAN), the policy
     * picks the one to advertise instead of whichever enumerates first. Returns {name, broadcast, ip, kind}.
     */
    Object[] choose(InetAddress peer) {
        String pol = policy();
        if ("local".equals(pol)) {
            // This phone only: answer apps on this phone (the request comes from one of our own addresses) and
            // ignore everyone else. The app's SDK sends discovery out of every interface (lo, wlan, eth, p2p,
            // rmnet), so every reply advertises the same 127.0.0.1: one MAC at several IPs would make the SDK
            // hang at Preview (as for a PC on two networks). Loopback doesn't depend on any particular network.
            if (!Bridge.isLocalAddress(peer)) return null;
            String name = "lo";
            try {
                java.net.NetworkInterface ni = java.net.NetworkInterface.getByInetAddress(peer);
                if (ni != null) name = ni.getName();
            } catch (Exception ignored) {
            }
            return new Object[]{name, null, "127.0.0.1", "local"};
        }
        Object[] best = null;
        for (Object[] c : ifacesFor(peer)) {
            String k = kind((String) c[0]);
            if (!allowedKind(pol, k)) continue;
            if (best == null || rank(k) < rank((String) best[3])) best = new Object[]{c[0], c[1], c[2], k};
        }
        return best;
    }

    private final Cfg cfg;
    private DatagramSocket sock;
    private volatile boolean running;
    private static final int MAX_CLIENTS = 32;
    private final Map<SocketAddress, Object[]> clients = new HashMap<>();   // peer -> {Long seen, String ip}
    public volatile int acks;

    public Gvcp(Cfg cfg) {
        this.cfg = cfg;
    }

    static byte[] ip4(String s) {
        try {
            return InetAddress.getByName(s).getAddress();
        } catch (Exception e) {
            return new byte[4];
        }
    }

    /** 248-byte DISCOVERY_ACK payload, offsets as parsed by GVCPClient::sendGVCPDiscovery (big-endian). */
    byte[] payload(String ip) {
        byte[] p = new byte[PAYLOAD_LEN];
        Hp.putBe16(p, 0x00, 1);
        Hp.putBe16(p, 0x02, 2);                       // spec version 1.2
        Hp.putBe32(p, 0x04, 0x80000000);              // device mode: big-endian regs
        System.arraycopy(cfg.mac, 0, p, 0x0A, 6);     // MAC (hi16 @0x0A, lo32 @0x0C)
        Hp.putBe32(p, 0x10, 0x00000007);              // IP cfg options: LLA|DHCP|persistent
        Hp.putBe32(p, 0x14, 0x00000006);              // IP cfg current:  LLA|DHCP
        System.arraycopy(ip4(ip), 0, p, 0x24, 4);
        System.arraycopy(ip4(cfg.mask), 0, p, 0x34, 4);
        System.arraycopy(ip4(cfg.gateway), 0, p, 0x44, 4);
        System.arraycopy(Hp.fixedStr(cfg.manufacturer, 32), 0, p, 0x48, 32);
        System.arraycopy(Hp.fixedStr(cfg.model, 28), 0, p, 0x68, 28);
        Hp.putBe32(p, 0x84, cfg.pid);                 // Orbbec PID (big-endian u32)
        System.arraycopy(Hp.fixedStr(cfg.version, 32), 0, p, 0x88, 32);
        System.arraycopy(Hp.fixedStr(cfg.mfgInfo, 48), 0, p, 0xA8, 48);
        System.arraycopy(Hp.fixedStr(cfg.sn, 16), 0, p, 0xD8, 16);
        System.arraycopy(Hp.fixedStr(cfg.userName, 16), 0, p, 0xE8, 16);
        return p;
    }

    byte[] packet(String ip, int reqId) {
        byte[] pl = payload(ip);
        byte[] pkt = new byte[8 + pl.length];
        Hp.putBe16(pkt, 0, 0);
        Hp.putBe16(pkt, 2, ACK_DISCOVERY);
        Hp.putBe16(pkt, 4, pl.length);
        Hp.putBe16(pkt, 6, reqId);
        System.arraycopy(pl, 0, pkt, 8, pl.length);
        return pkt;
    }

    /** {interface name, subnet broadcast} of the local IPv4 interface whose subnet contains peer, or null. */
    static Object[] ifaceFor(InetAddress peer) {
        try {
            byte[] pa = peer.getAddress();
            if (pa.length != 4) return null;
            int p = Hp.be16(pa, 0) << 16 | Hp.be16(pa, 2);
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    int len = ia.getNetworkPrefixLength();
                    if (len <= 0 || len > 32) continue;
                    byte[] aa = a.getAddress();
                    int ai = Hp.be16(aa, 0) << 16 | Hp.be16(aa, 2);
                    int mask = len == 32 ? -1 : ~((1 << (32 - len)) - 1);
                    if ((ai & mask) == (p & mask)) return new Object[]{ni.getName(), ia.getBroadcast(), a.getHostAddress()};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** All {name, broadcast, ip} of local IPv4 interfaces whose subnet contains peer. */
    static List<Object[]> ifacesFor(InetAddress peer) {
        List<Object[]> out = new ArrayList<>();
        try {
            byte[] pa = peer.getAddress();
            if (pa.length != 4) return out;
            int p = Hp.be16(pa, 0) << 16 | Hp.be16(pa, 2);
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    int len = ia.getNetworkPrefixLength();
                    if (len <= 0 || len > 32) continue;
                    byte[] aa = a.getAddress();
                    int ai = Hp.be16(aa, 0) << 16 | Hp.be16(aa, 2);
                    int mask = len == 32 ? -1 : ~((1 << (32 - len)) - 1);
                    if ((ai & mask) == (p & mask)) out.add(new Object[]{ni.getName(), ia.getBroadcast(), a.getHostAddress()});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Which local IP the OS would use to reach peer. */
    static String localIpTowards(InetAddress peer) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(peer, 9);
            InetAddress a = s.getLocalAddress();
            return a == null ? "0.0.0.0" : a.getHostAddress();
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }

    public void start() throws Exception {
        sock = new DatagramSocket(null);
        sock.setReuseAddress(true);
        sock.setBroadcast(true);
        sock.bind(new InetSocketAddress(PORT));
        running = true;
        StringBuilder mac = new StringBuilder();
        for (int i = 0; i < 6; i++) mac.append(i > 0 ? ":" : "").append(String.format("%02x", cfg.mac[i]));
        BLog.log("gvcp_listen", "addr", "0.0.0.0:" + PORT, "pid", "0x" + Integer.toHexString(cfg.pid), "mac", mac.toString(), "sn", cfg.sn);
        Thread t = new Thread(this::serve, "gvcp");
        t.start();
        if (cfg.keepalive > 0) {
            Thread k = new Thread(this::keepalive, "gvcp-keepalive");
            k.setDaemon(true);
            k.start();
        }
    }

    public void stop() {
        running = false;
        if (sock != null) sock.close();
    }

    private void serve() {
        byte[] buf = new byte[2048];
        while (running) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(dp);
            } catch (Exception e) {
                if (running) BLog.log("gvcp_error", "err", String.valueOf(e));
                break;
            }
            int n = dp.getLength();
            if (n < 8) continue;
            String peerTag = dp.getAddress().getHostAddress() + ":" + dp.getPort();
            int key = buf[0] & 0xFF, flags = buf[1] & 0xFF, cmd = Hp.be16(buf, 2), len = Hp.be16(buf, 4), reqId = Hp.be16(buf, 6);
            if (key != KEY) {
                BLog.log("gvcp_ignored", "peer", peerTag, "hex", Hp.hex(buf, 0, Math.min(n, 16)));
                continue;
            }
            BLog.log("gvcp_rx", "peer", peerTag, "flags", "0x" + Integer.toHexString(flags), "cmd", "0x" + Integer.toHexString(cmd),
                    "len", len, "req_id", reqId);
            if (cmd != CMD_DISCOVERY) continue;
            // advertise the IP of the interface facing the requester (192.168.49.1 on p2p, LAN IP on wlan0)
            SocketAddress peer = dp.getSocketAddress();
            Object[] ifc = choose(dp.getAddress());
            if (ifc == null) {           // not on an interface the "advertise" policy allows
                synchronized (clients) {
                    clients.remove(peer);
                }
                continue;
            }
            String ip = (String) ifc[2];
            synchronized (clients) {
                // bounded: keepalives go to every client for 30 s, and a UDP request's source address is spoofable
                if (clients.containsKey(peer) || clients.size() < MAX_CLIENTS)
                    clients.put(peer, new Object[]{System.currentTimeMillis(), ip, null});
            }
            byte[] pkt = packet(ip, reqId);
            String sent = peerTag;
            try {
                sock.send(new DatagramPacket(pkt, pkt.length, peer));
                acks++;
            } catch (Exception e) {
                BLog.log("gvcp_send_err", "err", String.valueOf(e));
            }
            // broadcast copy of the ACK, if the requester allows it; not in "This phone only" mode, where it would
            // announce the bridge to the whole network (the unicast reply reaches the app on this phone)
            if ((flags & 0x10) != 0 && !"local".equals(ifc[3])) {
                try {
                    sock.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName("255.255.255.255"), dp.getPort()));
                    sent += ",255.255.255.255:" + dp.getPort();
                } catch (Exception e) {
                    BLog.log("gvcp_bcast_err", "err", String.valueOf(e));
                }
                // 255.255.255.255 only leaves through the default interface; also hit the p2p subnet
                if (ifc[1] != null && "p2p".equals(ifc[3])) {
                    try {
                        sock.send(new DatagramPacket(pkt, pkt.length, (InetAddress) ifc[1], dp.getPort()));
                        sent += "," + ((InetAddress) ifc[1]).getHostAddress() + ":" + dp.getPort();
                    } catch (Exception e) {
                        BLog.log("gvcp_bcast_err", "err", String.valueOf(e));
                    }
                }
            }
            BLog.log("gvcp_ack", "to", sent, "advertised_ip", ip, "iface", ifc[0], "policy", policy(),
                    "pid", "0x" + Integer.toHexString(cfg.pid));
        }
    }

    /** Re-send the ACK (req_id 1) to recently seen clients every second; the SDK drops the device after one missed round. */
    private void keepalive() {
        while (running) {
            Otter.sleep((long) (cfg.keepalive * 1000));
            long now = System.currentTimeMillis();
            List<Object[]> targets = new ArrayList<>();
            synchronized (clients) {
                Iterator<Map.Entry<SocketAddress, Object[]>> it = clients.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<SocketAddress, Object[]> e = it.next();
                    if (now - (Long) e.getValue()[0] >= 30000) it.remove();
                    else targets.add(new Object[]{e.getKey()});
                }
            }
            for (Object[] t : targets) {
                InetSocketAddress peer = (InetSocketAddress) t[0];
                Object[] ifc = choose(peer.getAddress());      // same rule as the request path, re-evaluated
                if (ifc == null) continue;
                byte[] pkt = packet((String) ifc[2], 1);
                try {
                    sock.send(new DatagramPacket(pkt, pkt.length, peer));
                    if (ifc[1] != null && "p2p".equals(ifc[3]))   // p2p client: also the p2p subnet broadcast
                        sock.send(new DatagramPacket(pkt, pkt.length, (InetAddress) ifc[1], peer.getPort()));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** IPs of discovery clients seen in the last 30 s. */
    public java.util.List<String> recentPeers() {
        java.util.List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (clients) {
            for (Map.Entry<SocketAddress, Object[]> e : clients.entrySet()) {
                if (now - (Long) e.getValue()[0] >= 30000) continue;
                String ip = ((InetSocketAddress) e.getKey()).getAddress().getHostAddress();
                if (!out.contains(ip)) out.add(ip);
            }
        }
        return out;
    }

    public int clientCount() {
        synchronized (clients) {
            return clients.size();
        }
    }
}
