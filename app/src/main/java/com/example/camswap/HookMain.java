package com.example.camswap;

import android.app.Application;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.example.camswap.utils.PermissionHelper;
import com.example.camswap.utils.VideoManager;
import com.example.camswap.utils.LogUtil;

public class HookMain implements IXposedHookLoadPackage {
    public static final MediaPlayerManager playerManager = new MediaPlayerManager();
    public static final Camera2SessionHook camera2Hook = new Camera2SessionHook(playerManager);

    // Camera1 shared state
    public static Surface mSurface;
    public static SurfaceTexture mSurfacetexture;
    public static SurfaceTexture fake_SurfaceTexture;
    public static Camera origin_preview_camera;
    public static Camera camera_onPreviewFrame;
    public static Camera start_preview_camera;
    public static volatile byte[] data_buffer = { 0 };
    public static byte[] input;
    public static int mhight;
    public static int mwidth;
    public static boolean is_someone_playing;
    public static boolean is_hooked;
    public static VideoToFrames hw_decode_obj;
    public static SurfaceTexture c1_fake_texture;
    public static Surface c1_fake_surface;
    public static SurfaceHolder ori_holder;
    public static Camera mcamera1;
    public static volatile int mDisplayOrientation = 0;

    // Camera2 shared state
    public static int imageReaderFormat = 0;
    public static boolean need_to_show_toast = true;
    public static int c2_ori_width = 1280;
    public static int c2_ori_height = 720;
    public static Class c2_state_callback;
    public static CameraDevice.StateCallback c2_state_cb;
    public static Context toast_content;

    // =====================================================================
    // Delegates (kept for backward compatibility with Camera1/2 Handlers)
    // =====================================================================

    public static android.os.ParcelFileDescriptor getVideoPFD() {
        return VideoManager.getVideoPFD();
    }

    private static void checkProviderAvailability() {
        VideoManager.checkProviderAvailability();
    }

    public static ConfigManager getConfig() {
        return VideoManager.getConfig();
    }

    public static void updateVideoPath(boolean forceRandom) {
        VideoManager.updateVideoPath(forceRandom);
    }

    public static String getCurrentVideoPath() {
        return VideoManager.getCurrentVideoPath();
    }

    public static void reloadRandomVideo() {
        VideoManager.updateVideoPath(true);
    }

    public static void updateAllRendererRotations(int degrees) {
        playerManager.updateRotation(degrees);
    }

    public static void releaseAllRenderers() {
        playerManager.releaseAllRenderers();
    }

    public static void process_camera2_play() {
        camera2Hook.startPlayback();
    }

    public static void process_camera2_init(Class hooked_class) {
        camera2Hook.hookStateCallback(hooked_class);
    }

    public static void showToast(final String message) {
        PermissionHelper.showToast(toast_content, message);
    }

    // =====================================================================
    // Configuration watching (delegated to ConfigWatcher)
    // =====================================================================

    private static ConfigWatcher configWatcher;

    private static void initContentObserver(final Context context) {
        if (configWatcher == null) {
            configWatcher = new ConfigWatcher(new ConfigWatcher.Callback() {
                @Override
                public void onMediaSourceChanged() {
                    playerManager.restartAll();
                }

                @Override
                public void onRotationChanged(int degrees) {
                    playerManager.updateRotation(degrees);
                }
            });
            configWatcher.init(context);
        }
    }

    private static void switchVideo(boolean next) {
        if (VideoManager.switchVideo(next)) {
            playerManager.restartAll();
        }
    }

    // =====================================================================
    // Entry point
    // =====================================================================

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        // Hook self to return true for isModuleActive
        if (lpparam.packageName.equals("com.example.camswap")) {
            XposedHelpers.findAndHookMethod("com.example.camswap.MainActivity", lpparam.classLoader, "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });
        }

        // Check if module is disabled
        if (getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            LogUtil.log("【CS】模块已被配置禁用");
            return;
        }

        Set<String> targetPackages = getConfig().getTargetPackages();
        if (!targetPackages.isEmpty() && !targetPackages.contains(lpparam.packageName)) {
            return;
        }

        // Initialize Camera Handlers
        new Camera1Handler().init(lpparam);
        new Camera2Handler().init(lpparam);

        // Initialize Microphone Handler
        new MicrophoneHandler().init(lpparam);

        XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader, "setCamera", Camera.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        LogUtil.log("【CS】[record]" + lpparam.packageName);
                        if (toast_content != null && need_to_show_toast) {
                            try {
                                showToast("应用：" + lpparam.appInfo.name + "(" + lpparam.packageName + ")"
                                        + "触发了录像，但目前无法拦截");
                            } catch (Exception ee) {
                                LogUtil.log("【CS】[toast]" + Arrays.toString(ee.getStackTrace()));
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
                Application.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        if (param.args[0] instanceof Application) {
                            try {
                                toast_content = ((Application) param.args[0]).getApplicationContext();
                                VideoManager.setContext(toast_content);
                                checkProviderAvailability();

                                getConfig().setContext(toast_content);
                                getConfig().forceReload();
                                VideoManager.updateVideoPath(false);
                                LogUtil.log("【CS】Application.onCreate 预热：配置和视频路径已加载");

                                initContentObserver(toast_content);
                            } catch (Exception ee) {
                                LogUtil.log("【CS】" + ee.toString());
                            }

                            PermissionHelper.checkAndSetupPaths(toast_content, lpparam.packageName);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.media.ImageReader", lpparam.classLoader, "newInstance", int.class,
                int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        LogUtil.log("【CS】应用创建了渲染器：宽：" + param.args[0] + " 高：" + param.args[1] + "格式" + param.args[2]);
                        c2_ori_width = (int) param.args[0];
                        c2_ori_height = (int) param.args[1];
                        imageReaderFormat = (int) param.args[2];
                        need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (toast_content != null && need_to_show_toast) {
                            try {
                                showToast("应用创建了渲染器：\n宽：" + param.args[0] + "\n高：" + param.args[1] + "\n一般只需要宽高比与视频相同");
                            } catch (Exception e) {
                                LogUtil.log("【CS】[toast]" + e.toString());
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)) {
                                Object imageReader = param.getResult();
                                if (imageReader != null) {
                                    Surface surface = (Surface) XposedHelpers.callMethod(imageReader, "getSurface");
                                    if (surface != null) {
                                        camera2Hook.trackedReaderSurfaces.add(surface);
                                        camera2Hook.surfaceFormatMap.put(surface, (Integer) param.args[2]);
                                        LogUtil.log("【CS】已记录拍照用 ImageReader Surface: " + surface);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LogUtil.log("【CS】记录 ImageReader 失败: " + e);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraCaptureSession.CaptureCallback",
                lpparam.classLoader, "onCaptureFailed", CameraCaptureSession.class, CaptureRequest.class,
                CaptureFailure.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        LogUtil.log("【CS】onCaptureFailed" + "原因：" + ((CaptureFailure) param.args[2]).getReason());
                    }
                });
    }
}
