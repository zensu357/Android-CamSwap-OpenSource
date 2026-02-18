package com.example.camswap;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

import de.robv.android.xposed.XposedBridge;
import com.example.camswap.utils.LogUtil;

public class VideoToFrames implements Runnable {
    private static final String TAG = "VideoToFrames";
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;

    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    private LinkedBlockingQueue<byte[]> mQueue;
    private OutputImageFormat outputImageFormat;
    private volatile boolean stopDecode = false;
    private int mVideoRotation = 0;

    private String videoFilePath;
    private Throwable throwable;
    private Thread childThread;
    private Surface play_surf;

    private Callback callback;

    public interface Callback {
        void onFinishDecode();

        void onDecodeFrame(int index);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setEnqueue(LinkedBlockingQueue<byte[]> queue) {
        mQueue = queue;
    }

    // 设置输出位置，没啥用
    public void setSaveFrames(String dir, OutputImageFormat imageFormat) throws IOException {
        outputImageFormat = imageFormat;

    }

    public void set_surfcae(Surface player_surface) {
        if (player_surface != null) {
            play_surf = player_surface;
        }
    }

    public void stopDecode() {
        stopDecode = true;
        if (childThread != null) {
            try {
                childThread.join(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void reset(String videoFilePath) throws Throwable {
        stopDecode();
        stopDecode = false;
        this.videoFd = null;
        this.videoPfd = null;
        this.videoFilePath = videoFilePath;
        childThread = null;
        decode(videoFilePath);
    }

    public void reset(android.os.ParcelFileDescriptor pfd) throws Throwable {
        stopDecode();
        stopDecode = false;
        this.videoFilePath = null;
        this.videoPfd = pfd;
        this.videoFd = pfd.getFileDescriptor();
        childThread = null;
        decode(pfd);
    }

    public void decode(String videoFilePath) throws Throwable {
        this.videoFilePath = videoFilePath;
        if (childThread == null) {
            childThread = new Thread(this, "decode");
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    private java.io.FileDescriptor videoFd;
    private android.os.ParcelFileDescriptor videoPfd;

    public void decode(android.os.ParcelFileDescriptor pfd) throws Throwable {
        this.videoPfd = pfd;
        this.videoFd = pfd.getFileDescriptor();
        if (childThread == null) {
            childThread = new Thread(this, "decode");
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    public void decode(java.io.FileDescriptor fd) throws Throwable {
        this.videoFd = fd;
        if (childThread == null) {
            childThread = new Thread(this, "decode");
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    public void run() {
        try {
            if (videoFd != null) {
                videoDecode(videoFd);
            } else {
                videoDecode(videoFilePath);
            }
        } catch (Throwable t) {
            throwable = t;
        }
    }

    @SuppressLint("WrongConstant")
    public void videoDecode(java.io.FileDescriptor fd) throws IOException {
        LogUtil.log("【CS】【decoder】开始解码(FD)");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(fd);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                mVideoRotation = Integer.parseInt(rotation);
                LogUtil.log("【CS】【decoder】视频旋转角度: " + mVideoRotation);
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】获取视频旋转角度失败: " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(fd);
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                LogUtil.log("【CS】【decoder】未发现视频轨道(FD)");
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                LogUtil.log("【CS】【decoder】设置解码颜色格式为 " + decodeColorFormat);
            } else {
                LogUtil.log("【CS】【decoder】无法设置解码颜色格式, 颜色格式类型 " + decodeColorFormat + " 不支持");
                LogUtil.log("【CS】【decoder】unable to set decode color format, color format type " + decodeColorFormat
                        + " not supported");
            }
            // 解码第一帧
            decodeFramesToImage(decoder, extractor, mediaFormat);
            decoder.stop();
            while (!stopDecode) {
                extractor.seekTo(0, 0);
                // 解码后续帧
                decodeFramesToImage(decoder, extractor, mediaFormat);
                decoder.stop();
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】【videoFD】" + e.toString());
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException ignored) {
                }
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
            if (videoPfd != null) {
                try {
                    videoPfd.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                videoPfd = null;
            }
        }
    }

    @SuppressLint("WrongConstant")
    public void videoDecode(String videoFilePath) throws IOException {
        LogUtil.log("【CS】【decoder】开始解码(" + videoFilePath + ")");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFilePath);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotation != null) {
                mVideoRotation = Integer.parseInt(rotation);
                LogUtil.log("【CS】【decoder】视频旋转角度: " + mVideoRotation);
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】获取视频旋转角度失败: " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            File videoFile = new File(videoFilePath);
            extractor = new MediaExtractor();
            extractor.setDataSource(videoFilePath);
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                LogUtil.log("【CS】【decoder】未发现视频轨道(" + videoFilePath + ")");
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                LogUtil.log("【CS】【decoder】设置解码颜色格式为 " + decodeColorFormat);
            } else {
                LogUtil.log("【CS】【decoder】无法设置解码颜色格式，颜色格式类型 " + decodeColorFormat + " 不支持");
                LogUtil.log("【CS】【decoder】unable to set decode color format, color format type " + decodeColorFormat
                        + " not supported");
            }
            decodeFramesToImage(decoder, extractor, mediaFormat);
            decoder.stop();
            while (!stopDecode) {
                extractor.seekTo(0, 0);
                decodeFramesToImage(decoder, extractor, mediaFormat);
                decoder.stop();
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【decoder】【videofile】" + e.toString());
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException ignored) {
                }
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private void showSupportedColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        StringBuilder sb = new StringBuilder("supported color format: ");
        for (int c : caps.colorFormats) {
            sb.append(c).append("\t");
        }
        LogUtil.log("【CS】" + sb.toString());
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) {
                return true;
            }
        }
        return false;
    }

    private void decodeFramesToImage(MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat) {
        boolean is_first = false;
        long startWhen = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // 计算初始旋转角度决定是否需要内存解码模式
        int manualOffset = 0;
        try {
            manualOffset = HookMain.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0);
        } catch (Exception ignored) {
        }
        int initialEffectiveRotation = (mVideoRotation + manualOffset + 360) % 360;
        boolean needSoftDecode = (initialEffectiveRotation != 0) || outputImageFormat != null;

        // 如果需要旋转或需要填充 data_buffer，则不配置 Surface（走内存解码）
        Surface configSurface = needSoftDecode ? null : play_surf;
        decoder.configure(mediaFormat, configSurface, null, 0);

        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        decoder.start();
        final int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        final int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int outputFrameCount = 0;
        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }
            int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                boolean doRender = (info.size != 0);
                if (doRender) {
                    outputFrameCount++;
                    if (callback != null) {
                        callback.onDecodeFrame(outputFrameCount);
                    }
                    if (!is_first) {
                        startWhen = System.currentTimeMillis();
                        is_first = true;
                    }
                    if (configSurface == null) {
                        // 内存解码模式：获取帧数据，做旋转处理
                        Image image = decoder.getOutputImage(outputBufferId);

                        // 动态重新计算旋转角度（配置可能被通知栏按钮实时更新）
                        int currentManualOffset = 0;
                        try {
                            currentManualOffset = HookMain.getConfig().getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET,
                                    0);
                        } catch (Exception ignored) {
                        }
                        int effectiveRotation = (mVideoRotation + currentManualOffset + 360) % 360;

                        boolean needSwapDimensions = (effectiveRotation == 90 || effectiveRotation == 270);
                        int rotatedWidth = needSwapDimensions ? height : width;
                        int rotatedHeight = needSwapDimensions ? width : height;

                        byte[] rawData = getDataFromImage(image, COLOR_FormatNV21);
                        byte[] rotatedData;
                        if (effectiveRotation != 0) {
                            rotatedData = rotateNV21(rawData, width, height, effectiveRotation);
                        } else {
                            rotatedData = rawData;
                        }

                        if (mQueue != null) {
                            try {
                                mQueue.put(rotatedData);
                            } catch (InterruptedException e) {
                                LogUtil.log("【CS】" + e.toString());
                                BytePool.release(rotatedData);
                            }
                        }
                        if (outputImageFormat != null) {
                            byte[] old = HookMain.data_buffer;
                            HookMain.data_buffer = rotatedData;
                            HookMain.mwidth = rotatedWidth;
                            HookMain.mhight = rotatedHeight;
                            if (old != null && old.length > 1 && old != rotatedData) {
                                BytePool.release(old);
                            }
                        }

                        // 如果有 play_surf（Camera2 reader Surface），将旋转后的帧渲染上去
                        if (play_surf != null) {
                            try {
                                renderNV21ToSurface(rotatedData, rotatedWidth, rotatedHeight, play_surf);
                            } catch (Exception e) {
                                LogUtil.log("【CS】渲染到Surface失败: " + e.toString());
                            }
                        }

                        image.close();
                    }
                    long sleepTime = info.presentationTimeUs / 1000 - (System.currentTimeMillis() - startWhen);
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime);
                        } catch (InterruptedException e) {
                            LogUtil.log("【CS】" + e.toString());
                            LogUtil.log("【CS】线程延迟出错");
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferId, configSurface != null);
                }
            }
        }
        if (callback != null) {
            callback.onFinishDecode();
        }
    }

    /**
     * 将 NV21 数据转为 Bitmap 并渲染到指定 Surface。
     * 用于 Camera2 reader 路径在旋转后手动渲染帧。
     */
    private void renderNV21ToSurface(byte[] nv21Data, int width, int height, Surface surface) {
        android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                nv21Data, android.graphics.ImageFormat.NV21, width, height, null);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 90, baos);
        byte[] jpegData = baos.toByteArray();
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);

        if (bitmap != null) {
            android.graphics.Canvas canvas = surface.lockCanvas(null);
            if (canvas != null) {
                // 缩放 bitmap 以填充 canvas
                android.graphics.Rect destRect = new android.graphics.Rect(0, 0, canvas.getWidth(), canvas.getHeight());
                canvas.drawBitmap(bitmap, null, destRect, null);
                surface.unlockCanvasAndPost(canvas);
            }
            bitmap.recycle();
        }
    }

    private byte[] rotateNV21(byte[] input, int width, int height, int rotation) {
        byte[] output = new byte[input.length];
        int frameSize = width * height;
        boolean swap = (rotation == 90 || rotation == 270);

        // 90 degrees CW
        if (rotation == 90) {
            int k = 0;
            // Rotate Y
            for (int x = 0; x < width; x++) {
                for (int y = height - 1; y >= 0; y--) {
                    output[k++] = input[y * width + x];
                }
            }
            // Rotate UV
            for (int x = 0; x < width; x += 2) {
                for (int y = height / 2 - 1; y >= 0; y--) {
                    output[k++] = input[frameSize + y * width + x];
                    output[k++] = input[frameSize + y * width + x + 1];
                }
            }
            return output;
        }

        // 180 degrees
        if (rotation == 180) {
            int k = 0;
            // Rotate Y
            for (int i = frameSize - 1; i >= 0; i--) {
                output[k++] = input[i];
            }
            // Rotate UV
            for (int i = input.length - 2; i >= frameSize; i -= 2) {
                output[k++] = input[i]; // V
                output[k++] = input[i + 1]; // U
            }
            return output;
        }

        // 270 degrees CW (or 90 CCW)
        if (rotation == 270) {
            int k = 0;
            // Rotate Y
            for (int x = width - 1; x >= 0; x--) {
                for (int y = 0; y < height; y++) {
                    output[k++] = input[y * width + x];
                }
            }
            // Rotate UV
            for (int x = width - 2; x >= 0; x -= 2) {
                for (int y = 0; y < height / 2; y++) {
                    output[k++] = input[frameSize + y * width + x];
                    output[k++] = input[frameSize + y * width + x + 1];
                }
            }
            return output;
        }

        return input;
    }

    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                return true;
        }
        return false;
    }

    private static byte[] getDataFromImage(Image image, int colorFormat) {
        if (colorFormat != COLOR_FormatI420 && colorFormat != COLOR_FormatNV21) {
            throw new IllegalArgumentException("only support COLOR_FormatI420 " + "and COLOR_FormatNV21");
        }
        if (!isImageFormatSupported(image)) {
            throw new RuntimeException("can't convert Image to byte array, format " + image.getFormat());
        }
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = BytePool.acquire(width * height * ImageFormat.getBitsPerPixel(format) / 8);
        byte[] rowData = new byte[planes[0].getRowStride()];
        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = width * height;
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height + 1;
                        outputStride = 2;
                    }
                    break;
                case 2:
                    if (colorFormat == COLOR_FormatI420) {
                        channelOffset = (int) (width * height * 1.25);
                        outputStride = 1;
                    } else if (colorFormat == COLOR_FormatNV21) {
                        channelOffset = width * height;
                        outputStride = 2;
                    }
                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }
        return data;
    }

}

enum OutputImageFormat {
    I420("I420"),
    NV21("NV21"),
    JPEG("JPEG");

    private final String friendlyName;

    OutputImageFormat(String friendlyName) {
        this.friendlyName = friendlyName;
    }

    public String toString() {
        return friendlyName;
    }
}
