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
 * OpenGL ES 旋转渲染器。
 * 在 MediaPlayer 输出和目标 Surface 之间插入 GL 旋转层，
 * 实现 GPU 加速的实时画面旋转。
 *
 * 使用流程：
 * 1. new GLVideoRenderer(targetSurface, tag)
 * 2. mediaPlayer.setSurface(renderer.getInputSurface())
 * 3. renderer.setRotation(90) // 实时调整
 * 4. renderer.release() // 释放资源
 */
public class GLVideoRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "GLVideoRenderer";

    // EGL
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

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

    private static final float[] VERTICES = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f,
    };

    private static final float[] TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    private static final String VERTEX_SHADER = "uniform mat4 uSTMatrix;\n" +
            "uniform mat4 uRotMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uRotMatrix * aPosition;\n" +
            "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    /**
     * 创建 GL 旋转渲染器。
     *
     * @param targetSurface 渲染目标 Surface（预览或 ImageReader 的 Surface）
     * @param tag           日志标识
     */
    public GLVideoRenderer(Surface targetSurface, String tag) {
        mTag = tag;
        Matrix.setIdentityM(mRotMatrix, 0);
        Matrix.setIdentityM(mSTMatrix, 0);

        mGLThread = new HandlerThread("GLRenderer-" + tag);
        mGLThread.start();
        mGLHandler = new Handler(mGLThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        mGLHandler.post(() -> {
            try {
                initEGL(targetSurface);
                initGL();
                mInitialized = true;
                LogUtil.log("【CS】【GL】" + mTag + " 初始化成功");
            } catch (Exception e) {
                LogUtil.log("【CS】【GL】" + mTag + " 初始化失败: " + e);
                mInitialized = false;
            }
            latch.countDown();
        });

        try {
            if (!latch.await(3000, TimeUnit.MILLISECONDS)) {
                LogUtil.log("【CS】【GL】" + mTag + " 初始化超时");
            }
        } catch (InterruptedException e) {
            LogUtil.log("【CS】【GL】" + mTag + " 初始化被中断");
        }
    }

    public boolean isInitialized() {
        return mInitialized && !mReleased;
    }

    /**
     * 获取输入 Surface，供 MediaPlayer.setSurface() 使用。
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * 设置旋转角度（0/90/180/270），实时生效。
     */
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
            if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
                return;
            }

            mInputSurfaceTexture.updateTexImage();
            mInputSurfaceTexture.getTransformMatrix(mSTMatrix);

            // Update rotation matrix
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

            EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
        } catch (Exception e) {
            LogUtil.log("【CS】【GL】" + mTag + " drawFrame 异常: " + e);
        }
    }

    private void initEGL(Surface targetSurface) {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            throw new RuntimeException("eglInitialize failed");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("eglChooseConfig failed");
        }
        if (numConfigs[0] == 0) {
            throw new RuntimeException("No matching EGL config");
        }

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (mEGLContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("eglCreateContext failed");
        }

        int[] surfaceAttribs = { EGL14.EGL_NONE };
        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], targetSurface, surfaceAttribs, 0);
        if (mEGLSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("eglCreateWindowSurface failed");
        }

        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private void initGL() {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
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

        // Create external OES texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Create input SurfaceTexture bound to the external texture
        mInputSurfaceTexture = new SurfaceTexture(mTextureId);
        mInputSurfaceTexture.setOnFrameAvailableListener(this);
        mInputSurface = new Surface(mInputSurfaceTexture);

        // Vertex buffers
        mVertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVertexBuffer.put(VERTICES).position(0);

        mTexCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexCoordBuffer.put(TEX_COORDS).position(0);
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + error);
        }
        return shader;
    }

    /**
     * 释放所有 GL/EGL 资源。调用后该渲染器不可再使用。
     */
    public void release() {
        if (mReleased)
            return;
        mReleased = true;
        if (mGLHandler != null) {
            mGLHandler.post(this::releaseInternal);
        }
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
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mEGLSurface = EGL14.EGL_NO_SURFACE;
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

    // ---- Static helpers for managing multiple renderers ----

    /**
     * 安全创建渲染器，失败时返回 null 而非抛异常。
     */
    public static GLVideoRenderer createSafely(Surface targetSurface, String tag) {
        if (targetSurface == null || !targetSurface.isValid()) {
            LogUtil.log("【CS】【GL】" + tag + " 目标 Surface 无效，跳过创建");
            return null;
        }
        try {
            GLVideoRenderer renderer = new GLVideoRenderer(targetSurface, tag);
            if (renderer.isInitialized()) {
                return renderer;
            } else {
                renderer.release();
                LogUtil.log("【CS】【GL】" + tag + " 初始化失败，回退到直接播放");
                return null;
            }
        } catch (Exception e) {
            LogUtil.log("【CS】【GL】" + tag + " 创建异常: " + e);
            return null;
        }
    }

    /**
     * 安全释放渲染器。
     */
    public static void releaseSafely(GLVideoRenderer renderer) {
        if (renderer != null) {
            try {
                renderer.release();
            } catch (Exception e) {
                LogUtil.log("【CS】【GL】释放渲染器异常: " + e);
            }
        }
    }
}
