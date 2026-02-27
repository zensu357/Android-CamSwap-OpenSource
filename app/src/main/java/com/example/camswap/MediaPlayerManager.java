package com.example.camswap;

import android.media.MediaPlayer;
import android.view.Surface;

import com.example.camswap.utils.LogUtil;
import com.example.camswap.utils.VideoManager;

/**
 * Manages all MediaPlayer, GLVideoRenderer, and SurfaceRelay instances.
 * Centralizes player lifecycle, restart, rotation, and release logic.
 * <p>
 * Future: supports per-app video via {@link #setPackageName(String)}.
 */
public final class MediaPlayerManager {
    private final Object mediaLock = new Object();
    private String currentPackageName;

    // ---- Camera1 players (created by Camera1Handler) ----
    MediaPlayer mplayer1;
    MediaPlayer mMediaPlayer;
    GLVideoRenderer c1_renderer_holder;
    GLVideoRenderer c1_renderer_texture;

    // ---- Camera2 preview players ----
    MediaPlayer c2_player;
    MediaPlayer c2_player_1;
    GLVideoRenderer c2_renderer;
    GLVideoRenderer c2_renderer_1;
    SurfaceRelay c2_relay;
    SurfaceRelay c2_relay_1;

    // ---- Camera2 reader players ----
    MediaPlayer c2_reader_player;
    MediaPlayer c2_reader_player_1;
    GLVideoRenderer c2_reader_renderer;
    GLVideoRenderer c2_reader_renderer_1;
    SurfaceRelay c2_reader_relay;
    SurfaceRelay c2_reader_relay_1;

    /** Set current package name (future per-app video). */
    public void setPackageName(String packageName) {
        this.currentPackageName = packageName;
    }

    /**
     * Central video path query.
     * TODO(功能二): return VideoManager.getVideoPathForPackage(currentPackageName);
     */
    String getVideoPath() {
        return VideoManager.getCurrentVideoPath();
    }

    // =====================================================================
    // Camera2 player initialization
    // =====================================================================

    /**
     * Initialize Camera2 MediaPlayers for the given surfaces.
     */
    void initCamera2Players(Surface readerSurface, Surface readerSurface1,
            Surface previewSurface, Surface previewSurface1) {
        if (readerSurface != null) {
            c2_reader_player = recreatePlayer(c2_reader_player);
            GLVideoRenderer[] r = { c2_reader_renderer };
            SurfaceRelay[] rr = { c2_reader_relay };
            setupMediaPlayer(c2_reader_player, r, rr, readerSurface, "c2_reader", false);
            c2_reader_renderer = r[0];
            c2_reader_relay = rr[0];
        }
        if (readerSurface1 != null) {
            c2_reader_player_1 = recreatePlayer(c2_reader_player_1);
            GLVideoRenderer[] r = { c2_reader_renderer_1 };
            SurfaceRelay[] rr = { c2_reader_relay_1 };
            setupMediaPlayer(c2_reader_player_1, r, rr, readerSurface1, "c2_reader_1", false);
            c2_reader_renderer_1 = r[0];
            c2_reader_relay_1 = rr[0];
        }

        boolean playSound = VideoManager.getConfig().getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false);

        if (previewSurface != null) {
            c2_player = recreatePlayer(c2_player);
            GLVideoRenderer[] r = { c2_renderer };
            SurfaceRelay[] rr = { c2_relay };
            setupMediaPlayer(c2_player, r, rr, previewSurface, "c2_preview", playSound);
            c2_renderer = r[0];
            c2_relay = rr[0];
        }
        if (previewSurface1 != null) {
            c2_player_1 = recreatePlayer(c2_player_1);
            GLVideoRenderer[] r = { c2_renderer_1 };
            SurfaceRelay[] rr = { c2_relay_1 };
            setupMediaPlayer(c2_player_1, r, rr, previewSurface1, "c2_preview_1", playSound);
            c2_renderer_1 = r[0];
            c2_relay_1 = rr[0];
        }
        LogUtil.log("【CS】Camera2处理过程完全执行");
    }

    private MediaPlayer recreatePlayer(MediaPlayer old) {
        if (old != null)
            old.release();
        return new MediaPlayer();
    }

    // =====================================================================
    // Restart / rotation / release
    // =====================================================================

    /** Restart all active MediaPlayers with current video. */
    void restartAll() {
        synchronized (mediaLock) {
            VideoManager.checkProviderAvailability();
            restartSinglePlayer(mplayer1, c1_renderer_holder, "mplayer1");
            restartSinglePlayer(mMediaPlayer, c1_renderer_texture, "mMediaPlayer");
            restartSinglePlayer(c2_reader_player, c2_reader_renderer, "c2_reader_player");
            restartSinglePlayer(c2_reader_player_1, c2_reader_renderer_1, "c2_reader_player_1");
            restartSinglePlayer(c2_player, c2_renderer, "c2_player");
            restartSinglePlayer(c2_player_1, c2_renderer_1, "c2_player_1");
        }
    }

    private void restartSinglePlayer(MediaPlayer player, GLVideoRenderer renderer, String tag) {
        if (player == null)
            return;
        try {
            if (player.isPlaying())
                player.stop();
            player.reset();
            if (renderer != null && renderer.isInitialized()) {
                player.setSurface(renderer.getInputSurface());
            }
            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                player.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                player.setDataSource(getVideoPath());
            }
            player.prepare();
            player.start();
        } catch (Exception e) {
            LogUtil.log("【CS】重启 " + tag + " 失败: " + android.util.Log.getStackTraceString(e));
        }
    }

    /** Update rotation on all active GL renderers (no player restart). */
    void updateRotation(int degrees) {
        GLVideoRenderer[] all = {
                c2_reader_renderer, c2_reader_renderer_1,
                c2_renderer, c2_renderer_1,
                c1_renderer_holder, c1_renderer_texture
        };
        for (GLVideoRenderer r : all) {
            if (r != null && r.isInitialized())
                r.setRotation(degrees);
        }
        LogUtil.log("【CS】所有渲染器旋转角度已更新: " + degrees + "°");
    }

    /** Release all GL renderers. */
    void releaseAllRenderers() {
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

    /** Release Camera2 players and renderers (called from onOpened). */
    void releaseCamera2Resources() {
        GLVideoRenderer.releaseSafely(c2_renderer);
        c2_renderer = null;
        GLVideoRenderer.releaseSafely(c2_renderer_1);
        c2_renderer_1 = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer);
        c2_reader_renderer = null;
        GLVideoRenderer.releaseSafely(c2_reader_renderer_1);
        c2_reader_renderer_1 = null;
        stopAndRelease(c2_player);
        c2_player = null;
        stopAndRelease(c2_reader_player_1);
        c2_reader_player_1 = null;
        stopAndRelease(c2_reader_player);
        c2_reader_player = null;
        stopAndRelease(c2_player_1);
        c2_player_1 = null;
    }

    private void stopAndRelease(MediaPlayer player) {
        if (player == null)
            return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
    }

    // =====================================================================
    // Private: three-tier surface rendering setup
    // =====================================================================

    private void setupMediaPlayer(MediaPlayer player, GLVideoRenderer[] rendererRef,
            SurfaceRelay[] relayRef, Surface targetSurface, String tag, boolean playSound) {
        if (targetSurface == null)
            return;
        GLVideoRenderer.releaseSafely(rendererRef[0]);
        SurfaceRelay.releaseSafely(relayRef[0]);
        int rotation = VideoManager.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        rendererRef[0] = GLVideoRenderer.createSafely(targetSurface, tag);
        if (!playSound)
            player.setVolume(0, 0);
        player.setLooping(true);
        try {
            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                player.setDataSource(pfd.getFileDescriptor());
                pfd.close();
            } else {
                player.setDataSource(getVideoPath());
            }
            player.prepare();
            if (rendererRef[0] != null) {
                player.setSurface(rendererRef[0].getInputSurface());
                rendererRef[0].setRotation(rotation);
                LogUtil.log("【CS】【GL】" + tag + " 使用 GL 渲染器 (旋转:" + rotation + "°)");
            } else {
                LogUtil.log("【CS】【Relay】" + tag + " GL 失败，尝试 SurfaceTexture 中继");
                relayRef[0] = SurfaceRelay.createSafely(targetSurface, tag);
                if (relayRef[0] != null) {
                    player.setSurface(relayRef[0].getInputSurface());
                    relayRef[0].setRotation(rotation);
                    LogUtil.log("【CS】【Relay】" + tag + " 使用 Relay 渲染器 (旋转:" + rotation + "°)");
                } else {
                    player.setSurface(targetSurface);
                    LogUtil.log("【CS】" + tag + " 回退到直接 Surface（无旋转）");
                }
            }
            player.setOnPreparedListener(mp -> player.start());
            player.start();
            LogUtil.log("【CS】" + tag + " 已启动播放");
        } catch (Exception e) {
            LogUtil.log("【CS】[" + tag + "] 初始化播放器异常: " + android.util.Log.getStackTraceString(e));
        }
    }
}
