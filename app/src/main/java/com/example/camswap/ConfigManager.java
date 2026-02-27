package com.example.camswap;

import android.os.Environment;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

public class ConfigManager {
    public static final String CONFIG_FILE_NAME = "cs_config.json";
    public static final String DEFAULT_CONFIG_DIR;
    static {
        String path;
        try {
            path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/";
        } catch (Throwable e) {
            path = "/sdcard/DCIM/Camera1/";
        }
        DEFAULT_CONFIG_DIR = path;
    }

    // Config Keys
    public static final String KEY_DISABLE_MODULE = "disable_module";
    public static final String KEY_PLAY_VIDEO_SOUND = "play_video_sound";
    public static final String KEY_FORCE_PRIVATE_DIR = "force_private_dir";
    public static final String KEY_DISABLE_TOAST = "disable_toast";
    public static final String KEY_ENABLE_RANDOM_PLAY = "enable_random_play";
    public static final String KEY_TARGET_PACKAGES = "target_packages";
    public static final String KEY_SELECTED_VIDEO = "selected_video";
    public static final String KEY_ORIGINAL_VIDEO_NAME = "original_video_name";
    public static final String KEY_SELECTED_IMAGE = "selected_image";
    public static final String KEY_ENABLE_MIC_HOOK = "enable_mic_hook";
    public static final String KEY_MIC_HOOK_MODE = "mic_hook_mode"; // "mute" | "replace" | "video_sync"
    public static final String KEY_SELECTED_AUDIO = "selected_audio"; // 音频文件名
    public static final String MIC_MODE_MUTE = "mute";
    public static final String MIC_MODE_REPLACE = "replace";
    public static final String MIC_MODE_VIDEO_SYNC = "video_sync";
    public static final String KEY_VIDEO_ROTATION_OFFSET = "video_rotation_offset"; // 视频旋转偏移角度
    public static final String KEY_ENABLE_PHOTO_FAKE = "enable_photo_fake"; // 启用拍照替换 (动态防御)

    // Broadcast Actions
    public static final String ACTION_UPDATE_CONFIG = "com.example.camswap.ACTION_UPDATE_CONFIG";
    public static final String ACTION_REQUEST_CONFIG = "com.example.camswap.ACTION_REQUEST_CONFIG";
    public static final String EXTRA_CONFIG_JSON = "config_json";

    // Fallback switch
    public static boolean ENABLE_LEGACY_FILE_ACCESS = true;

    private JSONObject configData;
    private long lastLoadedTime = 0;
    private android.content.Context context; // Context for remote loading
    private boolean skipProviderReload = false;

    public ConfigManager() {
        this(true);
    }

    public ConfigManager(boolean initReload) {
        if (initReload) {
            reload();
        }
    }

    public void setSkipProviderReload(boolean skip) {
        this.skipProviderReload = skip;
    }

    public void setContext(android.content.Context context) {
        this.context = context;
        reload(); // Reload with context
    }

    public JSONObject getConfigData() {
        return configData;
    }

    private long lastReloadTime = 0;
    private static final long MIN_RELOAD_INTERVAL_MS = 1000; // 1 second debounce

    public void reload() {
        long now = System.currentTimeMillis();
        if (now - lastReloadTime < MIN_RELOAD_INTERVAL_MS) {
            // Skip reload if too frequent
            return;
        }
        lastReloadTime = now;

        boolean providerSuccess = false;
        if (context != null && !skipProviderReload) {
            providerSuccess = reloadFromProvider();
        }

        if (!providerSuccess && ENABLE_LEGACY_FILE_ACCESS) {
            reloadFromFile();
        }
    }

    /**
     * 强制重新加载配置，忽略防抖时间限制和文件修改时间检查。
     * 用于 ContentObserver.onChange() 等需要立即读取最新配置的场景。
     */
    public void forceReload() {
        lastReloadTime = 0; // 重置防抖
        lastLoadedTime = 0; // 重置文件时间戳，强制重读文件
        reload();
    }

    private boolean reloadFromProvider() {
        android.net.Uri uri = android.net.Uri.parse("content://com.example.camswap.provider/config");
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null) {
                JSONObject newConfig = new JSONObject();
                while (cursor.moveToNext()) {
                    String key = cursor.getString(0);
                    String valueStr = cursor.getString(1);
                    String type = cursor.getString(2);

                    try {
                        if ("boolean".equals(type)) {
                            newConfig.put(key, Boolean.parseBoolean(valueStr));
                        } else if ("int".equals(type)) {
                            newConfig.put(key, Integer.parseInt(valueStr));
                        } else if ("long".equals(type)) {
                            newConfig.put(key, Long.parseLong(valueStr));
                        } else if ("json_array".equals(type)) {
                            newConfig.put(key, new JSONArray(valueStr));
                        } else {
                            newConfig.put(key, valueStr);
                        }
                    } catch (Exception e) {
                        newConfig.put(key, valueStr);
                    }
                }

                if (newConfig.length() > 0) {
                    configData = newConfig;
                    com.example.camswap.utils.LogUtil.log("【CS】配置已通过 ContentProvider 重新加载: " + configData);
                    return true;
                } else {
                    com.example.camswap.utils.LogUtil
                            .log("【CS】配置 Provider 返回的 Cursor 为空 (0 行), URI: " + uri + "，降级到文件读取");
                    com.example.camswap.utils.LogUtil
                            .log("【CS】Reload trigger stack: " + android.util.Log.getStackTraceString(new Throwable()));
                }
            } else {
                com.example.camswap.utils.LogUtil.log("【CS】配置 Provider 返回的 Cursor 为空, URI: " + uri);
                com.example.camswap.utils.LogUtil
                        .log("【CS】Reload trigger stack: " + android.util.Log.getStackTraceString(new Throwable()));
            }
        } catch (Exception e) {
            com.example.camswap.utils.LogUtil.log("【CS】配置 Provider 错误: " + e);
        }
        return false;
    }

    /**
     * Request config from host app via broadcast.
     * Useful for cold start of target app when provider/file is inaccessible.
     */
    public void requestConfig(Context context) {
        try {
            android.content.Intent intent = new android.content.Intent(ACTION_REQUEST_CONFIG);
            intent.setPackage("com.example.camswap"); // Explicit intent to wake up host receiver
            context.sendBroadcast(intent);
            com.example.camswap.utils.LogUtil.log("【CS】已发送配置请求广播 config request broadcast sent");
        } catch (Exception e) {
            com.example.camswap.utils.LogUtil.log("【CS】发送配置请求广播失败: " + e);
        }
    }

    /**
     * Send current config via broadcast.
     */
    public void sendConfigBroadcast(Context context) {
        try {
            android.content.Intent intent = new android.content.Intent(ACTION_UPDATE_CONFIG);
            intent.putExtra(EXTRA_CONFIG_JSON, configData.toString());

            if (getBoolean(KEY_FORCE_PRIVATE_DIR, false)) {
                String videoName = getString(KEY_SELECTED_VIDEO, "Cam.mp4");
                File videoFile = null;
                if (videoName != null && !videoName.isEmpty()) {
                    videoFile = new File(DEFAULT_CONFIG_DIR, videoName);
                }
                if (videoFile == null || !videoFile.exists()) {
                    File[] files = new File(DEFAULT_CONFIG_DIR)
                            .listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
                    if (files != null && files.length > 0) {
                        videoFile = files[0];
                    }
                }
                if (videoFile != null && !videoFile.exists()) {
                    videoFile = new File(DEFAULT_CONFIG_DIR, "Cam.mp4");
                }
                if (videoFile != null && videoFile.exists()) {
                    try {
                        final File finalVideoFile = videoFile;
                        android.os.Bundle bundle = new android.os.Bundle();
                        com.example.camswap.utils.LogUtil
                                .log("【CS】准备附加 video_binder: " + finalVideoFile.getAbsolutePath());
                        bundle.putBinder("video_binder", new android.os.Binder() {
                            @Override
                            protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply,
                                    int flags) throws android.os.RemoteException {
                                com.example.camswap.utils.LogUtil.log("【CS】【Binder】收到 transact 请求, code=" + code);
                                if (code == 1) { // 1 = Get FD
                                    reply.writeNoException();
                                    try {
                                        android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor
                                                .open(finalVideoFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                                        reply.writeInt(1);
                                        pfd.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                                        com.example.camswap.utils.LogUtil
                                                .log("【CS】【Binder】成功将 ParcelFileDescriptor 写入 reply");
                                    } catch (Exception e) {
                                        com.example.camswap.utils.LogUtil.log("【CS】【Binder】提取 PFD 失败: " + e);
                                        reply.writeInt(0);
                                    }
                                    return true;
                                }
                                return super.onTransact(code, data, reply, flags);
                            }
                        });
                        intent.putExtra("video_bundle", bundle);
                    } catch (Exception e) {
                        com.example.camswap.utils.LogUtil.log("【CS】广播附加 video_bundle 失败: " + e);
                    }
                }
            }

            context.sendBroadcast(intent);
            com.example.camswap.utils.LogUtil.log("【CS】已广播当前配置 config broadcast sent");
        } catch (Exception e) {
            com.example.camswap.utils.LogUtil.log("【CS】广播配置失败: " + e);
        }
    }

    private void reloadFromFile() {
        File configFile = new File(DEFAULT_CONFIG_DIR, CONFIG_FILE_NAME);
        if (configFile.exists()) {
            long fileModTime = configFile.lastModified();
            // fileModTime==0 means we couldn't get modification time (external storage
            // restriction).
            // When lastLoadedTime==0 (forceReload triggered), always read regardless of
            // timestamp.
            boolean shouldRead = (lastLoadedTime == 0) || (fileModTime > 0 && fileModTime > lastLoadedTime);
            if (shouldRead) {
                try {
                    StringBuilder stringBuilder = new StringBuilder();
                    try (BufferedReader bufferedReader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(configFile)))) {
                        String line;
                        while ((line = bufferedReader.readLine()) != null) {
                            stringBuilder.append(line);
                        }
                    }
                    configData = new JSONObject(stringBuilder.toString());
                    lastLoadedTime = (fileModTime > 0) ? fileModTime : System.currentTimeMillis();
                    com.example.camswap.utils.LogUtil
                            .log("【CS】Config reloaded from file: " + configFile.getAbsolutePath());
                    com.example.camswap.utils.LogUtil.log("【CS】File content: " + configData);
                } catch (Exception e) {
                    com.example.camswap.utils.LogUtil.log("【CS】Config file read error: " + e);
                    if (configData == null)
                        configData = new JSONObject();
                }
            } else {
                com.example.camswap.utils.LogUtil.log("【CS】Config file unchanged (modTime=" + fileModTime
                        + " lastLoaded=" + lastLoadedTime + "), skip read");
            }
        } else {
            com.example.camswap.utils.LogUtil.log("【CS】Config file not found: " + configFile.getAbsolutePath());
            if (configData == null) {
                configData = new JSONObject();
            }
        }
    }

    public boolean getBoolean(String key, boolean defValue) {
        return configData.optBoolean(key, defValue);
    }

    public int getInt(String key, int defValue) {
        return configData.optInt(key, defValue);
    }

    public void setInt(String key, int value) {
        try {
            configData.put(key, value);
            save();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setBoolean(String key, boolean value) {
        try {
            configData.put(key, value);
            save();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public Set<String> getTargetPackages() {
        Set<String> packages = new HashSet<>();
        JSONArray jsonArray = configData.optJSONArray(KEY_TARGET_PACKAGES);
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                try {
                    packages.add(jsonArray.getString(i));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        return packages;
    }

    public void setTargetPackages(Set<String> packages) {
        JSONArray jsonArray = new JSONArray();
        for (String pkg : packages) {
            jsonArray.put(pkg);
        }
        try {
            configData.put(KEY_TARGET_PACKAGES, jsonArray);
            save();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void addTargetPackage(String pkg) {
        Set<String> packages = getTargetPackages();
        packages.add(pkg);
        setTargetPackages(packages);
    }

    public void removeTargetPackage(String pkg) {
        Set<String> packages = getTargetPackages();
        packages.remove(pkg);
        setTargetPackages(packages);
    }

    public String getString(String key, String defValue) {
        return configData.optString(key, defValue);
    }

    public void setString(String key, String value) {
        try {
            configData.put(key, value);
            save();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void save() {
        File dir = new File(DEFAULT_CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File configFile = new File(dir, CONFIG_FILE_NAME);
        try {
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(configData.toString(4).getBytes());
            fos.close();

            // Set world-readable so hook processes (inside target apps) can read
            // the config file via direct path when ContentProvider is unavailable.
            try {
                configFile.setReadable(true, false);
                configFile.setWritable(true, true); // Keep write restricted to owner
                // Also chmod parents so directory is traversable
                dir.setExecutable(true, false);
                dir.setReadable(true, false);
                // Double-ensure with Runtime.exec (some ROMs ignore Java setReadable)
                Runtime.getRuntime().exec(new String[] { "chmod", "644", configFile.getAbsolutePath() });
            } catch (Exception ignored) {
                // Best-effort
            }

            // Notify ContentObserver and broadcast changes
            if (context != null) {
                try {
                    android.net.Uri uri = android.net.Uri.parse("content://com.example.camswap.provider/config");
                    context.getContentResolver().notifyChange(uri, null);
                } catch (Exception ignored) {
                }
                sendConfigBroadcast(context);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Migration logic
    public boolean migrateIfNeeded() {
        boolean migrated = false;
        File dir = new File(DEFAULT_CONFIG_DIR);

        // Map old files to new keys
        String[][] fileToKey = {
                { "disable.jpg", KEY_DISABLE_MODULE },
                { "no-silent.jpg", KEY_PLAY_VIDEO_SOUND },
                { "private_dir.jpg", KEY_FORCE_PRIVATE_DIR },
                { "no_toast.jpg", KEY_DISABLE_TOAST }
        };

        for (String[] map : fileToKey) {
            File oldFile = new File(dir, map[0]);
            if (oldFile.exists()) {
                setBoolean(map[1], true);
                oldFile.delete();
                migrated = true;
            }
        }

        return migrated;
    }

    public void resetToDefault() {
        configData = new JSONObject();
        save();
    }

    public String exportConfig() {
        return configData.toString();
    }

    public void importConfig(String json) throws JSONException {
        configData = new JSONObject(json);
        save();
    }

    /**
     * Parse config from JSON string and update memory cache.
     * Does NOT save to file to avoid EACCES errors in target app.
     */
    public void updateConfigFromJSON(String json) {
        try {
            configData = new JSONObject(json);
            // Update timestamps to prevent reloadFromFile from overwriting
            lastLoadedTime = System.currentTimeMillis();
            lastReloadTime = System.currentTimeMillis();
            com.example.camswap.utils.LogUtil.log("【CS】已通过广播更新内存配置");
        } catch (JSONException e) {
            com.example.camswap.utils.LogUtil.log("【CS】解析广播配置失败: " + e);
        }
    }
}
