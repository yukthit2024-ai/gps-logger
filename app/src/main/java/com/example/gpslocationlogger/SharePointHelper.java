package com.example.gpslocationlogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SharePointHelper {
    private static final String TAG = "SharePointHelper";
    private static final String GPS_FOLDER = "Vypeensoft/GPS_Location_Logger/tracks";

    /**
     * Attempts to parse coordinates from the saved files (.gpx, .kml, .json) matching baseName.
     * Returns an array [latitude, longitude] if successful, or null otherwise.
     */
    public static double[] tryExtractCoordinates(String baseName) {
        File dir = new File(Environment.getExternalStorageDirectory(), GPS_FOLDER);
        File gpxFile = new File(dir, baseName + ".gpx");
        File kmlFile = new File(dir, baseName + ".kml");
        File jsonFile = new File(dir, baseName + ".json");

        File fileToParse = null;
        String format = null;

        if (gpxFile.exists()) {
            fileToParse = gpxFile;
            format = "GPX";
        } else if (kmlFile.exists()) {
            fileToParse = kmlFile;
            format = "KML";
        } else if (jsonFile.exists()) {
            fileToParse = jsonFile;
            format = "JSON";
        }

        if (fileToParse == null) {
            Log.d(TAG, "No file found to extract coordinates for " + baseName);
            return null;
        }

        try {
            FileInputStream fis = new FileInputStream(fileToParse);
            byte[] data = new byte[(int) fileToParse.length()];
            int bytesRead = fis.read(data);
            fis.close();

            if (bytesRead <= 0) {
                return null;
            }

            String fileStr = new String(data, 0, bytesRead, "UTF-8");

            if ("GPX".equals(format)) {
                Pattern p = Pattern.compile("<(trkpt|wpt) lat=\"([^\"]+)\" lon=\"([^\"]+)\">");
                Matcher m = p.matcher(fileStr);
                if (m.find()) {
                    double lat = Double.parseDouble(m.group(2));
                    double lon = Double.parseDouble(m.group(3));
                    return new double[]{lat, lon};
                }
            } else if ("KML".equals(format)) {
                // KML coordinates format: lon,lat,alt
                Pattern p = Pattern.compile("([\\d\\.-]+),([\\d\\.-]+),0");
                Matcher m = p.matcher(fileStr);
                if (m.find()) {
                    double lon = Double.parseDouble(m.group(1));
                    double lat = Double.parseDouble(m.group(2));
                    return new double[]{lat, lon};
                }
            } else if ("JSON".equals(format)) {
                JSONArray jsonArray = new JSONArray(fileStr);
                if (jsonArray.length() > 0) {
                    JSONObject obj = jsonArray.getJSONObject(0);
                    double lat = obj.getDouble("latitude");
                    double lon = obj.getDouble("longitude");
                    return new double[]{lat, lon};
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting coordinates from " + fileToParse.getName(), e);
        }

        return null;
    }

    /**
     * Shows a dialog with options for a single point, or falls back to file sharing if not a single point/parsing fails.
     */
    public static void showShareOptionsDialog(final Activity activity, final String baseName, final String displayName, final Runnable onShareFiles) {
        final double[] coords = tryExtractCoordinates(baseName);
        if (coords == null) {
            // Fallback directly to file sharing
            onShareFiles.run();
            return;
        }

        final double lat = coords[0];
        final double lon = coords[1];

        CharSequence[] options = new CharSequence[] {
                "📍 Show on Google Maps",
                "🔗 Copy Google Maps URL",
                "📋 Copy Lat/Lon Values",
                "📁 Share Location File(s)"
        };

        AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Recorded Point Actions")
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which == 0) {
                            // Show on Google Maps
                            String cleanName = displayName;
                            if (cleanName != null) {
                                if (cleanName.startsWith("Recorded Point ")) {
                                    cleanName = cleanName.substring("Recorded Point ".length());
                                } else if (cleanName.equals("Recorded Point")) {
                                    cleanName = "";
                                }
                            }
                            String label = (cleanName != null && !cleanName.trim().isEmpty()) ? "[Recorded Point] " + cleanName : "[Recorded Point]";
                            String uriStr = String.format(java.util.Locale.US, "geo:%f,%f?q=%f,%f(%s)", lat, lon, lat, lon, label);
                            Intent mapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriStr));
                            try {
                                activity.startActivity(mapIntent);
                            } catch (Exception e) {
                                // Fallback to opening Google Maps in web browser
                                String webUriStr = String.format(java.util.Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lon);
                                Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(webUriStr));
                                try {
                                    activity.startActivity(webIntent);
                                } catch (Exception ex) {
                                    Toast.makeText(activity, "No application found to open map.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if (which == 1) {
                            // Copy Google Maps URL
                            String mapUrl = String.format("https://maps.google.com/?q=%.6f,%.6f", lat, lon);
                            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText("Google Maps URL", mapUrl);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(activity, "Google Maps URL copied to clipboard", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 2) {
                            // Copy Lat/Lon Values
                            String latLonStr = String.format("%.6f, %.6f", lat, lon);
                            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText("Coordinates", latLonStr);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(activity, "Coordinates copied to clipboard", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 3) {
                            // Share Location File(s)
                            onShareFiles.run();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#888EAB"));
    }
}
