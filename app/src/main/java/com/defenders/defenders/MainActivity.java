package com.defenders.defenders;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.FFmpeg;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private ImageView cameraView;
    private CardView connectCameraButton, trackObjectsButton, poiTrackingButton, recordButton, captureButton, videoProcessingButton;
    private CardView connectCameraStateButton, trackObjectsStateButton, poiTrackingStateButton, recordStateButton, captureStateButton, videoProcessingButtonState;
    private TextView connectButtonText, trackObjectsButtonText, poiTrackingButtonText, recordButtonText, captureButtonText, videoProcessingButtonText;

    DatagramSocket streamBridgeSocket;
    DatagramPacket dataFramePacket;

    private final int MAX_FRAME_LEN = 1472;
    private int dataLen = 0;
    private int dataOffsetCounter = 0;
    private boolean enableServerLockFlag = false;
    byte[] FrameBuffer = new byte[20480];
    byte[] packetData = new byte[MAX_FRAME_LEN];
    byte[] tempData;
    private final long [] ffmpegExecutionIds = {0,0};
    private long frameCounter = 0;
    private final int SERVER_PORT = 5151;


    private boolean isCameraStreaming = false;
    private boolean isTrackingEnabled = false;
    private boolean isPOITrackingEnabled = false;
    private boolean isRecordingEnabled = false;
    private boolean isProcessingData = false;
    private boolean isVideoFrameBitMapReady = false;

    private final String [] permissions = {Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private List<Detection> results;
    private String objectInformation;
    private String SENSOR_MODE = "";

    private Thread recordStreamThread;
    private Thread streamBridgeServerThread;

    private Handler uiHandler;
    private Message streamMessage;
    private Bitmap imageBitMap;
    private Bitmap videoFrameBitMap;
    private Bitmap rotatedImageBitMap;

    private Matrix rotationMatrix;
    private File snapshotsDirectory;
    private File framesDirectory;
    private File videosDirectory;

    private TensorImage targetImage;
    private ObjectDetector detector;
    private Paint boundingBoxPaint, labelPaint;
    private Canvas canvas;
    private Category category;
    private RectF boundingBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initSystemObjects();
        initStreamBridgeEndpoint();
        initObjectDetectionModel();
        initStorageAccess();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        uiHandler = new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message message){
                try {
                    String command = message.obj.toString();

                    if (command.equals("FRAME")) {
                        if (dataOffsetCounter >= 1024) {

                            imageBitMap = BitmapFactory.decodeByteArray(FrameBuffer, 0, dataOffsetCounter);
                            rotatedImageBitMap = Bitmap.createBitmap(imageBitMap, 0, 0, imageBitMap.getWidth(), imageBitMap.getHeight(), rotationMatrix, true);

                            if (isRecordingEnabled && !isVideoFrameBitMapReady) {
                                isVideoFrameBitMapReady = true;
                                videoFrameBitMap = Bitmap.createBitmap(rotatedImageBitMap);
                            }

                            if (isTrackingEnabled) {
                                runObjectDetection(rotatedImageBitMap);
                            } else {
                                cameraView.setImageBitmap(rotatedImageBitMap);
                            }


                            if (!isCameraStreaming) {
                                cameraView.setImageResource(R.mipmap.no_video);
                            }
                        }

                        enableServerLockFlag = false;
                        dataOffsetCounter = 0;
                    }
                }
                 catch (Exception ignored) {
                    dataOffsetCounter = 0;
                    enableServerLockFlag = false;
                }

            }
        };

        cameraView = findViewById(R.id.camera_view);

        connectCameraStateButton = findViewById(R.id.connect_state_button);
        trackObjectsStateButton = findViewById(R.id.tracking_state_button);
        poiTrackingStateButton = findViewById(R.id.poi_tracking_state_button);
        recordStateButton = findViewById(R.id.record_state_button);
        captureStateButton = findViewById(R.id.capture_state_button);
        videoProcessingButtonState = findViewById(R.id.video_processing_state_button);

        connectButtonText = findViewById(R.id.connect_button_text);
        trackObjectsButtonText = findViewById(R.id.tracking_control_button_text);
        poiTrackingButtonText = findViewById(R.id.poi_tracking_button_text);
        recordButtonText = findViewById(R.id.record_button_text);
        captureButtonText = findViewById(R.id.capture_button_text);
        videoProcessingButtonText = findViewById(R.id.video_processing_button_text);

        connectCameraButton = findViewById(R.id.connect_button);
        trackObjectsButton = findViewById(R.id.tracking_control);
        poiTrackingButton = findViewById(R.id.poi_tracking_button);
        recordButton = findViewById(R.id.record_button);
        captureButton = findViewById(R.id.capture_button);
        videoProcessingButton = findViewById(R.id.video_processing_button);

        connectCameraButton.setOnClickListener(view -> {
            if(isCameraStreaming){
                deactivateAllControls();
            }else {
                activateAllControls();
                startFFMPEGStreamBridgeServer();
            }
        });

        trackObjectsButton.setOnClickListener(view -> {
            if(isCameraStreaming){
                if(isTrackingEnabled){
                    isTrackingEnabled = false;
                    trackObjectsButton.setCardBackgroundColor(getColor(R.color.default_button_background));
                    trackObjectsStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
                    trackObjectsButtonText.setTextColor(0xffffffff);

                    isPOITrackingEnabled = false;
                    poiTrackingButton.setCardBackgroundColor(getColor(R.color.default_button_background));
                    poiTrackingStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
                    poiTrackingButtonText.setTextColor(0xffffffff);
                    SENSOR_MODE = "";

                }else {

                    SENSOR_MODE = "TRACK_MODE";
                    trackObjectsButton.setCardBackgroundColor(getColor(R.color.pressed_button_background));
                    trackObjectsStateButton.setCardBackgroundColor(getColor(R.color.active_button_background));
                    trackObjectsButtonText.setTextColor(0xff000000);
                    isTrackingEnabled = true;
                    Toast.makeText(getApplicationContext(),"Tracking AI: Enabled", Toast.LENGTH_SHORT).show();
                }
            }else{
                Toast.makeText(getApplicationContext(),"Press Link first", Toast.LENGTH_SHORT).show();
            }
        });

        poiTrackingButton.setOnClickListener(view -> {
            if(isCameraStreaming && isTrackingEnabled){
                if(isPOITrackingEnabled){
                    isPOITrackingEnabled = false;
                    poiTrackingButton.setCardBackgroundColor(getColor(R.color.default_button_background));
                    poiTrackingStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
                    poiTrackingButtonText.setTextColor(0xffffffff);
                    SENSOR_MODE = "TRACK_MODE";
                }else {
                    SENSOR_MODE = "POI_MODE";
                    poiTrackingButton.setCardBackgroundColor(getColor(R.color.pressed_button_background));
                    poiTrackingStateButton.setCardBackgroundColor(getColor(R.color.active_button_background));
                    poiTrackingButtonText.setTextColor(0xff000000);
                    isPOITrackingEnabled = true;
                }
            }else{
                Toast.makeText(getApplicationContext(),"Enable Link & Tracking", Toast.LENGTH_SHORT).show();
            }
        });

        recordButton.setOnClickListener(view -> {
            if(isCameraStreaming){
                if(!isProcessingData) {
                    if (isRecordingEnabled) {
                        isRecordingEnabled = false;
                        recordButton.setCardBackgroundColor(getColor(R.color.default_button_background));
                        recordStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
                        recordButtonText.setTextColor(0xffffffff);

                        String longFileName = new SimpleDateFormat("dd-MM-yyyy-HHmmss", Locale.getDefault()).format(new Date());
                        File destFile = new File(videosDirectory, String.format(Locale.ENGLISH, "%s.mp4", longFileName));
                        processImageFramesToVideoFile(framesDirectory, destFile);

                    } else {
                        recordButton.setCardBackgroundColor(getColor(R.color.record_pressed_button_background));
                        recordStateButton.setCardBackgroundColor(getColor(R.color.record_active_button_background));
                        recordButtonText.setTextColor(getColor(R.color.record_active_button_background));
                        isRecordingEnabled = true;
                    }
                }else{
                    Toast.makeText(getApplicationContext(),"Encoding Video", Toast.LENGTH_SHORT).show();
                }
            }else{
                Toast.makeText(getApplicationContext(),"Press Link first", Toast.LENGTH_SHORT).show();
            }
        });

        captureButton.setOnTouchListener((view, motionEvent) -> {
            if(isCameraStreaming){
                if(motionEvent.getAction() == MotionEvent.ACTION_UP){
                    captureButton.setCardBackgroundColor(getColor(R.color.default_button_background));
                    captureStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
                    captureButtonText.setTextColor(0xffffffff);
                }else if(motionEvent.getAction() == MotionEvent.ACTION_DOWN){
                    String longFileName = new SimpleDateFormat("dd-MM-yyyy-HHmmss", Locale.getDefault()).format(new Date());
                    saveImageCapture(longFileName);
                    captureButton.setCardBackgroundColor(getColor(R.color.pressed_button_background));
                    captureStateButton.setCardBackgroundColor(getColor(R.color.active_button_background));
                    captureButtonText.setTextColor(0xff000000);
                }
            }else{
                Toast.makeText(getApplicationContext(),"Press Link first", Toast.LENGTH_SHORT).show();
            }
            return true;
        });


        streamBridgeServerThread = new Thread(() -> {

            while (true){
                try {

                    if(!enableServerLockFlag) {

                        streamBridgeSocket.receive(dataFramePacket);
                        tempData = dataFramePacket.getData();
                        dataLen = dataFramePacket.getLength();

                        if (dataLen == MAX_FRAME_LEN ) {
                            System.arraycopy(tempData, 0, FrameBuffer, dataOffsetCounter, dataLen);
                            dataOffsetCounter += dataLen;
                        } else {
                            dataOffsetCounter += dataLen;
                            System.arraycopy(tempData, 0, FrameBuffer, dataOffsetCounter, dataLen);
                            dataLen = 0;

                            if (isCameraStreaming) {
                                enableServerLockFlag = true;
                                streamMessage = new Message();
                                streamMessage.obj = "FRAME";
                                uiHandler.handleMessage(streamMessage);
                            }
                        }
                    }
                    Thread.sleep(1);
                }catch (Exception ignored){}
            }
        });

        recordStreamThread = new Thread(() -> {
            while (true){
                try{
                    if(isRecordingEnabled){
                        if(isVideoFrameBitMapReady) {
                            File destFile = new File(framesDirectory, String.format(Locale.ENGLISH, "F_%d.jpg", frameCounter));
                            FileOutputStream fileOutputStream = new FileOutputStream(destFile);
                            videoFrameBitMap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
                            fileOutputStream.flush();
                            fileOutputStream.close();
                            isVideoFrameBitMapReady = false;
                            frameCounter++;
                        }
                    }
                    Thread.sleep(5);
                }catch (Exception ignored){}
            }
        });

        streamBridgeServerThread.start();
        recordStreamThread.start();
    }

    private void activateAllControls() {
        connectCameraButton.setCardBackgroundColor(getColor(R.color.pressed_button_background));
        connectCameraStateButton.setCardBackgroundColor(getColor(R.color.active_button_background));
        connectButtonText.setTextColor(0xff000000);
        isCameraStreaming = true;
        dataOffsetCounter = 0;
        enableServerLockFlag = false;
    }

    private void saveImageCapture(String longFileName) {
        try {
            File destFile = new File(snapshotsDirectory, String.format(Locale.ENGLISH,"%s.jpg", longFileName));
            FileOutputStream fileOutputStream = new FileOutputStream(destFile);
            rotatedImageBitMap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
            fileOutputStream.flush();
            fileOutputStream.close();
        }catch (Exception ignored){
            Toast.makeText(getApplicationContext(),"Capture Error", Toast.LENGTH_SHORT).show();
        }
    }

    private void initStorageAccess() {
        if(ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, permissions, 200);
        }
        initDataDirectories();
    }

    private void initObjectDetectionModel() {

        try {
            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setMaxResults(3)
                    .setScoreThreshold(0.5f)
                    .build();
            detector = ObjectDetector.createFromFileAndOptions(getApplicationContext(), "model.tflite", options);
        } catch (IOException ignored) {
            Toast.makeText(getApplicationContext(),"Model LOAD Failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void initStreamBridgeEndpoint() {
        try{
            streamBridgeSocket = new DatagramSocket(SERVER_PORT);
            dataFramePacket = new DatagramPacket(packetData, packetData.length);
        }catch (Exception ignored){
            Toast.makeText(getApplicationContext(),"Net Error", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void initSystemObjects() {
        rotationMatrix = new Matrix();
        rotationMatrix.postRotate(-90f);

        boundingBoxPaint = new Paint();
        boundingBoxPaint.setColor(0xF0D4FFA1);
        boundingBoxPaint.setStyle(Paint.Style.STROKE);
        boundingBoxPaint.setStrokeWidth(0.7f);

        labelPaint = new Paint();
        labelPaint.setColor(0xF0FF9800);
        labelPaint.setStyle(Paint.Style.STROKE);
        labelPaint.setStrokeWidth(0.7f);

    }


    private void runObjectDetection(Bitmap imageBitMap) {
        targetImage = TensorImage.fromBitmap(imageBitMap);
        results = detector.detect(targetImage);
        canvas = new Canvas(imageBitMap);

        for(Detection result : results){
            category = result.getCategories().get(0);

            switch (SENSOR_MODE){
                case "TRACK_MODE":
                    objectInformation = String.format(Locale.ENGLISH, "%s %d", category.getLabel().toUpperCase(Locale.ENGLISH), ((int) (category.getScore() * 100))) + "%";
                    boundingBox = result.getBoundingBox();
                    canvas.drawText(objectInformation, boundingBox.left, boundingBox.top - 10, labelPaint);
                    canvas.drawRect(boundingBox, boundingBoxPaint);
                    break;

                case "POI_MODE":
                    if(category.getLabel().equals("person")){
                        objectInformation = String.format(Locale.ENGLISH, "%s %d", category.getLabel().toUpperCase(Locale.ENGLISH), ((int) (category.getScore() * 100))) + "%";
                        boundingBox = result.getBoundingBox();
                        canvas.drawText(objectInformation, boundingBox.left, boundingBox.top - 10, labelPaint);
                        canvas.drawRect(boundingBox, boundingBoxPaint);
                    }
                    break;
            }

        }

        cameraView.setImageBitmap(imageBitMap);

    }


    private void initDataDirectories() {
        snapshotsDirectory = new File(getExternalFilesDir(null),"Camera");
        framesDirectory = new File(getExternalFilesDir(null), ".frames");
        videosDirectory = new File(getExternalFilesDir(null), "Videos");

        if(!snapshotsDirectory.exists()){
            snapshotsDirectory.mkdir();
        }

        if(!framesDirectory.exists()){
            framesDirectory.mkdir();
        }

        if(!videosDirectory.exists()){
            videosDirectory.mkdir();
        }
    }

    private void startFFMPEGStreamBridgeServer() {

        String [] ffmpegStreamServerBridgeCommand = {
                "-loglevel","error", // Disable loglevel to errors
                "-i", "http://192.168.25.1:8080/?action=stream", // Target Data Stream URL
                "-vcodec", "copy",
                "-pix_fmt", "yuvj422p",
                "-g", "2",
                "-f", "mjpeg",
                String.format(Locale.ENGLISH, "udp://127.0.0.1:%d", SERVER_PORT)
        };

        FFmpeg.executeAsync(ffmpegStreamServerBridgeCommand, (executionId, returnCode) -> {
            if(returnCode == Config.RETURN_CODE_SUCCESS){
                ffmpegExecutionIds[0] = executionId;
            }
        });
    }

    private void processImageFramesToVideoFile(File sourceDir, File destFile) {

        String[] videoProcessingCommand = new String[8];
        videoProcessingCommand[0] = "-y";
        videoProcessingCommand[1] = "-framerate";
        videoProcessingCommand[2] = "15";
        videoProcessingCommand[3] = "-loglevel";
        videoProcessingCommand[4] = "error";
        videoProcessingCommand[5] = "-i";
        videoProcessingCommand[6] = sourceDir.getPath()+"/F_%d.jpg";
        videoProcessingCommand[7] = destFile.getPath();

        isProcessingData = true;
        setVideoProcessingButtonState(true);

        FFmpeg.executeAsync(videoProcessingCommand, (executionId, returnCode) -> {
            if(returnCode == Config.RETURN_CODE_SUCCESS){
                isProcessingData = false;
                ffmpegExecutionIds[1] = executionId;

                for(File frameFile : Objects.requireNonNull(framesDirectory.listFiles())){
                    frameFile.delete();
                }

                frameCounter = 0;
                setVideoProcessingButtonState(false);
                Toast.makeText(getApplicationContext(),"Process Complete", Toast.LENGTH_SHORT).show();
            }else{
                isProcessingData = false;
                setVideoProcessingButtonState(false);
                Toast.makeText(getApplicationContext(),"Processing Failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setVideoProcessingButtonState(boolean isProcessingVideoFrames) {
        if(isProcessingVideoFrames){
            videoProcessingButton.setCardBackgroundColor(getColor(R.color.processing_button_background));
            videoProcessingButtonState.setCardBackgroundColor(getColor(R.color.active_button_background));
            videoProcessingButtonState.setVisibility(View.VISIBLE);
            videoProcessingButtonText.setVisibility(View.VISIBLE);
            videoProcessingButtonText.setTextColor(0xff000000);
        }else{
            videoProcessingButton.setCardBackgroundColor(getColor(R.color.default_button_background));
            videoProcessingButtonState.setCardBackgroundColor(getColor(R.color.inactive_button_background));
            videoProcessingButtonText.setTextColor(0xff);
            videoProcessingButtonState.setVisibility(View.INVISIBLE);
            videoProcessingButtonText.setVisibility(View.INVISIBLE);
        }
    }

    private void deactivateAllControls() {
        isCameraStreaming = false;
        isTrackingEnabled = false;
        isRecordingEnabled = false;
        isPOITrackingEnabled = false;

        if(ffmpegExecutionIds[0] != 0) {
            FFmpeg.cancel(ffmpegExecutionIds[0]);
        }
        if(ffmpegExecutionIds[1] != 0) {
            FFmpeg.cancel(ffmpegExecutionIds[1]);
        }

        cameraView.setImageResource(R.mipmap.no_video);
        connectCameraButton.setCardBackgroundColor(getColor(R.color.default_button_background));
        connectCameraStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
        connectButtonText.setTextColor(0xffffffff);

        recordButton.setCardBackgroundColor(getColor(R.color.default_button_background));
        recordStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
        recordButtonText.setTextColor(0xffffffff);

        trackObjectsButton.setCardBackgroundColor(getColor(R.color.default_button_background));
        trackObjectsStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
        trackObjectsButtonText.setTextColor(0xffffffff);

        poiTrackingButton.setCardBackgroundColor(getColor(R.color.default_button_background));
        poiTrackingStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
        poiTrackingButtonText.setTextColor(0xffffffff);

        captureButton.setCardBackgroundColor(getColor(R.color.default_button_background));
        captureStateButton.setCardBackgroundColor(getColor(R.color.inactive_button_background));
        captureButtonText.setTextColor(0xffffffff);
    }
}