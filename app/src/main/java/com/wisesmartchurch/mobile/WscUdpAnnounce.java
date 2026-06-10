package com.wisesmartchurch.mobile;

// Developed by Prophète Josias & Wise Design (WhatsApp: +240555445514)

import android.content.Context;
import android.util.Log;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * Annonce périodiquement le serveur Mobile sur le réseau (port UDP 9002).
 * Protocole identique au récepteur WiseScreen :
 *   Mobile → broadcast UDP(9002) : "WSC_ANNOUNCE:<ip>:9000"
 *   Mobile → répond à "WSC_DISCOVER" par "WSC_ANNOUNCE:..."
 */
public class WscUdpAnnounce extends Thread {

    private static final String TAG      = "WscUdpAnnounce";
    private static final String ANNOUNCE = "WSC_ANNOUNCE:";
    private static final String DISCOVER = "WSC_DISCOVER";
    private static final long   INTERVAL = 8000;

    private final Context context;
    private final int     wsPort;
    private final int     udpPort;
    private DatagramSocket socket;
    private volatile boolean running = true;

    public WscUdpAnnounce(Context context, int wsPort, int udpPort) {
        this.context = context;
        this.wsPort  = wsPort;
        this.udpPort = udpPort;
        setDaemon(true);
        setName("WscUdpAnnounce");
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(udpPort);
            socket.setBroadcast(true);
            socket.setSoTimeout(2000);

            while (running) {
                String localIp = getLocalIp();
                String msg = ANNOUNCE + localIp + ":" + wsPort;
                broadcast(msg);

                long deadline = System.currentTimeMillis() + INTERVAL;
                while (running && System.currentTimeMillis() < deadline) {
                    try {
                        byte[] buf = new byte[256];
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        socket.receive(pkt);
                        String received = new String(pkt.getData(), 0, pkt.getLength(),
                                StandardCharsets.UTF_8).trim();
                        if (DISCOVER.equals(received)) {
                            Log.d(TAG, "DISCOVER reçu de " + pkt.getAddress().getHostAddress());
                            byte[] reply = msg.getBytes(StandardCharsets.UTF_8);
                            socket.send(new DatagramPacket(reply, reply.length,
                                    pkt.getAddress(), udpPort));
                        }
                    } catch (SocketTimeoutException e) {
                        // normal
                    }
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "UDP Announce error", e);
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
        }
    }

    private void broadcast(String msg) {
        new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                send(ds, data, "255.255.255.255");
                for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                     en != null && en.hasMoreElements(); ) {
                    for (InterfaceAddress ia : en.nextElement().getInterfaceAddresses()) {
                        InetAddress bcast = ia.getBroadcast();
                        if (bcast != null) send(ds, data, bcast.getHostAddress());
                    }
                }
                Log.d(TAG, "📡 Annonce: " + msg);
            } catch (Exception e) { Log.w(TAG, "broadcast: " + e.getMessage()); }
        }, "WscUdpBcast").start();
    }

    private void send(DatagramSocket ds, byte[] data, String address) {
        try { ds.send(new DatagramPacket(data, data.length,
                InetAddress.getByName(address), udpPort)); }
        catch (Exception e) { /* ignorer */ }
    }

    public String getLocalIp() {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) return (ip & 0xff) + "." + ((ip >> 8) & 0xff)
                        + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
            }
        } catch (Exception e) {}
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                 en != null && en.hasMoreElements(); ) {
                for (Enumeration<InetAddress> addrs = en.nextElement().getInetAddresses();
                     addrs.hasMoreElements(); ) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address)
                        return a.getHostAddress();
                }
            }
        } catch (Exception e) {}
        return "127.0.0.1";
    }

    public void stopAnnounce() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
    }
}
