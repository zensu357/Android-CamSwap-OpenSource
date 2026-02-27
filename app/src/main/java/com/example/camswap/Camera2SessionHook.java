package com.example.camswap;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.Map;
import android.media.ImageWriter;
import android.media.Image;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.graphics.Rect;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import com.example.camswap.utils.LogUtil;
import com.example.camswap.utils.VideoManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Handles Camera2 session interception: replaces real camera surfaces with
 * a virtual surface, then starts video playback via {@link MediaPlayerManager}.
 */
public final class Camera2SessionHook {
    private final MediaPlayerManager playerManager;

    // Camera2 surfaces
    Surface previewSurface;
    Surface previewSurface1;
    Surface readerSurface;
    Surface readerSurface1;

    // Tracker for Photo Fake
    public final Set<Surface> trackedReaderSurfaces = Collections
            .newSetFromMap(new ConcurrentHashMap<Surface, Boolean>());
    public final Map<Surface, ImageWriter> imageWriterMap = new ConcurrentHashMap<>();
    public final Map<Surface, Integer> surfaceFormatMap = new ConcurrentHashMap<>();

    // Photo Fake: 等待 build() 时触发
    public volatile Surface pendingPhotoSurface;

    // Virtual surface for session hijacking
    private Surface virtualSurface;
    private SurfaceTexture virtualTexture;
    private boolean needRecreate;

    /** Public accessor for Camera2Handler to check/redirect surfaces. */
    public Surface getVirtualSurface() {
        return virtualSurface;
    }

    // Session config
    CaptureRequest.Builder captureBuilder;
    SessionConfiguration fakeSessionConfig;
    SessionConfiguration realSessionConfig;
    OutputConfiguration outputConfig;
    boolean isFirstHookBuild = true;

    public Camera2SessionHook(MediaPlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    /** Called by Camera2Handler when onOpened fires on the state callback class. */
    public void hookStateCallback(Class<?> hookedClass) {
        XposedHelpers.findAndHookMethod(hookedClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                needRecreate = true;
                createVirtualSurface();
                playerManager.releaseCamera2Resources();
                releaseImageWriters();
                previewSurface1 = null;
                readerSurface1 = null;
                readerSurface = null;
                previewSurface = null;
                isFirstHookBuild = true;
                LogUtil.log("【CS】打开相机C2");

                File file = new File(VideoManager.getCurrentVideoPath());
                boolean showToast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                if (!file.exists()) {
                    if (HookMain.toast_content != null && showToast) {
                        try {
                            LogUtil.log("【CS】不存在替换视频: " + HookMain.toast_content.getPackageName()
                                    + " 当前路径：" + VideoManager.video_path);
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + ee);
                        }
                    }
                    return;
                }

                hookAllCreateSessionVariants(param.args[0].getClass());
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onClosed", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                LogUtil.log("【CS】相机关闭 onClosed");
                releaseImageWriters();
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onError", CameraDevice.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                LogUtil.log("【CS】相机错误onerror：" + (int) param.args[1]);
            }
        });

        XposedHelpers.findAndHookMethod(hookedClass, "onDisconnected", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                LogUtil.log("【CS】相机断开onDisconnected ：");
            }
        });
    }

    /** Start video playback on all current surfaces. */
    public void startPlayback() {
        playerManager.initCamera2Players(readerSurface, readerSurface1,
                previewSurface, previewSurface1);
    }

    // =====================================================================
    // Virtual surface management
    // =====================================================================

    private Surface createVirtualSurface() {
        if (needRecreate) {
            if (virtualTexture != null) {
                virtualTexture.release();
                virtualTexture = null;
            }
            if (virtualSurface != null) {
                virtualSurface.release();
                virtualSurface = null;
            }
            virtualTexture = new SurfaceTexture(15);
            virtualSurface = new Surface(virtualTexture);
            needRecreate = false;
        } else {
            if (virtualSurface == null) {
                needRecreate = true;
                virtualSurface = createVirtualSurface();
            }
        }
        LogUtil.log("【CS】【重建虚拟Surface】" + virtualSurface);
        return virtualSurface;
    }

    // =====================================================================
    // Hook all createCaptureSession variants
    // =====================================================================

    private void hookAllCreateSessionVariants(Class<?> deviceClass) {
        // 1. createCaptureSession(List, StateCallback, Handler)
        XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class,
                CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args[0] != null) {
                            LogUtil.log("【CS】createCaptureSession创建捕获，原始:" + p.args[0] + "虚拟：" + virtualSurface);
                            p.args[0] = Arrays.asList(virtualSurface);
                            if (p.args[1] != null)
                                hookSessionCallback((CameraCaptureSession.StateCallback) p.args[1]);
                        }
                    }
                });

        // 2. createCaptureSessionByOutputConfigurations (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            XposedHelpers.findAndHookMethod(deviceClass,
                    "createCaptureSessionByOutputConfigurations", List.class,
                    CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[0] != null) {
                                outputConfig = new OutputConfiguration(virtualSurface);
                                p.args[0] = Arrays.asList(outputConfig);
                                LogUtil.log("【CS】执行了createCaptureSessionByOutputConfigurations");
                                if (p.args[1] != null)
                                    hookSessionCallback((CameraCaptureSession.StateCallback) p.args[1]);
                            }
                        }
                    });
        }

        // 3. createConstrainedHighSpeedCaptureSession
        XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession",
                List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args[0] != null) {
                            p.args[0] = Arrays.asList(virtualSurface);
                            LogUtil.log("【CS】执行了 createConstrainedHighSpeedCaptureSession");
                            if (p.args[1] != null)
                                hookSessionCallback((CameraCaptureSession.StateCallback) p.args[1]);
                        }
                    }
                });

        // 4. createReprocessableCaptureSession (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSession",
                    InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class,
                    Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[1] != null) {
                                p.args[1] = Arrays.asList(virtualSurface);
                                LogUtil.log("【CS】执行了 createReprocessableCaptureSession ");
                                if (p.args[2] != null)
                                    hookSessionCallback((CameraCaptureSession.StateCallback) p.args[2]);
                            }
                        }
                    });
        }

        // 5. createReprocessableCaptureSessionByConfigurations (API 24+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            XposedHelpers.findAndHookMethod(deviceClass,
                    "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class,
                    CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[1] != null) {
                                outputConfig = new OutputConfiguration(virtualSurface);
                                p.args[0] = Arrays.asList(outputConfig);
                                LogUtil.log("【CS】执行了 createReprocessableCaptureSessionByConfigurations");
                                if (p.args[2] != null)
                                    hookSessionCallback((CameraCaptureSession.StateCallback) p.args[2]);
                            }
                        }
                    });
        }

        // 6. createCaptureSession(SessionConfiguration) (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession",
                    SessionConfiguration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args[0] != null) {
                                LogUtil.log("【CS】执行了 createCaptureSession (SessionConfiguration)");
                                realSessionConfig = (SessionConfiguration) p.args[0];
                                outputConfig = new OutputConfiguration(virtualSurface);
                                fakeSessionConfig = new SessionConfiguration(
                                        realSessionConfig.getSessionType(),
                                        Arrays.asList(outputConfig),
                                        realSessionConfig.getExecutor(),
                                        realSessionConfig.getStateCallback());
                                p.args[0] = fakeSessionConfig;
                                hookSessionCallback(realSessionConfig.getStateCallback());
                            }
                        }
                    });
        }
    }

    // =====================================================================
    // Session callback logging hooks
    // =====================================================================

    private void hookSessionCallback(CameraCaptureSession.StateCallback cb) {
        if (cb == null)
            return;
        XposedHelpers.findAndHookMethod(cb.getClass(), "onConfigureFailed", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        LogUtil.log("【CS】onConfigureFailed ：" + p.args[0]);
                    }
                });
        XposedHelpers.findAndHookMethod(cb.getClass(), "onConfigured", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        LogUtil.log("【CS】onConfigured ：" + p.args[0]);
                    }
                });
        XposedHelpers.findAndHookMethod(cb.getClass(), "onClosed", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        LogUtil.log("【CS】onClosed ：" + p.args[0]);
                    }
                });
    }

    public void releaseImageWriters() {
        for (ImageWriter writer : imageWriterMap.values()) {
            try {
                writer.close();
            } catch (Exception e) {
                LogUtil.log("【CS】关闭 ImageWriter 失败: " + e);
            }
        }
        imageWriterMap.clear();
        trackedReaderSurfaces.clear();
        surfaceFormatMap.clear();
    }

    /** 获取当前活跃的 GLVideoRenderer（优先 preview，其次 reader） */
    public GLVideoRenderer getActiveRenderer() {
        MediaPlayerManager pm = HookMain.playerManager;
        if (pm.c2_renderer != null && pm.c2_renderer.isInitialized())
            return pm.c2_renderer;
        if (pm.c2_renderer_1 != null && pm.c2_renderer_1.isInitialized())
            return pm.c2_renderer_1;
        if (pm.c2_reader_renderer != null && pm.c2_reader_renderer.isInitialized())
            return pm.c2_reader_renderer;
        if (pm.c2_reader_renderer_1 != null && pm.c2_reader_renderer_1.isInitialized())
            return pm.c2_reader_renderer_1;
        return null;
    }

    public void createOrPumpImage(Surface targetSurface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return;
        try {
            // 1. 从当前活跃的 GLVideoRenderer 截帧
            GLVideoRenderer activeRenderer = getActiveRenderer();
            if (activeRenderer == null) {
                LogUtil.log("【CS】无可用 GL 渲染器，无法截帧");
                return;
            }
            // 使用 ImageReader 的原始尺寸
            int w = HookMain.c2_ori_width;
            int h = HookMain.c2_ori_height;
            if (w <= 0 || h <= 0) {
                w = 1280;
                h = 720;
            }
            Bitmap frame = activeRenderer.captureFrame(w, h);
            if (frame == null) {
                LogUtil.log("【CS】GL 截帧返回 null");
                return;
            }

            // 2. Bitmap → JPEG byte[]
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 92, baos);
            byte[] jpegBytes = baos.toByteArray();
            frame.recycle();

            // 3. 通过 ImageWriter 注入 JPEG
            ImageWriter writer = imageWriterMap.get(targetSurface);
            if (writer == null) {
                writer = ImageWriter.newInstance(targetSurface, 2);
                imageWriterMap.put(targetSurface, writer);
            }
            Image image = writer.dequeueInputImage();
            if (image == null)
                return;

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.clear();

            if (jpegBytes.length <= buffer.capacity()) {
                buffer.put(jpegBytes);
            } else {
                // 降低质量重新压缩
                baos.reset();
                Bitmap frame2 = activeRenderer.captureFrame(w, h);
                if (frame2 != null) {
                    frame2.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                    byte[] smaller = baos.toByteArray();
                    if (smaller.length <= buffer.capacity()) {
                        buffer.put(smaller);
                    } else {
                        LogUtil.log("【CS】照片压缩后依然大于 Buffer 容量 (" + smaller.length + " > " + buffer.capacity() + ")");
                    }
                    frame2.recycle();
                }
            }
            writer.queueInputImage(image);
            LogUtil.log("【CS】成功泵入一张伪造图片 (" + jpegBytes.length + " bytes)");
        } catch (Exception e) {
            LogUtil.log("【CS】照片注入失败: " + e);
        }
    }
}
