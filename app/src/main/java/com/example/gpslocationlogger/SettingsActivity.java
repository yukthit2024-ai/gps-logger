package com.example.gpslocationlogger;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

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

    /** Keys for format selection. */
    public static final String KEY_SAVE_JSON = "save_json";
    public static final String KEY_SAVE_GPX  = "save_gpx";
    public static final String KEY_SAVE_KML  = "save_kml";

    /** Default interval: 5 seconds. */
    public static final long DEFAULT_INTERVAL_MS = 5_000L;

    private RadioGroup rgFrequency;
    private TextView   tvIntervalPreview;
    private CheckBox   cbJson, cbGpx, cbKml;
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
        cbJson           = findViewById(R.id.cbJson);
        cbGpx            = findViewById(R.id.cbGpx);
        cbKml            = findViewById(R.id.cbKml);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // ── 1. Logging Frequency ──
        long savedInterval = prefs.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        selectRadioForInterval(savedInterval);
        updatePreview(savedInterval);

        rgFrequency.setOnCheckedChangeListener((group, checkedId) -> {
            long interval = intervalForRadioId(checkedId);
            prefs.edit().putLong(KEY_INTERVAL_MS, interval).apply();
            updatePreview(interval);
        });

        // ── 2. Format Selection ──
        cbJson.setChecked(prefs.getBoolean(KEY_SAVE_JSON, true));
        cbGpx.setChecked(prefs.getBoolean(KEY_SAVE_GPX, true));
        cbKml.setChecked(prefs.getBoolean(KEY_SAVE_KML, true));

        cbJson.setOnCheckedChangeListener((v, checked) -> handleFormatChange(KEY_SAVE_JSON, checked, cbJson));
        cbGpx.setOnCheckedChangeListener((v, checked) -> handleFormatChange(KEY_SAVE_GPX, checked, cbGpx));
        cbKml.setOnCheckedChangeListener((v, checked) -> handleFormatChange(KEY_SAVE_KML, checked, cbKml));
    }

    /**
     * Saves the format preference.
     * Prevents unchecking if it's the last one selected.
     */
    private void handleFormatChange(String key, boolean isChecked, CheckBox checkBox) {
        if (!isChecked && !cbJson.isChecked() && !cbGpx.isChecked() && !cbKml.isChecked()) {
            // Revert: can't uncheck the last one
            checkBox.setChecked(true);
            Toast.makeText(this, "At least one format must be selected.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putBoolean(key, isChecked).apply();
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
