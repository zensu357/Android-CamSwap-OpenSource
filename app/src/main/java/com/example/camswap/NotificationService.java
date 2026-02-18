package com.example.camswap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

public class NotificationService extends Service {
    private static final String CHANNEL_ID = "camswap_control_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static final String ACTION_NEXT = "com.example.camswap.ACTION_CAMSWAP_NEXT";
    private static final String ACTION_EXIT = "com.example.camswap.ACTION_CAMSWAP_EXIT";
    private static final String ACTION_ROTATE = "com.example.camswap.ACTION_CAMSWAP_ROTATE";

    private ConfigManager configManager;
    private int currentRotationOffset = 0;

    private BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            android.util.Log.d("Camswap_NOTIF", "收到操作指令: " + action);
            if (ACTION_EXIT.equals(action)) {
                stopSelf();
            } else if (ACTION_ROTATE.equals(action)) {
                // 循环切换旋转偏移: 0 -> 90 -> 180 -> 270 -> 0
                currentRotationOffset = (currentRotationOffset + 90) % 360;
                if (configManager != null) {
                    configManager.setInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, currentRotationOffset);
                }
                // 更新通知显示
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, buildNotification());
                }
                android.util.Log.d("Camswap_NOTIF", "旋转偏移已切换为: " + currentRotationOffset + "°");
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(com.example.camswap.utils.LocaleHelper.INSTANCE.onAttach(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        configManager = new ConfigManager();
        configManager.setContext(this);
        currentRotationOffset = configManager.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_EXIT);
        filter.addAction(ACTION_ROTATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(controlReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(controlReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        String rotationLabel = getString(R.string.notif_rotate_label) + currentRotationOffset + "°";

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_rotate_offset) + currentRotationOffset + "°")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        builder.addAction(new Notification.Action.Builder(null, getString(R.string.notif_action_next),
                getNextPendingIntent()).build());

        builder.addAction(new Notification.Action.Builder(null, rotationLabel,
                getRotatePendingIntent()).build());

        builder.addAction(new Notification.Action.Builder(null, getString(R.string.notif_action_exit),
                getPendingIntent(ACTION_EXIT)).build());

        return builder.build();
    }

    private PendingIntent getPendingIntent(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent getNextPendingIntent() {
        Intent intent = new Intent(ACTION_NEXT);
        return PendingIntent.getBroadcast(this, ACTION_NEXT.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent getRotatePendingIntent() {
        Intent intent = new Intent(ACTION_ROTATE);
        return PendingIntent.getBroadcast(this, ACTION_ROTATE.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.enableLights(false);
            channel.enableVibration(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
