package io.github.robiii7369.chirari;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Runs while "Word on lock screen" is on. Its notification is the lock-screen word card,
 * and it listens for the screen turning off so the next word is ready when the phone is picked up again.
 * (Android only tells running apps about the screen turning off, so this can't be done without it.)
 */
public class WordService extends Service {
    private BroadcastReceiver screenOff;

    @Override
    public void onCreate() {
        super.onCreate();
        screenOff = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                WordClock.onScreenOff(context);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!LockWord.isOn(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = LockWord.build(this);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(LockWord.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(LockWord.NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        WordClock.schedule(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (screenOff != null) unregisterReceiver(screenOff);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
