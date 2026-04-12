package com.example.gpslocationlogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * LocationService — Foreground Service for Background GPS Tracking
 *
 * This service runs independently of the MainActivity lifecycle, allowing
 * tracking to continue even when the app is minimized or the screen is off.
 *
 * It provides:
 * 1. Background location updates via FusedLocationProviderClient.
 * 2. In-memory storage of location fixes.
 * 3. A persistent Foreground Notification required by Android.
 */
public class LocationService extends Service {

    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "gps_logger_channel_id";
    private static final int NOTIFICATION_ID = 12345;

    private final IBinder binder = new LocalBinder();

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private final List<JSONObject> locationRecords = new ArrayList<>();

    private boolean isTracking = false;

    /**
     * Interface for MainActivity to bind and get live data.
     */
    public class LocalBinder extends Binder {
        LocationService getService() {
            return LocationService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate()");
        createNotificationChannel();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand()");

        if (!isTracking) {
            startForeground(NOTIFICATION_ID, getNotification("Starting tracking..."));
            startLocationUpdates();
            isTracking = true;
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startLocationUpdates() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        long intervalMs = prefs.getLong(SettingsActivity.KEY_INTERVAL_MS, SettingsActivity.DEFAULT_INTERVAL_MS);

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .build();

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permission for location updates", e);
            stopSelf();
        }
    }

    private void handleNewLocation(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        String time = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        try {
            JSONObject record = new JSONObject();
            record.put("latitude", lat);
            record.put("longitude", lon);
            record.put("timestamp", time);
            locationRecords.add(record);

            // Update notification with count
            updateNotification("Tracking active: " + locationRecords.size() + " points recorded.");

            // Notify activity if it's listening? 
            // In a simple Binder setup, MainActivity will pull this data.
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GPS Logger Tracking",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification getNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Location Logger")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_settings) // Reusing settings icon for now
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, getNotification(contentText));
        }
    }

    public List<JSONObject> getLocationRecords() {
        return new ArrayList<>(locationRecords);
    }

    public void stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
        stopForeground(true);
        stopSelf();
        isTracking = false;
        locationRecords.clear();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy()");
        if (isTracking) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }
}
