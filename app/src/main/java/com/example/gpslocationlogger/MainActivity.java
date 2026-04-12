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
    private static final long LOCATION_INTERVAL_MS = 5000;   // 5 seconds
    private static final long LOCATION_FASTEST_INTERVAL_MS = 5000;

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

        // Timezone-aware timestamp for file name — colons and '+' replaced so it is
        // filesystem-safe, e.g. "2026-04-12T20-00-00.123+05-30" → safe on all OSes
        String fileTimestamp = ZonedDateTime.now()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .replace(":", "-");   // covers both time separators and timezone colon
        String fileName = "location_logs_" + fileTimestamp + ".json";

        // Delegate to MediaStore — Android 10+ only (minSdk = 29)
        saveViaMediaStore(jsonArray, fileName);
    }

    /**
     * Writes the JSON file to Downloads/GPSLocationLogger/ via MediaStore.Downloads.
     *
     * WHY NOT /sdcard/GPSLocationLogger/ (root) ?
     * Android scoped storage (enforced from API 29) blocks apps from creating
     * folders at the external storage root without MANAGE_EXTERNAL_STORAGE —
     * a highly restricted permission that causes Play Store rejection.
     * MediaStore.Downloads with a subfolder is the correct, unrestricted API.
     *
     * Resulting path: /sdcard/Download/GPSLocationLogger/<filename>.json
     * Accessible via: any file manager → Downloads → GPSLocationLogger
     *                 Windows File Explorer over USB (MTP)
     *                 adb pull /sdcard/Download/GPSLocationLogger/
     */
    private void saveViaMediaStore(JSONArray jsonArray, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE,    "application/json");
        // RELATIVE_PATH for Downloads collection is relative to /sdcard/
        // "Download/GPSLocationLogger/" → /sdcard/Download/GPSLocationLogger/
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + GPS_FOLDER);

        Uri collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Log.d(TAG, "Inserting into MediaStore: " + collectionUri);
        Uri fileUri = getContentResolver().insert(collectionUri, values);

        if (fileUri == null) {
            Log.e(TAG, "MediaStore.Downloads.insert() returned null — storage may be unavailable.");
            Toast.makeText(this,
                    "Could not create file in Downloads. Check storage availability.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Log.d(TAG, "MediaStore URI created: " + fileUri);

        try (OutputStream os = getContentResolver().openOutputStream(fileUri);
             OutputStreamWriter writer = new OutputStreamWriter(os)) {

            writer.write(jsonArray.toString(2)); // pretty-print with 2-space indent
            writer.flush();

            String displayPath = "Downloads/" + GPS_FOLDER + "/" + fileName;
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
