package com.wisesmartchurch.mobile;

// Developed by Prophète Josias & Wise Design (WhatsApp: +240555445514)

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * WiseCast Mobile — Émetteur
 * Interface principale : sélection de média + commandes de projection
 */
public class MainActivity extends AppCompatActivity implements WscWebSocketServer.Listener {

    private static final String TAG     = "WscMobile";
    private static final int    REQ_PIC = 10;
    private static final int    REQ_VID = 11;
    private static final int    REQ_AUD = 12;
    private static final int    REQ_PERM = 20;

    // ── UI ────────────────────────────────────────────────────────────
    private TextView   tvStatus, tvIp, tvClients, tvMediaName;
    private Button     btnStart, btnStop, btnBlack, btnClear;
    private Button     btnPickImage, btnPickVideo, btnPickAudio;
    private View       dotStatus;
    private LinearLayout cardMedia;

    // ── Service ───────────────────────────────────────────────────────
    private StreamService streamService;
    private boolean       bound = false;
    private final Handler ui    = new Handler(Looper.getMainLooper());

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            streamService = ((StreamService.LocalBinder) binder).getService();
            streamService.setListener(MainActivity.this);
            bound = true;
            refreshUi();
            Log.i(TAG, "Service lié");
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            streamService = null;
        }
    };

    // ── Média courant ─────────────────────────────────────────────────
    private String currentMediaUrl  = null;
    private String currentMediaMime = null;
    private String currentMediaName = null;

    // ═══════════════════════════════════════════════ onCreate

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupButtons();
        checkPermissions();
    }

    private void bindViews() {
        tvStatus    = findViewById(R.id.tv_status);
        tvIp        = findViewById(R.id.tv_ip);
        tvClients   = findViewById(R.id.tv_clients);
        tvMediaName = findViewById(R.id.tv_media_name);
        dotStatus   = findViewById(R.id.dot_status);
        cardMedia   = findViewById(R.id.card_media);
        btnStart    = findViewById(R.id.btn_start);
        btnStop     = findViewById(R.id.btn_stop);
        btnBlack    = findViewById(R.id.btn_black);
        btnClear    = findViewById(R.id.btn_clear);
        btnPickImage = findViewById(R.id.btn_pick_image);
        btnPickVideo = findViewById(R.id.btn_pick_video);
        btnPickAudio = findViewById(R.id.btn_pick_audio);
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v  -> stopStreaming());

        btnPickImage.setOnClickListener(v -> pickMedia("image/*",   REQ_PIC));
        btnPickVideo.setOnClickListener(v -> pickMedia("video/*",   REQ_VID));
        btnPickAudio.setOnClickListener(v -> pickMedia("audio/*",   REQ_AUD));

        btnBlack.setOnClickListener(v -> sendJson("{\"type\":\"clear\"}"));
        btnClear.setOnClickListener(v -> sendJson("{\"type\":\"clear\"}"));
    }

    // ═══════════════════════════════════════════════ Service

    private void startStreaming() {
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        bindService(new Intent(this, StreamService.class), conn, BIND_AUTO_CREATE);
    }

    private void stopStreaming() {
        if (bound) {
            unbindService(conn);
            bound = false;
            streamService = null;
        }
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_STOP);
        startService(intent);
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Si le service tourne déjà, se rebinder
        Intent intent = new Intent(this, StreamService.class);
        bindService(intent, conn, 0); // 0 = ne pas créer si absent
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) { unbindService(conn); bound = false; }
    }

    // ═══════════════════════════════════════════════ WscWebSocketServer.Listener

    @Override
    public void onClientConnected(int count) {
        ui.post(() -> {
            tvClients.setText(count + " écran(s) connecté(s)");
            dotStatus.setBackgroundResource(R.drawable.dot_green);
            // Si un média est déjà sélectionné, le pousser sur les nouveaux écrans
            if (currentMediaUrl != null) pushMedia();
        });
    }

    @Override
    public void onClientDisconnected(int count) {
        ui.post(() -> {
            tvClients.setText(count > 0 ? count + " écran(s) connecté(s)" : "En attente d'écrans…");
            if (count == 0) dotStatus.setBackgroundResource(R.drawable.dot_orange);
        });
    }

    @Override
    public void onMessage(String json) {
        Log.d(TAG, "Message reçu: " + json);
    }

    // ═══════════════════════════════════════════════ Sélection de médias

    private void pickMedia(String mime, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Choisir un fichier"), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String mime;
        switch (requestCode) {
            case REQ_PIC: mime = "image/*";  break;
            case REQ_VID: mime = "video/*";  break;
            case REQ_AUD: mime = "audio/*";  break;
            default: return;
        }
        // Résoudre le MIME réel
        String realMime = getContentResolver().getType(uri);
        if (realMime != null) mime = realMime;

        handleMediaPicked(uri, mime);
    }

    private void handleMediaPicked(Uri uri, String mime) {
        new Thread(() -> {
            try {
                // Copier dans le cache interne (NanoHTTPD a besoin d'un File)
                String fileName = resolveFileName(uri);
                File dest = new File(getCacheDir(), "wsc_" + System.currentTimeMillis()
                        + "_" + fileName);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream os = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                }

                // Enregistrer dans le serveur HTTP
                String url = (streamService != null)
                        ? streamService.serveFile(dest, mime)
                        : null;

                if (url == null) {
                    ui.post(() -> Toast.makeText(this,
                            "⚠ Démarrez la diffusion d'abord", Toast.LENGTH_SHORT).show());
                    return;
                }

                currentMediaUrl  = url;
                currentMediaMime = mime;
                currentMediaName = fileName;

                ui.post(() -> {
                    tvMediaName.setText("📎 " + fileName);
                    cardMedia.setVisibility(View.VISIBLE);
                    pushMedia();
                });
            } catch (Exception e) {
                Log.e(TAG, "handleMediaPicked", e);
                ui.post(() -> Toast.makeText(this, "Erreur: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "WscMediaLoad").start();
    }

    /** Envoie l'URL de streaming aux TV Box */
    private void pushMedia() {
        if (currentMediaUrl == null || streamService == null) return;
        try {
            JSONObject p = new JSONObject();
            if (currentMediaMime.startsWith("image")) {
                p.put("type",  "media");
                p.put("kind",  "image");
                p.put("url",   currentMediaUrl);
            } else if (currentMediaMime.startsWith("video")) {
                p.put("type",  "media");
                p.put("kind",  "video");
                p.put("url",   currentMediaUrl);
            } else if (currentMediaMime.startsWith("audio")) {
                p.put("type",  "media");
                p.put("kind",  "audio");
                p.put("url",   currentMediaUrl);
            }
            sendJson(p.toString());
        } catch (Exception e) { Log.e(TAG, "pushMedia", e); }
    }

    private void sendJson(String json) {
        if (streamService != null) {
            streamService.broadcast(json);
        } else {
            // Fallback via Intent si service non lié
            Intent intent = new Intent(this, StreamService.class);
            intent.setAction(StreamService.ACTION_SEND);
            intent.putExtra(StreamService.EXTRA_JSON, json);
            startService(intent);
        }
    }

    // ═══════════════════════════════════════════════ Helpers

    private String resolveFileName(Uri uri) {
        String name = "media";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception e) {}
        return name;
    }

    private void refreshUi() {
        boolean running = bound && streamService != null && streamService.isRunning();
        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        btnPickImage.setEnabled(running);
        btnPickVideo.setEnabled(running);
        btnPickAudio.setEnabled(running);
        if (!running) {
            tvStatus.setText("Service arrêté");
            tvClients.setText("—");
            dotStatus.setBackgroundResource(R.drawable.dot_grey);
        } else {
            tvStatus.setText("En diffusion");
        }
    }

    // ═══════════════════════════════════════════════ Permissions

    private void checkPermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            perms = new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE };
        }
        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false; break;
            }
        }
        if (!allGranted) ActivityCompat.requestPermissions(this, perms, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        // Continuer même si refusé — l'utilisateur pourra accorder plus tard
    }
}
