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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.util.Rational;

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

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

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
 *   Vypeensoft/GPS_Location_Logger/  (Android 10+, via MANAGE_EXTERNAL_STORAGE)
 *
 * Requires WRITE_EXTERNAL_STORAGE (API < 30) and MANAGE_EXTERNAL_STORAGE (API >= 30).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GPSLocationLogger";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    /**
     * Subfolder name inside Downloads.
     * Final path: /sdcard/Vypeensoft/GPS_Location_Logger/
     * Visible in all file managers and via USB/MTP — no root required.
     *
     * NOTE: Android scoped storage (API 29+) prevents creating arbitrary
     * folders at /sdcard/ root without MANAGE_EXTERNAL_STORAGE. The safe,
     * reliable option is a subfolder inside a well-known public directory
     * such as Downloads, which MediaStore.Downloads handles natively.
     */
    private static final String GPS_FOLDER = "Vypeensoft/GPS_Location_Logger/tracks";

    // ── UI ──────────────────────────────────────────────────────────────────
    private Button btnRecordPoint;
    private Button btnStartTracking;
    private Button btnPauseTracking;
    private Button btnEndTracking;
    private TextView tvStatus;
    private TextView tvCoordinates;
    private EditText etTrackingInfo;
    private CardView cardTrackingInfo;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private View statusBar;
    private ImageView ivPipIcon;
    private ImageView ivLockTrackingInfo;
    private View mainContent;

    // ── Service ─────────────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocationClient;
    private LocationService locationService;
    private boolean isBound = false;
    private boolean isTracking = false;
    private boolean isRecordingPointPending = false;
    private boolean isStartupPermissionRequest = false;
    private Uri lastSavedUri = null;
    private String lastSavedName = null;

    // Polling handler for live UI updates
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            updateUiFromService();
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

        // Load settings from settings.json if it exists on external storage
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        SettingsHelper.loadSettingsFromJson(this, prefs);

        // Bind views
        btnRecordPoint   = findViewById(R.id.btnRecordPoint);
        btnStartTracking = findViewById(R.id.btnStartTracking);
        btnPauseTracking = findViewById(R.id.btnPauseTracking);
        btnEndTracking   = findViewById(R.id.btnEndTracking);
        tvStatus         = findViewById(R.id.tvStatus);
        tvCoordinates    = findViewById(R.id.tvCoordinates);
        etTrackingInfo   = findViewById(R.id.etTrackingInfo);
        cardTrackingInfo = findViewById(R.id.cardTrackingInfo);
        statusBar        = findViewById(R.id.statusBar);
        ivPipIcon        = findViewById(R.id.ivPipIcon);
        ivLockTrackingInfo = findViewById(R.id.ivLockTrackingInfo);
        mainContent      = findViewById(R.id.main_content);

        // Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Button listeners
        btnRecordPoint.setOnClickListener(v -> onRecordPointClicked());
        btnStartTracking.setOnClickListener(v -> onStartTrackingClicked());
        btnPauseTracking.setOnClickListener(v -> onPauseTrackingClicked());
        btnEndTracking.setOnClickListener(v -> onEndTrackingClicked());
        ivLockTrackingInfo.setOnClickListener(v -> onLockTrackingInfoClicked());
        cardTrackingInfo.setOnClickListener(v -> {
            etTrackingInfo.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etTrackingInfo, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });

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
            if (id == R.id.nav_tracks_list) {
                startActivity(new Intent(this, TracksListActivity.class));
            } else if (id == R.id.nav_settings) {
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

        // On first use / app launch, check and request permissions immediately
        requestPermissionsOnStartup();
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        SettingsHelper.loadSettingsFromJson(this, prefs);
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
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // If we are tracking, enter Picture-in-Picture mode when the user leaves the app (home/recents)
        if (isTracking && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode();
        }
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Make the icon visible and slightly smaller for PiP to encourage a smaller window
            ivPipIcon.setVisibility(View.VISIBLE);
            ivPipIcon.getLayoutParams().width = (int) (48 * getResources().getDisplayMetrics().density);
            ivPipIcon.getLayoutParams().height = (int) (48 * getResources().getDisplayMetrics().density);
            ivPipIcon.requestLayout();
            
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
            // Reverting to the requested 2:3 aspect ratio
            Rational aspectRatio = new Rational(2, 3);
            builder.setAspectRatio(aspectRatio);
            
            // Use the scaled icon as the source rect hint
            android.graphics.Rect rect = new android.graphics.Rect();
            ivPipIcon.getGlobalVisibleRect(rect);
            if (rect.width() > 0 && rect.height() > 0) {
                builder.setSourceRectHint(rect);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(false);
            }
            
            enterPictureInPictureMode(builder.build());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            // Hide non-essential UI for PiP
            if (getSupportActionBar() != null) getSupportActionBar().hide();
            
            // Hide EVERYTHING except the status bar and the icon
            findViewById(R.id.cardStatus).setVisibility(View.GONE);
            findViewById(R.id.cardCoordinates).setVisibility(View.GONE);
            if (btnRecordPoint != null) btnRecordPoint.setVisibility(View.GONE);
            findViewById(R.id.btnStartTracking).setVisibility(View.GONE);
            findViewById(R.id.btnPauseTracking).setVisibility(View.GONE);
            findViewById(R.id.btnEndTracking).setVisibility(View.GONE);
            findViewById(R.id.cardTrackingInfo).setVisibility(View.GONE);
            
            // Show the PiP icon and ensure it's the smaller size
            ivPipIcon.setVisibility(View.VISIBLE);
            ivPipIcon.getLayoutParams().width = (int) (48 * getResources().getDisplayMetrics().density);
            ivPipIcon.getLayoutParams().height = (int) (48 * getResources().getDisplayMetrics().density);
            ivPipIcon.requestLayout();
            
            // Make the status bar fill the background
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp = 
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) statusBar.getLayoutParams();
            lp.height = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT;
            statusBar.setLayoutParams(lp);

        } else {
            // Restore UI when returning to full screen
            if (getSupportActionBar() != null) getSupportActionBar().show();
            
            // Restore status bar height (20dp)
            int heightPx = (int) (20 * getResources().getDisplayMetrics().density);
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp = 
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) statusBar.getLayoutParams();
            lp.height = heightPx;
            statusBar.setLayoutParams(lp);

            // Restore icon size
            ivPipIcon.getLayoutParams().width = (int) (64 * getResources().getDisplayMetrics().density);
            ivPipIcon.getLayoutParams().height = (int) (64 * getResources().getDisplayMetrics().density);
            ivPipIcon.requestLayout();

            ivPipIcon.setVisibility(View.GONE);
            findViewById(R.id.cardStatus).setVisibility(View.VISIBLE);
            findViewById(R.id.cardCoordinates).setVisibility(View.VISIBLE);
            
            // Restore visibility of buttons and info card
            if (btnRecordPoint != null) btnRecordPoint.setVisibility(View.VISIBLE);
            btnStartTracking.setVisibility(View.VISIBLE);
            btnPauseTracking.setVisibility(View.VISIBLE);
            btnEndTracking.setVisibility(View.VISIBLE);
            
            setTrackingUiState(isTracking);
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
        if (id == R.id.action_share) {
            shareLastLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Button Handlers ────────────────────────────────────────────────────
    private boolean hasLocationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                return false;
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void onRecordPointClicked() {
        isRecordingPointPending = true;
        if (hasLocationPermissions()) {
            isRecordingPointPending = false;
            recordSinglePoint();
        } else {
            checkAndRequestPermission();
        }
    }

    private void recordSinglePoint() {
        Toast.makeText(this, "Getting accurate GPS location...", Toast.LENGTH_SHORT).show();
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        saveSinglePointToFile(location);
                    } else {
                        fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                            if (lastLoc != null) {
                                saveSinglePointToFile(lastLoc);
                            } else {
                                Toast.makeText(MainActivity.this, "Unable to get GPS location. Make sure GPS is enabled.", Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    fusedLocationClient.getLastLocation().addOnSuccessListener(lastLoc -> {
                        if (lastLoc != null) {
                            saveSinglePointToFile(lastLoc);
                        } else {
                            Toast.makeText(MainActivity.this, "Failed to get GPS location: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                });
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing", e);
            Toast.makeText(this, "Location permission missing.", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveSinglePointToFile(Location location) {
        String fileTimestamp = ZonedDateTime.now()
                //.format(DateTimeFormatter.ofPattern("yyyy-MM-dd.HHmmss"));
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .replace(":", "-");

        String infoText = etTrackingInfo.getText().toString().trim();
        String sanitizedInfo = sanitizeFilename(infoText);
        
        String baseName;
        if (!sanitizedInfo.isEmpty()) {
            baseName = "gps_" + fileTimestamp + "_Recorded_Point_" + sanitizedInfo;
        } else {
            baseName = "gps_" + fileTimestamp + "_Recorded_Point";
        }

        List<JSONObject> records = new ArrayList<>();
        try {
            JSONObject record = new JSONObject();
            record.put("latitude", location.getLatitude());
            record.put("longitude", location.getLongitude());
            record.put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            records.add(record);
        } catch (JSONException e) {
            Log.e(TAG, "Error formatting single point JSON", e);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        boolean saveJson = prefs.getBoolean(SettingsActivity.KEY_SAVE_JSON, true);
        boolean saveGpx  = prefs.getBoolean(SettingsActivity.KEY_SAVE_GPX, true);
        boolean saveKml  = prefs.getBoolean(SettingsActivity.KEY_SAVE_KML, true);

        if (saveJson) {
            try {
                JSONArray jsonArray = new JSONArray();
                jsonArray.put(records.get(0));
                saveToFile(jsonArray.toString(2), baseName + ".json");
            } catch (JSONException e) {
                Log.e(TAG, "Error formatting JSON", e);
            }
        }

        if (saveGpx) {
            String gpxContent = generateGpx(records);
            saveToFile(gpxContent, baseName + ".gpx");
        }

        if (saveKml) {
            String kmlContent = generateKml(records);
            lastSavedUri = saveToFile(kmlContent, baseName + ".kml");
            lastSavedName = baseName + ".kml";
        } else if (saveGpx) {
            String gpxContent = generateGpx(records);
            lastSavedUri = saveToFile(gpxContent, baseName + ".gpx");
            lastSavedName = baseName + ".gpx";
        } else if (saveJson) {
            try {
                JSONArray jsonArray = new JSONArray();
                jsonArray.put(records.get(0));
                lastSavedUri = saveToFile(jsonArray.toString(2), baseName + ".json");
                lastSavedName = baseName + ".json";
            } catch (JSONException ignored) {}
        }

        if (lastSavedUri != null) {
            invalidateOptionsMenu();
            etTrackingInfo.setText("");
            etTrackingInfo.setEnabled(true);
            ivLockTrackingInfo.setEnabled(true);
            ivLockTrackingInfo.setAlpha(1.0f);
            
            tvCoordinates.setText(String.format("Lat: %.6f   Lon: %.6f", location.getLatitude(), location.getLongitude()));
            
            Toast.makeText(this, String.format("Recorded Point Saved:\nLat: %.6f, Lon: %.6f", 
                    location.getLatitude(), location.getLongitude()), Toast.LENGTH_LONG).show();
        }
    }

    private void onStartTrackingClicked() {
        checkAndRequestPermission();
    }

    private void onPauseTrackingClicked() {
        if (isBound && locationService != null) {
            boolean isPaused = locationService.isPaused();
            locationService.setPaused(!isPaused);
            setTrackingUiState(true);
        }
    }

    private void onEndTrackingClicked() {
        stopTracking();
    }

    private void onLockTrackingInfoClicked() {
        if (etTrackingInfo.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Please enter tracking info first", Toast.LENGTH_SHORT).show();
            return;
        }
        etTrackingInfo.setEnabled(false);
        ivLockTrackingInfo.setEnabled(false);
        ivLockTrackingInfo.setAlpha(0.5f);
        Toast.makeText(this, "Tracking info locked", Toast.LENGTH_SHORT).show();
    }

    /** Checks whether required permissions are granted, requests them if not. */
    private void checkAndRequestPermission() {
        isStartupPermissionRequest = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
                return;
            }
        }

        List<String> permissionsNeeded = new ArrayList<>();
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);

        // Android 13+ requires explicit notification permission for Foreground Services
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (listPermissionsNeeded.isEmpty()) {
            if (isRecordingPointPending) {
                isRecordingPointPending = false;
                recordSinglePoint();
            } else {
                startTracking();
            }
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
                if (isStartupPermissionRequest) {
                    isStartupPermissionRequest = false;
                    Log.d(TAG, "Startup permissions granted. Checking for file access.");
                    checkAndRequestFileAccessStartup();
                } else if (isRecordingPointPending) {
                    isRecordingPointPending = false;
                    recordSinglePoint();
                } else {
                    startTracking();
                }
            } else {
                isStartupPermissionRequest = false;
                isRecordingPointPending = false;
                Log.w(TAG, "Permissions denied.");
                Toast.makeText(this, "Required permissions denied.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Requests disk and location permissions immediately on app launch. */
    private void requestPermissionsOnStartup() {
        isStartupPermissionRequest = true;

        List<String> permissionsNeeded = new ArrayList<>();
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    listPermissionsNeeded.toArray(new String[0]),
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            // Runtime permissions already granted; check file access permission
            checkAndRequestFileAccessStartup();
        }
    }

    private void checkAndRequestFileAccessStartup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
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
        updateUiFromService(); // Refresh color to RED
        Log.d(TAG, "Tracking stopped. Records: " + records.size());
        Toast.makeText(this, R.string.label_end_tracking, Toast.LENGTH_SHORT).show();
    }

    /** Updates the UI with the latest data from the service. */
    private void updateUiFromService() {
        if (!isBound || locationService == null) {
            statusBar.setBackgroundColor(0xFFFF0000); // RED
            return;
        }

        List<JSONObject> records = locationService.getLocationRecords();
        
        // Determine status color and text
        int statusColor;
        String statusText;
        if (!isTracking) {
            statusColor = 0xFFFF0000; // RED
            statusText = "Idle / Stopped";
        } else if (locationService.isPaused()) {
            statusColor = 0xFF0000FF; // BLUE
            statusText = "Logging Paused";
        } else {
            long lastUpdate = locationService.getLastLocationTimeMillis();
            long now = System.currentTimeMillis();
            if (lastUpdate > 0 && (now - lastUpdate) < 15000) { // 15 seconds threshold
                statusColor = 0xFF00FF00; // GREEN
                statusText = "Tracking... (" + records.size() + " fixes)";
            } else {
                statusColor = 0xFFA52A2A; // BROWN (SaddleBrown)
                statusText = "Waiting for GPS...";
            }
        }
        statusBar.setBackgroundColor(statusColor);
        tvStatus.setText(statusText);

        if (!records.isEmpty()) {
            JSONObject last = records.get(records.size() - 1);
            try {
                double lat = last.getDouble("latitude");
                double lon = last.getDouble("longitude");
                tvCoordinates.setText(String.format("Lat: %.6f   Lon: %.6f", lat, lon));
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
                //.format(DateTimeFormatter.ofPattern("yyyy-MM-dd.HHmmss"));
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .replace(":", "-");

        // 2. Prepare user info string (sanitized)
        String infoText = etTrackingInfo.getText().toString().trim();
        String sanitizedInfo = sanitizeFilename(infoText);
        
        String baseName;
        if (!sanitizedInfo.isEmpty()) {
            baseName = "gps_" + fileTimestamp + "_" + sanitizedInfo;
        } else {
            baseName = "gps_" + fileTimestamp;
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
                saveToFile(jsonArray.toString(2), baseName + ".json");
            } catch (JSONException e) {
                Log.e(TAG, "Error formatting JSON", e);
            }
        }

        // 3. Generate and save GPX
        if (saveGpx) {
            String gpxContent = generateGpx(records);
            saveToFile(gpxContent, baseName + ".gpx");
        }

        // 4. Generate and save KML
        if (saveKml) {
            String kmlContent = generateKml(records);
            lastSavedUri = saveToFile(kmlContent, baseName + ".kml");
            lastSavedName = baseName + ".kml";
        } else if (saveGpx) {
            // If KML not saved, use GPX as last saved for sharing
            String gpxContent = generateGpx(records);
            lastSavedUri = saveToFile(gpxContent, baseName + ".gpx");
            lastSavedName = baseName + ".gpx";
        } else if (saveJson) {
            // Fallback to JSON
            try {
                JSONArray jsonArray = new JSONArray();
                for (JSONObject record : records) jsonArray.put(record);
                lastSavedUri = saveToFile(jsonArray.toString(2), baseName + ".json");
                lastSavedName = baseName + ".json";
            } catch (JSONException ignored) {}
        }

        if (lastSavedUri != null) {
            invalidateOptionsMenu(); // Refresh share icon visibility
            etTrackingInfo.setText(""); // Clear for next session
            etTrackingInfo.setEnabled(true); // Re-enable for next session
            ivLockTrackingInfo.setEnabled(true);
            ivLockTrackingInfo.setAlpha(1.0f);
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
     * Writes string content to a file in Vypeensoft/GPS_Location_Logger/ using standard file I/O.
     * @return the Uri of the saved file via FileProvider, or null if failed.
     */
    private Uri saveToFile(String content, String fileName) {
        File dir = new File(Environment.getExternalStorageDirectory(), GPS_FOLDER);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
            }
        }
        
        File file = new File(dir, fileName);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
             java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(fos)) {

            writer.write(content);
            writer.flush();
            Log.i(TAG, "Saved → " + file.getAbsolutePath());
            return androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".provider", file);

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
                sb.append("      <trkpt lat=\"").append(Double.toString(lat)).append("\" lon=\"").append(Double.toString(lon)).append("\">\n");
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
                sb.append("          ").append(Double.toString(lon)).append(",").append(Double.toString(lat)).append(",0\n");
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
        btnPauseTracking.setEnabled(tracking);
        btnEndTracking.setEnabled(tracking);

        if (tracking && isBound && locationService != null) {
            boolean isPaused = locationService.isPaused();
            btnPauseTracking.setText(isPaused ? R.string.label_resume_tracking : R.string.label_pause_tracking);
            btnPauseTracking.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    isPaused ? 0xFF4CAF50 : 0xFF2196F3)); // Green for Resume, Blue for Pause
        } else {
            btnPauseTracking.setText(R.string.label_pause_tracking);
            btnPauseTracking.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2196F3));
        }

        if (cardTrackingInfo != null) {
            // Keep visible at all times to prevent layout shifting
            cardTrackingInfo.setVisibility(View.VISIBLE);
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
