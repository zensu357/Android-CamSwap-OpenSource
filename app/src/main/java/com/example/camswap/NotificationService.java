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

        // 使用 Notification.Action 添加按钮 (简单样式)
        // 也可以使用 RemoteViews 做更复杂的布局，这里为了兼容性使用 Action

        String rotationLabel = "旋转:" + currentRotationOffset + "°";

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Camswap")
                .setContentText("旋转偏移: " + currentRotationOffset + "°")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true);

        // 添加操作按钮
        // 由于权限限制，无法准确传递复杂指令，仅保留“切换视频”作为核心功能
        // 新增“下一条”按钮，点击后发送切换指令
        // "下一条" button directly calls Provider via PendingIntent to avoid broadcast
        // self-loop
        builder.addAction(new Notification.Action.Builder(null, "下一条",
                getNextPendingIntent()).build());

        builder.addAction(new Notification.Action.Builder(null, rotationLabel,
                getRotatePendingIntent()).build());

        builder.addAction(new Notification.Action.Builder(null, "退出",
                getPendingIntent(ACTION_EXIT)).build());

        return builder.build();
    }

    private PendingIntent getPendingIntent(String action) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * "下一条"按钮的 PendingIntent：发送全局广播（不限 package），
     * 让 HookMain 中注册的 BroadcastReceiver 接收并处理切换逻辑。
     * NotificationService 自身不注册 ACTION_NEXT，因此不会形成自循环。
     */
    private PendingIntent getNextPendingIntent() {
        Intent intent = new Intent(ACTION_NEXT);
        // 不设置 package，让广播能被其他进程（目标App中的HookMain）接收
        return PendingIntent.getBroadcast(this, ACTION_NEXT.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 旋转广播也用全局广播，让 Hook 进程能接收并 forceReload 配置。
     */
    private PendingIntent getRotatePendingIntent() {
        Intent intent = new Intent(ACTION_ROTATE);
        // 不限制 package，让 NotificationService 自身和 Hook 进程都能收到
        return PendingIntent.getBroadcast(this, ACTION_ROTATE.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Camswap控制服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示Camswap视频切换控制");
            channel.enableLights(false);
            channel.enableVibration(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
