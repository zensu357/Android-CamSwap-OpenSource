package com.example.camswap;

import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.File;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.example.camswap.utils.VideoManager;
import com.example.camswap.utils.PermissionHelper;
import com.example.camswap.utils.LogUtil;

public class Camera2Handler implements ICameraHandler {

    @Override
    public void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
                String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[1] == null) {
                            return;
                        }
                        if (param.args[1].equals(HookMain.c2_state_cb)) {
                            return;
                        }
                        HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[1];
                        HookMain.c2_state_callback = param.args[1].getClass();
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        VideoManager.updateVideoPath(true);
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        LogUtil.log("【CS】1位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                        HookMain.camera2Hook.isFirstHookBuild = true;
                        HookMain.process_camera2_init(HookMain.c2_state_callback);
                    }
                });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
                    String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args[2] == null) {
                                return;
                            }
                            if (param.args[2].equals(HookMain.c2_state_cb)) {
                                return;
                            }
                            HookMain.c2_state_cb = (CameraDevice.StateCallback) param.args[2];
                            if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                                return;
                            }
                            VideoManager.updateVideoPath(true);
                            File file = new File(VideoManager.getCurrentVideoPath());
                            HookMain.need_to_show_toast = !VideoManager.getConfig()
                                    .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                            if (!file.exists()) {
                                if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                    try {
                                        LogUtil.log("【CS】不存在替换视频: " + lpparam.packageName + " 当前路径："
                                                + VideoManager.video_path);
                                    } catch (Exception ee) {
                                        LogUtil.log("【CS】[toast]" + ee.toString());
                                    }
                                }
                                return;
                            }
                            HookMain.c2_state_callback = param.args[2].getClass();
                            LogUtil.log("【CS】2位参数初始化相机，类：" + HookMain.c2_state_callback.toString());
                            HookMain.camera2Hook.isFirstHookBuild = true;
                            HookMain.process_camera2_init(HookMain.c2_state_callback);
                        }
                    });
        }

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader,
                "addTarget", Surface.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {

                        if (param.args[0] == null) {
                            return;
                        }
                        if (param.thisObject == null) {
                            return;
                        }
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        if (param.args[0].equals(HookMain.camera2Hook.getVirtualSurface())) {
                            return;
                        }

                        // Check disable module
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }

                        // Dynamic defense for Photo Fake
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)
                                && HookMain.camera2Hook.trackedReaderSurfaces.contains(param.args[0])) {
                            LogUtil.log("【CS】检测到 ImageReader Surface 在 addTarget: " + param.args[0]);
                            HookMain.camera2Hook.pendingPhotoSurface = (Surface) param.args[0];
                            // 不阻塞 addTarget：仍然重定向到 virtualSurface（这样 CaptureRequest 不为空）
                            // 注入将在 build() 时触发
                        }

                        String surfaceInfo = param.args[0].toString();
                        if (surfaceInfo.contains("Surface(name=null)")) {
                            if (HookMain.camera2Hook.readerSurface == null) {
                                HookMain.camera2Hook.readerSurface = (Surface) param.args[0];
                            } else {
                                if ((!HookMain.camera2Hook.readerSurface.equals(param.args[0]))
                                        && HookMain.camera2Hook.readerSurface1 == null) {
                                    HookMain.camera2Hook.readerSurface1 = (Surface) param.args[0];
                                }
                            }
                        } else {
                            if (HookMain.camera2Hook.previewSurface == null) {
                                HookMain.camera2Hook.previewSurface = (Surface) param.args[0];
                            } else {
                                if ((!HookMain.camera2Hook.previewSurface.equals(param.args[0]))
                                        && HookMain.camera2Hook.previewSurface1 == null) {
                                    HookMain.camera2Hook.previewSurface1 = (Surface) param.args[0];
                                }
                            }
                        }
                        LogUtil.log("【CS】添加目标：" + param.args[0].toString());
                        param.args[0] = HookMain.camera2Hook.getVirtualSurface();

                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader,
                "removeTarget", Surface.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {

                        if (param.args[0] == null) {
                            return;
                        }
                        if (param.thisObject == null) {
                            return;
                        }
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        Surface rm_surf = (Surface) param.args[0];
                        if (rm_surf.equals(HookMain.camera2Hook.previewSurface)) {
                            HookMain.camera2Hook.previewSurface = null;
                        }
                        if (rm_surf.equals(HookMain.camera2Hook.previewSurface1)) {
                            HookMain.camera2Hook.previewSurface1 = null;
                        }
                        if (rm_surf.equals(HookMain.camera2Hook.readerSurface1)) {
                            HookMain.camera2Hook.readerSurface1 = null;
                        }
                        if (rm_surf.equals(HookMain.camera2Hook.readerSurface)) {
                            HookMain.camera2Hook.readerSurface = null;
                        }

                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.thisObject == null) {
                            return;
                        }
                        if (param.thisObject.equals(HookMain.camera2Hook.captureBuilder)) {
                            return;
                        }
                        HookMain.camera2Hook.captureBuilder = (CaptureRequest.Builder) param.thisObject;
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists() && HookMain.need_to_show_toast) {
                            if (HookMain.toast_content != null) {
                                try {
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }

                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        LogUtil.log("【CS】开始build请求");

                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)
                                && HookMain.camera2Hook.pendingPhotoSurface != null) {
                            final Surface photoSurface = HookMain.camera2Hook.pendingPhotoSurface;
                            HookMain.camera2Hook.pendingPhotoSurface = null;
                            new Thread(() -> {
                                try {
                                    Thread.sleep(100); // 等 App 准备接收
                                    HookMain.camera2Hook.createOrPumpImage(photoSurface);
                                } catch (Exception e) {
                                    LogUtil.log("【CS】build 触发注入异常: " + e);
                                }
                            }, "PhotoFake-Pump").start();
                        }

                        HookMain.process_camera2_play();
                    }
                });
    }
}
