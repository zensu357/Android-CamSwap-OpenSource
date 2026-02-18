package com.example.camswap;

import android.os.Environment;
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
    public static final String CONFIG_FILE_NAME = "vcam_config.json";
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
    public static final String KEY_FORCE_SHOW_WARNING = "force_show_warning";
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
    public static final String KEY_VIDEO_ROTATION_OFFSET = "video_rotation_offset"; // 手动旋转偏移 0/90/180/270

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
     * 强制重新加载配置，忽略防抖时间限制。
     * 用于 ContentObserver.onChange() 等需要立即读取最新配置的场景。
     */
    public void forceReload() {
        lastReloadTime = 0; // Reset debounce
        reload();
    }

    private boolean reloadFromProvider() {
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.example.camswap.provider/config");
            android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
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
                cursor.close();
                configData = newConfig;

                // Debug log
                com.example.camswap.utils.LogUtil.log("【CS】配置已通过 ContentProvider 重新加载: " + configData.toString());

                return true;
            } else {
                com.example.camswap.utils.LogUtil.log("【CS】配置 Provider 返回的 Cursor 为空, URI: " + uri.toString());
                // Log caller to identify who is triggering reload
                com.example.camswap.utils.LogUtil
                        .log("【CS】Reload trigger stack: " + android.util.Log.getStackTraceString(new Throwable()));
            }
        } catch (Exception e) {
            com.example.camswap.utils.LogUtil.log("【CS】配置 Provider 错误: " + e.toString());
        }
        return false;
    }

    private void reloadFromFile() {
        File configFile = new File(DEFAULT_CONFIG_DIR, CONFIG_FILE_NAME);
        if (configFile.exists()) {
            if (configFile.lastModified() > lastLoadedTime) {
                try {
                    FileInputStream fis = new FileInputStream(configFile);
                    InputStreamReader isr = new InputStreamReader(fis);
                    BufferedReader bufferedReader = new BufferedReader(isr);
                    StringBuilder stringBuilder = new StringBuilder();
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        stringBuilder.append(line);
                    }
                    bufferedReader.close();
                    configData = new JSONObject(stringBuilder.toString());
                    lastLoadedTime = configFile.lastModified();
                    // Debug log
                    com.example.camswap.utils.LogUtil.log("【CS】Config reloaded from: " + configFile.getAbsolutePath());
                    com.example.camswap.utils.LogUtil.log("【CS】Content: " + configData.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                    if (configData == null)
                        configData = new JSONObject();
                }
            }
        } else {
            // Debug log for missing config
            com.example.camswap.utils.LogUtil.log("【CS】Config file not found: " + configFile.getAbsolutePath());

            // Only reset if we haven't loaded anything yet or if the file was deleted
            if (configData == null) {
                configData = new JSONObject();
            }
            // Don't reset lastLoadedTime so we don't reload empty config if file is missing
            // but we have data in memory
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
            if (context != null) {
                try {
                    android.net.Uri uri = android.net.Uri.parse("content://com.example.camswap.provider/config");
                    context.getContentResolver().notifyChange(uri, null);
                } catch (Exception ignored) {
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void setBoolean(String key, boolean value) {
        try {
            configData.put(key, value);
            save();
            // Notify if context is available
            if (context != null) {
                try {
                    android.net.Uri uri = android.net.Uri.parse("content://com.example.camswap.provider/config");
                    context.getContentResolver().notifyChange(uri, null);
                } catch (Exception ignored) {
                }
            }
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
            // Notify if context is available (only for App process, Hook process usually
            // doesn't write)
            if (context != null) {
                try {
                    android.net.Uri uri = android.net.Uri.parse("content://com.example.camswap.provider/config");
                    context.getContentResolver().notifyChange(uri, null);
                } catch (Exception ignored) {
                }
            }
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
                { "force_show.jpg", KEY_FORCE_SHOW_WARNING },
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
}
