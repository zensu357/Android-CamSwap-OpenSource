package com.example.camswap.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import com.example.camswap.BuildConfig;
import com.example.camswap.ConfigManager;
import com.example.camswap.HookMain;

import java.io.File;
import java.io.FileOutputStream;

public class PermissionHelper {

    public static void checkAndSetupPaths(Context context, String packageName) {
        if (context == null)
            return;

        ConfigManager config = VideoManager.getConfig();
        boolean forcePrivate = config.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
        boolean providerAvailable = VideoManager.isProviderAvailable();

        int auth_statue = 0;
        if (providerAvailable) {
            auth_statue = 2; // Provider available, treat as authorized
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && auth_statue < 2) {
            try {
                auth_statue += (context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) + 1);
            } catch (Exception ee) {
                LogUtil.log("【CS】[permission-check]" + ee.toString());
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    auth_statue += (context.checkSelfPermission(Manifest.permission.MANAGE_EXTERNAL_STORAGE) + 1);
                }
            } catch (Exception ee) {
                LogUtil.log("【CS】[permission-check]" + ee.toString());
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    // Android 13+ has granular permissions.
                    int videoPerm = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO);
                    int imagesPerm = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES);
                    if (videoPerm == PackageManager.PERMISSION_GRANTED
                            || imagesPerm == PackageManager.PERMISSION_GRANTED) {
                        auth_statue += 1;
                    }
                }
            } catch (Exception ee) {
                LogUtil.log("【CS】[permission-check-33]" + ee.toString());
            }
        } else {
            if (context.checkCallingPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                auth_statue = 2;
            }
        }

        // 权限判断完毕
        if (auth_statue < 1 && !forcePrivate) {
            // Try public dir check
            try {
                File publicDir = new File(ConfigManager.DEFAULT_CONFIG_DIR);
                File configFile = new File(publicDir, ConfigManager.CONFIG_FILE_NAME);
                if ((publicDir.exists() && publicDir.isDirectory() && publicDir.list() != null)
                        || (configFile.exists() && configFile.canRead())) {
                    LogUtil.log("【CS】权限检查失败但公共目录或配置文件可读，强制使用公共目录");
                    auth_statue = 2;
                }
            } catch (Exception e) {
                LogUtil.log("【CS】公共目录检查异常：" + e.toString());
            }
        }

        LogUtil.log("【CS】权限状态 auth_statue: " + auth_statue + ", forcePrivate: " + forcePrivate + ", provider: "
                + providerAvailable);

        if ((auth_statue < 1 && !providerAvailable) || forcePrivate) {
            // Fallback to private directory ONLY if no permission AND no provider, OR
            // forced
            setupPrivateDirectory(context, packageName, config);
        } else {
            VideoManager.video_path = ConfigManager.DEFAULT_CONFIG_DIR;
            File uni_DCIM_path = new File(ConfigManager.DEFAULT_CONFIG_DIR);
            if (uni_DCIM_path.canWrite()) {
                File uni_Camera1_path = new File(VideoManager.video_path);
                if (!uni_Camera1_path.exists()) {
                    uni_Camera1_path.mkdir();
                }
                createNoMediaFile(uni_Camera1_path);
            }
        }
    }

    private static void createNoMediaFile(File dir) {
        try {
            File noMedia = new File(dir, ".nomedia");
            if (!noMedia.exists()) {
                noMedia.createNewFile();
            }
        } catch (Exception e) {
            LogUtil.log("【CS】创建.nomedia失败: " + e.toString());
        }
    }

    private static void setupPrivateDirectory(Context context, String packageName, ConfigManager config) {
        File privateDir = context.getExternalFilesDir(null);
        if (privateDir == null) {
            LogUtil.log("【CS】无法获取私有目录，可能存储不可用");
            return;
        }

        File shown_file = new File(privateDir.getAbsolutePath() + "/Camera1/");
        if ((!shown_file.isDirectory()) && shown_file.exists()) {
            shown_file.delete();
        }
        if (!shown_file.exists()) {
            shown_file.mkdir();
        }

        VideoManager.video_path = shown_file.getAbsolutePath() + "/";
        LogUtil.log("【CS】切换到私有目录: " + VideoManager.video_path);
        try {
            createNoMediaFile(shown_file);
        } catch (Exception e) {
            LogUtil.log("【CS】私有目录创建.nomedia失败: " + e);
        }

        File markerFile = new File(privateDir.getAbsolutePath() + "/Camera1/" + "has_shown");
        if ((!packageName.equals(BuildConfig.APPLICATION_ID)) && (!markerFile.exists())) {
            try {
                showToast(context, packageName + "未授予读取本地目录权限，请检查权限\nCamera1目前重定向为 " + VideoManager.video_path);
                FileOutputStream fos = new FileOutputStream(markerFile);
                String info = "shown";
                fos.write(info.getBytes());
                fos.flush();
                fos.close();
            } catch (Exception e) {
                LogUtil.log("【CS】[switch-dir]" + e.toString());
            }
        }
        // Update current path immediately after switching directory
        VideoManager.updateVideoPath(false);
    }

    public static void showToast(final Context context, final String message) {
        if (context != null) {
            // Check if toasts are disabled
            try {
                ConfigManager config = VideoManager.getConfig();
                if (config != null && config.getBoolean(ConfigManager.KEY_DISABLE_TOAST, false)) {
                    return;
                }
            } catch (Exception e) {
                // Ignore config check errors and proceed to show toast (safe fallback)
            }

            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        LogUtil.log("【CS】[toast]" + e.toString());
                    }
                }
            });
        }
    }
}
