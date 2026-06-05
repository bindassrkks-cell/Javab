package com.zero.xploid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "Device boot completed, starting service...");
            
            try {
                Intent serviceIntent = new Intent(context, MainService.class);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                
                Log.d("BootReceiver", "Service started successfully");
                
            } catch (Exception e) {
                Log.e("BootReceiver", "Error starting service: " + e.getMessage());
            }
        }
    }
}