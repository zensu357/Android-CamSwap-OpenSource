package com.example.camswap;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import android.os.FileObserver;
import java.io.FileInputStream;
import java.util.Collections;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;

import com.example.camswap.utils.ImageUtils;
import com.example.camswap.utils.PermissionHelper;
import com.example.camswap.utils.VideoManager;
import com.example.camswap.utils.LogUtil;

public class HookMain implements IXposedHookLoadPackage {
    public static Surface mSurface;
    public static SurfaceTexture mSurfacetexture;
    public static MediaPlayer mMediaPlayer;
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
    public static MediaPlayer c2_reader_player;
    public static MediaPlayer c2_reader_player_1;
    public static SurfaceTexture c1_fake_texture;
    public static Surface c1_fake_surface;
    public static SurfaceHolder ori_holder;
    public static MediaPlayer mplayer1;
    public static Camera mcamera1;
    public static int imageReaderFormat = 0;
    public static boolean is_first_hook_build = true;
    public static volatile int mDisplayOrientation = 0; // 宿主App通过setDisplayOrientation设置的期望方向

    // Thread safety lock for MediaPlayer/decoder operations
    private static final Object mediaLock = new Object();

    // GL renderers for rotation (one per MediaPlayer)
    public static GLVideoRenderer c2_reader_renderer;
    public static GLVideoRenderer c2_reader_renderer_1;
    public static GLVideoRenderer c2_renderer;
    public static GLVideoRenderer c2_renderer_1;
    public static GLVideoRenderer c1_renderer_holder;
    public static GLVideoRenderer c1_renderer_texture;

    public static int onemhight;
    public static int onemwidth;
    public static Class camera_callback_calss;

    public static android.os.ParcelFileDescriptor getVideoPFD() {
        return VideoManager.getVideoPFD();
    }

    private static void checkProviderAvailability() {
        VideoManager.checkProviderAvailability();
    }

    // Removed FileObserver logic
    private static android.database.ContentObserver configObserver;
    private static FileObserver configFileObserver;

    private static void initContentObserver(final Context context) {
        if (configObserver == null) {
            LogUtil.log("【CS】正在初始化配置监听器");
            configObserver = new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    LogUtil.log("【CS】通过 ContentProvider 监听到配置变更");
                    getConfig().forceReload();
                    VideoManager.updateVideoPath(false);
                    restartDecoders();
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

            // Fallback：当 Provider 不可用时，使用 FileObserver 监听配置文件
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
                                // 延迟 200ms 确保文件写入完成
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    getConfig().forceReload();
                                    VideoManager.updateVideoPath(false);
                                    restartDecoders();
                                }, 200);
                            }
                        }
                    };
                    configFileObserver.startWatching();
                    LogUtil.log("【CS】FileObserver 启动成功，监控目录: " + configDir);
                } catch (Exception e) {
                    LogUtil.log("【CS】FileObserver 启动失败: " + e);
                }

                // Active Config Request: Provider 不可用时，主动向主 App 请求配置
                // 解决冷启动时无配置且无文件权限的问题
                LogUtil.log("【CS】主动请求配置广播...");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    VideoManager.getConfig().requestConfig(context);
                }, 1000); // 延迟 1 秒确保接收器已注册
            }

            // Also register BroadcastReceiver for control signals (e.g. from Notification)
            // This allows us to receive commands without relying solely on file/provider
            // changes
            try {
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        LogUtil.log("【CS】收到广播指令: " + action);
                        if (ConfigManager.ACTION_UPDATE_CONFIG.equals(action)) {
                            String configJson = intent.getStringExtra(ConfigManager.EXTRA_CONFIG_JSON);
                            if (configJson != null) {
                                // Backup old crucial values before updating
                                String oldVideo = getConfig().getString(ConfigManager.KEY_SELECTED_VIDEO, "");
                                String oldImage = getConfig().getString(ConfigManager.KEY_SELECTED_IMAGE, "");
                                String oldMode = getConfig().getString("replace_mode", "video");
                                boolean oldFpd = getConfig().getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
                                int oldRotation = getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);

                                getConfig().updateConfigFromJSON(configJson);

                                // Check what actually changed
                                String newVideo = getConfig().getString(ConfigManager.KEY_SELECTED_VIDEO, "");
                                String newImage = getConfig().getString(ConfigManager.KEY_SELECTED_IMAGE, "");
                                String newMode = getConfig().getString("replace_mode", "video");
                                boolean newFpd = getConfig().getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false);
                                int newRotation = getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);

                                boolean mediaChanged = !oldVideo.equals(newVideo) ||
                                        !oldImage.equals(newImage) ||
                                        !oldMode.equals(newMode) ||
                                        (oldFpd != newFpd);

                                if (mediaChanged) {
                                    if (getConfig().getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false)) {
                                        android.os.Bundle bundle = intent.getBundleExtra("video_bundle");
                                        if (bundle != null) {
                                            LogUtil.log("【CS】成功获取到 video_bundle");
                                            android.os.IBinder binder = bundle.getBinder("video_binder");
                                            if (binder != null) {
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
                                                        android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.CREATOR
                                                                .createFromParcel(reply);
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
                                            } else {
                                                LogUtil.log("【CS】bundle.getBinder 返回 null");
                                            }
                                        } else {
                                            LogUtil.log("【CS】并没有找到 video_bundle 额外数据");
                                        }
                                    }

                                    VideoManager.updateVideoPath(false);
                                    restartDecoders();
                                    LogUtil.log("【CS】收到配置更新且媒体源发生变化，已应用并重启播放器");
                                } else {
                                    if (oldRotation != newRotation) {
                                        LogUtil.log("【CS】收到配置更新，仅旋转偏移变更: " + newRotation + "°");
                                        updateAllRendererRotations(newRotation);
                                    } else {
                                        LogUtil.log("【CS】收到配置更新，核心媒体参数无变化，忽略重启");
                                    }
                                }
                            }
                        } else if ("com.example.camswap.ACTION_CAMSWAP_NEXT".equals(action)) {
                            // Provider 不可用时，直接走本地文件切换
                            if (!VideoManager.isProviderAvailable()) {
                                VideoManager.switchVideo(true);
                                restartDecoders();
                            } else {
                                switchVideo(true);
                            }
                        } else if ("com.example.camswap.ACTION_CAMSWAP_ROTATE".equals(action)) {
                            // 旋转偏移变更，重载配置
                            getConfig().forceReload();
                            int newRotation = getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
                            LogUtil.log("【CS】旋转偏移已更新: " + newRotation + "°");
                            // 通过 GL 渲染器实时更新旋转，无需重启播放器
                            updateAllRendererRotations(newRotation);
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
    }

    private static void switchVideo(boolean next) {
        if (VideoManager.switchVideo(next)) {
            restartDecoders();
        }
    }

    private static void restartDecoders() {
        synchronized (mediaLock) {
            // Check provider availability
            checkProviderAvailability();

            // Restart Camera1 players (保持渲染器，只重载视频源)
            restartMediaPlayer(mplayer1, c1_renderer_holder, "mplayer1");
            restartMediaPlayer(mMediaPlayer, c1_renderer_texture, "mMediaPlayer");

            // Restart Camera2 reader players
            restartMediaPlayer(c2_reader_player, c2_reader_renderer, "c2_reader_player");
            restartMediaPlayer(c2_reader_player_1, c2_reader_renderer_1, "c2_reader_player_1");

            // Restart Camera2 preview players
            restartMediaPlayer(c2_player, c2_renderer, "c2_player");
            restartMediaPlayer(c2_player_1, c2_renderer_1, "c2_player_1");
        }
    }

    private static void restartMediaPlayer(MediaPlayer player, GLVideoRenderer renderer, String tag) {
        if (player == null)
            return;
        try {
            if (player.isPlaying()) {
                player.stop();
            }
            player.reset();
            // reset() 会清除 surface，需要重新设置
            if (renderer != null && renderer.isInitialized()) {
                player.setSurface(renderer.getInputSurface());
            }
            android.os.ParcelFileDescriptor pfd = getVideoPFD();
            if (pfd != null) {
                player.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                player.setDataSource(VideoManager.getCurrentVideoPath());
            }
            player.prepare();
            player.start();
        } catch (Exception e) {
            LogUtil.log("【CS】重启 " + tag + " 失败: " + android.util.Log.getStackTraceString(e));
        }
    }

    /**
     * 更新所有 GL 渲染器的旋转角度（实时生效，无需重启播放器）。
     */
    public static void updateAllRendererRotations(int degrees) {
        GLVideoRenderer[] renderers = {
                c2_reader_renderer, c2_reader_renderer_1,
                c2_renderer, c2_renderer_1,
                c1_renderer_holder, c1_renderer_texture
        };
        for (GLVideoRenderer r : renderers) {
            if (r != null && r.isInitialized()) {
                r.setRotation(degrees);
            }
        }
        LogUtil.log("【CS】所有渲染器旋转角度已更新: " + degrees + "°");
    }

    /**
     * 释放所有 GL 渲染器。
     */
    public static void releaseAllRenderers() {
        GLVideoRenderer.releaseSafely(c2_reader_renderer);
        c2_reader_renderer = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
        c2_reader_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c2_renderer);
        c2_renderer = null;
        GLVideoRenderer.releaseSafely(c2_renderer_1);
        c2_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c1_renderer_holder);
        c1_renderer_holder = null;
        GLVideoRenderer.releaseSafely(c1_renderer_texture);
        c1_renderer_texture = null;
    }

    /**
     * 为 MediaPlayer 创建 GL 渲染器并设置到目标 Surface。
     * 失败时回退到直接 Surface（无旋转支持）。
     *
     * @return 创建的渲染器，失败返回 null
     */
    private static GLVideoRenderer setupPlayerWithRenderer(MediaPlayer player, Surface targetSurface, String tag) {
        int rotation = getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        GLVideoRenderer renderer = GLVideoRenderer.createSafely(targetSurface, tag);
        if (renderer != null) {
            player.setSurface(renderer.getInputSurface());
            renderer.setRotation(rotation);
            LogUtil.log("【CS】【GL】" + tag + " 使用 GL 渲染器 (旋转:" + rotation + "°)");
        } else {
            // Fallback: 直接播放到 Surface（无旋转支持）
            player.setSurface(targetSurface);
            LogUtil.log("【CS】" + tag + " 回退到直接 Surface（无旋转）");
        }
        return renderer;
    }

    private static void restartDecoder(VideoToFrames decoder, Surface surface, String tag) {
        if (decoder == null)
            return;
        try {
            decoder.setSaveFrames("null", OutputImageFormat.NV21);
            decoder.set_surfcae(surface);
            android.os.ParcelFileDescriptor pfd = getVideoPFD();
            if (pfd != null) {
                decoder.reset(pfd);
            } else {
                decoder.reset(VideoManager.getCurrentVideoPath());
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】重启 " + tag + " 失败: " + android.util.Log.getStackTraceString(t));
        }
    }

    // Video path logic moved to VideoManager
    public static void updateVideoPath(boolean forceRandom) {
        VideoManager.updateVideoPath(forceRandom);
    }

    public static String getCurrentVideoPath() {
        return VideoManager.getCurrentVideoPath();
    }

    public static void reloadRandomVideo() {
        VideoManager.updateVideoPath(true);
    }

    public static Surface c2_preview_Surfcae;
    public static Surface c2_preview_Surfcae_1;
    public static Surface c2_reader_Surfcae;
    public static Surface c2_reader_Surfcae_1;
    public static MediaPlayer c2_player;
    public static MediaPlayer c2_player_1;
    public static Surface c2_virtual_surface;
    public static SurfaceTexture c2_virtual_surfaceTexture;
    public static boolean need_recreate;
    public static CameraDevice.StateCallback c2_state_cb;
    public static CaptureRequest.Builder c2_builder;
    public static SessionConfiguration fake_sessionConfiguration;
    public static SessionConfiguration sessionConfiguration;
    public static OutputConfiguration outputConfiguration;
    public static boolean need_to_show_toast = true;

    public static int c2_ori_width = 1280;
    public static int c2_ori_height = 720;

    public static Class c2_state_callback;
    public static Context toast_content;

    public static ConfigManager getConfig() {
        return VideoManager.getConfig();
    }

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

        // Removed FileObserver init

        // Initialize video path from config immediately
        // Removed premature updateVideoPath call. Path setup should wait for Context
        // and PermissionHelper.
        // VideoManager.updateVideoPath(false);

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
                                checkProviderAvailability(); // Check provider immediately

                                // Initialize ConfigManager with context
                                getConfig().setContext(toast_content);
                                // Initialize ContentObserver
                                initContentObserver(toast_content);
                            } catch (Exception ee) {
                                LogUtil.log("【CS】" + ee.toString());
                            }

                            // Delegate permission and path setup to PermissionHelper
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

    public static void process_camera2_play() {
        process_camera2_play_video();
    }

    /**
     * Helper to setup MediaPlayer reliably, isolating EGL/Surface issues.
     */
    private static void setupMediaPlayer(MediaPlayer player, GLVideoRenderer[] rendererRef, SurfaceRelay[] relayRef,
            Surface targetSurface,
            String tag, boolean playSound) {
        if (targetSurface == null)
            return;

        GLVideoRenderer.releaseSafely(rendererRef[0]);
        SurfaceRelay.releaseSafely(relayRef[0]);
        int rotation = getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);

        // 1. Try GL Renderer setup
        rendererRef[0] = GLVideoRenderer.createSafely(targetSurface, tag);

        if (!playSound) {
            player.setVolume(0, 0);
        }
        player.setLooping(true);

        try {
            // 2. Setup Data Source
            android.os.ParcelFileDescriptor pfd = getVideoPFD();
            if (pfd != null) {
                player.setDataSource(pfd.getFileDescriptor());
                // We MUST not close the PFD before prepare() is finished!
                // However, setDataSource(FD) usually duplicates it or consumes it
                // synchronously.
                // Just in case, we will close it immediately as standard Android docs allow,
                // but if this still fails, we'll know it's the Surface.
                pfd.close();
            } else {
                player.setDataSource(getCurrentVideoPath());
            }

            // 3. Prepare FIRST, before setting the potentially problematic targetSurface!
            // If prepare() fails here, it's 100% the video file/FD.
            player.prepare();

            // 4. Set Surface AFTER successful prepare()
            if (rendererRef[0] != null) {
                player.setSurface(rendererRef[0].getInputSurface());
                rendererRef[0].setRotation(rotation);
                LogUtil.log("【CS】【GL】" + tag + " 使用 GL 渲染器 (旋转:" + rotation + "°)");
            } else {
                // Tier 2 Fallback: SurfaceRelay
                LogUtil.log("【CS】【Relay】" + tag + " GL 失败，尝试 SurfaceTexture 中继");
                relayRef[0] = SurfaceRelay.createSafely(targetSurface, tag);

                if (relayRef[0] != null) {
                    player.setSurface(relayRef[0].getInputSurface());
                    relayRef[0].setRotation(rotation);
                    LogUtil.log("【CS】【Relay】" + tag + " 使用 Relay 渲染器 (旋转:" + rotation + "°)");
                } else {
                    // Tier 3: Direct Surface fallback (May still cause MediaServer to crash native
                    // window)
                    player.setSurface(targetSurface);
                    LogUtil.log("【CS】" + tag + " 回退到直接 Surface（无旋转）");
                }
            }

            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    player.start();
                }
            });
            // Since prepare() is synchronous, we can start immediately:
            player.start();
            LogUtil.log("【CS】" + tag + " 已启动播放");

        } catch (Exception e) {
            LogUtil.log("【CS】[" + tag + "] 初始化播放器异常: " + android.util.Log.getStackTraceString(e));
        }
    }

    public static SurfaceRelay c2_reader_relay;
    public static SurfaceRelay c2_reader_relay_1;
    public static SurfaceRelay c2_relay;
    public static SurfaceRelay c2_relay_1;

    /**
     * 视频模式：原有的 MediaPlayer 路径（保持不变）。
     */
    private static void process_camera2_play_video() {
        if (c2_reader_Surfcae != null) {
            if (c2_reader_player == null)
                c2_reader_player = new MediaPlayer();
            else {
                c2_reader_player.release();
                c2_reader_player = new MediaPlayer();
            }

            GLVideoRenderer[] r = new GLVideoRenderer[] { c2_reader_renderer };
            SurfaceRelay[] rr = new SurfaceRelay[] { c2_reader_relay };
            setupMediaPlayer(c2_reader_player, r, rr, c2_reader_Surfcae, "c2_reader", false);
            c2_reader_renderer = r[0];
            c2_reader_relay = rr[0];
        }

        if (c2_reader_Surfcae_1 != null) {
            if (c2_reader_player_1 == null)
                c2_reader_player_1 = new MediaPlayer();
            else {
                c2_reader_player_1.release();
                c2_reader_player_1 = new MediaPlayer();
            }

            GLVideoRenderer[] r = new GLVideoRenderer[] { c2_reader_renderer_1 };
            SurfaceRelay[] rr = new SurfaceRelay[] { c2_reader_relay_1 };
            setupMediaPlayer(c2_reader_player_1, r, rr, c2_reader_Surfcae_1, "c2_reader_1", false);
            c2_reader_renderer_1 = r[0];
            c2_reader_relay_1 = rr[0];
        }

        if (c2_preview_Surfcae != null) {
            if (c2_player == null)
                c2_player = new MediaPlayer();
            else {
                c2_player.release();
                c2_player = new MediaPlayer();
            }

            boolean playSound = getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);
            GLVideoRenderer[] r = new GLVideoRenderer[] { c2_renderer };
            SurfaceRelay[] rr = new SurfaceRelay[] { c2_relay };
            setupMediaPlayer(c2_player, r, rr, c2_preview_Surfcae, "c2_preview", playSound);
            c2_renderer = r[0];
            c2_relay = rr[0];
        }

        if (c2_preview_Surfcae_1 != null) {
            if (c2_player_1 == null)
                c2_player_1 = new MediaPlayer();
            else {
                c2_player_1.release();
                c2_player_1 = new MediaPlayer();
            }

            boolean playSound = getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);
            GLVideoRenderer[] r = new GLVideoRenderer[] { c2_renderer_1 };
            SurfaceRelay[] rr = new SurfaceRelay[] { c2_relay_1 };
            setupMediaPlayer(c2_player_1, r, rr, c2_preview_Surfcae_1, "c2_preview_1", playSound);
            c2_renderer_1 = r[0];
            c2_relay = rr[0];
        }

        LogUtil.log("【CS】Camera2处理过程完全执行");
    }

    private static Surface create_virtual_surface() {
        if (need_recreate) {
            if (c2_virtual_surfaceTexture != null) {
                c2_virtual_surfaceTexture.release();
                c2_virtual_surfaceTexture = null;
            }
            if (c2_virtual_surface != null) {
                c2_virtual_surface.release();
                c2_virtual_surface = null;
            }
            c2_virtual_surfaceTexture = new SurfaceTexture(15);
            c2_virtual_surface = new Surface(c2_virtual_surfaceTexture);
            need_recreate = false;
        } else {
            if (c2_virtual_surface == null) {
                need_recreate = true;
                c2_virtual_surface = create_virtual_surface();
            }
        }
        LogUtil.log("【CS】【重建虚拟Surface】" + c2_virtual_surface.toString());
        return c2_virtual_surface;
    }

    public static void process_camera2_init(Class hooked_class) {

        XposedHelpers.findAndHookMethod(hooked_class, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                need_recreate = true;
                create_virtual_surface();
                // 释放 Camera2 相关的 GL 渲染器
                GLVideoRenderer.releaseSafely(c2_renderer);
                c2_renderer = null;
                GLVideoRenderer.releaseSafely(c2_renderer_1);
                c2_renderer_1 = null;
                GLVideoRenderer.releaseSafely(c2_reader_renderer);
                c2_reader_renderer = null;
                GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
                GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
                c2_reader_renderer_1 = null;
                if (c2_player != null) {
                    c2_player.stop();
                    c2_player.reset();
                    c2_player.release();
                    c2_player = null;
                }
                if (c2_reader_player_1 != null) {
                    try {
                        c2_reader_player_1.stop();
                    } catch (Exception ignored) {
                    }
                    c2_reader_player_1.release();
                    c2_reader_player_1 = null;
                }
                if (c2_reader_player != null) {
                    try {
                        c2_reader_player.stop();
                    } catch (Exception ignored) {
                    }
                    c2_reader_player.release();
                    c2_reader_player = null;
                }
                if (c2_player_1 != null) {
                    c2_player_1.stop();
                    c2_player_1.reset();
                    c2_player_1.release();
                    c2_player_1 = null;
                }
                c2_preview_Surfcae_1 = null;
                c2_reader_Surfcae_1 = null;
                c2_reader_Surfcae = null;
                c2_preview_Surfcae = null;
                is_first_hook_build = true;
                LogUtil.log("【CS】打开相机C2");

                File file = new File(VideoManager.getCurrentVideoPath());
                need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                if (!file.exists()) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            showToast("不存在替换视频\n" + toast_content.getPackageName() + "当前路径："
                                    + VideoManager.video_path);
                        } catch (Exception ee) {
                            LogUtil.log("【CS】[toast]" + ee.toString());
                        }
                    }
                    return;
                }
                XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession", List.class,
                        CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                                if (paramd.args[0] != null) {
                                    LogUtil.log("【CS】createCaptureSession创建捕获，原始:" + paramd.args[0].toString() + "虚拟："
                                            + c2_virtual_surface.toString());
                                    paramd.args[0] = Arrays.asList(c2_virtual_surface);
                                    if (paramd.args[1] != null) {
                                        process_camera2Session_callback(
                                                (CameraCaptureSession.StateCallback) paramd.args[1]);
                                    }
                                }
                            }
                        });

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(),
                            "createCaptureSessionByOutputConfigurations", List.class,
                            CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    super.beforeHookedMethod(param);
                                    if (param.args[0] != null) {
                                        outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                        param.args[0] = Arrays.asList(outputConfiguration);

                                        LogUtil.log("【CS】执行了createCaptureSessionByOutputConfigurations");
                                        if (param.args[1] != null) {
                                            process_camera2Session_callback(
                                                    (CameraCaptureSession.StateCallback) param.args[1]);
                                        }
                                    }
                                }
                            });
                }

                XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createConstrainedHighSpeedCaptureSession",
                        List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                super.beforeHookedMethod(param);
                                if (param.args[0] != null) {
                                    param.args[0] = Arrays.asList(c2_virtual_surface);
                                    LogUtil.log("【CS】执行了 createConstrainedHighSpeedCaptureSession");
                                    if (param.args[1] != null) {
                                        process_camera2Session_callback(
                                                (CameraCaptureSession.StateCallback) param.args[1]);
                                    }
                                }
                            }
                        });

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createReprocessableCaptureSession",
                            InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class,
                            Handler.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    super.beforeHookedMethod(param);
                                    if (param.args[1] != null) {
                                        param.args[1] = Arrays.asList(c2_virtual_surface);
                                        LogUtil.log("【CS】执行了 createReprocessableCaptureSession ");
                                        if (param.args[2] != null) {
                                            process_camera2Session_callback(
                                                    (CameraCaptureSession.StateCallback) param.args[2]);
                                        }
                                    }
                                }
                            });
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(),
                            "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class,
                            CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    super.beforeHookedMethod(param);
                                    if (param.args[1] != null) {
                                        outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                        param.args[0] = Arrays.asList(outputConfiguration);
                                        LogUtil.log("【CS】执行了 createReprocessableCaptureSessionByConfigurations");
                                        if (param.args[2] != null) {
                                            process_camera2Session_callback(
                                                    (CameraCaptureSession.StateCallback) param.args[2]);
                                        }
                                    }
                                }
                            });
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession",
                            SessionConfiguration.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    super.beforeHookedMethod(param);
                                    if (param.args[0] != null) {
                                        LogUtil.log("【CS】执行了 createCaptureSession (SessionConfiguration)");
                                        sessionConfiguration = (SessionConfiguration) param.args[0];
                                        outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                        fake_sessionConfiguration = new SessionConfiguration(
                                                sessionConfiguration.getSessionType(),
                                                Arrays.asList(outputConfiguration),
                                                sessionConfiguration.getExecutor(),
                                                sessionConfiguration.getStateCallback());
                                        param.args[0] = fake_sessionConfiguration;
                                        process_camera2Session_callback(sessionConfiguration.getStateCallback());
                                    }
                                }
                            });
                }
            }
        });

        XposedHelpers.findAndHookMethod(hooked_class, "onError", CameraDevice.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                LogUtil.log("【CS】相机错误onerror：" + (int) param.args[1]);
            }

        });

        XposedHelpers.findAndHookMethod(hooked_class, "onDisconnected", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                LogUtil.log("【CS】相机断开onDisconnected ：");
            }

        });

    }

    // Image picking logic moved to VideoManager

    public static void process_a_shot_jpeg(XC_MethodHook.MethodHookParam param, int index) {
        try {
            LogUtil.log("【CS】第二个jpeg:" + param.args[index].toString());
        } catch (Exception eee) {
            LogUtil.log("【CS】" + eee);

        }
        Class callback = param.args[index].getClass();

        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, android.hardware.Camera.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        try {
                            Camera loaclcam = (Camera) paramd.args[1];
                            onemwidth = loaclcam.getParameters().getPreviewSize().width;
                            onemhight = loaclcam.getParameters().getPreviewSize().height;
                            LogUtil.log("【CS】JPEG拍照回调初始化：宽：" + onemwidth + "高：" + onemhight + "对应的类："
                                    + loaclcam.toString());
                            need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                            if (toast_content != null && need_to_show_toast) {
                                try {
                                    showToast("发现拍照\n宽：" + onemwidth + "\n高：" + onemhight + "\n格式：JPEG");
                                } catch (Exception e) {
                                    LogUtil.log("【CS】[toast]" + e.toString());
                                }
                            }
                            if (getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                                return;
                            }

                            // Image replacement removed as part of refactoring
                            LogUtil.log("【CS】拍照替换功能已禁用 (纯视频模式)");
                        } catch (Exception ee) {
                            LogUtil.log("【CS】" + ee.toString());
                        }
                    }
                });
    }

    public static void process_a_shot_YUV(XC_MethodHook.MethodHookParam param) {
        try {
            LogUtil.log("【CS】发现拍照YUV:" + param.args[1].toString());
        } catch (Exception eee) {
            LogUtil.log("【CS】" + eee);
        }
        Class callback = param.args[1].getClass();
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, android.hardware.Camera.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        try {
                            Camera loaclcam = (Camera) paramd.args[1];
                            onemwidth = loaclcam.getParameters().getPreviewSize().width;
                            onemhight = loaclcam.getParameters().getPreviewSize().height;
                            LogUtil.log(
                                    "【CS】YUV拍照回调初始化：宽：" + onemwidth + "高：" + onemhight + "对应的类：" + loaclcam.toString());
                            need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                            if (toast_content != null && need_to_show_toast) {
                                try {
                                    showToast("发现拍照\n宽：" + onemwidth + "\n高：" + onemhight + "\n格式：YUV_420_888");
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            if (getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
                                return;
                            }
                            // Image replacement removed as part of refactoring
                            LogUtil.log("【CS】YUV拍照替换功能已禁用 (纯视频模式)");
                        } catch (Exception ee) {
                            LogUtil.log("【CS】" + ee.toString());
                        }
                    }
                });
    }

    public static void process_callback(XC_MethodHook.MethodHookParam param) {
        Class preview_cb_class = param.args[0].getClass();
        int need_stop = 0;
        if (getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            need_stop = 1;
        }
        File file = new File(getCurrentVideoPath());
        need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
        if (!file.exists()) {
            if (toast_content != null && need_to_show_toast) {
                try {
                    showToast("不存在替换视频\n" + toast_content.getPackageName() + "当前路径：" + VideoManager.video_path);
                } catch (Exception ee) {
                    LogUtil.log("【CS】[toast]" + ee);
                }
            }
            need_stop = 1;
        }
        int finalNeed_stop = need_stop;
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame", byte[].class, android.hardware.Camera.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        Camera localcam = (android.hardware.Camera) paramd.args[1];
                        if (localcam.equals(camera_onPreviewFrame)) {
                            for (int _w = 0; _w < 100 && data_buffer == null; _w++) {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                            }
                            if (data_buffer == null)
                                return;
                            System.arraycopy(data_buffer, 0, paramd.args[0], 0,
                                    Math.min(data_buffer.length, ((byte[]) paramd.args[0]).length));
                        } else {
                            camera_callback_calss = preview_cb_class;
                            camera_onPreviewFrame = (android.hardware.Camera) paramd.args[1];
                            mwidth = camera_onPreviewFrame.getParameters().getPreviewSize().width;
                            mhight = camera_onPreviewFrame.getParameters().getPreviewSize().height;
                            int frame_Rate = camera_onPreviewFrame.getParameters().getPreviewFrameRate();
                            LogUtil.log("【CS】帧预览回调初始化：宽：" + mwidth + " 高：" + mhight + " 帧率：" + frame_Rate);
                            need_to_show_toast = !getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
                            if (toast_content != null && need_to_show_toast) {
                                try {
                                    showToast("发现预览\n宽：" + mwidth + "\n高：" + mhight + "\n" + "需要视频分辨率与其完全相同");
                                } catch (Exception ee) {
                                    LogUtil.log("【CS】[toast]" + ee.toString());
                                }
                            }
                            if (finalNeed_stop == 1) {
                                return;
                            }
                            if (hw_decode_obj == null) {
                                hw_decode_obj = new VideoToFrames();
                            }
                            // Set target size to match preview size (fixes WeChat video call 480x640 issue)
                            hw_decode_obj.setTargetSize(mwidth, mhight);

                            hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                            try {
                                hw_decode_obj.reset(getCurrentVideoPath());
                            } catch (Throwable t) {
                                LogUtil.log("【CS】" + t);
                            }
                            for (int _w = 0; _w < 100 && data_buffer == null; _w++) {
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                            }
                            if (data_buffer == null)
                                return;
                            System.arraycopy(data_buffer, 0, paramd.args[0], 0,
                                    Math.min(data_buffer.length, ((byte[]) paramd.args[0]).length));
                        }

                    }
                });

    }

    private static void process_camera2Session_callback(CameraCaptureSession.StateCallback callback_calss) {
        if (callback_calss == null) {
            return;
        }
        XposedHelpers.findAndHookMethod(callback_calss.getClass(), "onConfigureFailed", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        LogUtil.log("【CS】onConfigureFailed ：" + param.args[0].toString());
                    }

                });

        XposedHelpers.findAndHookMethod(callback_calss.getClass(), "onConfigured", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        LogUtil.log("【CS】onConfigured ：" + param.args[0].toString());
                    }
                });

        XposedHelpers.findAndHookMethod(callback_calss.getClass(), "onClosed", CameraCaptureSession.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        LogUtil.log("【CS】onClosed ：" + param.args[0].toString());
                    }
                });
    }

    public static void showToast(final String message) {
        PermissionHelper.showToast(toast_content, message);
    }
}
