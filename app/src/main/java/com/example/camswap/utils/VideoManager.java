package com.example.camswap.utils;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.net.Uri;
import android.os.Bundle;

import com.example.camswap.ConfigManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class VideoManager {
    public static String video_path = "/storage/emulated/0/DCIM/Camera1/";
    public static String current_video_path = null;
    public static final String CAM_VIDEO_NAME = "Cam.mp4";
    private static final Object pathLock = new Object();
    private static boolean providerAvailable = false;
    private static Context toast_content;
    private static ConfigManager configManager;

    /** Supported video file extensions */
    private static final String[] VIDEO_EXTENSIONS = { ".mp4", ".mov", ".avi", ".mkv" };

    /**
     * List video files in a directory, sorted by name.
     * 
     * @return sorted array of video files, or null if none found
     */
    public static File[] listVideoFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory())
            return null;
        File[] files = dir.listFiles(file -> {
            String name = file.getName().toLowerCase();
            for (String ext : VIDEO_EXTENSIONS) {
                if (name.endsWith(ext))
                    return true;
            }
            return false;
        });
        if (files != null && files.length > 0) {
            Arrays.sort(files);
            return files;
        }
        return null;
    }

    public static void showToast(final String message) {
        if (toast_content != null) {
            PermissionHelper.showToast(toast_content, message);
        }
    }

    public static void setContext(Context context) {
        toast_content = context;
        if (configManager != null) {
            configManager.setContext(context);
        }
    }

    public static void setConfigManager(ConfigManager manager) {
        configManager = manager;
    }

    public static ConfigManager getConfig() {
        if (configManager == null) {
            configManager = new ConfigManager();
            if (toast_content != null) {
                configManager.setContext(toast_content);
            }
        }
        // Removed auto-reload to avoid performance issues (IPC on every call).
        // Reloading should be handled by ContentObserver or explicit calls.
        return configManager;
    }

    public static ParcelFileDescriptor getVideoPFD() {
        if (toast_content == null) {
            log("【CS】getVideoPFD: toast_content is null, skip");
            return null;
        }

        if (getConfig().getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false)) {
            File privateFile = new File(toast_content.getFilesDir(), "vcam_private.mp4");
            if (privateFile.exists()) {
                try {
                    return ParcelFileDescriptor.open(privateFile, ParcelFileDescriptor.MODE_READ_ONLY);
                } catch (Exception e) {
                    log("【CS】[Private] 打开私有视频 Fd 失败: " + e);
                }
            }
        }
        // directly.

        try {
            Uri uri = Uri.parse("content://com.example.camswap.provider/video");
            ParcelFileDescriptor pfd = toast_content.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                log("【CS】getVideoPFD: 成功获取 PFD");
            } else {
                log("【CS】getVideoPFD: openFileDescriptor 返回 null");
            }
            return pfd;
        } catch (Exception e) {
            log("【CS】getVideoPFD 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    public static void copyToPrivateDir(ParcelFileDescriptor pfd) {
        if (toast_content == null)
            return;
        File privateFile = new File(toast_content.getFilesDir(), "vcam_private.mp4");

        try {
            long size = pfd.getStatSize();
            if (privateFile.exists() && privateFile.length() == size) {
                log("【CS】[Private] 文件大小一致，跳过拷贝 (" + size + " bytes)");
                return;
            }

            log("【CS】[Private] 开始拷贝视频到私有目录 (" + size + " bytes)...");
            java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
            java.io.FileOutputStream fos = new java.io.FileOutputStream(privateFile);

            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.close();
            fis.close();
            log("【CS】[Private] 视频拷贝完成 (" + size + " bytes)");
        } catch (Exception e) {
            log("【CS】[Private] 视频拷贝失败: " + e);
        }
    }

    public static void checkProviderAvailability() {
        ParcelFileDescriptor pfd = getVideoPFD();
        if (pfd != null) {
            providerAvailable = true;
            try {
                pfd.close();
            } catch (Exception e) {
                log("【CS】Error closing PFD check: " + e);
            }
        } else {
            providerAvailable = false;
        }
    }

    public static boolean isProviderAvailable() {
        return providerAvailable;
    }

    // Use LogUtil instead of direct XposedBridge to avoid crash in non-Xposed
    // process
    private static void log(String msg) {
        try {
            // Try to use LogUtil if available
            Class<?> logUtilClass = Class.forName("com.example.camswap.utils.LogUtil");
            logUtilClass.getMethod("log", String.class).invoke(null, msg);
        } catch (Throwable e) {
            // Fallback to standard android log if Xposed bridge is not available
            android.util.Log.i("LSPosed-Bridge", msg);
        }
    }

    public static void updateVideoPath(boolean forceRandom) {
        synchronized (pathLock) {
            ConfigManager config = getConfig();

            if (config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false) && toast_content != null) {
                File privateFile = new File(toast_content.getFilesDir(), "vcam_private.mp4");

                // Try from provider implicitly if needed
                try {
                    Uri uri = Uri.parse("content://com.example.camswap.provider/video");
                    ParcelFileDescriptor providerPfd = toast_content.getContentResolver().openFileDescriptor(uri, "r");
                    if (providerPfd != null) {
                        copyToPrivateDir(providerPfd);
                        try {
                            providerPfd.close();
                        } catch (Exception e) {
                        }
                    }
                } catch (Exception e) {
                    // Provider might not be available
                }

                if (privateFile.exists()) {
                    current_video_path = privateFile.getAbsolutePath();
                    log("【CS】[Private] 使用私有目录视频: " + current_video_path);
                    return;
                }
            }

            if (toast_content != null) {
                // Try to trigger random update via provider first if needed
                if (forceRandom && config.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false)) {
                    try {
                        toast_content.getContentResolver().call(Uri.parse("content://com.example.camswap.provider"),
                                "random", null, null);
                    } catch (Exception e) {
                        // log("【CS】Provider random failed: " + e);
                    }
                }

                checkProviderAvailability();
                if (providerAvailable) {
                    // If provider is available, we use it.
                    // The provider's openFile() will handle the random selection logic.
                    current_video_path = "/proc/self/cmdline";
                    return;
                }
            }

            File camFile = new File(video_path, CAM_VIDEO_NAME);

            // 1. 随机播放模式：随机选择一个视频文件名存入配置（不再重命名文件）
            if (config.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false)) {
                if (forceRandom) {
                    pickRandomVideoToConfig(config);
                }
                // 使用配置中选中的视频
                String randomSelected = config.getString(ConfigManager.KEY_SELECTED_VIDEO, null);
                if (randomSelected != null) {
                    File randomFile = new File(video_path, randomSelected);
                    if (randomFile.exists()) {
                        current_video_path = randomFile.getAbsolutePath();
                        log("【CS】[Random] 使用: " + current_video_path);
                        return;
                    }
                }
                // 降级：尝试 Cam.mp4，再尝试目录中任意视频
                current_video_path = findFallbackVideo(camFile);
                return;
            }

            // 2. 普通模式：优先使用配置中选中的视频
            String selectedName = config.getString(ConfigManager.KEY_SELECTED_VIDEO, null);
            if (selectedName != null && !selectedName.isEmpty()) {
                File selectedFile = new File(video_path, selectedName);
                if (selectedFile.exists()) {
                    current_video_path = selectedFile.getAbsolutePath();
                    log("【CS】[Video] 使用配置路径: " + current_video_path);
                    return;
                }
                log("【CS】[Video] 配置的视频不存在: " + selectedName);
            }

            // 3. 降级：Cam.mp4 → 目录中任意视频
            current_video_path = findFallbackVideo(camFile);
        }
    }

    /**
     * 降级查找视频：先尝试 Cam.mp4，再扫描目录中任意视频文件。
     * 确保只要目录中有视频就能找到。
     */
    private static String findFallbackVideo(File camFile) {
        // 尝试 Cam.mp4
        if (camFile.exists()) {
            log("【CS】[Video] 使用默认路径: " + camFile.getAbsolutePath());
            return camFile.getAbsolutePath();
        }

        // 扫描目录中任意视频
        File[] files = listVideoFiles(new File(video_path));
        if (files != null) {
            log("【CS】[Video] 自动选择目录中的视频: " + files[0].getName());
            return files[0].getAbsolutePath();
        }

        // 无可用视频，仍返回 Cam.mp4 路径（后续解码器会处理文件不存在的情况）
        log("【CS】[Video] 警告：目录中无可用视频文件");
        return camFile.getAbsolutePath();
    }

    /**
     * 随机选择视频文件名并存入配置（不再重命名文件）
     */
    private static void pickRandomVideoToConfig(ConfigManager config) {
        File dir = new File(video_path);
        if (!dir.exists() || !dir.isDirectory()) {
            log("【CS】[Random] 视频目录不存在");
            return;
        }

        File[] files = listVideoFiles(dir);

        if (files != null && files.length > 0) {
            int index = ThreadLocalRandom.current().nextInt(files.length);
            File selectedFile = files[index];
            config.setString(ConfigManager.KEY_SELECTED_VIDEO, selectedFile.getName());
            log("【CS】[Random] 选择了: " + selectedFile.getName());
        } else {
            log("【CS】[Random] 无可用视频文件");
        }
    }

    public static String getCurrentVideoPath() {
        synchronized (pathLock) {
            if (current_video_path == null) {
                updateVideoPath(false);
            }
            return current_video_path;
        }
    }

    public static boolean switchVideo(boolean next) {
        if (toast_content != null) {
            try {
                Bundle res = toast_content.getContentResolver().call(
                        Uri.parse("content://com.example.camswap.provider"),
                        next ? "next" : "prev", null, null);
                if (res != null && res.getBoolean("changed")) {
                    return true;
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Unknown authority") || msg.contains("No content provider"))) {
                    // Expected when provider is not visible
                } else {
                    log("【CS】Provider switch failed: " + e);
                }
            }
        }

        if (providerAvailable) {
            log("【CS】Provider call failed but provider is available. Skipping fallback.");
            showToast("Provider调用失败，无法切换视频");
            return false;
        }

        File dir = new File(video_path);
        File[] files = listVideoFiles(dir);

        if (files == null || files.length == 0)
            return false;

        int currentIndex = -1;
        if (current_video_path != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].getAbsolutePath().equals(current_video_path)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        int newIndex;
        if (currentIndex == -1) {
            newIndex = 0;
        } else {
            if (next) {
                newIndex = (currentIndex + 1) % files.length;
            } else {
                newIndex = (currentIndex - 1 + files.length) % files.length;
            }
        }

        // Use performVideoSelection to handle renaming and config update
        performVideoSelection(files[newIndex].getName());
        return true;
    }

    /**
     * 执行视频选择逻辑：
     * 仅更新配置中的选中视频名称和当前路径（不再重命名文件）
     */
    public static boolean performVideoSelection(String targetFileName) {
        synchronized (pathLock) {
            ConfigManager config = getConfig();
            File dir = new File(video_path);
            File targetFile = new File(dir, targetFileName);

            if (targetFile.exists()) {
                config.setString(ConfigManager.KEY_SELECTED_VIDEO, targetFileName);
                current_video_path = targetFile.getAbsolutePath();
                log("【CS】Selected: " + targetFileName);
                return true;
            } else {
                log("【CS】Target file not found: " + targetFileName);
                return false;
            }
        }
    }

}
