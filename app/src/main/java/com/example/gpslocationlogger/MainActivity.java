package com.example.gpslocationlogger;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity — GPS Location Logger
 *
 * Tracks GPS location using FusedLocationProviderClient, stores updates
 * in-memory as a JSON array, and saves to:
 *   /sdcard/GPSLocationLogger/  (root of internal storage, via MediaStore.Files)
 *
 * No special permission needed on Android 10+ (API 29+).
 * Visible in every file manager and via USB/MTP without root.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GPSLocationLogger";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final long LOCATION_INTERVAL_MS = 5000;   // 5 seconds
    private static final long LOCATION_FASTEST_INTERVAL_MS = 5000;

    /** Folder created at the root of internal storage: /sdcard/GPSLocationLogger/ */
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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Build LocationRequest (modern API, API 31+; falls back gracefully)
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
                .build();

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

        // Clear any previous session data
        locationRecords.clear();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());

        isTracking = true;
        setTrackingUiState(true);
        Log.d(TAG, "Location tracking started.");
        Toast.makeText(this, "Tracking started.", Toast.LENGTH_SHORT).show();
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
        // ISO 8601 UTC timestamp  e.g. "2026-04-12T14:30:00Z"
        String timestamp = Instant.now().toString();

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
     * Serialises all collected records to a JSON file at:
     *   /sdcard/GPSLocationLogger/location_logs_<timestamp>.json
     *
     * Uses MediaStore.Files so the folder appears at the ROOT of internal
     * storage (not inside Downloads/DCIM/etc.) and is immediately visible
     * in every file manager and via USB/MTP — no root or special permission needed.
     */
    private void saveLocationDataToFile() {
        if (locationRecords.isEmpty()) {
            Toast.makeText(this, "No location data to save.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "saveLocationDataToFile() — nothing to write.");
            return;
        }

        // Build JSON array
        JSONArray jsonArray = new JSONArray();
        for (JSONObject record : locationRecords) {
            jsonArray.put(record);
        }

        // File-system-safe timestamp, e.g. "2026-04-12T08-30-00.123Z"
        String fileTimestamp = Instant.now().toString().replace(":", "-");
        String fileName = "location_logs_" + fileTimestamp + ".json";

        // Delegate to MediaStore — Android 10+ only (minSdk = 29)
        saveViaMediaStore(jsonArray, fileName);
    }

    /**
     * Writes the JSON file to /sdcard/GPSLocationLogger/ via MediaStore.Files.
     *
     * MediaStore.Files with RELATIVE_PATH = "GPSLocationLogger/" places the
     * file at the ROOT of external storage — not inside Downloads or DCIM.
     * No permission is required on Android 10+ (API 29+).
     */
    private void saveViaMediaStore(JSONArray jsonArray, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json");
        // RELATIVE_PATH is relative to the root of external storage.
        // "GPSLocationLogger/" creates /sdcard/GPSLocationLogger/
        values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, GPS_FOLDER + File.separator);

        Uri collectionUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri fileUri = getContentResolver().insert(collectionUri, values);

        if (fileUri == null) {
            Log.e(TAG, "MediaStore insert returned null URI.");
            Toast.makeText(this, "Could not create file. Check storage availability.", Toast.LENGTH_LONG).show();
            return;
        }

        try (OutputStream os = getContentResolver().openOutputStream(fileUri);
             OutputStreamWriter writer = new OutputStreamWriter(os)) {

            writer.write(jsonArray.toString(2)); // pretty-print with 2-space indent
            writer.flush();

            String displayPath = "/sdcard/" + GPS_FOLDER + "/" + fileName;
            Log.i(TAG, "Saved → " + displayPath);
            Toast.makeText(this,
                    "✅ Saved " + locationRecords.size() + " record(s) to:\n" + displayPath,
                    Toast.LENGTH_LONG).show();

        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error writing via MediaStore", e);
            Toast.makeText(this, "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
