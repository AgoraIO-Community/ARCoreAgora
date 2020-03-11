package com.example.arcoreagora;

import android.Manifest;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.arcoreagora.rendering.BackgroundRenderer;
import com.example.arcoreagora.rendering.ObjectRenderer;
import com.example.arcoreagora.rendering.PeerRenderer;
import com.example.arcoreagora.rendering.PlaneRenderer;
import com.example.arcoreagora.rendering.PointCloudRenderer;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import io.agora.rtc.Constants;
import io.agora.rtc.IRtcEngineEventHandler;
import io.agora.rtc.RtcEngine;
import io.agora.rtc.mediaio.MediaIO;
import io.agora.rtc.video.VideoCanvas;
import io.agora.rtc.video.VideoEncoderConfiguration;

public class AgoraARCoreActivity extends AppCompatActivity implements GLSurfaceView.Renderer{
    private static final String TAG = AgoraARCoreActivity.class.getSimpleName();
    private static final int PERMISSION_REQUEST_CODE = 0X0001;

    private SurfaceView mRemoteView;
    private RelativeLayout mRemoteContainer;
    private ImageView mMuteBtn;
    private GLSurfaceView mSurfaceView;
    private GestureDetector mGestureDetector;
    private Snackbar mMessageSnackbar;

    private RtcEngine mRtcEngine;
    private AgoraVideoSource mSource;
    private AgoraVideoRender mRender;
    private Handler mSenderHandler;
    private Session mSession;
    private final ObjectRenderer mVirtualObject = new ObjectRenderer();
    private final ObjectRenderer mVirtualObjectShadow = new ObjectRenderer();
    private final PlaneRenderer mPlaneRenderer = new PlaneRenderer();
    private final PointCloudRenderer mPointCloud = new PointCloudRenderer();
    private final BackgroundRenderer mBackgroundRenderer = new BackgroundRenderer();
    private PeerRenderer mPeerObject = new PeerRenderer();
    private DisplayRotationHelper mDisplayRotationHelper;
    private Instrumentation instrumentation = new Instrumentation();

    private final ArrayBlockingQueue<MotionEvent> queuedSingleTaps = new ArrayBlockingQueue<>(16);
    private final ArrayList<Anchor> anchors = new ArrayList<>();
    private final float[] mAnchorMatrix = new float[16];
    private float mScaleFactor = 0.1f;
    private String channelName = "";
    private boolean installRequested;
    private boolean mHidePoint;
    private boolean mHidePlane;
    private boolean isCalling = true;
    private boolean isMuted = false;
    private int mWidth, mHeight;

    private IRtcEngineEventHandler mRtcEventHandler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(final String channel, int uid, int elapsed) {
            //when local user joined the channel
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(AgoraARCoreActivity.this, "Joined channel " + channel, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRemoteVideoStateChanged(final int uid, int state, int reason, int elapsed) {
            super.onRemoteVideoStateChanged(uid, state, reason, elapsed);
            //when remote user join the channel
            if (state == Constants.REMOTE_VIDEO_STATE_STARTING) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        addRemoteRender(uid);
                    }
                });
            }
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            //when remote user leave the channel
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    removeRemoteRender();
                }
            });
        }

        @Override
        public void onStreamMessage(int uid, int streamId, byte[] data) {
            //when received the remote user's stream message data
            super.onStreamMessage(uid, streamId, data);
            int touchCount = data.length / 8;       //number of touch points from data array
            for (int k = 0; k < touchCount; k++) {
                //get the touch point's x,y position related to the center of the screen and calculated the raw position
                byte[] xByte = new byte[4];
                byte[] yByte = new byte[4];
                for (int i = 0; i < 4; i++) {
                    xByte[i] = data[i + 8 * k];
                    yByte[i] = data[i + 8 * k + 4];
                }
                float convertedX = ByteBuffer.wrap(xByte).getFloat();
                float convertedY = ByteBuffer.wrap(yByte).getFloat();
                float center_X = convertedX + ((float) mWidth / 2);
                float center_Y = convertedY + ((float) mHeight / 2);

                //simulate the clicks based on the touch position got from the data array
                instrumentation.sendPointerSync(MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, center_X, center_Y, 0));
                instrumentation.sendPointerSync(MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, center_X, center_Y, 0));
            }
        }
    };

    private String[] permissions = {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.hide();
        }
        setContentView(R.layout.activity_agora_arcore);

        //get device screen size
        mWidth= this.getResources().getDisplayMetrics().widthPixels;
        mHeight= this.getResources().getDisplayMetrics().heightPixels;

        channelName = getIntent().getStringExtra("ChannelName");

        mRemoteContainer = findViewById(R.id.remote_video_view_container);
        mSurfaceView = findViewById(R.id.surfaceview);
        mMuteBtn = findViewById(R.id.btn_mute);

        mDisplayRotationHelper = new DisplayRotationHelper(this);

        mGestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        onSingleTap(e);
                        return true;
                    }

                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }
                });

        mSurfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mGestureDetector.onTouchEvent(event);
            }
        });

        mSurfaceView.setPreserveEGLContextOnPause(true);
        mSurfaceView.setEGLContextClientVersion(2);
        mSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        mSurfaceView.setRenderer(this);
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        installRequested = false;

        checkAndInitRtc();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mSession == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                mSession = new Session(/* context= */ this);
            } catch (UnavailableArcoreNotInstalledException
                    | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (Exception e) {
                message = "This device does not support AR";
                exception = e;
            }

            if (message != null) {
                showSnackbarMessage(message, true);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }

            // Create default config and check if supported.
            Config config = new Config(mSession);
            if (!mSession.isSupported(config)) {
                showSnackbarMessage("This device does not support AR", true);
            }
            mSession.configure(config);
        }

        showLoadingMessage();
        // Note that order matters - see the note in onPause(), the reverse applies here.
        mSession.resume();
        mSurfaceView.onResume();
        mDisplayRotationHelper.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Note that the order matters - GLSurfaceView is paused first so that it does not try
        // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
        // still call mSession.update() and get a SessionPausedException.
        mDisplayRotationHelper.onPause();
        mSurfaceView.onPause();
        if (mSession != null) {
            mSession.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        removeRemoteRender();
        mSenderHandler.getLooper().quit();

        mRtcEngine.leaveChannel();

        RtcEngine.destroy();

    }

    private void checkAndInitRtc() {
        if (checkSelfPermissions()) {
            initRtcEngine();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            int deniedCount = 0;

            for (int i = 0; i < results.length; i++) {
                if (results[i] == PackageManager.PERMISSION_DENIED) {
                    deniedCount++;
                }
            }

            if (deniedCount == 0) {
                initRtcEngine();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Standard Android full-screen functionality.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showLoadingMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showSnackbarMessage("Searching for surfaces...", false);
            }
        });
    }

    private void showSnackbarMessage(String message, boolean finishOnDismiss) {
        mMessageSnackbar = Snackbar.make(
                AgoraARCoreActivity.this.findViewById(android.R.id.content),
                message, Snackbar.LENGTH_INDEFINITE);
        mMessageSnackbar.getView().setBackgroundColor(0xbf323232);
        if (finishOnDismiss) {
            mMessageSnackbar.setAction(
                    "Dismiss",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mMessageSnackbar.dismiss();
                        }
                    });
            mMessageSnackbar.addCallback(
                    new BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        @Override
                        public void onDismissed(Snackbar transientBottomBar, int event) {
                            super.onDismissed(transientBottomBar, event);
                            finish();
                        }
                    });
        }
        mMessageSnackbar.show();
    }

    private void initRtcEngine() {
        mHidePlane = true;
        mHidePoint = true;

        Button hidePointButton = findViewById(R.id.show_point_cloud);
        hidePointButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPointCloud((Button) v);
            }
        });

        Button hidePlaneButton = findViewById(R.id.show_plane);
        hidePlaneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPlane((Button) v);
            }
        });

        try {

            mRtcEngine = RtcEngine.create(this, getString(R.string.private_broadcasting_app_id), mRtcEventHandler);
            mRtcEngine.setParameters("{\"rtc.log_filter\": 65535}");
            mRtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING);
            mRtcEngine.enableDualStreamMode(true);
            mRtcEngine.setVideoEncoderConfiguration(new VideoEncoderConfiguration(VideoEncoderConfiguration.VD_640x480,
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30, VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE));
            mRtcEngine.setClientRole(Constants.CLIENT_ROLE_BROADCASTER);

            mRtcEngine.enableVideo();

            mSource = new AgoraVideoSource();
            mRender = new AgoraVideoRender(0, true);
            mRtcEngine.setVideoSource(mSource);
            mRtcEngine.setLocalVideoRenderer(mRender);

            mRtcEngine.joinChannel(null, channelName, "", 0);
        }catch (Exception ex) {
            Toast.makeText(this, "Exception: " + ex, Toast.LENGTH_SHORT).show();
        }

        HandlerThread thread = new HandlerThread("ArSendThread");
        thread.start();
        mSenderHandler = new Handler(thread.getLooper());
    }

    private void removeRemoteRender() {
        if (mRemoteView != null) {
            mRemoteContainer.removeView(mRemoteView);
        }
        mRemoteView = null;
    }

    private void addRemoteRender(int uid) {
        mRemoteView = RtcEngine.CreateRendererView(getBaseContext());
        mRemoteContainer.addView(mRemoteView);
        VideoCanvas remoteVideoCanvas = new VideoCanvas(mRemoteView, VideoCanvas.RENDER_MODE_HIDDEN, uid);
        mRtcEngine.setupRemoteVideo(remoteVideoCanvas);
    }

    private boolean checkSelfPermissions() {
        List<String> needList = new ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needList.add(perm);
            }
        }

        if (!needList.isEmpty()) {
            ActivityCompat.requestPermissions(this, needList.toArray(new String[needList.size()]), PERMISSION_REQUEST_CODE);
            return false;
        }

        return true;
    }

    private void onSingleTap(MotionEvent e) {
        queuedSingleTaps.offer(e);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f,0.1f,0.1f,1.0f);

        // Create the texture and pass it to ARCore session to be filled during update().
        mBackgroundRenderer.createOnGlThread(/*context=*/ this);
        if (mSession != null) {
            mSession.setCameraTextureName(mBackgroundRenderer.getTextureId());
        }

        // Prepare the other rendering objects.
        try {
            mVirtualObject.createOnGlThread(/*context=*/this, "andy.obj", "andy.png");
            mVirtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);

            mVirtualObjectShadow.createOnGlThread(/*context=*/this,
                    "andy_shadow.obj", "andy_shadow.png");
            mVirtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow);
            mVirtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read obj file");
        }
        try {
            mPlaneRenderer.createOnGlThread(/*context=*/this, "trigrid.png");
        } catch (IOException e) {
            Log.e(TAG, "Failed to read plane texture");
        }
        mPointCloud.createOnGlThread(/*context=*/this);

        try {
            mPeerObject.createOnGlThread(this);
        } catch (IOException ex) {
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mDisplayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (mSession == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        mDisplayRotationHelper.updateSessionIfNeeded(mSession);

        try {
            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            Frame frame = mSession.update();
            Camera camera = frame.getCamera();

            // Handle taps. Handling only one tap per frame, as taps are usually low frequency
            // compared to frame rate.
            MotionEvent tap = queuedSingleTaps.poll();
            if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
                for (HitResult hit : frame.hitTest(tap)) {
                    // Check if any plane was hit, and if it was hit inside the plane polygon
                    Trackable trackable = hit.getTrackable();
                    // Creates an anchor if a plane or an oriented point was hit.
                    if ((trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))
                            || (trackable instanceof Point
                            && ((Point) trackable).getOrientationMode()
                            == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
                        // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
                        // Cap the number of objects created. This avoids overloading both the
                        // rendering system and ARCore.
                        if (anchors.size() >= 250) {
                            anchors.get(0).detach();
                            anchors.remove(0);
                        }
                        // Adding an Anchor tells ARCore that it should track this position in
                        // space. This anchor is created on the Plane to place the 3D model
                        // in the correct position relative both to the world and to the plane.
                        anchors.add(hit.createAnchor());
                        break;
                    }
                }
            }

            // Draw background.
            mBackgroundRenderer.draw(frame);

            // If not tracking, don't draw 3d objects.
            if (camera.getTrackingState() == TrackingState.PAUSED) {
                return;
            }

            // Get projection matrix.
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);

            // Compute lighting from average intensity of the image.
            final float lightIntensity = frame.getLightEstimate().getPixelIntensity();

            if (isShowPointCloud()) {
                // Visualize tracked points.
                PointCloud pointCloud = frame.acquirePointCloud();
                mPointCloud.update(pointCloud);
                mPointCloud.draw(viewmtx, projmtx);

                // Application is responsible for releasing the point cloud resources after
                // using it.
                pointCloud.release();
            }

            // Check if we detected at least one plane. If so, hide the loading message.
            if (mMessageSnackbar != null) {
                for (Plane plane : mSession.getAllTrackables(Plane.class)) {
                    if (plane.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING
                            && plane.getTrackingState() == TrackingState.TRACKING) {
                        hideLoadingMessage();
                        break;
                    }
                }
            }

            if (isShowPlane()) {
                // Visualize planes.
                mPlaneRenderer.drawPlanes(
                        mSession.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);
            }

            // Visualize anchors created by touch.
            float scaleFactor = 1.0f;

            for (Anchor anchor : anchors) {
                if (anchor.getTrackingState() != TrackingState.TRACKING) {
                    continue;
                }
                // Get the current pose of an Anchor in world space. The Anchor pose is updated
                // during calls to session.update() as ARCore refines its estimate of the world.
                anchor.getPose().toMatrix(mAnchorMatrix, 0);


                // Update and draw the model and its shadow.
                mVirtualObject.updateModelMatrix(mAnchorMatrix, mScaleFactor);
                mVirtualObjectShadow.updateModelMatrix(mAnchorMatrix, scaleFactor);
                mVirtualObject.draw(viewmtx, projmtx, lightIntensity);
                mVirtualObjectShadow.draw(viewmtx, projmtx, lightIntensity);
            }

            sendARViewMessage();
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private void hideLoadingMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mMessageSnackbar != null) {
                    mMessageSnackbar.dismiss();
                }
                mMessageSnackbar = null;
            }
        });
    }

    private void sendARViewMessage() {
        final Bitmap outBitmap = Bitmap.createBitmap(mSurfaceView.getWidth(), mSurfaceView.getHeight(), Bitmap.Config.ARGB_8888);
        PixelCopy.request(mSurfaceView, outBitmap, new PixelCopy.OnPixelCopyFinishedListener() {
            @Override
            public void onPixelCopyFinished(int copyResult) {
                if (copyResult == PixelCopy.SUCCESS) {
                    sendARView(outBitmap);
                } else {
                    Toast.makeText(AgoraARCoreActivity.this, "Pixel Copy Failed", Toast.LENGTH_SHORT);
                }
            }
        }, mSenderHandler);
    }

    private void sendARView(Bitmap bitmap) {
        if (bitmap == null) return;

        if (mSource.getConsumer() == null) return;

        //Bitmap bitmap = source.copy(Bitmap.Config.ARGB_8888,true);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int size = bitmap.getRowBytes() * bitmap.getHeight();
        ByteBuffer byteBuffer = ByteBuffer.allocate(size);
        bitmap.copyPixelsToBuffer(byteBuffer);
        byte[] data = byteBuffer.array();

        mSource.getConsumer().consumeByteArrayFrame(data, MediaIO.PixelFormat.RGBA.intValue(), width, height, 0, System.currentTimeMillis());
    }

    private void showPointCloud(Button button) {
        button.setText((mHidePoint = !mHidePoint) ? "Show point cloud" :
                "Hide point cloud");
    }

    private void showPlane(Button button) {
        button.setText((mHidePlane = !mHidePlane) ? "Show plane" :
                "Hide plane");
    }

    private boolean isShowPointCloud() {
        return !mHidePoint;
    }

    private boolean isShowPlane() {
        return !mHidePlane;
    }

    public void onCallClicked(View view) {
        if (isCalling) {
            isCalling = false;
            removeRemoteRender();
            mSenderHandler.getLooper().quit();
            mRtcEngine.leaveChannel();
            Toast.makeText(this, "Left Channel", Toast.LENGTH_SHORT).show();
        }else {
            isCalling = true;
            initRtcEngine();
        }
    }

    public void onLocalAudioMuteClicked(View view) {
        isMuted = !isMuted;
        mRtcEngine.muteLocalAudioStream(isMuted);
        mMuteBtn.setImageResource(isMuted ? R.drawable.btn_mute : R.drawable.btn_unmute);
    }
}
