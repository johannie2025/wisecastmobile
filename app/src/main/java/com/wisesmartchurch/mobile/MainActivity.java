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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * WiseCast Mobile — Émetteur
 * Interface principale : sélection de média + playlist + commandes de projection
 */
public class MainActivity extends AppCompatActivity implements WscWebSocketServer.Listener {

    private static final String TAG     = "WscMobile";
    private static final int    REQ_PIC = 10;
    private static final int    REQ_VID = 11;
    private static final int    REQ_AUD = 12;
    private static final int    REQ_PERM = 20;

    // ── UI ────────────────────────────────────────────────────────────
    private TextView   tvStatus, tvIp, tvClients;
    private Button     btnStart, btnStop, btnBlack, btnClear;
    private Button     btnPickImage, btnPickVideo, btnPickAudio;
    private View       dotStatus;

    // Playlist UI
    private RecyclerView rvPlaylist;
    private Button       btnPlaylistSend, btnPlaylistClear;
    private Button       btnPrev, btnNext;
    private TextView     tvPlaylistCount;

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
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            streamService = null;
        }
    };

    // ── Playlist ──────────────────────────────────────────────────────
    // Chaque item : { kind, url, name }
    private final List<PlaylistItem> playlistItems = new ArrayList<>();
    private PlaylistAdapter          playlistAdapter;
    private int                      currentIndex  = -1;

    static class PlaylistItem {
        String kind; // "image" | "video" | "audio"
        String url;
        String name;
        PlaylistItem(String kind, String url, String name) {
            this.kind = kind; this.url = url; this.name = name;
        }
    }

    // ═══════════════════════════════════════════════ onCreate

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupPlaylistAdapter();
        setupButtons();
        checkPermissions();
    }

    private void bindViews() {
        tvStatus    = findViewById(R.id.tv_status);
        tvIp        = findViewById(R.id.tv_ip);
        tvClients   = findViewById(R.id.tv_clients);
        dotStatus   = findViewById(R.id.dot_status);
        btnStart    = findViewById(R.id.btn_start);
        btnStop     = findViewById(R.id.btn_stop);
        btnBlack    = findViewById(R.id.btn_black);
        btnClear    = findViewById(R.id.btn_clear);
        btnPickImage = findViewById(R.id.btn_pick_image);
        btnPickVideo = findViewById(R.id.btn_pick_video);
        btnPickAudio = findViewById(R.id.btn_pick_audio);

        rvPlaylist      = findViewById(R.id.rv_playlist);
        btnPlaylistSend  = findViewById(R.id.btn_playlist_send);
        btnPlaylistClear = findViewById(R.id.btn_playlist_clear);
        btnPrev          = findViewById(R.id.btn_prev);
        btnNext          = findViewById(R.id.btn_next);
        tvPlaylistCount  = findViewById(R.id.tv_playlist_count);
    }

    private void setupPlaylistAdapter() {
        playlistAdapter = new PlaylistAdapter(playlistItems,
            pos -> removeFromPlaylist(pos),   // swipe/delete
            pos -> sendSingleItem(pos)         // tap → envoyer direct
        );
        rvPlaylist.setLayoutManager(new LinearLayoutManager(this));
        rvPlaylist.setAdapter(playlistAdapter);

        // Drag-to-reorder
        ItemTouchHelper ith = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int f = from.getAdapterPosition(), t = to.getAdapterPosition();
                PlaylistItem item = playlistItems.remove(f);
                playlistItems.add(t, item);
                playlistAdapter.notifyItemMoved(f, t);
                updatePlaylistCount();
                return true;
            }
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                removeFromPlaylist(vh.getAdapterPosition());
            }
        });
        ith.attachToRecyclerView(rvPlaylist);
    }

    private void setupButtons() {
        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v  -> stopStreaming());

        btnPickImage.setOnClickListener(v -> pickMedia("image/*", REQ_PIC));
        btnPickVideo.setOnClickListener(v -> pickMedia("video/*", REQ_VID));
        btnPickAudio.setOnClickListener(v -> pickMedia("audio/*", REQ_AUD));

        btnBlack.setOnClickListener(v -> sendJson("{\"type\":\"clear\"}"));
        btnClear.setOnClickListener(v -> {
            sendJson("{\"type\":\"clear\"}");
        });

        btnPlaylistSend.setOnClickListener(v -> sendFullPlaylist());
        btnPlaylistClear.setOnClickListener(v -> {
            playlistItems.clear();
            playlistAdapter.notifyDataSetChanged();
            updatePlaylistCount();
        });

        btnPrev.setOnClickListener(v -> {
            if (playlistItems.isEmpty()) return;
            currentIndex = (currentIndex - 1 + playlistItems.size()) % playlistItems.size();
            sendPlaylistCmd("goto", currentIndex);
            playlistAdapter.setActiveIndex(currentIndex);
        });
        btnNext.setOnClickListener(v -> {
            if (playlistItems.isEmpty()) return;
            currentIndex = (currentIndex + 1) % playlistItems.size();
            sendPlaylistCmd("goto", currentIndex);
            playlistAdapter.setActiveIndex(currentIndex);
        });
    }

    // ═══════════════════════════════════════════════ Service

    private void startStreaming() {
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        bindService(new Intent(this, StreamService.class), conn, BIND_AUTO_CREATE);
    }

    private void stopStreaming() {
        if (bound) { unbindService(conn); bound = false; streamService = null; }
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_STOP);
        startService(intent);
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, StreamService.class), conn, 0);
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
            // Ré-envoyer la playlist si déjà constituée
            if (!playlistItems.isEmpty()) sendFullPlaylist();
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
        // Sélection multiple (Android 7+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(Intent.createChooser(intent, "Choisir fichier(s)"), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) return;

        String baseMime;
        switch (requestCode) {
            case REQ_PIC: baseMime = "image"; break;
            case REQ_VID: baseMime = "video"; break;
            case REQ_AUD: baseMime = "audio"; break;
            default: return;
        }

        // Sélection multiple
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                enqueueMedia(uri, baseMime);
            }
        } else if (data.getData() != null) {
            enqueueMedia(data.getData(), baseMime);
        }
    }

    /** Copie le fichier dans le cache et l'ajoute à la playlist */
    private void enqueueMedia(Uri uri, String baseMime) {
        new Thread(() -> {
            try {
                String realMime = getContentResolver().getType(uri);
                if (realMime == null) realMime = baseMime + "/*";
                String kind = realMime.startsWith("video") ? "video"
                            : realMime.startsWith("audio") ? "audio"
                            : "image";

                String fileName = resolveFileName(uri);
                File dest = new File(getCacheDir(),
                        "wsc_" + System.currentTimeMillis() + "_" + fileName);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream os = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                }

                String url = (streamService != null)
                        ? streamService.serveFile(dest, realMime)
                        : null;

                if (url == null) {
                    ui.post(() -> Toast.makeText(this,
                            "⚠ Démarrez la diffusion d'abord", Toast.LENGTH_SHORT).show());
                    return;
                }

                PlaylistItem item = new PlaylistItem(kind, url, fileName);
                ui.post(() -> {
                    playlistItems.add(item);
                    playlistAdapter.notifyItemInserted(playlistItems.size() - 1);
                    updatePlaylistCount();
                });
            } catch (Exception e) {
                Log.e(TAG, "enqueueMedia", e);
                ui.post(() -> Toast.makeText(this,
                        "Erreur: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "WscMediaLoad").start();
    }

    // ═══════════════════════════════════════════════ Envoi WS

    /** Envoie toute la playlist d'un coup et démarre au premier élément */
    private void sendFullPlaylist() {
        if (playlistItems.isEmpty()) {
            Toast.makeText(this, "Playlist vide", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (PlaylistItem item : playlistItems) {
                JSONObject o = new JSONObject();
                o.put("type", "media");
                o.put("kind", item.kind);
                o.put("url",  item.url);
                o.put("name", item.name);
                arr.put(o);
            }
            JSONObject msg = new JSONObject();
            msg.put("type",  "playlist");
            msg.put("items", arr);
            sendJson(msg.toString());
            currentIndex = 0;
            playlistAdapter.setActiveIndex(0);
            Toast.makeText(this, "✅ Playlist envoyée (" + playlistItems.size() + " fichiers)",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Log.e(TAG, "sendFullPlaylist", e); }
    }

    /** Envoie un seul item directement (tap sur élément de la liste) */
    private void sendSingleItem(int pos) {
        if (pos < 0 || pos >= playlistItems.size()) return;
        PlaylistItem item = playlistItems.get(pos);
        try {
            JSONObject p = new JSONObject();
            p.put("type", "media");
            p.put("kind", item.kind);
            p.put("url",  item.url);
            p.put("name", item.name);
            sendJson(p.toString());
            currentIndex = pos;
            playlistAdapter.setActiveIndex(pos);
        } catch (Exception e) { Log.e(TAG, "sendSingleItem", e); }
    }

    private void sendPlaylistCmd(String cmd, int index) {
        try {
            JSONObject p = new JSONObject();
            p.put("type",  "playlist_cmd");
            p.put("cmd",   cmd);
            p.put("index", index);
            sendJson(p.toString());
        } catch (Exception e) { Log.e(TAG, "sendPlaylistCmd", e); }
    }

    private void removeFromPlaylist(int pos) {
        if (pos < 0 || pos >= playlistItems.size()) return;
        playlistItems.remove(pos);
        playlistAdapter.notifyItemRemoved(pos);
        updatePlaylistCount();
    }

    private void updatePlaylistCount() {
        tvPlaylistCount.setText(playlistItems.size() + " fichier(s)");
        btnPlaylistSend.setEnabled(!playlistItems.isEmpty());
        btnPrev.setEnabled(!playlistItems.isEmpty());
        btnNext.setEnabled(!playlistItems.isEmpty());
    }

    private void sendJson(String json) {
        if (streamService != null) {
            streamService.broadcast(json);
        } else {
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
        btnPlaylistSend.setEnabled(running && !playlistItems.isEmpty());
        if (!running) {
            tvStatus.setText("Service arrêté");
            tvClients.setText("—");
            dotStatus.setBackgroundResource(R.drawable.dot_grey);
        } else {
            tvStatus.setText("En diffusion");
        }
        updatePlaylistCount();
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
    }
}