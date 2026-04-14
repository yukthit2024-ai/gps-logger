package com.example.gpslocationlogger;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView tvAboutContent = findViewById(R.id.tvAboutContent);
        String buildInfo = "GPS Location Logger\n" +
                "Timestamp: " + BuildConfig.BUILD_TIMESTAMP + "\n" +
                "Commit: " + BuildConfig.GIT_SHA + "\n" +
                "Full SHA: " + BuildConfig.GIT_SHA_FULL + "\n" +
//                "Tag: " + BuildConfig.GIT_TAG + "\n\n" +
                getString(R.string.about_description);
        tvAboutContent.setText(buildInfo);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.menu_about);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
