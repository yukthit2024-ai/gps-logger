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
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    // ── Service ─────────────────────────────────────────────────────────────
    private LocationService locationService;
    private boolean isBound = false;
    private boolean isTracking = false;

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

        // Button listeners
        btnStartTracking.setOnClickListener(v -> onStartTrackingClicked());
        btnEndTracking.setOnClickListener(v -> onEndTrackingClicked());

        // Initial UI state
        setTrackingUiState(false);
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
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Button Handlers ────────────────────────────────────────────────────    
    /** Checks whether required permissions are granted, requests them if not. */
    private void checkAndRequestPermission() {
        List<String> permissionsNeeded = new ArrayList<>();
        permissionsNeeded.add(android.Manifest.permission.ACCESS_FINE_LOCATION);

        // Android 13+ requires explicit notification permission for Foreground Services
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(android.Manifest.permission.POST_NOTIFICATIONS);
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
    }                    ActivityCompat.requestPermissions(
                                    this,
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    LOCATION_PERMISSION_REQUEST_CODE))
                    .setNegativeButton("Cancel", (dialog, which) ->
                            Toast.makeText(this, "Location permission denied.", Toast.LENGTH_SHORT).show())
                    .show();
        } else {
            // Request directly (first time, or "don't ask again")
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
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
        Toast.makeText(this, "Background tracking started.", Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, "Tracking stopped.", Toast.LENGTH_SHORT).show();
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
                tvStatus.setText("Tracking… (" + records.size() + " fix(es))");
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
        String baseName = "location_logs_" + fileTimestamp;

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
            saveViaMediaStore(kmlContent, baseName + ".kml", "application/vnd.google-earth.kml+xml");
        }
    }

    /**
     * Writes string content to a file in Downloads/GPSLocationLogger/ via MediaStore.
     */
    private void saveViaMediaStore(String content, String fileName, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE,    mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + GPS_FOLDER);

        Uri collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri fileUri = getContentResolver().insert(collectionUri, values);

        if (fileUri == null) {
            Log.e(TAG, "MediaStore insert failed for " + fileName);
            return;
        }

        try (OutputStream os = getContentResolver().openOutputStream(fileUri);
             OutputStreamWriter writer = new OutputStreamWriter(os)) {

            writer.write(content);
            writer.flush();
            Log.i(TAG, "Saved → " + fileName);

        } catch (IOException e) {
            Log.e(TAG, "Error writing " + fileName, e);
        }
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

        if (tracking) {
            tvStatus.setText("Tracking in progress…");
            tvCoordinates.setText("Waiting for first fix…");
        } else {
            tvStatus.setText("Press \"Start Tracking\" to begin.");
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
