package com.example.camswap;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.File;
import java.io.IOException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.example.camswap.utils.VideoManager;
import com.example.camswap.utils.PermissionHelper;
import com.example.camswap.utils.LogUtil;

public class Camera1Handler implements ICameraHandler {

    @Override
    public void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture",
                SurfaceTexture.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (HookMain.origin_preview_camera == null
                                || !HookMain.origin_preview_camera.equals(param.thisObject)) {
                            VideoManager.updateVideoPath(true);
                        }
                        File file = new File(VideoManager.getCurrentVideoPath());
                        if (file.exists()) {
                            if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                                return;
                            }
                            if (HookMain.is_hooked) {
                                HookMain.is_hooked = false;
                                return;
                            }
                            if (param.args[0] == null) {
                                return;
                            }
                            if (param.args[0].equals(HookMain.c1_fake_texture)) {
                                return;
                            }

                            if (HookMain.origin_preview_camera != null
                                    && HookMain.origin_preview_camera.equals(param.thisObject)) {
                                param.args[0] = HookMain.fake_SurfaceTexture;
                                LogUtil.log("【CS】发现重复" + HookMain.origin_preview_camera.toString());
                                return;
                            } else {
                                LogUtil.log("【CS】创建预览");
                            }

                            HookMain.origin_preview_camera = (Camera) param.thisObject;
                            HookMain.mSurfacetexture = (SurfaceTexture) param.args[0];
                            if (HookMain.fake_SurfaceTexture == null) {
                                HookMain.fake_SurfaceTexture = new SurfaceTexture(10);
                            } else {
                                HookMain.fake_SurfaceTexture.release();
                                HookMain.fake_SurfaceTexture = new SurfaceTexture(10);
                            }
                            param.args[0] = HookMain.fake_SurfaceTexture;
                        } else {
                            HookMain.need_to_show_toast = !VideoManager.getConfig()
                                    .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    PermissionHelper.showToast(HookMain.toast_content,
                                            "不存在替换视频\n" + lpparam.packageName + "当前路径：" + VideoManager.video_path);
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                        }
                    }
                });

        // Hook setDisplayOrientation 以捕获宿主App期望的相机方向
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setDisplayOrientation",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int degrees = (int) param.args[0];
                        HookMain.mDisplayOrientation = degrees;
                        LogUtil.log("【CS】setDisplayOrientation: " + degrees);
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        File file = new File(VideoManager.getCurrentVideoPath());
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    PermissionHelper.showToast(HookMain.toast_content,
                                            "不存在替换视频\n" + lpparam.packageName + "当前路径：" + VideoManager.video_path);
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        HookMain.is_someone_playing = false;
                        LogUtil.log("【CS】开始预览");
                        HookMain.start_preview_camera = (Camera) param.thisObject;
                        if (HookMain.ori_holder != null) {

                            if (HookMain.mplayer1 == null) {
                                HookMain.mplayer1 = new MediaPlayer();
                            } else {
                                HookMain.mplayer1.release();
                                HookMain.mplayer1 = null;
                                HookMain.mplayer1 = new MediaPlayer();
                            }
                            if (HookMain.ori_holder == null || !HookMain.ori_holder.getSurface().isValid()) {
                                return;
                            }
                            // 使用 GL 渲染器实现旋转
                            GLVideoRenderer.releaseSafely(HookMain.c1_renderer_holder);
                            GLVideoRenderer renderer = GLVideoRenderer.createSafely(HookMain.ori_holder.getSurface(),
                                    "c1_holder");
                            HookMain.c1_renderer_holder = renderer;
                            if (renderer != null && renderer.isInitialized()) {
                                HookMain.mplayer1.setSurface(renderer.getInputSurface());
                                int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET,
                                        0);
                                renderer.setRotation(rotation);
                            } else {
                                HookMain.mplayer1.setSurface(HookMain.ori_holder.getSurface());
                            }
                            boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND,
                                    false);
                            if (!(playSound && (!HookMain.is_someone_playing))) {
                                HookMain.mplayer1.setVolume(0, 0);
                                HookMain.is_someone_playing = false;
                            } else {
                                HookMain.is_someone_playing = true;
                            }
                            HookMain.mplayer1.setLooping(true);

                            HookMain.mplayer1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                @Override
                                public void onPrepared(MediaPlayer mp) {
                                    HookMain.mplayer1.start();
                                }
                            });

                            try {
                                android.os.ParcelFileDescriptor pfd = HookMain.getVideoPFD();
                                if (pfd != null) {
                                    HookMain.mplayer1.setDataSource(pfd.getFileDescriptor());
                                    pfd.close();
                                } else {
                                    HookMain.mplayer1.setDataSource(VideoManager.getCurrentVideoPath());
                                }
                                HookMain.mplayer1.prepare();
                            } catch (IOException e) {
                                LogUtil.log("【CS】" + e.toString());
                            }
                        }

                        if (HookMain.mSurfacetexture != null) {
                            if (HookMain.mSurface == null) {
                                HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
                            } else {
                                HookMain.mSurface.release();
                                HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
                            }

                            if (HookMain.mMediaPlayer == null) {
                                HookMain.mMediaPlayer = new MediaPlayer();
                            } else {
                                HookMain.mMediaPlayer.release();
                                HookMain.mMediaPlayer = new MediaPlayer();
                            }

                            // 使用 GL 渲染器实现旋转
                            GLVideoRenderer.releaseSafely(HookMain.c1_renderer_texture);
                            GLVideoRenderer renderer2 = GLVideoRenderer.createSafely(HookMain.mSurface, "c1_texture");
                            HookMain.c1_renderer_texture = renderer2;
                            if (renderer2 != null && renderer2.isInitialized()) {
                                HookMain.mMediaPlayer.setSurface(renderer2.getInputSurface());
                                int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET,
                                        0);
                                renderer2.setRotation(rotation);
                            } else {
                                HookMain.mMediaPlayer.setSurface(HookMain.mSurface);
                            }

                            boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND,
                                    false);
                            if (!(playSound && (!HookMain.is_someone_playing))) {
                                HookMain.mMediaPlayer.setVolume(0, 0);
                                HookMain.is_someone_playing = false;
                            } else {
                                HookMain.is_someone_playing = true;
                            }
                            HookMain.mMediaPlayer.setLooping(true);

                            HookMain.mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                @Override
                                public void onPrepared(MediaPlayer mp) {
                                    HookMain.mMediaPlayer.start();
                                }
                            });

                            try {
                                android.os.ParcelFileDescriptor pfd = HookMain.getVideoPFD();
                                if (pfd != null) {
                                    HookMain.mMediaPlayer.setDataSource(pfd.getFileDescriptor());
                                    pfd.close();
                                } else {
                                    HookMain.mMediaPlayer.setDataSource(VideoManager.getCurrentVideoPath());
                                }
                                HookMain.mMediaPlayer.prepare();
                            } catch (IOException e) {
                                LogUtil.log("【CS】" + e.toString());
                            }
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay",
                SurfaceHolder.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        LogUtil.log("【CS】添加Surfaceview预览");
                        VideoManager.updateVideoPath(true);
                        File file = new File(VideoManager.getCurrentVideoPath());
                        HookMain.need_to_show_toast = !VideoManager.getConfig()
                                .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                        if (!file.exists()) {
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    PermissionHelper.showToast(HookMain.toast_content,
                                            "不存在替换视频\n" + lpparam.packageName + "\n当前路径：" + file.getAbsolutePath());
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                            return;
                        }
                        HookMain.mcamera1 = (Camera) param.thisObject;
                        HookMain.ori_holder = (SurfaceHolder) param.args[0];
                        if (HookMain.c1_fake_texture == null) {
                            HookMain.c1_fake_texture = new SurfaceTexture(11);
                        } else {
                            HookMain.c1_fake_texture.release();
                            HookMain.c1_fake_texture = null;
                            HookMain.c1_fake_texture = new SurfaceTexture(11);
                        }

                        if (HookMain.c1_fake_surface == null) {
                            HookMain.c1_fake_surface = new Surface(HookMain.c1_fake_texture);
                        } else {
                            HookMain.c1_fake_surface.release();
                            HookMain.c1_fake_surface = null;
                            HookMain.c1_fake_surface = new Surface(HookMain.c1_fake_texture);
                        }
                        HookMain.is_hooked = true;
                        HookMain.mcamera1.setPreviewTexture(HookMain.c1_fake_texture);
                        param.setResult(null);
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer",
                Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) {
                            HookMain.process_callback(param);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer",
                byte[].class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) {
                            param.args[0] = new byte[((byte[]) param.args[0]).length];
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallback",
                Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) {
                            HookMain.process_callback(param);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setOneShotPreviewCallback",
                Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) {
                            HookMain.process_callback(param);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "takePicture",
                Camera.ShutterCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        LogUtil.log("【CS】4参数拍照");
                        if (param.args[1] != null) {
                            HookMain.process_a_shot_YUV(param);
                        }

                        if (param.args[3] != null) {
                            HookMain.process_a_shot_jpeg(param, 3);
                        }
                    }
                });
    }
}
