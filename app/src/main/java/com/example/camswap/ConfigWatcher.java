package com.example.camswap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import com.example.camswap.utils.LogUtil;
import com.example.camswap.utils.VideoManager;

/**
 * Watches for configuration changes via ContentObserver, FileObserver, and
 * BroadcastReceiver, then notifies via {@link Callback}.
 */
public final class ConfigWatcher {

    public interface Callback {
        void onMediaSourceChanged();

        void onRotationChanged(int degrees);
    }

    private final Callback callback;
    private android.database.ContentObserver configObserver;
    private FileObserver configFileObserver;

    public ConfigWatcher(Callback callback) {
        this.callback = callback;
    }

    /**
     * Register all observers and receivers.
     * Must be called from a thread with a Looper (typically main thread).
     */
    public void init(final Context context) {
        if (configObserver != null)
            return; // already initialized

        LogUtil.log("【CS】正在初始化配置监听器");
        configObserver = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                LogUtil.log("【CS】通过 ContentProvider 监听到配置变更");
                VideoManager.getConfig().forceReload();
                VideoManager.updateVideoPath(false);
                callback.onMediaSourceChanged();
            }
        };

        boolean observerRegistered = false;
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.example.camswap.provider/config");
            context.getContentResolver().registerContentObserver(uri, true, configObserver);
            observerRegistered = true;
        } catch (Exception e) {
            LogUtil.log("【CS】注册配置监听器失败: " + e);
        }

        // Fallback: FileObserver when Provider unavailable
        if (!observerRegistered) {
            LogUtil.log("【CS】Provider 不可用，启用 FileObserver 监听配置文件");
            try {
                String configDir = ConfigManager.DEFAULT_CONFIG_DIR;
                configFileObserver = new FileObserver(configDir,
                        FileObserver.MODIFY | FileObserver.CREATE | FileObserver.MOVED_TO) {
                    @Override
                    public void onEvent(int event, String path) {
                        if (path != null && path.endsWith(".json")) {
                            LogUtil.log("【CS】检测到配置文件变更: " + path);
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                VideoManager.getConfig().forceReload();
                                VideoManager.updateVideoPath(false);
                                callback.onMediaSourceChanged();
                            }, 200);
                        }
                    }
                };
                configFileObserver.startWatching();
                LogUtil.log("【CS】FileObserver 启动成功，监控目录: " + configDir);
            } catch (Exception e) {
                LogUtil.log("【CS】FileObserver 启动失败: " + e);
            }

            // Active Config Request
            LogUtil.log("【CS】主动请求配置广播...");
            new Handler(Looper.getMainLooper()).postDelayed(() -> VideoManager.getConfig().requestConfig(context),
                    1000);
        }

        // BroadcastReceiver for control signals
        registerBroadcastReceiver(context);
    }

    private void registerBroadcastReceiver(final Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    LogUtil.log("【CS】收到广播指令: " + action);

                    if (ConfigManager.ACTION_UPDATE_CONFIG.equals(action)) {
                        handleConfigUpdate(intent);
                    } else if ("com.example.camswap.ACTION_CAMSWAP_NEXT".equals(action)) {
                        handleNextVideo();
                    } else if ("com.example.camswap.ACTION_CAMSWAP_ROTATE".equals(action)) {
                        handleRotate();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConfigManager.ACTION_UPDATE_CONFIG);
            filter.addAction("com.example.camswap.ACTION_CAMSWAP_NEXT");
            filter.addAction("com.example.camswap.ACTION_CAMSWAP_ROTATE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            LogUtil.log("【CS】广播接收器注册成功");
        } catch (Exception e) {
            LogUtil.log("【CS】注册广播接收器失败: " + e);
        }
    }

    private void handleConfigUpdate(Intent intent) {
        ConfigManager config = VideoManager.getConfig();
        String configJson = intent.getStringExtra(ConfigManager.EXTRA_CONFIG_JSON);
        if (configJson == null)
            return;

        // Snapshot old values
        String oldVideo = config.getString(ConfigManager.KEY_SELECTED_VIDEO, "");
        String oldImage = config.getString(ConfigManager.KEY_SELECTED_IMAGE, "");
        String oldMode = config.getString("replace_mode", "video");
        boolean oldFpd = config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
        int oldRotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);

        config.updateConfigFromJSON(configJson);

        // Snapshot new values
        String newVideo = config.getString(ConfigManager.KEY_SELECTED_VIDEO, "");
        String newImage = config.getString(ConfigManager.KEY_SELECTED_IMAGE, "");
        String newMode = config.getString("replace_mode", "video");
        boolean newFpd = config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
        int newRotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);

        boolean mediaChanged = !oldVideo.equals(newVideo) ||
                !oldImage.equals(newImage) ||
                !oldMode.equals(newMode) ||
                (oldFpd != newFpd);

        if (mediaChanged) {
            // Handle Binder-based video file transfer
            if (config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false)) {
                extractVideoFromBinder(intent);
            }
            VideoManager.updateVideoPath(false);
            callback.onMediaSourceChanged();
            LogUtil.log("【CS】收到配置更新且媒体源发生变化，已应用并重启播放器");
        } else if (oldRotation != newRotation) {
            LogUtil.log("【CS】收到配置更新，仅旋转偏移变更: " + newRotation + "°");
            callback.onRotationChanged(newRotation);
        } else {
            LogUtil.log("【CS】收到配置更新，核心媒体参数无变化，忽略重启");
        }
    }

    private void extractVideoFromBinder(Intent intent) {
        android.os.Bundle bundle = intent.getBundleExtra("video_bundle");
        if (bundle == null) {
            LogUtil.log("【CS】并没有找到 video_bundle 额外数据");
            return;
        }
        LogUtil.log("【CS】成功获取到 video_bundle");
        android.os.IBinder binder = bundle.getBinder("video_binder");
        if (binder == null) {
            LogUtil.log("【CS】bundle.getBinder 返回 null");
            return;
        }
        LogUtil.log("【CS】提取到 video_binder，开始 transact");
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            boolean success = binder.transact(1, data, reply, 0);
            LogUtil.log("【CS】transact 结果: " + success);
            reply.readException();
            int hasFd = reply.readInt();
            LogUtil.log("【CS】reply 中有无 Fd 标志: " + hasFd);
            if (hasFd != 0) {
                android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                if (pfd != null) {
                    LogUtil.log("【CS】成功利用 FD 调用 copyToPrivateDir");
                    VideoManager.copyToPrivateDir(pfd);
                    pfd.close();
                } else {
                    LogUtil.log("【CS】创建 PFD 失败: null");
                }
            }
        } catch (Exception e) {
            LogUtil.log("【CS】从 Binder 获取 FD 失败: " + e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void handleNextVideo() {
        if (!VideoManager.isProviderAvailable()) {
            VideoManager.switchVideo(true);
            callback.onMediaSourceChanged();
        } else {
            if (VideoManager.switchVideo(true)) {
                callback.onMediaSourceChanged();
            }
        }
    }

    private void handleRotate() {
        VideoManager.getConfig().forceReload();
        int newRotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        LogUtil.log("【CS】旋转偏移已更新: " + newRotation + "°");
        callback.onRotationChanged(newRotation);
    }
}
