package com.mk.streamfromcamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;

import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {
    public static final String TAG = "SFCAM";
    private static final int CAMERA_PERMISSION_REQUEST = 54321;

    protected MediaFormat mFormat = null;
    protected int muxTrackIndex = 0;
    protected Handler mHandler = null;
    protected Surface mSurface = null;
    protected OutputStream mOut;

    TextView captcur, captavg;
    TextView enccur, encavg;
    TextView netcur, netavg;
    TextView sizecur, sizeavg;
    TextView framenum;

    TextView instruction;

    EditText bitrate, w, h, fps, i;

    Button start, stop;

    public class FrameStats {
        FrameStats() {start = System.nanoTime();}

        public long start    = 0;
        public long captured = 0;
        public long encoded  = 0;
        public int  size     = 0;
    }

    protected Map<Long, FrameStats> statsMap = Collections.synchronizedMap(new TreeMap<Long, FrameStats>());

    protected int encFrameCount = 0;
    protected double avgEncTimeMs = 0.0;
    protected double avgCaptTimeMs = 0.0;
    protected double avgFrameSizeKB = 0.0;

    public void statsDrop() {
        statsMap.clear();
        encFrameCount = 0;
        avgEncTimeMs = 0.0;
        avgCaptTimeMs = 0.0;
        avgFrameSizeKB = 0.0;
    }

    public void statsStart(long keyUs) {
        synchronized (statsMap) {
            statsMap.put(keyUs, new FrameStats());
        }
    }

    public void statsCaptured(long keyUs) {
        synchronized (statsMap) {
            FrameStats stats = statsMap.get(keyUs);

            if (stats != null && stats.start != 0) {
                stats.captured = System.nanoTime() - stats.start;
                statsMap.put(keyUs, stats);
            }
        }
    }

    public void statsEncoded(long keyUs, int sizeKB) {
        synchronized (statsMap) {
            FrameStats stats = statsMap.remove(keyUs);
            if (stats != null && stats.captured != 0) {
                stats.encoded = System.nanoTime() - stats.start - stats.captured;
                stats.size = sizeKB;

                encFrameCount++;
                double oldWeight = (encFrameCount - 1.0) / encFrameCount;
                double newWeight = 1.0 / encFrameCount;

                avgEncTimeMs = avgEncTimeMs * oldWeight + stats.encoded / 1000000.0 * newWeight;
                avgCaptTimeMs = avgCaptTimeMs * oldWeight + stats.captured / 1000000.0 * newWeight;
                avgFrameSizeKB = avgFrameSizeKB * oldWeight + stats.size / 1024.0 * newWeight;

                statsUpdateUI(stats);
            }
        }

    }

    public void statsUpdateUI(FrameStats stats) {
        framenum.setText("Frame: " + encFrameCount);
        captcur.setText(String.format( "%.2f", stats.captured / 1000000.0));
        captavg.setText(String.format( "%.2f", avgCaptTimeMs));
        enccur.setText(String.format( "%.2f", stats.encoded / 1000000.0));
        encavg.setText(String.format( "%.2f", avgEncTimeMs));
        //netcur.setText();
        //netavg.setText();
        sizecur.setText(String.format( "%.2f", stats.size / 1024.0));
        sizeavg.setText(String.format( "%.2f", avgFrameSizeKB));

        //Log.w(TAG, "_Enc " + encFrameCount + ": " + String.format( "%.2f", stats.captured / 1000000.0) + " ms, " + String.format( "%.2f", stats.encoded / 1000000.0) + " ms, " + String.format( "%.2f", stats.size / 1024.0) + " KB.");
        //Log.w(TAG, "Enc " + encFrameCount + ": " + String.format( "%.2f", avgCaptTimeMs) + " ms, " + String.format( "%.2f", avgEncTimeMs) + " ms, " + String.format( "%.2f", avgFrameSizeKB) + " KB.");
    }

    public static String selectEncoderForType(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] infos = codecList.getCodecInfos();
        String codecName = null;
        for (MediaCodecInfo codecInfo : infos) {
            if (!codecInfo.isEncoder()) {
                continue;
            }

            Log.e("MYCODECCCC", codecInfo.getName());

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    String curName = codecInfo.getName();
                    Log.d(TAG + "CODEC", "Compatible codec: " + curName);
                    if(codecName == null || !curName.contains("google")) {
                        codecName = curName;
                    }
                }
            }
        }
        if(codecName != null) {
            Log.d(TAG + "CODEC", "Selected codec: " + codecName);
        } else {
            Log.e(TAG + "CODEC", "No supported codec for type " + mimeType);
        }
        return codecName;
    }

    Socket socket;

    protected void connect() {
        try {
            //EditText iptv = (EditText) findViewById(R.id.ip);
            //EditText porttv = (EditText) findViewById(R.id.port);

            final ServerSocket serverSocket = new ServerSocket(5555);
            serverSocket.setPerformancePreferences(0, 2, 1);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    instruction.setText("StreamFromCameraClient.exe " + serverSocket.getInetAddress().getHostAddress() + " " + serverSocket.getLocalPort());
                }
            });

            socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            socket.setTrafficClass(0x10);
            socket.setPerformancePreferences(0, 2, 1);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    instruction.setText("StreamFromCameraClient.exe " + serverSocket.getInetAddress().getHostAddress() + " " + serverSocket.getLocalPort());
                }
            });

            mOut = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    CameraDevice mCamera;
    MediaCodec mCodec;

    final int addBytes = 4;

    int encodedFrames = 0;
    int framesWithMuxer = 0;
    byte[] videoSPSandPPS;

    public static void intToByteArray(int value, byte[] arr) {
        arr[0] = (byte)(value >>> 24);
        arr[1] = (byte)(value >>> 16);
        arr[2] = (byte)(value >>> 8);
        arr[3] = (byte) value;
    }

    protected void startCapture() {
        try {
            final CameraManager camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            final String[] ids = camManager.getCameraIdList();
            camManager.openCamera(ids[0], new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    try {
                        final int color = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                        final String mime = MediaFormat.MIMETYPE_VIDEO_AVC;
                        MediaFormat mFormat = MediaFormat.createVideoFormat(mime, Integer.parseInt(w.getText().toString()), Integer.parseInt(h.getText().toString()));
                        mFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
                        mFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, Integer.parseInt(bitrate.getText().toString()) * 1024);
                        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Integer.parseInt(fps.getText().toString()));
                        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Integer.parseInt(i.getText().toString()));

                        final MediaCodec codec = MediaCodec.createByCodecName(MainActivity.selectEncoderForType(mime));
                        if(codec == null) {
                            Log.e(TAG, "AVC encoder not found!");
                            return;
                        }
                        mCamera = camera;
                        mCodec = codec;
                        encodedFrames = 0;
                        final MediaCodecInfo.CodecCapabilities capabilities = codec.getCodecInfo().getCapabilitiesForType(mime);
                        boolean colorSupported = false;
                        for(int colorFormat : capabilities.colorFormats) {
                            colorSupported |= (colorFormat == color);
                        }
                        if(!colorSupported) {
                            Log.e(TAG, "Required color format is not supported by selected encoder!");
                            return;
                        }

                        final String filePath = getExternalFilesDir("encoded") + "/" + "firstframe.mp4";
                        final MediaMuxer muxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                        codec.setCallback(new MediaCodec.Callback() {
                            @Override
                            public void onInputBufferAvailable(MediaCodec codec, int index) {
                                //Not called by encoder
                            }

                            @Override
                            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                                ByteBuffer encoderOutputBuffer = codec.getOutputBuffer(index);
                                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    Log.d(TAG, "video encoder: codec config buffer");

                                    /*NEW*/
                                    videoSPSandPPS = new byte [info.size];
                                    encoderOutputBuffer.position(info.offset);
                                    encoderOutputBuffer.limit(info.offset + info.size);
                                    encoderOutputBuffer.get(videoSPSandPPS);
                                    /*NEW*/

                                    info.size = 0;
                                    codec.releaseOutputBuffer(index, false);
                                    return;
                                }

                                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    Log.d(TAG, "video encoder: end of stream");
                                    //codec.releaseOutputBuffer(index, false);
                                    return;
                                }

                                if (info.size != 0) {
                                    byte[] arr = null;
                                    encoderOutputBuffer.position(info.offset);
                                    encoderOutputBuffer.limit(info.offset + info.size);

                                    encodedFrames++;
                                    if(encodedFrames < framesWithMuxer) {
                                        muxer.writeSampleData(MainActivity.this.muxTrackIndex, encoderOutputBuffer, info);
                                    } else {
                                        if(encodedFrames > framesWithMuxer) {
                                            if(encodedFrames == 1) {
                                                int dataSize = info.size + videoSPSandPPS.length;
                                                arr = new byte[addBytes + dataSize];
                                                System.arraycopy(videoSPSandPPS, 0, arr, addBytes, videoSPSandPPS.length);
                                                encoderOutputBuffer.get(arr, videoSPSandPPS.length + addBytes, info.size);
                                                intToByteArray(dataSize, arr);
                                            } else {
                                                arr = new byte[addBytes + info.size];
                                                encoderOutputBuffer.get(arr, addBytes, info.size);
                                                intToByteArray(info.size, arr);
                                            }
                                        } else{
                                            try {

                                                muxer.writeSampleData(MainActivity.this.muxTrackIndex, encoderOutputBuffer, info);
                                                muxer.stop();
                                                muxer.release();

                                                File file = new File(filePath);
                                                int size = (int) file.length();
                                                arr = new byte[addBytes + size];
                                                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
                                                DataInputStream dis = new DataInputStream(bis);
                                                dis.readFully(arr, addBytes, size);
                                                intToByteArray(size, arr);
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        }

                                        try {
                                            Log.e("PACKET_SIZE", " " + encodedFrames + " " + (arr.length - 4));
                                            mOut.write(arr);
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }

                                    statsEncoded(info.presentationTimeUs, info.size);

                                }
                                codec.releaseOutputBuffer(index, false);
                            }

                            @Override
                            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                                Log.e(TAG, "Encoding error: " + e.getLocalizedMessage());
                            }

                            @Override
                            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                                MainActivity.this.mFormat = codec.getOutputFormat();
                                MainActivity.this.muxTrackIndex = muxer.addTrack(MainActivity.this.mFormat);
                                muxer.start();
                            }
                        });
                        codec.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                        Log.e("SFCFPS", codec.getInputFormat().toString());
                        mFormat = codec.getOutputFormat();
                        mSurface = codec.createInputSurface();
                        
                        camera.createCaptureSession(Arrays.asList(mSurface), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                try {
                                    codec.start();
                                    statsDrop();

                                    CaptureRequest.Builder crBuilder = session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                    crBuilder.addTarget(mSurface);
                                    crBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                    session.setRepeatingRequest(crBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                        @Override
                                        public void onCaptureStarted (CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                                            super.onCaptureStarted(session, request, timestamp, frameNumber);

                                            statsStart(timestamp / 1000);
                                        }

                                        @Override
                                        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                                            super.onCaptureCompleted(session, request, result);

                                            statsCaptured(result.get(CaptureResult.SENSOR_TIMESTAMP) / 1000);
                                        }

                                        @Override
                                        public void onCaptureBufferLost (CameraCaptureSession session, CaptureRequest request, Surface target, long frameNumber) {
                                            Log.e(TAG, "Capture buffer lost! " + frameNumber);
                                        }

                                        @Override
                                        public void onCaptureFailed (CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                                            super.onCaptureFailed(session, request, failure);
                                            Log.e(TAG, "Capture failed! " + failure.getReason());
                                        }

                                    }, mHandler);
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "Unable to create CaptureRequest");
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "Failed to configure CameraCaptureSession");
                            }
                        }, mHandler);

                    } catch (IOException e) {
                        Log.e(TAG, "MediaCodec creation error");
                        e.printStackTrace();
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Unable to create CameraCaptureSession");
                        e.printStackTrace();
                    } catch (MediaCodec.CodecException e) {
                        Log.e(TAG, "MediaCodec exception!");
                        e.printStackTrace();
                        Toast.makeText(MainActivity.this, "MediaCodec exception! " + e.getLocalizedMessage(), Toast.LENGTH_LONG);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.e(TAG, "Camera disconnected");
                    camera.close();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error code: " + error + " (see https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback.html)");
                    camera.close();
                    if(error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) {
                        Log.e(TAG, "Attempting to restart the camera");
                        try {
                            camManager.openCamera(ids[0], this, null);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Camera is disabled by device policy, has been disconnected, or is being used by a higher-priority camera API client.");
                            e.printStackTrace();
                        } catch (SecurityException e) {
                            Log.e(TAG, "Camera permisson disabled.");
                            e.printStackTrace();
                        }
                    }
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera is disabled by device policy, has been disconnected, or is being used by a higher-priority camera API client.");
            e.printStackTrace();
        } catch (SecurityException e) {
            Log.e(TAG, "Camera permisson disabled.");
            e.printStackTrace();
        } catch (IllegalStateException e) {
            Log.e(TAG, "ILLEGAL_STATE_EXCEPTION!");
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        framenum = (TextView) findViewById(R.id.framenum);
        captcur = (TextView) findViewById(R.id.captcur);
        captavg = (TextView) findViewById(R.id.captavg);
        enccur = (TextView) findViewById(R.id.enccur);
        encavg = (TextView) findViewById(R.id.encavg);
        netcur = (TextView) findViewById(R.id.netcur);
        netavg = (TextView) findViewById(R.id.netavg);
        sizecur = (TextView) findViewById(R.id.sizecur);
        sizeavg = (TextView) findViewById(R.id.sizeavg);
        instruction = (TextView) findViewById(R.id.instruction);

        bitrate = (EditText) findViewById(R.id.bitrate);
        w = (EditText) findViewById(R.id.w);
        h = (EditText) findViewById(R.id.h);
        fps = (EditText) findViewById(R.id.fps);
        i = (EditText) findViewById(R.id.i);

        start = (Button) findViewById(R.id.start);
        stop = (Button) findViewById(R.id.stop);

        switchButtons(false, false);

        HandlerThread bgThread = new HandlerThread("BackgroundThread");
        bgThread.setPriority(Thread.MAX_PRIORITY);
        bgThread.start();
        mHandler = new Handler(bgThread.getLooper());

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } else {
            switchButtons(true, false);
        }

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchButtons(false, false);
                AsyncTask.execute(new Runnable() {
                    @Override
                    public void run() {
                        connect();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                startCapture();
                                switchButtons(false, true);
                            }
                        });
                    }
                });
            }
        });

        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mCamera != null) mCamera.close();
                if(mCodec != null) {
                    mCodec.signalEndOfInputStream();
                    mCodec.stop();
                }
                switchButtons(true, false);
            }
        });
    }

    public void switchButtons(boolean startEnabled, boolean stopEnabled) {
        start.setClickable(startEnabled);
        start.setEnabled(startEnabled);
        stop.setClickable(stopEnabled);
        stop.setEnabled(stopEnabled);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case CAMERA_PERMISSION_REQUEST: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    switchButtons(true, false);
                } else {
                    Log.e(TAG, "Camera permisson denied by user. Asking again.");
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                }
                return;
            }
        }
    }
}
