package com.galix.opentiktok.avcore;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.galix.opentiktok.render.AudioRender;
import com.galix.opentiktok.render.OESRender;

import java.util.LinkedList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static com.galix.opentiktok.avcore.AVEngine.VideoState.VideoStatus.DESTROY;
import static com.galix.opentiktok.avcore.AVEngine.VideoState.VideoStatus.PAUSE;
import static com.galix.opentiktok.avcore.AVEngine.VideoState.VideoStatus.PLAY;
import static com.galix.opentiktok.avcore.AVEngine.VideoState.VideoStatus.SEEK;

/**
 * 视频引擎
 */
public class AVEngine implements GLSurfaceView.Renderer {

    private static final String TAG = AVEngine.class.getSimpleName();
    private static final int PLAY_GAP = 10;//MS
    private static final int MAX_TEXTURE = 30;
    private static AVEngine gAVEngine = null;
    private VideoState mVideoState;
    private HandlerThread mAudioThread;
    private Handler mAudioHandler;
    private GLSurfaceView mGLSurfaceView;
    private OnFrameUpdateCallback mOnFrameUpdateCallback;
    private LinkedList<AVComponent> mComponents;
    private AudioRender mAudioRender;
    private OESRender mOesRender;
    private int[] mTextures;
    private int mValidTexture;

    public interface OnFrameUpdateCallback {
        void onFrameUpdate();
    }

    private AVEngine() {
        mVideoState = new VideoState();
        mComponents = new LinkedList<>();
    }

    //视频核心信息类
    public static class VideoState {

        public enum VideoStatus {
            INIT,
            PLAY,
            PAUSE,
            SEEK,
            DESTROY
        }

        public boolean isInputEOF = false;
        public boolean isOutputEOF = false;
        public boolean isExit = false;

        public long position = 0;//当前视频位置 ms
        public long seekPosition = -1;
        public long duration = 0;//视频总时长 ms
        public long videoTime = Long.MIN_VALUE;//视频播放时间戳
        public long audioTime = Long.MIN_VALUE;//音频播放时间戳
        public VideoStatus status = VideoStatus.INIT;//播放状态

        @Override
        public String toString() {
            return "VideoState{" +
                    "isInputEOF=" + isInputEOF +
                    ", isOutputEOF=" + isOutputEOF +
                    ", isExit=" + isExit +
                    ", position=" + position +
                    ", seekPosition=" + seekPosition +
                    ", duration=" + duration +
                    ", videoTime=" + videoTime +
                    ", audioTime=" + audioTime +
                    ", status=" + status +
                    '}';
        }
    }

    public static AVEngine getVideoEngine() {
        if (gAVEngine == null) {
            synchronized (AVEngine.class) {
                if (gAVEngine == null) {
                    gAVEngine = new AVEngine();
                }
            }
        }
        return gAVEngine;
    }

    public void configure(GLSurfaceView glSurfaceView) {
        mGLSurfaceView = glSurfaceView;
        mGLSurfaceView.setEGLContextClientVersion(3);
        mGLSurfaceView.setRenderer(this);
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        mOesRender = new OESRender();
        mOesRender.open();
        mTextures = new int[MAX_TEXTURE];
        GLES30.glGenTextures(MAX_TEXTURE, mTextures, 0);
        mValidTexture = 0;
        createDaemon();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mOesRender.write(new OESRender.OesRenderConfig(width, height));
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        boolean needSwap = false;
        while (!needSwap) {
            if (mVideoState.status == PLAY || mVideoState.status == SEEK) {
                long targetPosition = mVideoState.status == PLAY ? mVideoState.position : mVideoState.seekPosition;
                List<AVComponent> components = findComponents(AVComponent.AVComponentType.VIDEO, targetPosition);
                for (AVComponent component : components) {
                    if (mVideoState.status == PLAY) {
                        component.readFrame();
                    } else {
                        component.seekFrame(targetPosition);
                    }
                    AVFrame avFrame = component.peekFrame();
                    if (avFrame != null) {
                        needSwap = true;
                        mOesRender.render(avFrame);
                        mVideoState.position = avFrame.getPts();
                    }
                }
                onFrameUpdate();
            } else {
                try {
                    Thread.sleep(PLAY_GAP);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void setOnFrameUpdateCallback(OnFrameUpdateCallback callback) {
        mOnFrameUpdateCallback = callback;
    }

    private void createDaemon() {
        mAudioRender = new AudioRender();
        mAudioRender.open();
        mAudioThread = new HandlerThread("audioThread");
        mAudioThread.start();
        mAudioHandler = new Handler(mAudioThread.getLooper());
        mAudioHandler.post(() -> {
            while (!mVideoState.isExit) {
                if (mVideoState.status == PLAY) {
                    List<AVComponent> components = findComponents(AVComponent.AVComponentType.AUDIO, mVideoState.position);
                    for (AVComponent avComponent : components) {
                        avComponent.readFrame();
                    }
                    if (!components.isEmpty()) {
                        AVAudio audio = (AVAudio) components.get(0);
                        audio.readFrame();
                        AVFrame audioFrame = audio.peekFrame();
                        mAudioRender.render(audioFrame);
                        mVideoState.audioTime = audio.getSrcStartTime() + audio.getPosition();
                    }
                } else {
                    try {
                        Thread.sleep(PLAY_GAP);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    public List<AVComponent> findComponents(AVComponent.AVComponentType type, long position) {
        List<AVComponent> components = new LinkedList<>();
        for (AVComponent component : mComponents) {
            if (component.getType() == type && component.isValid(position)) {
                components.add(component);
            }
        }
        return components;
    }

    public void addComponent(AVComponent avComponent) {
        mComponents.add(avComponent);
        avComponent.open();
    }

    public void removeComponent(AVComponent avComponent) {
        avComponent.close();
        mComponents.remove(avComponent);
    }

    public void start() {
        mVideoState.status = PLAY;
    }

    public void pause() {
        mVideoState.status = PAUSE;
    }

    public void playPause() {
        if (mVideoState.status == PLAY) {
            pause();
        } else {
            start();
        }
    }

    public void seek(long position) {
        mVideoState.status = SEEK;
        if (position != -1) {
            mVideoState.seekPosition = position;
            mVideoState.isInputEOF = false;
            mVideoState.isOutputEOF = false;
        }
    }

    public void release() {
        mVideoState.isExit = true;
        mVideoState.status = DESTROY;
        for (AVComponent avComponent : mComponents) {
            avComponent.close();
        }
        if (mAudioHandler != null && mAudioThread != null) {
            mAudioHandler.getLooper().quit();
            try {
                mAudioThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public VideoState getVideoState() {
        return mVideoState;
    }

    public int nextValidTexture() {
        int targetTexture = -1;
        if (mValidTexture < MAX_TEXTURE) {
            targetTexture = mValidTexture;
            mValidTexture++;
        }
        return targetTexture;
    }

    private void onFrameUpdate() {
        if (mOnFrameUpdateCallback != null) {
            mOnFrameUpdateCallback.onFrameUpdate();
        }
        dumpVideoState();
    }

    private void dumpVideoState() {
        Log.d(TAG, mVideoState.toString());
        for (AVComponent avComponent : mComponents) {
            Log.d(TAG, avComponent.toString());
        }
    }

}