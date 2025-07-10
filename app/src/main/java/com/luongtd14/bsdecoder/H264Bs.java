package com.luongtd14.bsdecoder;

import android.media.*;
import android.util.Log;
import android.view.Surface;

import java.io.*;
import java.nio.ByteBuffer;

public class H264Bs {
    private static final String TAG = "H264Bs";

    private final String filePath, fileOutPath;
    private final int width, height;
    private int cntIn, cntOut;
    private final Surface surface;

    private MediaCodec decoder;
    private FileInputStream fileInputStream;
    private BufferedInputStream bufferedInputStream;
    private Thread decodeThread;
    private boolean isRunning = false;

    private long pts = 0;

    public H264Bs(String filePath, String fileOutPath, Surface surface, int height, int width) {
        this.filePath = filePath;
        this.fileOutPath = fileOutPath;
        this.surface = surface;
        this.width = width;
        this.height = height;
    }

    public void start() {
        isRunning = true;
        decodeThread = new Thread(this::decodeLoop);
        decodeThread.start();
    }

    public void stop() {
        isRunning = false;
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Exception ignored) {}
            decoder.release();
        }
        try {
            if (fileInputStream != null) fileInputStream.close();
            if (bufferedInputStream != null) bufferedInputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing streams", e);
        }
    }

    private void decodeLoop() {
        try {
            decoder = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            decoder.configure(format, surface, null, 0);
            decoder.start();

            fileInputStream = new FileInputStream(filePath);
            bufferedInputStream = new BufferedInputStream(fileInputStream);

            ByteArrayOutputStream nalBuffer = new ByteArrayOutputStream();
            byte[] readBuf = new byte[4096];
            byte[] nalStartCode = {0x00, 0x00, 0x00, 0x01};
            byte[] prev4 = new byte[4];
            boolean foundFirstNal = false;
            int read;

            while (isRunning && (read = bufferedInputStream.read(readBuf)) != -1) {
                for (int i = 0; i < read; i++) {
                    prev4[0] = prev4[1];
                    prev4[1] = prev4[2];
                    prev4[2] = prev4[3];
                    prev4[3] = readBuf[i];

                    if (isStartCode(prev4)) {
                        if (foundFirstNal && nalBuffer.size() > 4) {
                            sendToDecoder(nalBuffer.toByteArray());
                            nalBuffer.reset();
                        } else {
                            foundFirstNal = true;
                        }
                        nalBuffer.write(nalStartCode);
                    } else {
                        if (foundFirstNal) {
                            nalBuffer.write(readBuf[i]);
                        }
                    }
                }
            }

            // Gửi NAL cuối cùng
            if (nalBuffer.size() > 0) {
                sendToDecoder(nalBuffer.toByteArray());
            }

            // Gửi EOS
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                decoder.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                Log.e(TAG, "Sent EOS at pts: " + pts);
            }

            // Drain phần còn lại
            drainOutput(true);

            Log.e("luongtd146", "FINAL In: " + cntIn + " Out: " + cntOut);

        } catch (Exception e) {
            Log.e(TAG, "Decode error", e);
        } finally {
            stop();
        }
    }

    private boolean isStartCode(byte[] data) {
        return data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01;
    }

    private void sendToDecoder(byte[] nal) {
        try {
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(nal);
//                    Log.e("luongtd146", "in: " + (cntIn++) + " " + pts);
                    decoder.queueInputBuffer(inputIndex, 0, nal.length, pts, 0);
                    pts += 33333;
                }
            }

            drainOutput(false);

        } catch (Exception e) {
            Log.e(TAG, "sendToDecoder error", e);
        }
    }

    private void drainOutput(boolean waitForEOS) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean eosReached = false;

        while (true) {
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputIndex >= 0) {
                Log.e("luongtd146", "out: " + (cntOut++) + " " + bufferInfo.presentationTimeUs);
                decoder.releaseOutputBuffer(outputIndex, true);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    eosReached = true;
                    break;
                }
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!waitForEOS) break;
            } else {
                // INFO_OUTPUT_BUFFERS_CHANGED or INFO_OUTPUT_FORMAT_CHANGED
                break;
            }
        }
    }

}
