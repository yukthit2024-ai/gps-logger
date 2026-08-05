package com.example.gpslocationlogger;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TracksListActivity extends AppCompatActivity implements TrackAdapter.TrackActionListener {

    private static final String TAG = "TracksListActivity";
    private static final String GPS_FOLDER = "Vypeensoft/GPS_Location_Logger/tracks";
    private static final int STORAGE_PERMISSION_CODE = 1002;

    private RecyclerView recyclerView;
    private TextView tvEmptyState;
    private TrackAdapter adapter;
    private android.widget.EditText etSearch;
    private List<TrackItem> allTrackList = new ArrayList<>();
    private List<TrackItem> displayTrackList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tracks_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.menu_tracks_list);
        }

        recyclerView = findViewById(R.id.recyclerViewTracks);
        tvEmptyState = findViewById(R.id.tvEmptyState);
        etSearch = findViewById(R.id.etSearch);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TrackAdapter(this, displayTrackList, this);
        recyclerView.setAdapter(adapter);

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterTracks(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        checkPermissionsAndLoadTracks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check permissions again in case user granted them from settings
        if (hasStoragePermission()) {
            loadTracks();
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                   ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkPermissionsAndLoadTracks() {
        if (hasStoragePermission()) {
            loadTracks();
        } else {
            requestStoragePermission();
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.addCategory("android.intent.category.DEFAULT");
                intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadTracks();
            } else {
                showEmptyState("Storage permission denied. Cannot read tracks.");
            }
        }
    }

    private void loadTracks() {
        File dir = new File(Environment.getExternalStorageDirectory(), GPS_FOLDER);
        
        if (!dir.exists()) {
            showEmptyState("Folder does not exist:\n" + dir.getAbsolutePath());
            return;
        }
        if (!dir.isDirectory()) {
            showEmptyState("Path is not a directory:\n" + dir.getAbsolutePath());
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            showEmptyState("Cannot read folder (Permission denied?):\n" + dir.getAbsolutePath());
            return;
        }
        if (files.length == 0) {
            showEmptyState("Folder is empty:\n" + dir.getAbsolutePath());
            return;
        }

        Map<String, TrackItem> trackMap = new HashMap<>();

        for (File file : files) {
            if (file.isFile()) {
                String name = file.getName();
                String baseName = getBaseName(name);
                String extension = getExtension(name);

                if (baseName.isEmpty()) continue;

                TrackItem trackItem = trackMap.get(baseName);
                if (trackItem == null) {
                    trackItem = new TrackItem(baseName);
                    parseTrackItemDetails(trackItem);
                    trackItem.lastModified = file.lastModified();
                    trackMap.put(baseName, trackItem);
                }

                // Keep the latest modified date
                if (file.lastModified() > trackItem.lastModified) {
                    trackItem.lastModified = file.lastModified();
                }

                if (!extension.isEmpty()) {
                    trackItem.extensions.add(extension);
                }
            }
        }

        allTrackList.clear();
        allTrackList.addAll(trackMap.values());

        // Sort descending by timestamp (lexicographically via baseName)
        Collections.sort(allTrackList, (t1, t2) -> t2.baseName.compareTo(t1.baseName));

        filterTracks(etSearch.getText().toString());
    }

    private void filterTracks(String query) {
        displayTrackList.clear();
        if (query == null || query.trim().isEmpty()) {
            displayTrackList.addAll(allTrackList);
        } else {
            String lowerCaseQuery = query.toLowerCase();
            for (TrackItem item : allTrackList) {
                String searchString = item.displayName != null ? item.displayName : item.baseName;
                if (searchString != null && searchString.toLowerCase().contains(lowerCaseQuery)) {
                    displayTrackList.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();

        if (displayTrackList.isEmpty() && !allTrackList.isEmpty()) {
            showEmptyState("No tracks match your search.");
        } else if (displayTrackList.isEmpty()) {
            showEmptyState("No valid tracks found in folder.");
        } else {
            tvEmptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void showEmptyState(String message) {
        tvEmptyState.setText(message);
        tvEmptyState.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    private String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex);
        }
        return "";
    }

    private void parseTrackItemDetails(TrackItem item) {
        String name = item.baseName;
        String tempName = name;
        if (tempName.startsWith("gps_")) {
            tempName = tempName.substring("gps_".length());
        } else if (tempName.startsWith("location_logs_")) {
            tempName = tempName.substring("location_logs_".length());
        }

        // Check if prefixed with timestamp of format YYYY-MM-DD.HHmmss or YYYY-MM-DDThhmmss (17 characters)
        if (tempName.length() >= 17) {
            String potentialTimestamp = tempName.substring(0, 17);
            if (potentialTimestamp.matches("\\d{4}-\\d{2}-\\d{2}[.T]\\d{6}")) {
                String remainder = tempName.substring(17);
                if (remainder.startsWith("_") || remainder.startsWith("-")) {
                    remainder = remainder.substring(1);
                }
                if (!remainder.isEmpty()) {
                    // There is tracking info
                    item.displayName = remainder.replace("_", " ");
                    item.displayTimestamp = formatTimestamp(potentialTimestamp);
                } else {
                    // Only timestamp
                    item.displayName = formatTimestamp(potentialTimestamp);
                    item.displayTimestamp = null;
                }
                return;
            }
        }

        // Fallback to legacy format parsing
        String prefix = "location_logs_";
        if (name.startsWith("gps_")) {
            prefix = "gps_";
        }
        if (name.startsWith(prefix)) {
            String remainder = name.substring(prefix.length());
            int underscoreIdx = remainder.indexOf('_');
            if (underscoreIdx != -1) {
                // There is tracking info
                String timestamp = remainder.substring(0, underscoreIdx);
                String info = remainder.substring(underscoreIdx + 1);
                item.displayName = info.replace("_", " ");
                item.displayTimestamp = formatTimestamp(timestamp);
            } else {
                // Only timestamp
                item.displayName = formatTimestamp(remainder);
                item.displayTimestamp = null;
            }
        } else {
            item.displayName = name;
            item.displayTimestamp = null;
        }
    }

    private String formatTimestamp(String rawTimestamp) {
        // e.g. 2026-05-17T18-15-09.123+05-30 -> 2026-05-17 18:15:09
        // or format YYYY-MM-DD.HHmmss or YYYY-MM-DDThhmmss -> YYYY-MM-DD hh:mm:ss
        if (rawTimestamp.matches("\\d{4}-\\d{2}-\\d{2}[.T]\\d{6}")) {
            String datePart = rawTimestamp.substring(0, 10);
            String timePart = rawTimestamp.substring(11, 17);
            String formattedTime = timePart.substring(0, 2) + ":" + timePart.substring(2, 4) + ":" + timePart.substring(4, 6);
            return datePart + " " + formattedTime;
        }

        String clean = rawTimestamp;
        int dotIdx = clean.indexOf('.');
        if (dotIdx != -1) clean = clean.substring(0, dotIdx);
        int plusIdx = clean.indexOf('+');
        if (plusIdx != -1) clean = clean.substring(0, plusIdx);

        // Replace T with space
        clean = clean.replace("T", " ");
        // In the time part, replace - with :
        int spaceIdx = clean.indexOf(' ');
        if (spaceIdx != -1 && clean.length() > spaceIdx + 1) {
            String datePart = clean.substring(0, spaceIdx);
            String timePart = clean.substring(spaceIdx + 1).replace("-", ":");
            return datePart + " " + timePart;
        }
        return clean;
    }

    @Override
    public void onShareClick(TrackItem trackItem) {
        if (trackItem.isRecordedPoint()) {
            SharePointHelper.showShareOptionsDialog(this, trackItem.baseName, trackItem.displayName, () -> shareTrackFiles(trackItem));
        } else {
            shareTrackFiles(trackItem);
        }
    }

    private void shareTrackFiles(TrackItem trackItem) {
        File dir = new File(Environment.getExternalStorageDirectory(), GPS_FOLDER);
        File gpxFile = new File(dir, trackItem.baseName + ".gpx");
        File kmlFile = new File(dir, trackItem.baseName + ".kml");
        File jsonFile = new File(dir, trackItem.baseName + ".json");

        List<String> availableExtensions = new ArrayList<>();
        List<File> availableFiles = new ArrayList<>();

        if (gpxFile.exists()) {
            availableExtensions.add("GPX (.gpx)");
            availableFiles.add(gpxFile);
        }
        if (kmlFile.exists()) {
            availableExtensions.add("KML (.kml)");
            availableFiles.add(kmlFile);
        }
        if (jsonFile.exists()) {
            availableExtensions.add("JSON (.json)");
            availableFiles.add(jsonFile);
        }

        if (availableFiles.isEmpty()) {
            Toast.makeText(this, "No valid files found for this track.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (availableFiles.size() == 1) {
            shareSingleFile(availableFiles.get(0));
            return;
        }

        CharSequence[] items = availableExtensions.toArray(new CharSequence[0]);
        boolean[] checkedItems = new boolean[items.length];
        // Default to all selected
        for (int i = 0; i < checkedItems.length; i++) {
            checkedItems[i] = true;
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Select formats to share")
                .setMultiChoiceItems(items, checkedItems, (dialogInterface, which, isChecked) -> checkedItems[which] = isChecked)
                .setPositiveButton("Share", (dialogInterface, which) -> {
                    List<File> selectedFiles = new ArrayList<>();
                    for (int i = 0; i < checkedItems.length; i++) {
                        if (checkedItems[i]) {
                            selectedFiles.add(availableFiles.get(i));
                        }
                    }

                    if (selectedFiles.isEmpty()) {
                        Toast.makeText(TracksListActivity.this, "Please select at least one format.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (selectedFiles.size() == 1) {
                        shareSingleFile(selectedFiles.get(0));
                    } else {
                        try {
                            File zippedFile = zipFiles(selectedFiles, trackItem.baseName + ".zip");
                            shareSingleFile(zippedFile);
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to zip files", e);
                            Toast.makeText(TracksListActivity.this, "Failed to pack files for sharing.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(Color.parseColor("#1E88E5"));
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#888EAB"));
    }

    private void shareSingleFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            if (file.getName().endsWith(".zip")) {
                shareIntent.setType("application/zip");
            } else {
                shareIntent.setType("application/octet-stream");
            }
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Track"));
        } catch (Exception e) {
            Log.e(TAG, "Error sharing file: " + file.getName(), e);
            Toast.makeText(this, "Could not share file.", Toast.LENGTH_SHORT).show();
        }
    }

    private File zipFiles(List<File> filesToZip, String zipFileName) throws IOException {
        File zipFile = new File(getExternalCacheDir(), zipFileName);
        if (zipFile.exists()) {
            zipFile.delete();
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            byte[] buffer = new byte[4096];
            for (File file : filesToZip) {
                if (!file.exists()) continue;
                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                    ZipEntry entry = new ZipEntry(file.getName());
                    zos.putNextEntry(entry);
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                    zos.closeEntry();
                }
            }
        }
        return zipFile;
    }

    @Override
    public void onMapClick(TrackItem trackItem) {
        Intent intent = new Intent(this, MapActivity.class);
        intent.putExtra("TRACK_BASENAME", trackItem.baseName);
        intent.putExtra("TRACK_DISPLAY_NAME", trackItem.displayName);
        startActivity(intent);
    }

    @Override
    public void onDeleteClick(TrackItem trackItem) {
        String itemType = trackItem.isRecordedPoint() ? "Recorded Point" : "Track";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete " + itemType)
                .setMessage("Are you sure you want to delete all files for this " + itemType.toLowerCase() + "?")
                .setPositiveButton("Delete", (dialogInterface, which) -> {
                    File dir = new File(Environment.getExternalStorageDirectory(), GPS_FOLDER);
                    boolean deletedAny = false;
                    for (String ext : trackItem.extensions) {
                        File f = new File(dir, trackItem.baseName + ext);
                        if (f.exists() && f.delete()) {
                            deletedAny = true;
                        }
                    }
                    if (deletedAny) {
                        Toast.makeText(TracksListActivity.this, itemType + " deleted", Toast.LENGTH_SHORT).show();
                        loadTracks();
                    } else {
                        Toast.makeText(TracksListActivity.this, "Failed to delete " + itemType.toLowerCase(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(Color.parseColor("#D32F2F"));
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#888EAB"));
    }

    @Override
    public void onRenameClick(TrackItem trackItem) {
        String itemType = trackItem.isRecordedPoint() ? "Point" : "Track";
        
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(getCleanDescriptionForEdit(trackItem));
        input.setSelection(input.getText().length());
        input.setPadding((int) (16 * getResources().getDisplayMetrics().density),
                         (int) (12 * getResources().getDisplayMetrics().density),
                         (int) (16 * getResources().getDisplayMetrics().density),
                         (int) (12 * getResources().getDisplayMetrics().density));
        input.setBackgroundResource(R.drawable.bg_edit_text);
        input.setHint("Enter description here...");
        input.setHintTextColor(Color.parseColor("#888EAB"));
        input.setTextColor(Color.parseColor("#1A1A2E"));
        input.setTextSize(16);
        
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int marginPx = (int) (20 * getResources().getDisplayMetrics().density);
        params.leftMargin = marginPx;
        params.rightMargin = marginPx;
        params.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        params.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
        input.setLayoutParams(params);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Rename " + itemType)
                .setMessage("Enter new description:")
                .setView(container)
                .setPositiveButton("Rename", (dialogInterface, which) -> {
                    String newName = input.getText().toString().trim();
                    String newBaseName = createNewBaseName(trackItem.baseName, newName);
                    
                    if (newBaseName.equals(trackItem.baseName)) {
                        return; // No change
                    }
                    
                    File dir = new File(Environment.getExternalStorageDirectory(), GPS_FOLDER);
                    
                    // Check if target file already exists
                    boolean destinationExists = false;
                    for (String ext : trackItem.extensions) {
                        File newFile = new File(dir, newBaseName + ext);
                        if (newFile.exists()) {
                            destinationExists = true;
                            break;
                        }
                    }
                    
                    if (destinationExists) {
                        Toast.makeText(TracksListActivity.this, "A " + itemType.toLowerCase() + " with that name already exists.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    boolean success = true;
                    int renamedCount = 0;
                    for (String ext : trackItem.extensions) {
                        File oldFile = new File(dir, trackItem.baseName + ext);
                        File newFile = new File(dir, newBaseName + ext);
                        if (oldFile.exists()) {
                            if (oldFile.renameTo(newFile)) {
                                renamedCount++;
                            } else {
                                success = false;
                            }
                        }
                    }
                    
                    if (renamedCount > 0) {
                        Toast.makeText(TracksListActivity.this, itemType + " renamed successfully", Toast.LENGTH_SHORT).show();
                        loadTracks();
                    } else {
                        Toast.makeText(TracksListActivity.this, "Failed to rename " + itemType.toLowerCase(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(Color.parseColor("#0F3460"));
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#888EAB"));
    }

    private String getCleanDescriptionForEdit(TrackItem item) {
        String displayName = item.displayName != null ? item.displayName : item.baseName;
        if (item.isRecordedPoint()) {
            if (displayName.startsWith("Recorded Point ")) {
                return displayName.substring("Recorded Point ".length());
            } else if ("Recorded Point".equals(displayName) || displayName.contains("Recorded_Point")) {
                return "";
            }
        } else {
            if (item.displayTimestamp == null && (item.baseName.startsWith("gps_") || item.baseName.startsWith("location_logs_"))) {
                return "";
            }
        }
        return displayName;
    }

    private String createNewBaseName(String originalBaseName, String newName) {
        String sanitizedName = sanitizeFilename(newName);
        
        // 1. Handle Recorded Point
        if (originalBaseName.contains("_Recorded_Point")) {
            int index = originalBaseName.indexOf("_Recorded_Point");
            String prefixPart = originalBaseName.substring(0, index);
            if (sanitizedName.isEmpty()) {
                return prefixPart + "_Recorded_Point";
            } else {
                return prefixPart + "_Recorded_Point_" + sanitizedName;
            }
        }
        
        // 2. Handle Track with Timestamp
        String tempName = originalBaseName;
        String prefix = "";
        if (tempName.startsWith("gps_")) {
            prefix = "gps_";
            tempName = tempName.substring("gps_".length());
        } else if (tempName.startsWith("location_logs_")) {
            prefix = "location_logs_";
            tempName = tempName.substring("location_logs_".length());
        }
        
        if (!prefix.isEmpty()) {
            int firstUnderscore = tempName.indexOf('_');
            String timestampPart;
            if (firstUnderscore != -1) {
                timestampPart = tempName.substring(0, firstUnderscore);
            } else {
                timestampPart = tempName;
            }
            String prefixPart = prefix + timestampPart;
            if (sanitizedName.isEmpty()) {
                return prefixPart;
            } else {
                return prefixPart + "_" + sanitizedName;
            }
        }
        
        // 3. Fallback for custom/legacy names
        return sanitizedName.isEmpty() ? originalBaseName : sanitizedName;
    }

    private String sanitizeFilename(String input) {
        if (input == null || input.isEmpty()) return "";
        return input.replaceAll("\\s+", "_")
                    .replaceAll("[^a-zA-Z0-9_-]", "");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
