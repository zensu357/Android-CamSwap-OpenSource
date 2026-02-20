package com.example.camswap.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 将图片转换为短循环 MP4 视频。
 * 使用 ByteBuffer 输入模式（COLOR_FormatYUV420Flexible），
 * 不依赖 OpenGL ES，兼容所有设备。
 */
public class ImageToVideoConverter {

    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25;
    private static final int IFRAME_INTERVAL = 1;
    private static final int DURATION_SEC = 3;
    private static final int TOTAL_FRAMES = FRAME_RATE * DURATION_SEC;
    private static final int TIMEOUT_USEC = 30000;

    /**
     * 将图片转换为 MP4 视频文件。
     *
     * @param imagePath 原始图片路径
     * @param outputDir 视频输出目录
     * @return 生成的 MP4 文件，失败返回 null
     */
    public static File convert(String imagePath, File outputDir) {
        LogUtil.log("【CS】【Converter】开始转换: " + imagePath);

        Bitmap originalBitmap = BitmapFactory.decodeFile(imagePath);
        if (originalBitmap == null) {
            LogUtil.log("【CS】【Converter】无法解码图片: " + imagePath);
            return null;
        }

        // 对齐宽高到偶数（编码器要求）
        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();

        // Limit dimensions to avoid codec issues (max 1920x1920)
        int maxDim = 1920;
        if (width > maxDim || height > maxDim) {
            float scale = Math.min((float) maxDim / width, (float) maxDim / height);
            width = (int) (width * scale);
            height = (int) (height * scale);
        }

        // Ensure even dimensions
        width = (width / 2) * 2;
        height = (height / 2) * 2;

        // Min dimensions
        if (width < 2)
            width = 2;
        if (height < 2)
            height = 2;

        LogUtil.log("【CS】【Converter】输出尺寸: " + width + "x" + height);

        // Scale bitmap if needed
        Bitmap bitmap;
        if (width != originalBitmap.getWidth() || height != originalBitmap.getHeight()) {
            bitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true);
            originalBitmap.recycle();
        } else {
            bitmap = originalBitmap;
        }

        // Generate output file name
        String baseName = new File(imagePath).getName();
        String nameNoExt = baseName.contains(".")
                ? baseName.substring(0, baseName.lastIndexOf('.'))
                : baseName;
        // Keep name short & safe
        if (nameNoExt.length() > 30) {
            nameNoExt = nameNoExt.substring(0, 30);
        }
        // Replace any characters that might cause issues
        nameNoExt = nameNoExt.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        File outputFile = new File(outputDir, "img_" + nameNoExt + ".mp4");
        // Avoid collision
        if (outputFile.exists()) {
            outputFile = new File(outputDir, "img_" + nameNoExt + "_" + System.currentTimeMillis() + ".mp4");
        }

        LogUtil.log("【CS】【Converter】输出文件: " + outputFile.getAbsolutePath());

        // Convert bitmap pixels to NV12 (YUV420SP) format
        byte[] yuvData = bitmapToNV12(bitmap, width, height);
        if (yuvData == null) {
            LogUtil.log("【CS】【Converter】NV12转换失败");
            bitmap.recycle();
            return null;
        }
        LogUtil.log("【CS】【Converter】NV12数据大小: " + yuvData.length);

        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        try {
            // Find a suitable encoder
            String codecName = findEncoderCodec(MIME_TYPE);
            int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

            if (codecName != null) {
                // Try to find the best color format
                MediaCodecInfo codecInfo = getCodecInfo(codecName);
                if (codecInfo != null) {
                    colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
                }
                LogUtil.log("【CS】【Converter】使用编码器: " + codecName + ", colorFormat=" + colorFormat);
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4); // Good quality
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            if (codecName != null) {
                encoder = MediaCodec.createByCodecName(codecName);
            } else {
                encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            doEncode(encoder, muxer, yuvData, width, height);

            LogUtil.log("【CS】【Converter】转换成功! 文件大小: " + outputFile.length() + " bytes");

            // Set world-readable so the hook process (inside target app) can read
            // the file via direct path when ContentProvider is unavailable.
            try {
                outputFile.setReadable(true, false);
                outputFile.setExecutable(false, false);
                // Also try chmod via runtime for older devices
                Runtime.getRuntime().exec(new String[] { "chmod", "644", outputFile.getAbsolutePath() });
            } catch (Exception ignored) {
                LogUtil.log("【CS】【Converter】chmod 失败，可能影响跨进程读取");
            }

            if (outputFile.length() == 0) {
                LogUtil.log("【CS】【Converter】错误: 输出文件为空!");
                outputFile.delete();
                return null;
            }

            return outputFile;

        } catch (Exception e) {
            LogUtil.log("【CS】【Converter】转换失败: " + e.getMessage());
            e.printStackTrace();
            if (outputFile.exists())
                outputFile.delete();
            return null;
        } finally {
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
            bitmap.recycle();
        }
    }

    /**
     * 编码所有帧
     */
    private static void doEncode(MediaCodec encoder, MediaMuxer muxer, byte[] yuvData,
            int width, int height) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int trackIndex = -1;
        boolean muxerStarted = false;
        int framesSubmitted = 0;
        boolean inputDone = false;

        while (true) {
            // Submit input frames
            if (!inputDone) {
                int inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuf = encoder.getInputBuffer(inputBufIndex);
                    if (inputBuf != null) {
                        inputBuf.clear();
                        inputBuf.put(yuvData);

                        long ptsUsec = computePts(framesSubmitted);

                        if (framesSubmitted >= TOTAL_FRAMES) {
                            // Send EOS
                            encoder.queueInputBuffer(inputBufIndex, 0, yuvData.length, ptsUsec,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            LogUtil.log("【CS】【Converter】发送 EOS，已提交帧: " + framesSubmitted);
                        } else {
                            encoder.queueInputBuffer(inputBufIndex, 0, yuvData.length, ptsUsec, 0);
                            framesSubmitted++;
                        }
                    }
                }
            }

            // Drain output
            int outputBufIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputDone) {
                    // Keep trying until we get EOS
                    continue;
                }
            } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new RuntimeException("Format changed twice");
                }
                MediaFormat newFormat = encoder.getOutputFormat();
                LogUtil.log("【CS】【Converter】输出格式: " + newFormat);
                trackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;
            } else if (outputBufIndex >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(outputBufIndex);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Codec specific data; not written to muxer
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                }

                encoder.releaseOutputBuffer(outputBufIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    LogUtil.log("【CS】【Converter】收到 EOS");
                    break;
                }
            }
        }
    }

    private static long computePts(int frameIndex) {
        return (long) frameIndex * 1000000L / FRAME_RATE;
    }

    /**
     * 将 Bitmap 转为 NV12 (YUV420 Semi-Planar) 字节数组
     */
    private static byte[] bitmapToNV12(Bitmap bitmap, int width, int height) {
        try {
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            int ySize = width * height;
            int uvSize = ySize / 2; // NV12: interleaved UV
            byte[] yuv = new byte[ySize + uvSize];

            int yIndex = 0;
            int uvIndex = ySize;

            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    int pixel = pixels[j * width + i];
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;

                    // RGB to YUV (BT.601)
                    int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                    yuv[yIndex++] = (byte) clamp(y, 0, 255);

                    // UV is sampled every 2x2 block
                    if (j % 2 == 0 && i % 2 == 0) {
                        yuv[uvIndex++] = (byte) clamp(u, 0, 255);
                        yuv[uvIndex++] = (byte) clamp(v, 0, 255);
                    }
                }
            }
            return yuv;
        } catch (Exception e) {
            LogUtil.log("【CS】【Converter】bitmapToNV12 异常: " + e.getMessage());
            return null;
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(val, max));
    }

    /**
     * 查找设备上的 H.264 编码器
     */
    private static String findEncoderCodec(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (!info.isEncoder())
                continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return info.getName();
                }
            }
        }
        return null;
    }

    private static MediaCodecInfo getCodecInfo(String codecName) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : codecList.getCodecInfos()) {
            if (info.getName().equals(codecName))
                return info;
        }
        return null;
    }

    /**
     * 选择编码器支持的颜色格式，优先选 NV12
     */
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        // Prefer NV12 (YUV420SemiPlanar)
        for (int format : capabilities.colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                return format;
            }
        }
        // Fallback: YUV420Planar
        for (int format : capabilities.colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return format;
            }
        }
        // Fallback: YUV420Flexible
        for (int format : capabilities.colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                return format;
            }
        }
        // Last resort
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }
}
