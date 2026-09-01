package com.okan.xraythermal;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Comparator;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 42;

    private CameraGLView glView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Surface cameraSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        TextView title = new TextView(this);
        glView = new CameraGLView(this, surfaceTexture -> runOnUiThread(() -> startCamera(surfaceTexture)));
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        title.setText("X-RAY MODU");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(14), dp(8), dp(14), dp(8));
        title.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        titleParams.topMargin = dp(18);
        root.addView(title, titleParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(12), dp(12), dp(12), dp(12));
        controls.setBackgroundColor(0xAA000000);

        Button xrayButton = buildButton("X-RAY");
        Button thermalButton = buildButton("TERMAL");

        xrayButton.setOnClickListener(v -> {
            glView.setMode(CameraRenderer.MODE_XRAY);
            title.setText("X-RAY MODU");
        });
        thermalButton.setOnClickListener(v -> {
            glView.setMode(CameraRenderer.MODE_THERMAL);
            title.setText("TERMAL MOD");
        });

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        buttonParams.setMargins(dp(6), 0, dp(6), 0);
        controls.addView(xrayButton, buttonParams);
        controls.addView(thermalButton, buttonParams);

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(controls, controlsParams);

        TextView note = new TextView(this);
        note.setText("Görsel simülasyon • Gerçek sıcaklık ölçmez / nesnelerin içini göstermez");
        note.setTextColor(0xCCFFFFFF);
        note.setTextSize(11f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(10), dp(5), dp(10), dp(5));
        note.setBackgroundColor(0x77000000);
        FrameLayout.LayoutParams noteParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        noteParams.bottomMargin = dp(78);
        root.addView(note, noteParams);

        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private Button buildButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16f);
        button.setTextColor(0xFFFFFFFF);
        button.setBackgroundColor(0xCC202020);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        glView.onResume();
    }

    @Override
    protected void onPause() {
        closeCamera();
        glView.onPause();
        stopCameraThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Kamera izni olmadan uygulama çalışamaz.", Toast.LENGTH_LONG).show();
            } else {
                glView.requestSurfaceRestart();
            }
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void startCamera(SurfaceTexture surfaceTexture) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (cameraDevice != null || cameraHandler == null) return;

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = chooseBackCamera(manager);
            if (cameraId == null) {
                Toast.makeText(this, "Arka kamera bulunamadı.", Toast.LENGTH_LONG).show();
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size previewSize = choosePreviewSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            if (previewSize != null) {
                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            }

            if (cameraSurface != null) cameraSurface.release();
            cameraSurface = new Surface(surfaceTexture);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Kamera açılamadı. Hata: " + error, Toast.LENGTH_LONG).show());
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Kamera hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String chooseBackCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            if (fallback == null) fallback = id;
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return fallback;
    }

    private Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        return Arrays.stream(sizes)
                .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                .orElse(sizes[0]);
    }

    private void createPreviewSession() {
        if (cameraDevice == null || cameraSurface == null) return;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(cameraSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(Arrays.asList(cameraSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Kamera önizleme hatası.", Toast.LENGTH_LONG).show());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Kamera oturumu kurulamadı.", Toast.LENGTH_LONG).show());
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Kamera önizleme hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
    }

    interface SurfaceReadyListener {
        void onSurfaceReady(SurfaceTexture surfaceTexture);
    }

    static class CameraGLView extends GLSurfaceView {
        private final CameraRenderer renderer;

        CameraGLView(Context context, SurfaceReadyListener listener) {
            super(context);
            setEGLContextClientVersion(2);
            renderer = new CameraRenderer(this, listener);
            setRenderer(renderer);
            setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }

        void setMode(int mode) {
            queueEvent(() -> renderer.setMode(mode));
            requestRender();
        }

        void requestSurfaceRestart() {
            queueEvent(renderer::notifySurfaceAgain);
        }
    }

    static class CameraRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        static final int MODE_XRAY = 0;
        static final int MODE_THERMAL = 1;

        private final GLSurfaceView view;
        private final SurfaceReadyListener listener;
        private final FloatBuffer vertexBuffer;
        private final FloatBuffer texBuffer;
        private final float[] texMatrix = new float[16];

        private SurfaceTexture surfaceTexture;
        private int textureId;
        private int program;
        private int mode = MODE_XRAY;
        private int aPosition;
        private int aTexCoord;
        private int uTexMatrix;
        private int uTexture;
        private int uMode;

        private final float[] vertices = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
        private final float[] texCoords = {0f,1f, 1f,1f, 0f,0f, 1f,0f};

        CameraRenderer(GLSurfaceView view, SurfaceReadyListener listener) {
            this.view = view;
            this.listener = listener;
            vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(vertices).position(0);
            texBuffer = ByteBuffer.allocateDirect(texCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            texBuffer.put(texCoords).position(0);
            Matrix.setIdentityM(texMatrix, 0);
        }

        void setMode(int mode) { this.mode = mode; }
        void notifySurfaceAgain() { if (surfaceTexture != null) listener.onSurfaceReady(surfaceTexture); }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f,0f,0f,1f);
            textureId = createExternalTexture();
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this);
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            uMode = GLES20.glGetUniformLocation(program, "uMode");
            listener.onSurfaceReady(surfaceTexture);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0,0,width,height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (surfaceTexture == null || program == 0) return;
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(texMatrix);
            } catch (RuntimeException ignored) { return; }

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniform1i(uMode, mode);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition,2,GLES20.GL_FLOAT,false,0,vertexBuffer);
            texBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord,2,GLES20.GL_FLOAT,false,0,texBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) { view.requestRender(); }

        private int createExternalTexture() {
            int[] tex = new int[1];
            GLES20.glGenTextures(1,tex,0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            return tex[0];
        }

        private int createProgram(String vs, String fs) {
            int v = compileShader(GLES20.GL_VERTEX_SHADER, vs);
            int f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p,v);
            GLES20.glAttachShader(p,f);
            GLES20.glLinkProgram(p);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,linked,0);
            if (linked[0] == 0) { GLES20.glDeleteProgram(p); return 0; }
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }

        private int compileShader(int type, String source) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s,source);
            GLES20.glCompileShader(s);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,compiled,0);
            if (compiled[0] == 0) { GLES20.glDeleteShader(s); return 0; }
            return s;
        }

        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTexCoord;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main(){ gl_Position=aPosition; vTexCoord=(uTexMatrix*aTexCoord).xy; }\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "uniform int uMode;\n" +
                "varying vec2 vTexCoord;\n" +
                "float lum(vec3 c){ return dot(c,vec3(0.299,0.587,0.114)); }\n" +
                "vec3 thermal(float t){ t=clamp(t,0.0,1.0); vec3 c; c.r=clamp(1.5-abs(4.0*t-3.0),0.0,1.0); c.g=clamp(1.5-abs(4.0*t-2.0),0.0,1.0); c.b=clamp(1.5-abs(4.0*t-1.0),0.0,1.0); return c; }\n" +
                "void main(){ vec3 src=texture2D(uTexture,vTexCoord).rgb; float y=lum(src); if(uMode==0){ float inv=1.0-y; float ct=smoothstep(0.08,0.95,inv); vec3 x=vec3(ct*0.28,ct*0.82,min(1.0,ct*1.18))+vec3(0.02,0.05,0.08); gl_FragColor=vec4(clamp(x,0.0,1.0),1.0); } else { gl_FragColor=vec4(thermal(smoothstep(0.05,0.95,y)),1.0); } }\n";
    }
}
