package com.xgimi.mediacodecplayer;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {
    private static String VIDEO_PATH = Environment.getExternalStorageDirectory() + "/XgimiVideo/video_2D.mp4";
    private static String PNG_PATH = Environment.getExternalStorageDirectory() + "/";

    private static int PNG_MAX_SAVE = 20;
    private boolean isplaying = true; //true: playing, false:save png


    private SurfaceView mSurfaceView;
    private MyHolder myHolder;
    private PlayThread playThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSurfaceView = new SurfaceView(this);
        myHolder = new MyHolder();
        mSurfaceView.getHolder().addCallback(myHolder);
        setContentView(mSurfaceView);
        verifyStoragePermissions(this);
    }

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"};


    public static void verifyStoragePermissions(Activity activity) {

        try {
            //检测是否有写的权限
            int permission = ActivityCompat.checkSelfPermission(activity,
                    "android.permission.WRITE_EXTERNAL_STORAGE");
            if (permission != PackageManager.PERMISSION_GRANTED) {
                // 没有写的权限，去申请写的权限，会弹出对话框
                ActivityCompat.requestPermissions(activity, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public class MyHolder implements SurfaceHolder.Callback {

        @Override
        public void surfaceCreated(SurfaceHolder holder) {

        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (playThread == null) {
                playThread = new PlayThread(holder.getSurface());
            }
            playThread.start();

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (playThread != null) {
                playThread.isExit = true;
            }
        }
    }

    public class PlayThread extends Thread {
        private Surface surface;
        public boolean isExit = false;

        private MediaExtractor extractor;
        private MediaCodec decoder;
        CodecOutputSurface outputSurface = null;
        int decodeCount = 0;


        public PlayThread(Surface s) {
            surface = s;
        }

        int outputFrameCount = 0;

        @Override
        public void run() {
            extractor = new MediaExtractor();
            try {
                extractor.setDataSource(VIDEO_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    try {
                        decoder = MediaCodec.createDecoderByType(mime);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (isplaying) {
                        decoder.configure(format, surface, null, 0);  //播放
                    } else {
                        outputSurface = new CodecOutputSurface(640, 480);
                        decoder.configure(format, outputSurface.getSurface(), null, 0);  //存图片
                    }
                    break;
                }
            }

            if (decoder == null) {
                Log.e("dddd", "Can't find video info!");
                return;
            }

            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean isEOS = false;
            long startMs = System.currentTimeMillis();

            while (!Thread.interrupted()) {
                if (!isEOS) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            // We shouldn't stop the playback at this point, just pass the EOS
                            // flag to decoder, we will get it again from the
                            // dequeueOutputBuffer
                            Log.d("dddd", "InputBuffer BUFFER_FLAG_END_OF_STREAM");
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isEOS = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    ByteBuffer buffer = decoder.getInputBuffer(outIndex);
                    Log.v("dddd", "We can't use this buffer but render it due to the API limit, " + buffer);

                    // We use a very simple clock to keep the video FPS, or the video
                    // playback will be too fast
                    while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
                        try {
                            sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, true);

                    if ((!isplaying) && (outputSurface != null)) {
                        outputSurface.awaitNewImage();
                        outputSurface.drawImage(true);

                        if (decodeCount < PNG_MAX_SAVE) {
                            File outputFile = new File(PNG_PATH,
                                    String.format("frame-%02d.png", decodeCount));
                            try {
                                outputSurface.saveFrame(outputFile.toString());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }else {
                            break;
                        }
                        decodeCount++;
                    }
                }

                // All decoded frames have been rendered, we can stop playing now
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                    break;
                }

            }

            decoder.stop();
            decoder.release();
            extractor.release();
        }
    }
}
