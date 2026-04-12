package com.example.gpslocationlogger;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * SettingsActivity — App Options Screen
 *
 * Persists user preferences via SharedPreferences.
 * Currently exposes: Logging Frequency (interval between GPS fixes).
 */
public class SettingsActivity extends AppCompatActivity {

    /** SharedPreferences file name — shared with MainActivity. */
    public static final String PREFS_NAME = "gps_logger_prefs";

    /** Key for the logging interval in milliseconds. */
    public static final String KEY_INTERVAL_MS = "logging_interval_ms";

    /** Default interval: 5 seconds. */
    public static final long DEFAULT_INTERVAL_MS = 5_000L;

    private RadioGroup rgFrequency;
    private TextView   tvIntervalPreview;
    private SharedPreferences prefs;

    // Maps each RadioButton ID to its interval in milliseconds
    private static final int[] RADIO_IDS = {
            R.id.rb5s,
            R.id.rb10s,
            R.id.rb30s,
            R.id.rb1m,
            R.id.rb5m
    };

    private static final long[] INTERVALS_MS = {
            5_000L,
            10_000L,
            30_000L,
            60_000L,
            300_000L
    };

    private static final String[] INTERVAL_LABELS = {
            "Every 5 seconds",
            "Every 10 seconds",
            "Every 30 seconds",
            "Every 1 minute",
            "Every 5 minutes"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Show back arrow in the toolbar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Options");
        }

        rgFrequency      = findViewById(R.id.rgFrequency);
        tvIntervalPreview = findViewById(R.id.tvIntervalPreview);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Restore saved preference and pre-select the matching radio button
        long savedInterval = prefs.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        selectRadioForInterval(savedInterval);
        updatePreview(savedInterval);

        // Save immediately when user changes selection
        rgFrequency.setOnCheckedChangeListener((group, checkedId) -> {
            long interval = intervalForRadioId(checkedId);
            prefs.edit().putLong(KEY_INTERVAL_MS, interval).apply();
            updatePreview(interval);
        });
    }

    /** Selects the radio button that matches the stored interval. */
    private void selectRadioForInterval(long intervalMs) {
        for (int i = 0; i < INTERVALS_MS.length; i++) {
            if (INTERVALS_MS[i] == intervalMs) {
                rgFrequency.check(RADIO_IDS[i]);
                return;
            }
        }
        // Fallback to default (5 s)
        rgFrequency.check(R.id.rb5s);
    }

    /** Returns the interval in ms corresponding to a radio button ID. */
    private long intervalForRadioId(int radioId) {
        for (int i = 0; i < RADIO_IDS.length; i++) {
            if (RADIO_IDS[i] == radioId) {
                return INTERVALS_MS[i];
            }
        }
        return DEFAULT_INTERVAL_MS;
    }

    /** Returns the human-readable label for a given interval in ms. */
    private String labelForInterval(long intervalMs) {
        for (int i = 0; i < INTERVALS_MS.length; i++) {
            if (INTERVALS_MS[i] == intervalMs) {
                return INTERVAL_LABELS[i];
            }
        }
        return "Every 5 seconds";
    }

    /** Updates the summary text below the RadioGroup. */
    private void updatePreview(long intervalMs) {
        tvIntervalPreview.setText("GPS fix logged: " + labelForInterval(intervalMs).toLowerCase());
    }

    /** Handle the toolbar back arrow. */
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
