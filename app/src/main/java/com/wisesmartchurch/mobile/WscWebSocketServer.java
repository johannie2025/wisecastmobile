package com.wisesmartchurch.mobile;

// Developed by Prophète Josias & Wise Design (WhatsApp: +240555445514)

import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Serveur combiné NanoHTTPD (HTTP média) + WebSocket (commandes vers TV Box).
 *
 * HTTP  port 9001 → sert les fichiers média enregistrés via serveMedia()
 * WS    port 9000 → diffuse les messages JSON aux TV Box connectées
 *
 * Le WebSocket est implémenté en pur Java (RFC 6455) pour éviter
 * les dépendances lourdes.
 */
public class WscWebSocketServer {

    private static final String TAG = "WscWsServer";

    // ── HTTP (NanoHTTPD) ──────────────────────────────────────────────
    private MediaHttpServer httpServer;
    private final int httpPort;

    // ── WebSocket (pur Java) ──────────────────────────────────────────
    private ServerSocket wssSocket;
    private final int wsPort;
    private final Set<WsConn> clients = Collections.synchronizedSet(new HashSet<>());
    private Thread acceptThread;
    private volatile boolean running = false;

    // ── Médias servis ─────────────────────────────────────────────────
    private final Map<String, File>   mediaFiles   = new ConcurrentHashMap<>();
    private final Map<String, String> mediaMimes   = new ConcurrentHashMap<>();
    private String localIp = "127.0.0.1";

    // ── Listener ─────────────────────────────────────────────────────
    public interface Listener {
        void onClientConnected(int count);
        void onClientDisconnected(int count);
        void onMessage(String json);
    }

    private Listener listener;

    public WscWebSocketServer(int wsPort, int httpPort) {
        this.wsPort   = wsPort;
        this.httpPort = httpPort;
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setLocalIp(String ip)   { this.localIp  = ip; }

    // ═══════════════════════════════════════════════ Start / Stop

    public void start() throws IOException {
        running = true;

        // 1. Démarrer le serveur HTTP média (NanoHTTPD)
        httpServer = new MediaHttpServer(httpPort);
        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "HTTP média démarré sur port " + httpPort);

        // 2. Démarrer le serveur WebSocket
        wssSocket = new ServerSocket(wsPort);
        wssSocket.setReuseAddress(true);
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket s = wssSocket.accept();
                    new Thread(new WsConn(s)).start();
                } catch (Exception e) {
                    if (running) Log.w(TAG, "accept: " + e.getMessage());
                }
            }
        }, "WscAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "WebSocket démarré sur port " + wsPort);
    }

    public void stop() {
        running = false;
        try { if (wssSocket != null) wssSocket.close(); } catch (Exception e) {}
        synchronized (clients) {
            for (WsConn c : clients) c.close();
            clients.clear();
        }
        if (httpServer != null) httpServer.stop();
        Log.i(TAG, "Serveur arrêté");
    }

    // ═══════════════════════════════════════════════ API publique

    /**
     * Enregistre un fichier à servir via HTTP.
     * @return URL complète "http://<ip>:<port>/media/<token>"
     */
    public String serveMedia(File file, String mimeType) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        mediaFiles.put(token, file);
        mediaMimes.put(token, mimeType);
        return "http://" + localIp + ":" + httpPort + "/media/" + token;
    }

    /** Broadcast un JSON à tous les TV Boxes connectés */
    public void broadcast(String json) {
        byte[] frame = encodeTextFrame(json);
        synchronized (clients) {
            Iterator<WsConn> it = clients.iterator();
            while (it.hasNext()) {
                WsConn c = it.next();
                if (!c.send(frame)) { it.remove(); }
            }
        }
    }

    public int getClientCount() { return clients.size(); }

    // ═══════════════════════════════════════════════ WebSocket RFC 6455

    private class WsConn implements Runnable {
        private final Socket socket;
        private OutputStream out;
        private volatile boolean open = true;

        WsConn(Socket s) { this.socket = s; }

        @Override
        public void run() {
            try {
                InputStream  in  = socket.getInputStream();
                out = socket.getOutputStream();

                // 1. Handshake HTTP
                if (!doHandshake(in, out)) { socket.close(); return; }

                clients.add(this);
                Log.i(TAG, "WS connecté: " + socket.getInetAddress().getHostAddress()
                        + " — total: " + clients.size());
                if (listener != null) listener.onClientConnected(clients.size());

                // 2. Lire les frames
                while (open) {
                    String msg = readFrame(in);
                    if (msg == null) break;
                    if (listener != null) listener.onMessage(msg);
                }
            } catch (Exception e) {
                if (open) Log.w(TAG, "WsConn: " + e.getMessage());
            } finally {
                close();
                clients.remove(this);
                Log.i(TAG, "WS déconnecté — reste: " + clients.size());
                if (listener != null) listener.onClientDisconnected(clients.size());
            }
        }

        boolean send(byte[] frame) {
            try { out.write(frame); out.flush(); return true; }
            catch (Exception e) { return false; }
        }

        void close() {
            open = false;
            try { socket.close(); } catch (Exception e) {}
        }
    }

    // ── Handshake WebSocket ──────────────────────────────────────────
    private boolean doHandshake(InputStream in, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        // Lire jusqu'à \r\n\r\n
        char[] last4 = new char[4];
        while ((c = in.read()) != -1) {
            char ch = (char) c;
            sb.append(ch);
            System.arraycopy(last4, 1, last4, 0, 3);
            last4[3] = ch;
            if (last4[0] == '\r' && last4[1] == '\n' && last4[2] == '\r' && last4[3] == '\n') break;
        }
        String request = sb.toString();
        String key = null;
        for (String line : request.split("\r\n")) {
            if (line.startsWith("Sec-WebSocket-Key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
                break;
            }
        }
        if (key == null) return false;

        String combined = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        byte[] sha1;
        try {
            sha1 = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(combined.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            return false; // SHA-1 toujours disponible sur Android
        }
        String accept = android.util.Base64.encodeToString(sha1, android.util.Base64.NO_WRAP);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    // ── Décodage frame WebSocket ──────────────────────────────────────
    private String readFrame(InputStream in) throws IOException {
        int b0 = in.read(); if (b0 < 0) return null;
        int b1 = in.read(); if (b1 < 0) return null;
        int opcode = b0 & 0x0F;
        if (opcode == 8) return null; // close
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        byte[] mask = new byte[4];
        if (masked) { for (int i = 0; i < 4; i++) mask[i] = (byte) in.read(); }
        byte[] payload = new byte[(int) len];
        int read = 0;
        while (read < len) {
            int r = in.read(payload, read, (int) len - read);
            if (r < 0) return null;
            read += r;
        }
        if (masked) { for (int i = 0; i < len; i++) payload[i] ^= mask[i % 4]; }
        if (opcode == 1) return new String(payload, StandardCharsets.UTF_8);
        return null; // ignorer les frames binaires
    }

    // ── Encodage frame WebSocket (serveur → client, sans masque) ──────
    private byte[] encodeTextFrame(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;
        byte[] frame;
        if (len < 126) {
            frame = new byte[2 + len];
            frame[0] = (byte) 0x81;
            frame[1] = (byte) len;
            System.arraycopy(payload, 0, frame, 2, len);
        } else if (len < 65536) {
            frame = new byte[4 + len];
            frame[0] = (byte) 0x81;
            frame[1] = 126;
            frame[2] = (byte) (len >> 8);
            frame[3] = (byte) (len & 0xFF);
            System.arraycopy(payload, 0, frame, 4, len);
        } else {
            frame = new byte[10 + len];
            frame[0] = (byte) 0x81;
            frame[1] = 127;
            for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((len >> (56 - 8 * i)) & 0xFF);
            System.arraycopy(payload, 0, frame, 10, len);
        }
        return frame;
    }

    // ═══════════════════════════════════════════════ NanoHTTPD pour les médias

    private class MediaHttpServer extends NanoHTTPD {
        MediaHttpServer(int port) { super(port); }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri(); // "/media/<token>"
            if (uri.startsWith("/media/")) {
                String token = uri.substring(7);
                File file = mediaFiles.get(token);
                String mime = mediaMimes.getOrDefault(token, "application/octet-stream");
                if (file != null && file.exists()) {
                    try {
                        FileInputStream fis = new FileInputStream(file);
                        return newChunkedResponse(Response.Status.OK, mime, fis);
                    } catch (FileNotFoundException e) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND,
                                MIME_PLAINTEXT, "Not Found");
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
        }
    }
}
