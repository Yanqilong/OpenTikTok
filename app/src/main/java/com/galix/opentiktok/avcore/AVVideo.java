package com.galix.opentiktok.avcore;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

import static android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM;

/**
 * 视频片段
 */
public class AVVideo extends AVComponent {
    private static final String TAG = AVVideo.class.getSimpleName();
    private int textureId;
    private boolean isInputEOF;
    private boolean isOutputEOF;
    private String path;
    private MediaCodec mediaCodec;
    private MediaExtractor mediaExtractor;
    private MediaFormat mediaFormat;
    private Surface surface;
    private SurfaceTexture surfaceTexture;
    private ByteBuffer sampleBuffer;
    private AVFrame avFrame;

    public AVVideo(long srcStartTime, long srcEndTime, String path, int textureId) {
        super(srcStartTime, srcEndTime, AVComponentType.VIDEO);
        this.path = path;
        this.textureId = textureId;
    }

    @Override
    public int open() {
        if (isOpen()) return RESULT_FAILED;
        avFrame = new AVFrame();//TODO release
        avFrame.setValid(false);
        mediaExtractor = new MediaExtractor();
        try {
            mediaExtractor.setDataSource(path);
            for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
                if (mediaExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).contains("video")) {
                    mediaFormat = mediaExtractor.getTrackFormat(i);
                    mediaExtractor.selectTrack(i);
                    mediaCodec = MediaCodec.createDecoderByType(mediaExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME));
                    setDuration(mediaFormat.getLong(MediaFormat.KEY_DURATION));
                    break;
                }
            }
            if (mediaCodec == null) {
                return RESULT_FAILED;
            }
            if (textureId != -1) {
                surfaceTexture = new SurfaceTexture(textureId);
                surface = new Surface(surfaceTexture);
                avFrame.setTexture(textureId);
                avFrame.setSurfaceTexture(surfaceTexture);
                mediaCodec.configure(mediaFormat, surface, null, 0);
            } else {
                sampleBuffer = ByteBuffer.allocateDirect(mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                mediaCodec.configure(mediaFormat, null, null, 0);
                avFrame.setByteBuffer(sampleBuffer);
            }
            mediaCodec.start();
            markOpen(true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return RESULT_OK;
    }

    @Override
    public int close() {
        if (!isOpen()) return RESULT_FAILED;
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
        if (mediaExtractor != null) {
            mediaExtractor.release();
            mediaExtractor = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (sampleBuffer != null) {
            sampleBuffer.reset();
            sampleBuffer = null;
        }
        isInputEOF = false;
        isOutputEOF = false;
        markOpen(false);
        return RESULT_OK;
    }

    @Override
    public AVFrame peekFrame() {
        return avFrame;
    }

    @Override
    public int readFrame() {
        if (!isOpen() || isOutputEOF) return RESULT_FAILED;
        while (!isInputEOF || !isOutputEOF) {
            if (!isInputEOF) {
                int inputBufIdx = mediaCodec.dequeueInputBuffer(0);
                if (inputBufIdx >= 0) {
                    int sampleSize = mediaExtractor.readSampleData(sampleBuffer, 0);
                    if (sampleSize < 0) {
                        sampleSize = 0;
                        isInputEOF = true;
                        Log.d(TAG, "isInputEOF");
                    }
                    mediaCodec.getInputBuffer(inputBufIdx).put(sampleBuffer);
                    mediaCodec.queueInputBuffer(inputBufIdx, 0,
                            sampleSize,
                            mediaExtractor.getSampleTime(),
                            isInputEOF ? BUFFER_FLAG_END_OF_STREAM : 0);
                    mediaExtractor.advance();
                }
            }
            if (!isOutputEOF) {
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputBufIdx = mediaCodec.dequeueOutputBuffer(bufferInfo, -1);
                if (outputBufIdx >= 0) {
                    if (bufferInfo.flags == BUFFER_FLAG_END_OF_STREAM) {
                        isOutputEOF = true;
                        avFrame.setEof(true);
                        avFrame.setPts(mediaFormat.getLong(mediaFormat.KEY_DURATION));
                    } else {
                        avFrame.setEof(false);
                        avFrame.setPts(bufferInfo.presentationTimeUs);
                    }
                    if (textureId == -1) {//no output surface texture
                        ByteBuffer byteBuffer = mediaCodec.getOutputBuffer(outputBufIdx);
                        sampleBuffer.put(byteBuffer);
                        mediaCodec.releaseOutputBuffer(outputBufIdx, false);
                    } else {
                        mediaCodec.releaseOutputBuffer(outputBufIdx, true);
                    }
                    break;
                } else if (outputBufIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(TAG, "INFO_TRY_AGAIN_LATER:" + bufferInfo.presentationTimeUs);
                } else if (outputBufIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED:" + bufferInfo.presentationTimeUs);
                } else if (outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED:" + bufferInfo.presentationTimeUs);
                }
            }
        }
        return RESULT_OK;
    }

    @Override
    public int seekFrame(long position) {
        if (!isOpen()) return RESULT_FAILED;
        long correctPosition = position - getSrcStartTime();
        if (position < getSrcStartTime() || position > getSrcEndTime() || correctPosition > getDuration()) {
            return RESULT_FAILED;
        }
        isInputEOF = false;
        isOutputEOF = false;
        mediaExtractor.seekTo(correctPosition, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        mediaCodec.flush();
        sampleBuffer.reset();
        readFrame();
        return RESULT_OK;
    }

    @Override
    public String toString() {
        return "AVVideo{" +
                "textureId=" + textureId +
                ", isInputEOF=" + isInputEOF +
                ", isOutputEOF=" + isOutputEOF +
                ", path='" + path + '\'' +
                ", mediaCodec=" + mediaCodec +
                ", mediaExtractor=" + mediaExtractor +
                ", mediaFormat=" + mediaFormat +
                ", surface=" + surface +
                ", surfaceTexture=" + surfaceTexture +
                ", sampleBuffer=" + sampleBuffer +
                ", avFrame=" + avFrame +
                "} " + super.toString();
    }
}
