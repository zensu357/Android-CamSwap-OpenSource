package com.example.camswap;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.example.camswap.utils.LogUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A robust fallback for when targetSurface (usually SurfaceTexture-backed)
 * rejects EGL Window creation. This creates an intermediate SurfaceTexture
 * for MediaPlayer, then blits it to targetSurface using an internal Pbuffer
 * EGL context + eglSwapBuffers (if possible) or Canvas.
 */
public class SurfaceRelay implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "SurfaceRelay";

    // EGL
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLPbufferSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface mEGLWindowSurface = EGL14.EGL_NO_SURFACE;

    // GL
    private int mProgram;
    private int mTextureId;
    private int maPositionHandle;
    private int maTextureHandle;
    private int muSTMatrixHandle;
    private int muRotMatrixHandle;

    // Input/Output
    private SurfaceTexture mInputSurfaceTexture;
    private Surface mInputSurface;
    private Surface mTargetSurface;

    // Matrices
    private final float[] mSTMatrix = new float[16];
    private final float[] mRotMatrix = new float[16];

    // State
    private volatile int mRotationDegrees = 0;
    private volatile boolean mReleased = false;
    private boolean mInitialized = false;

    // Thread
    private HandlerThread mGLThread;
    private Handler mGLHandler;

    // Tag for logging
    private final String mTag;

    // Geometry buffers
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    // Shader sources, vertices, and tex coords shared via GLHelper

    public SurfaceRelay(Surface targetSurface, String tag) {
        mTag = tag;
        mTargetSurface = targetSurface;
        Matrix.setIdentityM(mRotMatrix, 0);
        Matrix.setIdentityM(mSTMatrix, 0);

        mGLThread = new HandlerThread("GLRelay-" + tag);
        mGLThread.start();
        mGLHandler = new Handler(mGLThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        mGLHandler.post(() -> {
            try {
                initEGL();
                initGL();
                mInitialized = true;
                LogUtil.log("【CS】【Relay】" + mTag + " 初始化成功，提供中间 Surface");
            } catch (Exception e) {
                LogUtil.log("【CS】【Relay】" + mTag + " 初始化失败: " + e);
                mInitialized = false;
            }
            latch.countDown();
        });

        try {
            if (!latch.await(3000, TimeUnit.MILLISECONDS)) {
                LogUtil.log("【CS】【Relay】" + mTag + " 初始化超时");
            }
        } catch (InterruptedException e) {
            LogUtil.log("【CS】【Relay】" + mTag + " 初始化被中断");
        }
    }

    public boolean isInitialized() {
        return mInitialized && !mReleased;
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void setRotation(int degrees) {
        mRotationDegrees = ((degrees % 360) + 360) % 360;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (mReleased || !mInitialized)
            return;
        mGLHandler.post(this::drawFrame);
    }

    private void drawFrame() {
        if (mReleased || !mInitialized)
            return;
        try {
            if (!EGL14.eglMakeCurrent(mEGLDisplay,
                    mEGLWindowSurface != EGL14.EGL_NO_SURFACE ? mEGLWindowSurface : mEGLPbufferSurface,
                    mEGLWindowSurface != EGL14.EGL_NO_SURFACE ? mEGLWindowSurface : mEGLPbufferSurface, mEGLContext)) {
                return;
            }

            mInputSurfaceTexture.updateTexImage();
            mInputSurfaceTexture.getTransformMatrix(mSTMatrix);

            if (mEGLWindowSurface == EGL14.EGL_NO_SURFACE) {
                // Try to attach to target Surface
                int[] surfaceAttribs = { EGL14.EGL_NONE };
                EGLConfig[] configs = getEglConfigs();
                if (configs != null && configs.length > 0) {
                    mEGLWindowSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mTargetSurface,
                            surfaceAttribs, 0);
                    if (mEGLWindowSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(mEGLDisplay, mEGLWindowSurface, mEGLWindowSurface, mEGLContext);
                        LogUtil.log("【CS】【Relay】" + mTag + " late eglCreateWindowSurface 成功！");
                    } else {
                        int err = EGL14.eglGetError();
                        // LogUtil.log("【CS】【Relay】" + mTag + " eglCreateWindowSurface(late) 失败: " +
                        // err);
                        // Keep using PBuffer... No output to target though
                    }
                }
            }

            if (mRotationDegrees == 0) {
                Matrix.setIdentityM(mRotMatrix, 0);
            } else {
                Matrix.setRotateM(mRotMatrix, 0, -mRotationDegrees, 0, 0, 1.0f);
            }

            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);

            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
            GLES20.glUniformMatrix4fv(muRotMatrixHandle, 1, false, mRotMatrix, 0);

            mVertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);

            mTexCoordBuffer.position(0);
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            if (mEGLWindowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglSwapBuffers(mEGLDisplay, mEGLWindowSurface);
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【Relay】" + mTag + " drawFrame 异常: " + e);
        }
    }

    private EGLConfig[] getEglConfigs() {
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            return null;
        }
        return configs;
    }

    private void initEGL() {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY)
            throw new RuntimeException("eglGetDisplay failed");

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1))
            throw new RuntimeException("eglInitialize failed");

        EGLConfig[] configs = getEglConfigs();
        if (configs == null || configs.length == 0)
            throw new RuntimeException("No matching EGL config");

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (mEGLContext == EGL14.EGL_NO_CONTEXT)
            throw new RuntimeException("eglCreateContext failed");

        int[] pbufferAttribs = {
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
        };
        // Create a standalone PBuffer context so MediaPlayer can at least push frames
        mEGLPbufferSurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, configs[0], pbufferAttribs, 0);
        if (mEGLPbufferSurface == EGL14.EGL_NO_SURFACE)
            throw new RuntimeException("eglCreatePbufferSurface failed");

        // Try Window surface immediately
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        mEGLWindowSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mTargetSurface, surfaceAttribs, 0);
        if (mEGLWindowSurface == EGL14.EGL_NO_SURFACE) {
            LogUtil.log("【CS】【Relay】eglCreateWindowSurface initial fail (Expected). Proceeding with PBuffer.");
        }

        if (!EGL14.eglMakeCurrent(mEGLDisplay,
                mEGLWindowSurface != EGL14.EGL_NO_SURFACE ? mEGLWindowSurface : mEGLPbufferSurface,
                mEGLWindowSurface != EGL14.EGL_NO_SURFACE ? mEGLWindowSurface : mEGLPbufferSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private void initGL() {
        int vertexShader = GLHelper.loadShader(GLES20.GL_VERTEX_SHADER, GLHelper.VERTEX_SHADER);
        int fragmentShader = GLHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, GLHelper.FRAGMENT_SHADER);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetProgramInfoLog(mProgram);
            GLES20.glDeleteProgram(mProgram);
            throw new RuntimeException("Program link failed: " + error);
        }

        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        muRotMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uRotMatrix");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mInputSurfaceTexture = new SurfaceTexture(mTextureId);
        mInputSurfaceTexture.setOnFrameAvailableListener(this);
        mInputSurface = new Surface(mInputSurfaceTexture);

        mVertexBuffer = GLHelper.createFloatBuffer(GLHelper.VERTICES);
        mTexCoordBuffer = GLHelper.createFloatBuffer(GLHelper.TEX_COORDS);
    }

    public void release() {
        if (mReleased)
            return;
        mReleased = true;
        if (mGLHandler != null)
            mGLHandler.post(this::releaseInternal);
        if (mGLThread != null) {
            mGLThread.quitSafely();
            try {
                mGLThread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void releaseInternal() {
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mInputSurfaceTexture != null) {
            mInputSurfaceTexture.release();
            mInputSurfaceTexture = null;
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        if (mTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] { mTextureId }, 0);
            mTextureId = 0;
        }
        if (mEGLWindowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLWindowSurface);
            mEGLWindowSurface = EGL14.EGL_NO_SURFACE;
        }
        if (mEGLPbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLPbufferSurface);
            mEGLPbufferSurface = EGL14.EGL_NO_SURFACE;
        }
        if (mEGLContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            mEGLContext = EGL14.EGL_NO_CONTEXT;
        }
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglTerminate(mEGLDisplay);
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    public static SurfaceRelay createSafely(Surface targetSurface, String tag) {
        if (targetSurface == null || !targetSurface.isValid())
            return null;
        try {
            SurfaceRelay relay = new SurfaceRelay(targetSurface, tag);
            if (relay.isInitialized())
                return relay;
            relay.release();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void releaseSafely(SurfaceRelay relay) {
        if (relay != null) {
            try {
                relay.release();
            } catch (Exception e) {
            }
        }
    }
}
