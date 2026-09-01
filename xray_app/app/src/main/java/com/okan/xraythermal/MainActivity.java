package com.okan.xraythermal;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
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

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private ImageReader imageReader;
    private int mlRotationDegrees = 90;

    private FaceDetector faceDetector;
    private ObjectDetector objectDetector;
    private final AtomicBoolean aiBusy = new AtomicBoolean(false);
    private long lastAiFrameMs = 0;
    private TextView aiStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FaceDetectorOptions faceOptions = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.10f)
                .build();
        faceDetector = FaceDetection.getClient(faceOptions);

        ObjectDetectorOptions objectOptions = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build();
        objectDetector = ObjectDetection.getClient(objectOptions);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        glView = new CameraGLView(this, surfaceTexture -> runOnUiThread(() -> startCamera(surfaceTexture)));
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("X-RAY / AI THERMAL");
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

        aiStatus = new TextView(this);
        aiStatus.setText("AI hazırlanıyor");
        aiStatus.setTextColor(0xFFFFFFFF);
        aiStatus.setTextSize(12f);
        aiStatus.setGravity(Gravity.CENTER);
        aiStatus.setPadding(dp(10), dp(5), dp(10), dp(5));
        aiStatus.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams aiParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        aiParams.topMargin = dp(60);
        root.addView(aiStatus, aiParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(10), dp(8), dp(10), dp(8));
        controls.setBackgroundColor(0xB0000000);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        Button normalButton = buildButton("NORMAL");
        Button xrayButton = buildButton("X-RAY");
        Button thermalButton = buildButton("TERMAL");
        Button aiThermalButton = buildButton("AI TERMAL");
        Button rotateButton = buildButton("DÖNDÜR");

        normalButton.setOnClickListener(v -> {
            glView.setMode(CameraRenderer.MODE_NORMAL);
            title.setText("NORMAL KAMERA");
        });
        xrayButton.setOnClickListener(v -> {
            glView.setMode(CameraRenderer.MODE_XRAY);
            title.setText("X-RAY MODU");
        });
        thermalButton.setOnClickListener(v -> {
            glView.setMode(CameraRenderer.MODE_THERMAL);
            title.setText("TERMAL MOD");
        });
        aiThermalButton.setOnClickListener(v -> {
            glView.setMode(CameraRenderer.MODE_AI_THERMAL);
            title.setText("AI TERMAL MOD");
        });
        rotateButton.setOnClickListener(v -> {
            int r = glView.cycleUserRotation();
            title.setText("KAMERA DÖNDÜRME: " + (r * 90) + "°");
        });

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        bp.setMargins(dp(4), dp(3), dp(4), dp(3));
        row1.addView(normalButton, bp);
        row1.addView(xrayButton, bp);
        row1.addView(thermalButton, bp);
        row2.addView(aiThermalButton, bp);
        row2.addView(rotateButton, bp);
        controls.addView(row1);
        controls.addView(row2);

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(controls, controlsParams);

        TextView note = new TextView(this);
        note.setText("AI nesne/yüz bölgelerini tahmini sıcak gösterir • Gerçek sıcaklık ölçümü değildir");
        note.setTextColor(0xDFFFFFFF);
        note.setTextSize(10.5f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), dp(4), dp(8), dp(4));
        note.setBackgroundColor(0x77000000);
        FrameLayout.LayoutParams noteParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        noteParams.bottomMargin = dp(118);
        root.addView(note, noteParams);

        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private Button buildButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setBackgroundColor(0xCC202020);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
    protected void onDestroy() {
        if (faceDetector != null) faceDetector.close();
        if (objectDetector != null) objectDetector.close();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Kamera izni gerekli.", Toast.LENGTH_LONG).show();
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
        } catch (InterruptedException e) {
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

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int sensor = sensorOrientation == null ? 90 : sensorOrientation;
            mlRotationDegrees = sensor;
            int correctionSteps = ((360 - sensor) % 360) / 90;
            glView.setBaseRotation(correctionSteps);

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size preview = choosePreviewSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            if (preview != null) surfaceTexture.setDefaultBufferSize(preview.getWidth(), preview.getHeight());

            if (cameraSurface != null) cameraSurface.release();
            cameraSurface = new Surface(surfaceTexture);

            if (imageReader != null) imageReader.close();
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::analyzeFrame, cameraHandler);

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
                            "Kamera açılamadı: " + error, Toast.LENGTH_LONG).show());
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
        if (cameraDevice == null || cameraSurface == null || imageReader == null) return;
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(cameraSurface);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(
                    Arrays.asList(cameraSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                            } catch (CameraAccessException ignored) {
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "Kamera oturumu kurulamadı.", Toast.LENGTH_LONG).show());
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "Önizleme hatası.", Toast.LENGTH_LONG).show();
        }
    }

    private void analyzeFrame(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        long now = System.currentTimeMillis();
        if (aiBusy.get() || now - lastAiFrameMs < 180) {
            image.close();
            return;
        }
        lastAiFrameMs = now;
        aiBusy.set(true);

        final int iw = image.getWidth();
        final int ih = image.getHeight();
        final int rot = mlRotationDegrees;
        final int rw = (rot == 90 || rot == 270) ? ih : iw;
        final int rh = (rot == 90 || rot == 270) ? iw : ih;
        final InputImage input = InputImage.fromMediaImage(image, rot);
        final List<HotBox> hotBoxes = new ArrayList<>();
        final AtomicInteger pending = new AtomicInteger(2);

        Task<List<Face>> faceTask = faceDetector.process(input);
        faceTask.addOnSuccessListener(faces -> {
            synchronized (hotBoxes) {
                for (Face f : faces) addBox(hotBoxes, f.getBoundingBox(), rw, rh, 0.78f);
            }
        }).addOnCompleteListener(t -> finishAiPart(pending, image, hotBoxes));

        Task<List<DetectedObject>> objectTask = objectDetector.process(input);
        objectTask.addOnSuccessListener(objects -> {
            synchronized (hotBoxes) {
                for (DetectedObject o : objects) {
                    float heat = o.getLabels().isEmpty() ? 0.40f : 0.50f;
                    addBox(hotBoxes, o.getBoundingBox(), rw, rh, heat);
                }
            }
        }).addOnCompleteListener(t -> finishAiPart(pending, image, hotBoxes));
    }

    private void addBox(List<HotBox> list, Rect r, int w, int h, float heat) {
        if (list.size() >= CameraRenderer.MAX_BOXES || w <= 0 || h <= 0) return;
        float x1 = clamp01(r.left / (float) w);
        float y1 = clamp01(r.top / (float) h);
        float x2 = clamp01(r.right / (float) w);
        float y2 = clamp01(r.bottom / (float) h);
        if (x2 > x1 && y2 > y1) list.add(new HotBox(x1, y1, x2, y2, heat));
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void finishAiPart(AtomicInteger pending, Image image, List<HotBox> boxes) {
        if (pending.decrementAndGet() != 0) return;
        List<HotBox> copy;
        synchronized (boxes) {
            copy = new ArrayList<>(boxes);
        }
        image.close();
        aiBusy.set(false);
        glView.setDetections(copy);
        runOnUiThread(() -> aiStatus.setText("AI aktif • " + copy.size() + " sıcak bölge"));
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
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    static class HotBox {
        final float x1, y1, x2, y2, heat;
        HotBox(float x1, float y1, float x2, float y2, float heat) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.heat = heat;
        }
    }

    interface SurfaceReadyListener {
        void onSurfaceReady(SurfaceTexture surfaceTexture);
    }

    static class CameraGLView extends GLSurfaceView {
        private final CameraRenderer renderer;
        private int baseRotation = 0;
        private int userRotation = 0;

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

        void setBaseRotation(int r) {
            baseRotation = ((r % 4) + 4) % 4;
            queueEvent(() -> renderer.setRotation((baseRotation + userRotation) % 4));
        }

        int cycleUserRotation() {
            userRotation = (userRotation + 1) % 4;
            int finalRotation = (baseRotation + userRotation) % 4;
            queueEvent(() -> renderer.setRotation(finalRotation));
            requestRender();
            return userRotation;
        }

        void setDetections(List<HotBox> boxes) {
            queueEvent(() -> renderer.setDetections(boxes));
            requestRender();
        }

        void requestSurfaceRestart() {
            queueEvent(renderer::notifySurfaceAgain);
        }
    }

    static class CameraRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
        static final int MODE_NORMAL = 0;
        static final int MODE_XRAY = 1;
        static final int MODE_THERMAL = 2;
        static final int MODE_AI_THERMAL = 3;
        static final int MAX_BOXES = 8;

        private final GLSurfaceView view;
        private final SurfaceReadyListener listener;
        private final FloatBuffer vertexBuffer;
        private final FloatBuffer texBuffer;
        private final float[] texMatrix = new float[16];
        private final float[] boxes = new float[MAX_BOXES * 4];
        private final float[] heats = new float[MAX_BOXES];

        private SurfaceTexture surfaceTexture;
        private int textureId;
        private int program;
        private int mode = MODE_AI_THERMAL;
        private int rotation = 0;
        private int boxCount = 0;

        private int aPosition, aTexCoord, uTexMatrix, uTexture, uMode, uRotation;
        private int uBoxCount, uBoxes, uHeats;

        private final float[] vertices = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
        private final float[] texCoords = {0f,1f, 1f,1f, 0f,0f, 1f,0f};

        CameraRenderer(GLSurfaceView view, SurfaceReadyListener listener) {
            this.view = view;
            this.listener = listener;
            vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(vertices).position(0);
            texBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            texBuffer.put(texCoords).position(0);
            Matrix.setIdentityM(texMatrix, 0);
        }

        void setMode(int m) { mode = m; }
        void setRotation(int r) { rotation = r; }

        void setDetections(List<HotBox> list) {
            boxCount = Math.min(MAX_BOXES, list.size());
            Arrays.fill(boxes, 0f);
            Arrays.fill(heats, 0f);
            for (int i = 0; i < boxCount; i++) {
                HotBox b = list.get(i);
                int p = i * 4;
                boxes[p] = b.x1; boxes[p+1] = b.y1; boxes[p+2] = b.x2; boxes[p+3] = b.y2;
                heats[i] = b.heat;
            }
        }

        void notifySurfaceAgain() {
            if (surfaceTexture != null) listener.onSurfaceReady(surfaceTexture);
        }

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
            uRotation = GLES20.glGetUniformLocation(program, "uRotation");
            uBoxCount = GLES20.glGetUniformLocation(program, "uBoxCount");
            uBoxes = GLES20.glGetUniformLocation(program, "uBoxes[0]");
            uHeats = GLES20.glGetUniformLocation(program, "uHeats[0]");
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
            } catch (RuntimeException e) {
                return;
            }

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniform1i(uMode, mode);
            GLES20.glUniform1i(uRotation, rotation);
            GLES20.glUniform1i(uBoxCount, boxCount);
            GLES20.glUniform4fv(uBoxes, MAX_BOXES, boxes, 0);
            GLES20.glUniform1fv(uHeats, MAX_BOXES, heats, 0);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            texBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            view.requestRender();
        }

        private int createExternalTexture() {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return t[0];
        }

        private int createProgram(String vs, String fs) {
            int v = compileShader(GLES20.GL_VERTEX_SHADER, vs);
            int f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) return 0;
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }

        private int compileShader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) return 0;
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
                "uniform int uMode; uniform int uRotation; uniform int uBoxCount;\n" +
                "uniform vec4 uBoxes[8]; uniform float uHeats[8];\n" +
                "varying vec2 vTexCoord;\n" +
                "float lum(vec3 c){return dot(c,vec3(.299,.587,.114));}\n" +
                "vec2 rot(vec2 p){ if(uRotation==1)return vec2(1.0-p.y,p.x); if(uRotation==2)return vec2(1.0-p.x,1.0-p.y); if(uRotation==3)return vec2(p.y,1.0-p.x); return p;}\n" +
                "vec3 classic(float t){t=clamp(t,0.0,1.0);return vec3(clamp(1.5-abs(4.0*t-3.0),0.0,1.0),clamp(1.5-abs(4.0*t-2.0),0.0,1.0),clamp(1.5-abs(4.0*t-1.0),0.0,1.0));}\n" +
                "vec3 iron(float t){t=clamp(t,0.0,1.0);vec3 a=vec3(.03,.01,.08),b=vec3(.10,.05,.42),c=vec3(.45,.08,.60),d=vec3(.95,.26,.08),e=vec3(1.0,.82,.10),f=vec3(1.0,1.0,.95);if(t<.18)return mix(a,b,t/.18);if(t<.38)return mix(b,c,(t-.18)/.20);if(t<.62)return mix(c,d,(t-.38)/.24);if(t<.84)return mix(d,e,(t-.62)/.22);return mix(e,f,(t-.84)/.16);}\n" +
                "float aiHeat(vec2 p){float h=0.0;for(int i=0;i<8;i++){if(i>=uBoxCount)break;vec4 b=uBoxes[i];if(p.x>=b.x&&p.x<=b.z&&p.y>=b.y&&p.y<=b.w){vec2 c=(b.xy+b.zw)*.5;vec2 q=abs((p-c)/max((b.zw-b.xy)*.5,vec2(.001)));float soft=1.0-smoothstep(.55,1.0,max(q.x,q.y));h=max(h,uHeats[i]*(.5+.5*soft));}}return h;}\n" +
                "void main(){vec2 uv=rot(vTexCoord);vec3 src=texture2D(uTexture,uv).rgb;float y=lum(src);if(uMode==0){gl_FragColor=vec4(src,1.0);return;}if(uMode==1){float inv=1.0-y;float c=smoothstep(.05,.96,inv);gl_FragColor=vec4(clamp(vec3(c*.22,c*.78,min(1.0,c*1.15)),0.0,1.0),1.0);return;}if(uMode==2){gl_FragColor=vec4(classic(smoothstep(.04,.96,y)),1.0);return;}float mx=max(max(src.r,src.g),src.b),mn=min(min(src.r,src.g),src.b);float sat=(mx>0.0)?(mx-mn)/mx:0.0;float h=aiHeat(uv);float t=pow(clamp(y*.62+sat*.10+h*.48,0.0,1.0),.82);gl_FragColor=vec4(iron(t),1.0);}\n";
    }
}
