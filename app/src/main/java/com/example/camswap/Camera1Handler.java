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
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + VideoManager.video_path);
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
                                    LogUtil.log(
                                            "【CS】不存在替换视频: " + lpparam.packageName + " 当前路径：" + VideoManager.video_path);
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            return;
                        }
                        HookMain.is_someone_playing = false;
                        LogUtil.log("【CS】开始预览");
                        HookMain.start_preview_camera = (Camera) param.thisObject;

                        try {
                            android.hardware.Camera.Parameters params = HookMain.start_preview_camera.getParameters();
                            android.hardware.Camera.Size size = params.getPreviewSize();
                            if (size != null) {
                                if (HookMain.mSurfacetexture != null) {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                                        HookMain.mSurfacetexture.setDefaultBufferSize(size.width, size.height);
                                    }
                                    LogUtil.log("【CS】修正目标 SurfaceTexture 尺寸为: " + size.width + "x" + size.height);
                                }
                                // 注意：不对 ori_holder 调用 setFixedSize，因为它是异步操作，
                                // 会导致 Surface buffer 进入过渡态（1x1），使后续 EGL 初始化拿到错误尺寸。
                                // SurfaceHolder 保持 UI 布局赋予的自然尺寸即可。
                                if (HookMain.ori_holder != null) {
                                    LogUtil.log("【CS】SurfaceHolder 保持原始尺寸，预览尺寸: " + size.width + "x" + size.height);
                                }
                            }
                        } catch (Exception e) {
                            LogUtil.log("【CS】修正 Surface 尺寸异常: " + e.getMessage());
                        }

                        if (HookMain.ori_holder != null) {

                            if (HookMain.playerManager.mplayer1 == null) {
                                HookMain.playerManager.mplayer1 = new MediaPlayer();
                            } else {
                                HookMain.playerManager.mplayer1.release();
                                HookMain.playerManager.mplayer1 = null;
                                HookMain.playerManager.mplayer1 = new MediaPlayer();
                            }
                            if (HookMain.ori_holder == null || !HookMain.ori_holder.getSurface().isValid()) {
                                return;
                            }
                            // 使用 GL 渲染器实现旋转
                            GLVideoRenderer.releaseSafely(HookMain.playerManager.c1_renderer_holder);
                            GLVideoRenderer renderer = GLVideoRenderer.createSafely(HookMain.ori_holder.getSurface(),
                                    "c1_holder");
                            HookMain.playerManager.c1_renderer_holder = renderer;
                            if (renderer != null && renderer.isInitialized()) {
                                HookMain.playerManager.mplayer1.setSurface(renderer.getInputSurface());
                                int rotation2 = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET,
                                        0);
                                renderer.setRotation(rotation2);
                            } else {
                                HookMain.playerManager.mplayer1.setSurface(HookMain.ori_holder.getSurface());
                            }
                            boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND,
                                    false);
                            if (!(playSound && (!HookMain.is_someone_playing))) {
                                HookMain.playerManager.mplayer1.setVolume(0, 0);
                                HookMain.is_someone_playing = false;
                            } else {
                                HookMain.is_someone_playing = true;
                            }
                            HookMain.playerManager.mplayer1.setLooping(true);

                            HookMain.playerManager.mplayer1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                @Override
                                public void onPrepared(MediaPlayer mp) {
                                    HookMain.playerManager.mplayer1.start();
                                }
                            });

                            try {
                                android.os.ParcelFileDescriptor pfd = HookMain.getVideoPFD();
                                if (pfd != null) {
                                    HookMain.playerManager.mplayer1.setDataSource(pfd.getFileDescriptor());
                                    pfd.close();
                                } else {
                                    HookMain.playerManager.mplayer1.setDataSource(VideoManager.getCurrentVideoPath());
                                }
                                HookMain.playerManager.mplayer1.prepare();
                            } catch (Exception e) {
                                LogUtil.log("【CS】mplayer1 prepare 异常: " + e.toString());
                            }
                        }

                        if (HookMain.mSurfacetexture != null) {
                            if (HookMain.mSurface == null) {
                                HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
                            } else {
                                HookMain.mSurface.release();
                                HookMain.mSurface = new Surface(HookMain.mSurfacetexture);
                            }

                            if (HookMain.playerManager.mMediaPlayer == null) {
                                HookMain.playerManager.mMediaPlayer = new MediaPlayer();
                            } else {
                                HookMain.playerManager.mMediaPlayer.release();
                                HookMain.playerManager.mMediaPlayer = new MediaPlayer();
                            }

                            // 使用 GL 渲染器实现旋转
                            GLVideoRenderer.releaseSafely(HookMain.playerManager.c1_renderer_texture);
                            GLVideoRenderer renderer2 = GLVideoRenderer.createSafely(HookMain.mSurface, "c1_texture");
                            HookMain.playerManager.c1_renderer_texture = renderer2;
                            if (renderer2 != null && renderer2.isInitialized()) {
                                HookMain.playerManager.mMediaPlayer.setSurface(renderer2.getInputSurface());
                                int rotation2 = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET,
                                        0);
                                renderer2.setRotation(rotation2);
                            } else {
                                HookMain.playerManager.mMediaPlayer.setSurface(HookMain.mSurface);
                            }

                            boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND,
                                    false);
                            if (!(playSound && (!HookMain.is_someone_playing))) {
                                HookMain.playerManager.mMediaPlayer.setVolume(0, 0);
                                HookMain.is_someone_playing = false;
                            } else {
                                HookMain.is_someone_playing = true;
                            }
                            HookMain.playerManager.mMediaPlayer.setLooping(true);

                            HookMain.playerManager.mMediaPlayer
                                    .setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                        @Override
                                        public void onPrepared(MediaPlayer mp) {
                                            HookMain.playerManager.mMediaPlayer.start();
                                        }
                                    });

                            try {
                                android.os.ParcelFileDescriptor pfd = HookMain.getVideoPFD();
                                if (pfd != null) {
                                    HookMain.playerManager.mMediaPlayer.setDataSource(pfd.getFileDescriptor());
                                    pfd.close();
                                } else {
                                    HookMain.playerManager.mMediaPlayer
                                            .setDataSource(VideoManager.getCurrentVideoPath());
                                }
                                HookMain.playerManager.mMediaPlayer.prepare();
                            } catch (Exception e) {
                                LogUtil.log("【CS】mMediaPlayer prepare 异常: " + e.toString());
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
                        HookMain.mcamera1 = (Camera) param.thisObject;
                        HookMain.ori_holder = (SurfaceHolder) param.args[0];
                        // 使用 setPreviewDisplay 路径时，清除可能陈旧的 SurfaceTexture 引用
                        // 防止 startPreview 中执行无效的 texture 路径导致崩溃
                        if (HookMain.mSurfacetexture != null) {
                            HookMain.mSurfacetexture = null;
                        }
                        if (HookMain.mSurface != null) {
                            HookMain.mSurface.release();
                            HookMain.mSurface = null;
                        }
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
                            processCallback(param);
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
                            processCallback(param);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setOneShotPreviewCallback",
                Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null) {
                            processCallback(param);
                        }
                    }
                });

        // Hook takePicture for Photo Fake
        de.robv.android.xposed.XC_MethodHook takePictureHook = new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_PHOTO_FAKE, false)) {
                    LogUtil.log("【CS】Camera1 takePicture 触发，启动动态防御机制");
                    param.setResult(null); // 阻止硬件拍照

                    Camera.PictureCallback jpegCallback = null;
                    if (param.args.length == 3) {
                        jpegCallback = (Camera.PictureCallback) param.args[2];
                    } else if (param.args.length == 4) {
                        jpegCallback = (Camera.PictureCallback) param.args[3];
                    }

                    if (jpegCallback != null) {
                        Camera camera = (Camera) param.thisObject;

                        // 确保 mwidth/mhight 有值
                        if (HookMain.mwidth <= 0 || HookMain.mhight <= 0) {
                            try {
                                Camera.Parameters params = camera.getParameters();
                                Camera.Size size = params.getPreviewSize();
                                if (size != null) {
                                    HookMain.mwidth = size.width;
                                    HookMain.mhight = size.height;
                                }
                            } catch (Exception e) {
                                LogUtil.log("【CS】获取 Camera1 尺寸失败: " + e);
                            }
                        }
                        if (HookMain.mwidth <= 0)
                            HookMain.mwidth = 640;
                        if (HookMain.mhight <= 0)
                            HookMain.mhight = 480;

                        byte[] nv21 = HookMain.data_buffer;
                        byte[] jpegData = null;

                        // 方式1：从 onPreviewFrame 帧回调解码的 NV21 数据生成 JPEG
                        if (nv21 != null && nv21.length > 1) {
                            try {
                                android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(nv21,
                                        android.graphics.ImageFormat.NV21, HookMain.mwidth, HookMain.mhight, null);
                                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                                yuvImage.compressToJpeg(
                                        new android.graphics.Rect(0, 0, HookMain.mwidth, HookMain.mhight), 90, out);
                                jpegData = out.toByteArray();
                                LogUtil.log("【CS】Camera1 Photo Fake: 从 NV21 帧回调数据生成 JPEG");
                            } catch (Exception e) {
                                LogUtil.log("【CS】Camera1 截帧 JPEG 转换失败: " + e);
                            }
                        }

                        // 方式2：从当前播放的视频文件截取帧（适用于 setPreviewDisplay 路径，无帧回调）
                        if (jpegData == null || jpegData.length == 0) {
                            try {
                                // 获取 MediaPlayer 当前播放位置
                                long currentPosMs = 0;
                                if (HookMain.playerManager.mplayer1 != null
                                        && HookMain.playerManager.mplayer1.isPlaying()) {
                                    currentPosMs = HookMain.playerManager.mplayer1.getCurrentPosition() * 1000L;
                                } else if (HookMain.playerManager.mMediaPlayer != null
                                        && HookMain.playerManager.mMediaPlayer.isPlaying()) {
                                    currentPosMs = HookMain.playerManager.mMediaPlayer.getCurrentPosition() * 1000L;
                                }

                                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                                String videoPath = VideoManager.getCurrentVideoPath();
                                retriever.setDataSource(videoPath);
                                android.graphics.Bitmap frame = retriever.getFrameAtTime(currentPosMs,
                                        android.media.MediaMetadataRetriever.OPTION_CLOSEST);
                                retriever.release();

                                if (frame != null) {
                                    // 如果需要，缩放到目标尺寸
                                    if (frame.getWidth() != HookMain.mwidth
                                            || frame.getHeight() != HookMain.mhight) {
                                        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                                                frame, HookMain.mwidth, HookMain.mhight, true);
                                        frame.recycle();
                                        frame = scaled;
                                    }
                                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                    frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos);
                                    jpegData = bos.toByteArray();
                                    frame.recycle();
                                    LogUtil.log("【CS】Camera1 Photo Fake: 从视频文件截取帧生成 JPEG ("
                                            + HookMain.mwidth + "x" + HookMain.mhight + ")");
                                }
                            } catch (Exception e) {
                                LogUtil.log("【CS】Camera1 从视频截帧失败: " + e);
                            }
                        }

                        // 方式3：生成纯黑 JPEG 兜底
                        if (jpegData == null || jpegData.length == 0) {
                            try {
                                android.graphics.Bitmap fallback = android.graphics.Bitmap.createBitmap(HookMain.mwidth,
                                        HookMain.mhight, android.graphics.Bitmap.Config.ARGB_8888);
                                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                fallback.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, bos);
                                jpegData = bos.toByteArray();
                                fallback.recycle();
                                LogUtil.log("【CS】Camera1 Photo Fake: 使用纯黑兜底 JPEG");
                            } catch (Exception e) {
                                jpegData = new byte[0];
                            }
                        }

                        if (jpegData != null) {
                            try {
                                jpegCallback.onPictureTaken(jpegData, camera);
                            } catch (Exception e) {
                                LogUtil.log("【CS】Camera1 主动回调 PictureCallback 失败: " + e);
                            }
                        }
                    }
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "takePicture",
                    Camera.ShutterCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class,
                    takePictureHook);
        } catch (Throwable e) {
            LogUtil.log("【CS】Camera1 Hook takePicture 3-args 失败");
        }
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "takePicture",
                    Camera.ShutterCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class,
                    Camera.PictureCallback.class, takePictureHook);
        } catch (Throwable e) {
            LogUtil.log("【CS】Camera1 Hook takePicture 4-args 失败");
        }
    }

    /**
     * Hook onPreviewFrame to replace camera frame data with decoded video frames.
     * Moved from HookMain.process_callback().
     */
    private static void processCallback(XC_MethodHook.MethodHookParam param) {
        Class preview_cb_class = param.args[0].getClass();
        int need_stop = 0;
        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            need_stop = 1;
        }
        File file = new File(VideoManager.getCurrentVideoPath());
        HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
        if (!file.exists()) {
            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                try {
                    LogUtil.log("【CS】不存在替换视频: " + HookMain.toast_content.getPackageName()
                            + " 当前路径：" + VideoManager.video_path);
                } catch (Exception ee) {
                    LogUtil.log("【CS】[toast]" + ee);
                }
            }
            need_stop = 1;
        }
        int finalNeed_stop = need_stop;
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame", byte[].class,
                android.hardware.Camera.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        Camera localcam = (android.hardware.Camera) paramd.args[1];
                        if (localcam.equals(HookMain.camera_onPreviewFrame)) {
                            for (int _w = 0; _w < 100 && HookMain.data_buffer == null; _w++) {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                            }
                            if (HookMain.data_buffer == null)
                                return;
                            System.arraycopy(HookMain.data_buffer, 0, paramd.args[0], 0,
                                    Math.min(HookMain.data_buffer.length, ((byte[]) paramd.args[0]).length));
                        } else {
                            HookMain.camera_onPreviewFrame = (android.hardware.Camera) paramd.args[1];
                            HookMain.mwidth = HookMain.camera_onPreviewFrame.getParameters().getPreviewSize().width;
                            HookMain.mhight = HookMain.camera_onPreviewFrame.getParameters().getPreviewSize().height;
                            int frame_Rate = HookMain.camera_onPreviewFrame.getParameters().getPreviewFrameRate();
                            LogUtil.log("【CS】帧预览回调初始化：宽：" + HookMain.mwidth + " 高：" + HookMain.mhight
                                    + " 帧率：" + frame_Rate);
                            HookMain.need_to_show_toast = !VideoManager.getConfig()
                                    .getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                            if (HookMain.toast_content != null && HookMain.need_to_show_toast) {
                                try {
                                    HookMain.showToast("发现预览\n宽：" + HookMain.mwidth + "\n高："
                                            + HookMain.mhight + "\n" + "需要视频分辨率与其完全相同");
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            if (finalNeed_stop == 1)
                                return;
                            if (HookMain.hw_decode_obj == null) {
                                HookMain.hw_decode_obj = new VideoToFrames();
                            }
                            HookMain.hw_decode_obj.setTargetSize(HookMain.mwidth, HookMain.mhight);
                            HookMain.hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                            try {
                                HookMain.hw_decode_obj.reset(VideoManager.getCurrentVideoPath());
                            } catch (Throwable t) {
                                LogUtil.log("【CS】" + t);
                            }
                            for (int _w = 0; _w < 100 && HookMain.data_buffer == null; _w++) {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                            }
                            if (HookMain.data_buffer == null)
                                return;
                            System.arraycopy(HookMain.data_buffer, 0, paramd.args[0], 0,
                                    Math.min(HookMain.data_buffer.length, ((byte[]) paramd.args[0]).length));
                        }
                    }
                });
    }
}
