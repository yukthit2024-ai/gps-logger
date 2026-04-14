package com.example.gpslocationlogger;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.Manifest;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity — GPS Location Logger
 *
 * Tracks GPS location using FusedLocationProviderClient, stores updates
 * in-memory as a JSON array, and saves to:
 *   Downloads/GPSLocationLogger/  (Android 10+, via MediaStore.Downloads)
 *
 * No special permission needed. Visible in all file managers and USB/MTP.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GPSLocationLogger";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    /**
     * Subfolder name inside Downloads.
     * Final path: /sdcard/Download/GPSLocationLogger/
     * Visible in all file managers and via USB/MTP — no root required.
     *
     * NOTE: Android scoped storage (API 29+) prevents creating arbitrary
     * folders at /sdcard/ root without MANAGE_EXTERNAL_STORAGE. The safe,
     * reliable option is a subfolder inside a well-known public directory
     * such as Downloads, which MediaStore.Downloads handles natively.
     */
    private static final String GPS_FOLDER = "GPSLocationLogger";

    // ── UI ──────────────────────────────────────────────────────────────────
    private Button btnStartTracking;
    private Button btnEndTracking;
    private TextView tvStatus;
    private TextView tvCoordinates;
    private EditText etTrackingInfo;
    private CardView cardTrackingInfo;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    // ── Service ─────────────────────────────────────────────────────────────
    private LocationService locationService;
    private boolean isBound = false;
    private boolean isTracking = false;
    private Uri lastSavedUri = null;
    private String lastSavedName = null;

    // Polling handler for live UI updates
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            if (isBound && locationService != null) {
                updateUiFromService();
            }
            uiHandler.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LocationService.LocalBinder binder = (LocationService.LocalBinder) service;
            locationService = binder.getService();
            isBound = true;
            isTracking = true;
            setTrackingUiState(true);
            Log.d(TAG, "Bound to LocationService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            locationService = null;
            isBound = false;
            isTracking = false;
            setTrackingUiState(false);
            Log.d(TAG, "Unbound from LocationService");
        }
    };

    // ────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views
        btnStartTracking = findViewById(R.id.btnStartTracking);
        btnEndTracking   = findViewById(R.id.btnEndTracking);
        tvStatus         = findViewById(R.id.tvStatus);
        tvCoordinates    = findViewById(R.id.tvCoordinates);
        etTrackingInfo   = findViewById(R.id.etTrackingInfo);
        cardTrackingInfo = findViewById(R.id.cardTrackingInfo);

        // Button listeners
        btnStartTracking.setOnClickListener(v -> onStartTrackingClicked());
        btnEndTracking.setOnClickListener(v -> onEndTrackingClicked());

        // Setup Toolbar
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Setup Navigation Drawer
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar, R.string.label_start_tracking, R.string.label_end_tracking);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else if (id == R.id.nav_help) {
                startActivity(new Intent(this, HelpActivity.class));
            } else if (id == R.id.nav_about) {
                startActivity(new Intent(this, AboutActivity.class));
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Initial UI state
        setTrackingUiState(false);
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Attempt to bind to the service if it is already running
        Intent intent = new Intent(this, LocationService.class);
        bindService(intent, serviceConnection, 0); // 0 = don't auto-create
        uiHandler.post(uiUpdater);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        uiHandler.removeCallbacks(uiUpdater);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem shareItem = menu.findItem(R.id.action_share);
        if (shareItem != null) {
            shareItem.setVisible(lastSavedUri != null);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_share) {
            shareLastLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Button Handlers ────────────────────────────────────────────────────
    private void onStartTrackingClicked() {
        checkAndRequestPermission();
    }

    private void onEndTrackingClicked() {
        stopTracking();
    }

    /** Checks whether required permissions are granted, requests them if not. */
    private void checkAndRequestPermission() {
        List<String> permissionsNeeded = new ArrayList<>();
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);

        // Android 13+ requires explicit notification permission for Foreground Services
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (listPermissionsNeeded.isEmpty()) {
            startTracking();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    listPermissionsNeeded.toArray(new String[0]),
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * System callback for runtime permission results (location only).
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.d(TAG, "Permissions granted.");
                startTracking();
            } else {
                Log.w(TAG, "Permissions denied.");
                Toast.makeText(this, "Required permissions denied.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Tracking ─────────────────────────────────────────────────────────────

    /** Starts the Location Service. */
    private void startTracking() {
        Intent intent = new Intent(this, LocationService.class);
        ContextCompat.startForegroundService(this, intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        isTracking = true;
        setTrackingUiState(true);
        Log.d(TAG, "Location service started.");
        Toast.makeText(this, getString(R.string.status_tracking), Toast.LENGTH_SHORT).show();
    }

    /** Fetches data from service, saves it, and stops the service. */
    private void stopTracking() {
        if (!isBound || locationService == null) return;

        List<JSONObject> records = locationService.getLocationRecords();
        saveLocationDataToFile(records);

        locationService.stopTracking();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }

        isTracking = false;
        setTrackingUiState(false);
        Log.d(TAG, "Tracking stopped. Records: " + records.size());
        Toast.makeText(this, R.string.label_end_tracking, Toast.LENGTH_SHORT).show();
    }

    /** Updates the UI with the latest data from the service. */
    private void updateUiFromService() {
        List<JSONObject> records = locationService.getLocationRecords();
        if (!records.isEmpty()) {
            JSONObject last = records.get(records.size() - 1);
            try {
                double lat = last.getDouble("latitude");
                double lon = last.getDouble("longitude");
                tvCoordinates.setText(String.format("Lat: %.6f\nLon: %.6f", lat, lon));
                tvStatus.setText(getString(R.string.status_tracking) + " (" + records.size() + " fix(es))");
            } catch (JSONException e) {
                Log.e(TAG, "UI update error", e);
            }
        }
    }

    /**
     * Serialises collected records and saves them in selected formats.
     */
    private void saveLocationDataToFile(List<JSONObject> records) {
        if (records.isEmpty()) {
            Toast.makeText(this, "No location data to save.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "saveLocationDataToFile() — nothing to write.");
            return;
        }

        // 1. Prepare common timestamp for filenames
        String fileTimestamp = ZonedDateTime.now()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .replace(":", "-");

        // 2. Prepare user info string (sanitized)
        String infoText = etTrackingInfo.getText().toString().trim();
        String sanitizedInfo = sanitizeFilename(infoText);
        
        String baseName;
        if (!sanitizedInfo.isEmpty()) {
            baseName = "location_logs_" + fileTimestamp + "_" + sanitizedInfo;
        } else {
            baseName = "location_logs_" + fileTimestamp;
        }

        // Read format preferences
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean saveJson = prefs.getBoolean(SettingsActivity.KEY_SAVE_JSON, true);
        boolean saveGpx  = prefs.getBoolean(SettingsActivity.KEY_SAVE_GPX, true);
        boolean saveKml  = prefs.getBoolean(SettingsActivity.KEY_SAVE_KML, true);

        // 2. Generate and save JSON
        if (saveJson) {
            try {
                JSONArray jsonArray = new JSONArray();
                for (JSONObject record : records) {
                    jsonArray.put(record);
                }
                saveViaMediaStore(jsonArray.toString(2), baseName + ".json", "application/json");
            } catch (JSONException e) {
                Log.e(TAG, "Error formatting JSON", e);
            }
        }

        // 3. Generate and save GPX
        if (saveGpx) {
            String gpxContent = generateGpx(records);
            saveViaMediaStore(gpxContent, baseName + ".gpx", "application/gpx+xml");
        }

        // 4. Generate and save KML
        if (saveKml) {
            String kmlContent = generateKml(records);
            lastSavedUri = saveViaMediaStore(kmlContent, baseName + ".kml", "application/vnd.google-earth.kml+xml");
            lastSavedName = baseName + ".kml";
        } else if (saveGpx) {
            // If KML not saved, use GPX as last saved for sharing
            String gpxContent = generateGpx(records);
            lastSavedUri = saveViaMediaStore(gpxContent, baseName + ".gpx", "application/gpx+xml");
            lastSavedName = baseName + ".gpx";
        } else if (saveJson) {
            // Fallback to JSON
            try {
                JSONArray jsonArray = new JSONArray();
                for (JSONObject record : records) jsonArray.put(record);
                lastSavedUri = saveViaMediaStore(jsonArray.toString(2), baseName + ".json", "application/json");
                lastSavedName = baseName + ".json";
            } catch (JSONException ignored) {}
        }

        if (lastSavedUri != null) {
            TextView tvSavePath = findViewById(R.id.tvSavePath);
            tvSavePath.setText("📁 Last saved: " + lastSavedName);
            invalidateOptionsMenu(); // Refresh share icon visibility
            etTrackingInfo.setText(""); // Clear for next session
        }
    }

    /**
     * Removes characters that are unsafe for filenames.
     */
    private String sanitizeFilename(String input) {
        if (input == null || input.isEmpty()) return "";
        return input.replaceAll("\\s+", "_")
                    .replaceAll("[^a-zA-Z0-9_-]", "");
    }

    /**
     * Writes string content to a file in Downloads/GPSLocationLogger/ via MediaStore.
     * @return the Uri of the saved file, or null if failed.
     */
    private Uri saveViaMediaStore(String content, String fileName, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE,    mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + GPS_FOLDER);

        Uri collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri fileUri = getContentResolver().insert(collectionUri, values);

        if (fileUri == null) {
            Log.e(TAG, "MediaStore insert failed for " + fileName);
            return null;
        }

        try (OutputStream os = getContentResolver().openOutputStream(fileUri);
             OutputStreamWriter writer = new OutputStreamWriter(os)) {

            writer.write(content);
            writer.flush();
            Log.i(TAG, "Saved → " + fileName);
            return fileUri;

        } catch (IOException e) {
            Log.e(TAG, "Error writing " + fileName, e);
            return null;
        }
    }

    /** Triggers the standard Android system share sheet for the last saved log. */
    private void shareLastLog() {
        if (lastSavedUri == null) {
            Toast.makeText(this, "No log file to share yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/octet-stream"); // Generic stream
        shareIntent.putExtra(Intent.EXTRA_STREAM, lastSavedUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Share Location Log"));
    }

    /** Builds a GPX 1.1 XML string from the location records. */
    private String generateGpx(List<JSONObject> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"GPSLocationLogger\" ")
          .append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("  <trk>\n");
        sb.append("    <name>GPS Location Log</name>\n");
        sb.append("    <trkseg>\n");

        for (JSONObject record : records) {
            try {
                double lat = record.getDouble("latitude");
                double lon = record.getDouble("longitude");
                String time = record.getString("timestamp");
                sb.append(String.format("      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n", lat, lon));
                sb.append("        <time>").append(time).append("</time>\n");
                sb.append("      </trkpt>\n");
            } catch (JSONException e) {
                Log.e(TAG, "GPX row skip", e);
            }
        }

        sb.append("    </trkseg>\n");
        sb.append("  </trk>\n");
        sb.append("</gpx>");
        return sb.toString();
    }

    /** Builds a KML 2.2 XML string from the location records. */
    private String generateKml(List<JSONObject> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
        sb.append("  <Document>\n");
        sb.append("    <name>GPS Location Log</name>\n");
        sb.append("    <Placemark>\n");
        sb.append("      <name>Track Path</name>\n");
        sb.append("      <LineString>\n");
        sb.append("        <tessellate>1</tessellate>\n");
        sb.append("        <coordinates>\n");

        for (JSONObject record : records) {
            try {
                double lat = record.getDouble("latitude");
                double lon = record.getDouble("longitude");
                // KML coordinates are lon,lat,alt
                sb.append(String.format("          %.6f,%.6f,0\n", lon, lat));
            } catch (JSONException e) {
                Log.e(TAG, "KML row skip", e);
            }
        }

        sb.append("        </coordinates>\n");
        sb.append("      </LineString>\n");
        sb.append("    </Placemark>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>");
        return sb.toString();
    }

    // ── UI State Helpers ─────────────────────────────────────────────────────

    /**
     * Toggles button states and status text based on whether tracking is active.
     *
     * @param tracking true if a tracking session is in progress
     */
    private void setTrackingUiState(boolean tracking) {
        btnStartTracking.setEnabled(!tracking);
        btnEndTracking.setEnabled(tracking);

        if (cardTrackingInfo != null) {
            cardTrackingInfo.setVisibility(tracking ? View.VISIBLE : View.GONE);
        }

        if (tracking) {
            tvStatus.setText(R.string.status_tracking);
            tvCoordinates.setText(R.string.status_waiting);
        } else {
            tvStatus.setText(R.string.status_idle);
            tvCoordinates.setText("–");
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Stop polling to avoid leaks
        uiHandler.removeCallbacks(uiUpdater);
    }
}
