package com.google.android.exoplayer2.suite;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.RingBufferDataSource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.Locale;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Created by ahmed on 11/15/17.
 */

public class SuiteTvPlayer {

    private static final String TAG = "Player";

    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private static final CookieManager DEFAULT_COOKIE_MANAGER;

    static {
        DEFAULT_COOKIE_MANAGER = new CookieManager();
        DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private EventListener listener;
    private Handler mainHandler;
    private EventLogger eventLogger;
    private DataSource.Factory mediaDataSourceFactory;
    private SimpleExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private boolean inErrorState;
    private TrackGroupArray lastSeenTrackGroupArray;

    private boolean shouldAutoPlay;
    private Surface surface;
    private SurfaceTexture texture;
    private Callback callback;

    public SuiteTvPlayer(Context context) {
        mainHandler = new Handler();
        listener = new EventListener();
        TrackSelection.Factory adaptiveTrackSelectionFactory =
                new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);
        trackSelector = new DefaultTrackSelector(adaptiveTrackSelectionFactory);
        eventLogger = new EventLogger(trackSelector);

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context,
                null, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        player = ExoPlayerFactory.newSimpleInstance(
                renderersFactory, trackSelector
                , new DefaultLoadControl(
                        new DefaultAllocator(
                                true, 188, 340
                        ), 5, 20000, 500, 50
                )
        );
        player.addListener(listener);
        player.addListener(eventLogger);
        player.addMetadataOutput(eventLogger);
        player.setAudioDebugListener(eventLogger);
        player.setVideoDebugListener(eventLogger);
        mediaDataSourceFactory = buildDataSource();
        inErrorState = false;
    }

    public void setDataSource(Uri uri) {
        Log.d(TAG, "setDataSource: " + uri);
        inErrorState = false;
        player.stop();
        clearSurface();
        player.prepare(buildMediaSource(uri));
        enableSurface();
        player.setPlayWhenReady(true);
    }

    private void enableSurface() {
        player.setVideoSurface(surface);
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
    }

    public void setSurface(SurfaceTexture texture) {
        this.texture = texture;
        setSurface(new Surface(texture));
    }

    public void stop() {
        player.stop();
    }

    public void pause() {
        player.setPlayWhenReady(false);
    }

    public void start() {
        player.setPlayWhenReady(true);
    }

    public void release() {
        if (player != null) {
            shouldAutoPlay = player.getPlayWhenReady();
            player.release();
            player = null;
            trackSelector = null;
            eventLogger = null;
        }
    }

    private MediaSource buildMediaSource(Uri uri) {
        stop();
        if (!uri.getScheme().equalsIgnoreCase("UDP")) {
            throw new IllegalArgumentException("Only supports UDP streams, link should be udp://${IP_ADDR}:${PORT}");
        }

        return new ExtractorMediaSource(
                uri,
                mediaDataSourceFactory,
                new DefaultExtractorsFactory(), mainHandler, eventLogger
        );
    }

    private DataSource.Factory buildDataSource() {
        return new DataSource.Factory() {
            @Override
            public DataSource createDataSource() {
                return new RingBufferDataSource(
                        BANDWIDTH_METER,
                        65507,
                        12000
                );
            }
        };
    }

    private void clearSurface() {
        if (texture == null) {
            return;
        }
        player.clearVideoSurface();
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        egl.eglInitialize(display, null);

        int[] attribList = {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_NONE, 0,      // placeholder for recordable [@-3]
                EGL10.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        egl.eglChooseConfig(display, attribList, configs, configs.length, numConfigs);
        EGLConfig config = configs[0];
        EGLContext context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, new int[]{
                12440, 2,
                EGL10.EGL_NONE
        });
        EGLSurface eglSurface = egl.eglCreateWindowSurface(display, config, texture,
                new int[]{
                        EGL10.EGL_NONE
                });

        egl.eglMakeCurrent(display, eglSurface, eglSurface, context);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        egl.eglSwapBuffers(display, eglSurface);
        egl.eglDestroySurface(display, eglSurface);
        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_CONTEXT);
        egl.eglDestroyContext(display, context);
        egl.eglTerminate(display);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    class EventListener implements com.google.android.exoplayer2.Player.EventListener {

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest) {

        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

        }

        @Override
        public void onLoadingChanged(boolean isLoading) {
            Log.d(TAG, isLoading ? "loading resumed" : "loading paused");
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            if (callback != null) {
                for (Callback.Info info : Callback.Info.values()) {
                    if (info.state == playbackState) {
                        callback.onInfo(
                                info
                        );
                        break;
                    }
                }
            }
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {

        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {

        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            inErrorState = true;
            if (callback != null) {
                if (error.type == ExoPlaybackException.TYPE_RENDERER) {
                    Exception cause = error.getRendererException();
                    if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
                        // Special case for decoder initialization failures.
                        MediaCodecRenderer.DecoderInitializationException decoderInitializationException =
                                (MediaCodecRenderer.DecoderInitializationException) cause;
                        if (decoderInitializationException.decoderName == null) {
                            if (decoderInitializationException.getCause() instanceof MediaCodecUtil.DecoderQueryException) {
                                Log.e(TAG, "Decoder querying error", decoderInitializationException);
                                callback.onError(Callback.Error.RENDERER_QUERY_DECODER_ISSUE);
                            } else if (decoderInitializationException.secureDecoderRequired) {
                                callback.onError(Callback.Error.RENDERER_NO_SECURE_DECODER_FOUND);
                            } else {
                                callback.onError(Callback.Error.RENDERER_NO_DECODER_FOUND);
                            }
                        } else {
                            callback.onError(Callback.Error.RENDERER_DECODER_INITIALIZATION_ISSUE);
                        }
                    }
                } else if (error.type == ExoPlaybackException.TYPE_SOURCE) {
                    callback.onError(Callback.Error.SOURCE_ERROR);
                } else {
                    callback.onError(Callback.Error.UNKNOWN);
                }
            }
        }

        @Override
        public void onPositionDiscontinuity(int reason) {
            if (callback != null) {
                callback.onInfo(Callback.Info.PLAYBACK_POSITION_DISCONTINUITY);
            }
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

        }

        @Override
        public void onSeekProcessed() {

        }

    }

    public interface Callback {

        enum Error {
            RENDERER_QUERY_DECODER_ISSUE,
            RENDERER_NO_SECURE_DECODER_FOUND,
            RENDERER_NO_DECODER_FOUND,
            RENDERER_DECODER_INITIALIZATION_ISSUE,
            SOURCE_ERROR,
            UNKNOWN
        }

        enum Info {
            PLAYBACK_STATE_READY(Player.STATE_READY),
            PLAYBACK_STATE_BUFFERING(Player.STATE_BUFFERING),
            PLAYBACK_STATE_ENDED(Player.STATE_ENDED),
            PLAYBACK_STATE_IDLE(Player.STATE_IDLE),
            PLAYBACK_POSITION_DISCONTINUITY(10);

            private int state;

            Info(int state) {
                this.state = state;
            }

            public int getState() {
                return state;
            }

            public void setState(int state) {
                this.state = state;
            }
        }

        void onError(@NonNull Error error);
        void onInfo(@NonNull Info info);

    }

}
