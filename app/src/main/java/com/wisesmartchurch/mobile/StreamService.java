package com.wisesmartchurch.mobile;

// Developed by Prophète Josias & Wise Design (WhatsApp: +240555445514)

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;

import java.io.File;

/**
 * Foreground Service — maintient le serveur WebSocket + HTTP actif
 * en arrière-plan, même écran verrouillé.
 *
 * Interface IBinder exposé pour que MainActivity communique directement
 * sans passer par des Intents intermédiaires.
 */
public class StreamService extends Service {

    private static final String TAG      = "StreamService";
    public  static final String CHANNEL  = "wsc_stream";
    public  static final int    NOTIF_ID = 1001;

    // Actions Intent
    public static final String ACTION_START = "wsc.START";
    public static final String ACTION_STOP  = "wsc.STOP";
    public static final String ACTION_SEND  = "wsc.SEND";
    public static final String EXTRA_JSON   = "json";

    // ── Serveur ──────────────────────────────────────────────────────
    private WscWebSocketServer server;
    private WscUdpAnnounce     udpAnnounce;
    private static final int   WS_PORT   = 9000;
    private static final int   HTTP_PORT = 9001;
    private static final int   UDP_PORT  = 9002;

    // ── Verrous ──────────────────────────────────────────────────────
    private PowerManager.WakeLock        wakeLock;
    private WifiManager.WifiLock         wifiLock;
    private WifiManager.MulticastLock    multicastLock;

    // ── Binder ───────────────────────────────────────────────────────
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        StreamService getService() { return StreamService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ── Listener vers MainActivity ────────────────────────────────────
    private WscWebSocketServer.Listener externalListener;
    public void setListener(WscWebSocketServer.Listener l) {
        externalListener = l;
        if (server != null) server.setListener(buildListener());
    }

    // ═══════════════════════════════════════════════ Lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createNotifChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_START:
                startServer();
                break;
            case ACTION_STOP:
                stopSelf();
                break;
            case ACTION_SEND:
                String json = intent.getStringExtra(EXTRA_JSON);
                if (json != null && server != null) server.broadcast(json);
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        releaseLocks();
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════ Serveur

    private void startServer() {
        if (server != null) return; // déjà démarré
        try {
            String ip = new WscUdpAnnounce(this, WS_PORT, UDP_PORT).getLocalIp();

            server = new WscWebSocketServer(WS_PORT, HTTP_PORT);
            server.setLocalIp(ip);
            server.setListener(buildListener());
            server.start();

            udpAnnounce = new WscUdpAnnounce(this, WS_PORT, UDP_PORT);
            udpAnnounce.start();

            startForeground(NOTIF_ID, buildNotification("🟢 En diffusion — " + ip), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            Log.i(TAG, "Service démarré — IP: " + ip);
        } catch (Exception e) {
            Log.e(TAG, "Impossible de démarrer le serveur", e);
            stopSelf();
        }
    }

    private void stopServer() {
        if (server      != null) { server.stop();          server      = null; }
        if (udpAnnounce != null) { udpAnnounce.stopAnnounce(); udpAnnounce = null; }
    }

    private WscWebSocketServer.Listener buildListener() {
        return new WscWebSocketServer.Listener() {
            @Override public void onClientConnected(int count) {
                updateNotif("🟢 " + count + " écran(s) connecté(s)");
                if (externalListener != null) externalListener.onClientConnected(count);
            }
            @Override public void onClientDisconnected(int count) {
                updateNotif(count > 0 ? "🟡 " + count + " écran(s) connecté(s)" : "⏳ En attente d'écrans…");
                if (externalListener != null) externalListener.onClientDisconnected(count);
            }
            @Override public void onMessage(String json) {
                if (externalListener != null) externalListener.onMessage(json);
            }
        };
    }

    // ═══════════════════════════════════════════════ API publique (via Binder)

    public String serveFile(File file, String mimeType) {
        if (server == null) return null;
        return server.serveMedia(file, mimeType);
    }

    public void broadcast(String json) {
        if (server != null) server.broadcast(json);
    }

    public int getClientCount() {
        return server != null ? server.getClientCount() : 0;
    }

    public boolean isRunning() { return server != null; }

    // ═══════════════════════════════════════════════ Notification

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "WiseCast Streaming",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Service de projection WiseCast");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, StreamService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("WiseCast Mobile")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "Arrêter", stopPi)
                .build();
    }

    private void updateNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ═══════════════════════════════════════════════ Verrous

    private void acquireLocks() {
        // WakeLock partiel : garde le CPU actif, pas l'écran
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiseCast:stream");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        // WifiLock haute performance : maintient le Wi-Fi actif
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WiseCast:wifi");
            wifiLock.acquire();

            multicastLock = wm.createMulticastLock("WiseCast:mcast");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
    }

    private void releaseLocks() {
        try { if (wakeLock      != null && wakeLock.isHeld())      wakeLock.release();      } catch (Exception e) {}
        try { if (wifiLock      != null && wifiLock.isHeld())      wifiLock.release();      } catch (Exception e) {}
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception e) {}
    }
}
