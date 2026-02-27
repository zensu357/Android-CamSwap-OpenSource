package com.example.camswap;

import android.media.AudioFormat;
import android.media.MediaPlayer;
import android.os.Build;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.example.camswap.utils.AudioDataProvider;
import com.example.camswap.utils.LogUtil;
import com.example.camswap.utils.VideoManager;

/**
 * 麦克风 Hook 处理器 —— 支持三种模式
 * <ul>
 * <li><b>静音模式 (mute)</b>：方案 A，将音频数据替换为全零</li>
 * <li><b>替换模式 (replace)</b>：方案 B，注入本地音频文件的 PCM 数据</li>
 * <li><b>视频同步 (video_sync)</b>：方案 C，从当前视频提取音轨，与视频帧同步播放</li>
 * </ul>
 * <p>
 * 所有 Hook 在执行替换前都会实时检查 {@link ConfigManager#KEY_ENABLE_MIC_HOOK} 配置值，
 * 当配置为 {@code false} 时不做任何操作，保证运行时可热切换。
 */
public class MicrophoneHandler implements ICameraHandler {

    private static final String TAG = "【CS】[Mic]";

    // 方案 B 时长校验：是否已提醒过用户
    private static volatile boolean durationWarningShown = false;

    /**
     * 存储每个 AudioRecord 实例的构造参数
     * 使用 WeakHashMap 避免内存泄漏
     */
    private static final Map<Object, AudioRecordParams> recordParamsMap = new WeakHashMap<>();

    /**
     * AudioRecord 构造参数
     */
    private static class AudioRecordParams {
        final int audioSource;
        final int sampleRate;
        final int channelConfig;
        final int audioFormat;
        final int bufferSize;
        final int channelCount;

        AudioRecordParams(int audioSource, int sampleRate, int channelConfig,
                int audioFormat, int bufferSize) {
            this.audioSource = audioSource;
            this.sampleRate = sampleRate;
            this.channelConfig = channelConfig;
            this.audioFormat = audioFormat;
            this.bufferSize = bufferSize;
            this.channelCount = getChannelCount(channelConfig);
        }

        private static int getChannelCount(int channelConfig) {
            switch (channelConfig) {
                case AudioFormat.CHANNEL_IN_MONO:
                    return 1;
                case AudioFormat.CHANNEL_IN_STEREO:
                    return 2;
                default:
                    return Integer.bitCount(channelConfig);
            }
        }
    }

    // ================================================================
    // 配置读取
    // ================================================================

    private static boolean isMicHookEnabled() {
        try {
            return VideoManager.getConfig().getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getMicHookMode() {
        try {
            return VideoManager.getConfig().getString(
                    ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE);
        } catch (Exception e) {
            return ConfigManager.MIC_MODE_MUTE;
        }
    }

    // ================================================================
    // 模式判断 + 数据加载
    // ================================================================

    /**
     * 检查是否为替换模式，并确保音频数据已加载
     * 每次调用都检查配置文件路径是否变更，变更时重新加载
     */
    private static boolean isReplaceMode() {
        if (!ConfigManager.MIC_MODE_REPLACE.equals(getMicHookMode())) {
            return false;
        }
        String audioPath = AudioDataProvider.getAudioFilePath();
        if (audioPath == null)
            return false;

        // 检查是否需要（重新）加载：未就绪 或 文件已切换
        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !audioPath.equals(loadedPath)) {
            AudioDataProvider.loadAudioFile(audioPath);
            durationWarningShown = false; // 文件变了，重置警告
            checkDurationMismatch();
        }
        return AudioDataProvider.isReady();
    }

    /**
     * 检查是否为视频同步模式，并确保视频音轨数据已加载
     * 每次调用都检查视频路径是否变更，变更时重新加载
     */
    private static boolean isVideoSyncMode() {
        if (!ConfigManager.MIC_MODE_VIDEO_SYNC.equals(getMicHookMode())) {
            return false;
        }
        String videoPath = VideoManager.getCurrentVideoPath();
        if (videoPath == null)
            return false;

        String loadedPath = AudioDataProvider.getCurrentFilePath();
        if (!AudioDataProvider.isReady() || !videoPath.equals(loadedPath)) {
            AudioDataProvider.loadAudioFile(videoPath);
        }
        return AudioDataProvider.isReady();
    }

    /**
     * 获取当前视频 MediaPlayer 的播放位置（毫秒）
     * 按优先级尝试所有可能的 MediaPlayer 实例
     */
    private static long getVideoPlaybackPositionMs() {
        MediaPlayer[] players = {
                HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
        };
        for (MediaPlayer mp : players) {
            try {
                if (mp != null && mp.isPlaying()) {
                    return mp.getCurrentPosition();
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /**
     * 获取指定 AudioRecord 实例的参数
     */
    private static AudioRecordParams getParams(Object audioRecord) {
        synchronized (recordParamsMap) {
            AudioRecordParams params = recordParamsMap.get(audioRecord);
            if (params != null)
                return params;
        }
        return new AudioRecordParams(0, 44100,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096);
    }

    /**
     * 方案 B 时长校验：对比音频文件与视频文件时长
     */
    private static void checkDurationMismatch() {
        if (durationWarningShown)
            return;

        try {
            long audioDuration = AudioDataProvider.getDurationMs();
            if (audioDuration <= 0)
                return;

            // 尝试获取视频时长
            long videoDuration = -1;
            MediaPlayer[] players = {
                    HookMain.playerManager.c2_player, HookMain.playerManager.c2_player_1,
                    HookMain.playerManager.mMediaPlayer, HookMain.playerManager.mplayer1
            };
            for (MediaPlayer mp : players) {
                try {
                    if (mp != null) {
                        videoDuration = mp.getDuration();
                        if (videoDuration > 0)
                            break;
                    }
                } catch (Exception ignored) {
                }
            }

            if (videoDuration <= 0)
                return;

            // 超过 2 秒差异时警告
            long diff = Math.abs(audioDuration - videoDuration);
            if (diff > 2000) {
                String msg = "【CS】⚠ 音频文件时长(" + (audioDuration / 1000) + "s)与视频时长("
                        + (videoDuration / 1000) + "s)不一致，可能导致音画不同步";
                LogUtil.log(msg);
                VideoManager.showToast("音频与视频时长不一致\n音频: " + (audioDuration / 1000)
                        + "s  视频: " + (videoDuration / 1000) + "s");
                durationWarningShown = true;
            }
        } catch (Exception e) {
            LogUtil.log(TAG + " 时长校验异常: " + e);
        }
    }

    // ================================================================
    // Hook 初始化
    // ================================================================

    @Override
    public void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        LogUtil.log(TAG + " 初始化麦克风 Hook");

        // ============================================================
        // 1. Hook AudioRecord 构造函数 — 捕获参数 + 预加载音频
        // ============================================================
        try {
            XposedHelpers.findAndHookConstructor(
                    "android.media.AudioRecord", lpparam.classLoader,
                    int.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int audioSource = (int) param.args[0];
                            int sampleRate = (int) param.args[1];
                            int channelConfig = (int) param.args[2];
                            int audioFormat = (int) param.args[3];
                            int bufferSize = (int) param.args[4];

                            LogUtil.log(TAG + " AudioRecord 创建: audioSource=" + audioSource
                                    + " sampleRate=" + sampleRate
                                    + " channelConfig=" + channelConfig
                                    + " audioFormat=" + audioFormat
                                    + " bufferSize=" + bufferSize);

                            AudioRecordParams params = new AudioRecordParams(
                                    audioSource, sampleRate, channelConfig, audioFormat, bufferSize);
                            synchronized (recordParamsMap) {
                                recordParamsMap.put(param.thisObject, params);
                            }

                            // 预加载音频数据
                            if (isMicHookEnabled()) {
                                String mode = getMicHookMode();
                                if (ConfigManager.MIC_MODE_REPLACE.equals(mode)) {
                                    String audioPath = AudioDataProvider.getAudioFilePath();
                                    if (audioPath != null) {
                                        AudioDataProvider.loadAudioFile(audioPath);
                                        checkDurationMismatch();
                                    }
                                } else if (ConfigManager.MIC_MODE_VIDEO_SYNC.equals(mode)) {
                                    String videoPath = VideoManager.getCurrentVideoPath();
                                    if (videoPath != null) {
                                        AudioDataProvider.loadAudioFile(videoPath);
                                    }
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord 构造函数失败: " + t);
        }

        // ============================================================
        // 2. Hook AudioRecord.read(byte[], int, int)
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "read", byte[].class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isMicHookEnabled())
                                return;
                            int result = (int) param.getResult();
                            if (result <= 0)
                                return;

                            byte[] buffer = (byte[]) param.args[0];
                            int offset = (int) param.args[1];
                            AudioRecordParams p = getParams(param.thisObject);

                            if (isVideoSyncMode()) {
                                long posMs = getVideoPlaybackPositionMs();
                                AudioDataProvider.fillBytesAtPosition(buffer, offset, result,
                                        p.sampleRate, p.channelCount, posMs);
                            } else if (isReplaceMode()) {
                                AudioDataProvider.fillBytes(buffer, offset, result,
                                        p.sampleRate, p.channelCount);
                            } else {
                                Arrays.fill(buffer, offset, offset + result, (byte) 0);
                            }
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.read(byte[]) 失败: " + t);
        }

        // ============================================================
        // 3. Hook AudioRecord.read(short[], int, int)
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "read", short[].class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isMicHookEnabled())
                                return;
                            int result = (int) param.getResult();
                            if (result <= 0)
                                return;

                            short[] buffer = (short[]) param.args[0];
                            int offset = (int) param.args[1];
                            AudioRecordParams p = getParams(param.thisObject);

                            if (isVideoSyncMode()) {
                                long posMs = getVideoPlaybackPositionMs();
                                AudioDataProvider.fillShortsAtPosition(buffer, offset, result,
                                        p.sampleRate, p.channelCount, posMs);
                            } else if (isReplaceMode()) {
                                AudioDataProvider.fillShorts(buffer, offset, result,
                                        p.sampleRate, p.channelCount);
                            } else {
                                Arrays.fill(buffer, offset, offset + result, (short) 0);
                            }
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.read(short[]) 失败: " + t);
        }

        // ============================================================
        // 4. Hook AudioRecord.read(ByteBuffer, int)
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioRecord", lpparam.classLoader,
                    "read", ByteBuffer.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isMicHookEnabled())
                                return;
                            int result = (int) param.getResult();
                            if (result <= 0)
                                return;

                            ByteBuffer buffer = (ByteBuffer) param.args[0];
                            int pos = buffer.position();
                            AudioRecordParams p = getParams(param.thisObject);

                            if (isVideoSyncMode()) {
                                long posMs = getVideoPlaybackPositionMs();
                                buffer.position(pos - result);
                                AudioDataProvider.fillByteBufferAtPosition(buffer, result,
                                        p.sampleRate, p.channelCount, posMs);
                                buffer.position(pos);
                            } else if (isReplaceMode()) {
                                buffer.position(pos - result);
                                AudioDataProvider.fillByteBuffer(buffer, result,
                                        p.sampleRate, p.channelCount);
                                buffer.position(pos);
                            } else {
                                byte[] zeros = new byte[result];
                                buffer.position(pos - result);
                                buffer.put(zeros);
                                buffer.position(pos);
                            }
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook AudioRecord.read(ByteBuffer, int) 失败: " + t);
        }

        // ============================================================
        // 5. Hook AudioRecord.read(float[], int, int, int) — API 23+
        // ============================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(
                        "android.media.AudioRecord", lpparam.classLoader,
                        "read", float[].class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!isMicHookEnabled())
                                    return;
                                int result = (int) param.getResult();
                                if (result <= 0)
                                    return;

                                float[] buffer = (float[]) param.args[0];
                                int offset = (int) param.args[1];
                                AudioRecordParams p = getParams(param.thisObject);

                                if (isVideoSyncMode()) {
                                    long posMs = getVideoPlaybackPositionMs();
                                    AudioDataProvider.fillFloatsAtPosition(buffer, offset, result,
                                            p.sampleRate, p.channelCount, posMs);
                                } else if (isReplaceMode()) {
                                    AudioDataProvider.fillFloats(buffer, offset, result,
                                            p.sampleRate, p.channelCount);
                                } else {
                                    Arrays.fill(buffer, offset, offset + result, 0.0f);
                                }
                            }
                        });
            } catch (Throwable t) {
                LogUtil.log(TAG + " Hook AudioRecord.read(float[]) 失败: " + t);
            }
        }

        // ============================================================
        // 6. Hook AudioRecord.read(ByteBuffer, int, int) — API 23+
        // ============================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(
                        "android.media.AudioRecord", lpparam.classLoader,
                        "read", ByteBuffer.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!isMicHookEnabled())
                                    return;
                                int result = (int) param.getResult();
                                if (result <= 0)
                                    return;

                                ByteBuffer buffer = (ByteBuffer) param.args[0];
                                int pos = buffer.position();
                                AudioRecordParams p = getParams(param.thisObject);

                                if (isVideoSyncMode()) {
                                    long posMs = getVideoPlaybackPositionMs();
                                    buffer.position(pos - result);
                                    AudioDataProvider.fillByteBufferAtPosition(buffer, result,
                                            p.sampleRate, p.channelCount, posMs);
                                    buffer.position(pos);
                                } else if (isReplaceMode()) {
                                    buffer.position(pos - result);
                                    AudioDataProvider.fillByteBuffer(buffer, result,
                                            p.sampleRate, p.channelCount);
                                    buffer.position(pos);
                                } else {
                                    byte[] zeros = new byte[result];
                                    buffer.position(pos - result);
                                    buffer.put(zeros);
                                    buffer.position(pos);
                                }
                            }
                        });
            } catch (Throwable t) {
                LogUtil.log(TAG + " Hook AudioRecord.read(ByteBuffer, int, int) 失败: " + t);
            }
        }

        // ============================================================
        // 7. Hook MediaRecorder.setAudioSource(int) — 日志
        // ============================================================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.MediaRecorder", lpparam.classLoader,
                    "setAudioSource", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            LogUtil.log(TAG + " MediaRecorder.setAudioSource: " + param.args[0]
                                    + " (micHook=" + isMicHookEnabled()
                                    + " mode=" + getMicHookMode() + ")");
                        }
                    });
        } catch (Throwable t) {
            LogUtil.log(TAG + " Hook MediaRecorder.setAudioSource 失败: " + t);
        }

        LogUtil.log(TAG + " 麦克风 Hook 初始化完成");
    }
}
