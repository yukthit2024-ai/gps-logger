package com.example.gpslocationlogger;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
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

    // ── Location ─────────────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocationClient;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;

    // ── Data ─────────────────────────────────────────────────────────────────
    /** In-memory list accumulating location fixes during a session. */
    private final List<JSONObject> locationRecords = new ArrayList<>();

    /** Guards against starting multiple concurrent tracking sessions. */
    private boolean isTracking = false;

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

        // Initialise FusedLocationProviderClient
        // LocationRequest is NOT built here — it is rebuilt in startTracking()
        // each time so it always picks up the latest SharedPreferences value.
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Define callback that fires on each location fix
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                for (Location location : result.getLocations()) {
                    if (location != null) {
                        handleNewLocation(location);
                    }
                }
            }
        };

        // Button listeners
        btnStartTracking.setOnClickListener(v -> onStartTrackingClicked());
        btnEndTracking.setOnClickListener(v -> onEndTrackingClicked());

        // Initial UI state
        setTrackingUiState(false);
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

    // ── Button Handlers ──────────────────────────────────────────────────────

    /** Called when the user taps "Start Tracking". */
    private void onStartTrackingClicked() {
        if (isTracking) {
            Log.w(TAG, "Already tracking — ignoring duplicate start request.");
            return;
        }
        checkAndRequestPermission();
    }

    /** Called when the user taps "End Tracking". */
    private void onEndTrackingClicked() {
        if (!isTracking) {
            Log.w(TAG, "Not currently tracking — ignoring end request.");
            return;
        }
        stopTracking();
        saveLocationDataToFile();
    }

    // ── Permission Handling ──────────────────────────────────────────────────

    /** Checks whether ACCESS_FINE_LOCATION is granted, requests it if not. */
    private void checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // Already have permission — start tracking immediately
            startTracking();
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Show rationale then request
            new AlertDialog.Builder(this)
                    .setTitle("Location Permission Required")
                    .setMessage("This app needs access to your precise location to log GPS coordinates. Please grant the permission.")
                    .setPositiveButton("Grant", (dialog, which) ->
                            ActivityCompat.requestPermissions(
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Location permission granted.");
                Toast.makeText(this, "Permission granted — starting tracking.", Toast.LENGTH_SHORT).show();
                startTracking();
            } else {
                Log.w(TAG, "Location permission denied.");
                boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.ACCESS_FINE_LOCATION);
                if (!showRationale) {
                    new AlertDialog.Builder(this)
                            .setTitle("Permission Denied Permanently")
                            .setMessage("Location permission was permanently denied. Please enable it from App Settings to use this feature.")
                            .setPositiveButton("Open Settings", (dialog, which) -> {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", getPackageName(), null));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    Toast.makeText(this, "Location permission denied.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // ── Tracking ─────────────────────────────────────────────────────────────

    /** Starts location updates. Assumes permission is already granted. */
    private void startTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "startTracking() called without permission — aborting.");
            return;
        }

        // Read interval from settings
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        long intervalMs = prefs.getLong(SettingsActivity.KEY_INTERVAL_MS, SettingsActivity.DEFAULT_INTERVAL_MS);

        // Build LocationRequest dynamically
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs) // matched for simplicity
                .build();

        Log.d(TAG, "Starting tracking with interval: " + intervalMs + "ms");

        // Clear any previous session data
        locationRecords.clear();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());

        isTracking = true;
        setTrackingUiState(true);
        Log.d(TAG, "Location tracking started.");
        Toast.makeText(this, "Tracking started (" + (intervalMs/1000) + "s interval).", Toast.LENGTH_SHORT).show();
    }

    /** Stops location updates. */
    private void stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
        isTracking = false;
        setTrackingUiState(false);
        Log.d(TAG, "Location tracking stopped. Records collected: " + locationRecords.size());
        Toast.makeText(this, "Tracking stopped.", Toast.LENGTH_SHORT).show();
    }

    /**
     * Called on every location fix received from the callback.
     * Builds a JSON object and appends it to the in-memory list.
     */
    private void handleNewLocation(@NonNull Location location) {
        double latitude  = location.getLatitude();
        double longitude = location.getLongitude();
        // ISO 8601 timestamp with local timezone offset, e.g. "2026-04-12T20:00:00.123+05:30"
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Log.d(TAG, String.format("New fix → lat=%.6f  lon=%.6f  time=%s", latitude, longitude, timestamp));

        // Update live coordinate display
        tvCoordinates.setText(String.format("Lat: %.6f\nLon: %.6f", latitude, longitude));
        tvStatus.setText("Tracking… (" + locationRecords.size() + 1 + " fix(es))");

        // Build JSON record
        try {
            JSONObject record = new JSONObject();
            record.put("latitude",  latitude);
            record.put("longitude", longitude);
            record.put("timestamp", timestamp);
            locationRecords.add(record);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build JSON record", e);
        }
    }

    // ── File I/O ─────────────────────────────────────────────────────────────

    /**
     * Serialises all collected records and saves them in three formats:
     * JSON, GPX 1.1, and KML 2.2.
     * All files share the same base timestamp and are saved to:
     *   /sdcard/Download/GPSLocationLogger/
     */
    private void saveLocationDataToFile() {
        if (locationRecords.isEmpty()) {
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
                for (JSONObject record : locationRecords) {
                    jsonArray.put(record);
                }
                saveViaMediaStore(jsonArray.toString(2), baseName + ".json", "application/json");
            } catch (JSONException e) {
                Log.e(TAG, "Error formatting JSON", e);
            }
        }

        // 3. Generate and save GPX
        if (saveGpx) {
            String gpxContent = generateGpx(locationRecords);
            saveViaMediaStore(gpxContent, baseName + ".gpx", "application/gpx+xml");
        }

        // 4. Generate and save KML
        if (saveKml) {
            String kmlContent = generateKml(locationRecords);
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
        // Safety net: stop updates to avoid leak if activity is destroyed mid-session
        if (isTracking) {
            Log.w(TAG, "onDestroy() called while tracking — stopping updates.");
            fusedLocationClient.removeLocationUpdates(locationCallback);
            isTracking = false;
        }
    }
}
