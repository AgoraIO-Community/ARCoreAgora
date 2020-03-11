package com.example.arcoreagora;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import io.agora.rtc.Constants;
import io.agora.rtc.IRtcEngineEventHandler;
import io.agora.rtc.RtcEngine;
import io.agora.rtc.video.VideoCanvas;
import io.agora.rtc.video.VideoEncoderConfiguration;

public class AgoraARAudienceActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_ID = 22;

    private RtcEngine mRtcEngine;
    private RelativeLayout mRemoteContainer;
    private SurfaceView mRemoteView;
    private FrameLayout mLocalContainer;
    private SurfaceView mLocalView;
    private ImageView mMuteBtn;

    private String channelName = "";
    private boolean isCalling = true;
    private boolean isMuted = false;
    int dataChannel;
    int mWidth, mHeight;
    int touchCount = 0;
    private List<Float> floatList = new ArrayList<>();


    private IRtcEngineEventHandler mRtcEngineEventHandler = new IRtcEngineEventHandler() {

        @Override
        public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
            //when local user joined the channel
            super.onJoinChannelSuccess(channel, uid, elapsed);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(AgoraARAudienceActivity.this, "", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRemoteVideoStateChanged(final int uid, int state, int reason, int elapsed) {
            //when remote user join the channel
            super.onRemoteVideoStateChanged(uid, state, reason, elapsed);
            if (state == Constants.REMOTE_VIDEO_STATE_STARTING) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setUpRemoteView(uid);
                    }
                });
            }
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            //when remote user leave the channel
            super.onUserOffline(uid, reason);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    removeRemoteView();
                }
            });
        }
    };

    // Ask for Android device permissions at runtime.
    private static final String[] REQUESTED_PERMISSIONS = {
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

        setContentView(R.layout.activity_agora_araudience);

        initUI();

        channelName = getIntent().getStringExtra("ChannelName");
        mWidth= this.getResources().getDisplayMetrics().widthPixels;
        mHeight= this.getResources().getDisplayMetrics().heightPixels;

        if (checkSelfPermission(REQUESTED_PERMISSIONS[0], PERMISSION_REQ_ID) &&
                checkSelfPermission(REQUESTED_PERMISSIONS[1], PERMISSION_REQ_ID)) {
            initEngineAndJoinChannel();
        }

        mRemoteContainer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                        //get the touch position related to the center of the screen
                        touchCount++;
                        float x = event.getRawX() - ((float)mWidth / 2);
                        float y = event.getRawY() - ((float)mHeight / 2);
                        floatList.add(x);
                        floatList.add(y);
                        if (touchCount == 10) {
                            //send the touch positions when collected 10 touch points
                            sendMessage(touchCount, floatList);
                            touchCount = 0;
                            floatList.clear();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        //send touch positions after the touch motion
                        sendMessage(touchCount, floatList);
                        touchCount = 0;
                        floatList.clear();
                        break;
                }
                return true;
            }
        });
    }

    /**
     * send the touch points as a byte array to Agora sdk
     * @param touchCount
     * @param floatList
     */
    private void sendMessage(int touchCount, List<Float> floatList) {
        byte[] motionByteArray = new byte[touchCount * 4 * 2];
        for (int i = 0; i < floatList.size(); i++) {
            byte[] curr = ByteBuffer.allocate(4).putFloat(floatList.get(i)).array();
            for (int j = 0; j < 4; j++) {
                motionByteArray[i * 4 + j] = curr[j];
            }
        }
        mRtcEngine.sendStreamMessage(dataChannel, motionByteArray);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        removeLocalView();
        removeRemoteView();
        leaveChannel();
        RtcEngine.destroy();
    }

    private void initUI() {
        mLocalContainer = findViewById(R.id.local_video_view_container);
        mRemoteContainer = findViewById(R.id.remote_video_view_container);
        mMuteBtn = findViewById(R.id.btn_mute);
    }

    private boolean checkSelfPermission(String permission, int requestCode) {
        if (ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, REQUESTED_PERMISSIONS, requestCode);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSION_REQ_ID: {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                    break;
                }
                initEngineAndJoinChannel();
                break;
            }
        }
    }

    private void initEngineAndJoinChannel() {
        initializeEngine();
        setUpLocalView();
        joinChannel();
    }

    private void removeRemoteView() {
        if (mRemoteView != null) {
            mRemoteContainer.removeView(mRemoteView);
        }
        mRemoteView = null;
    }

    private void setUpRemoteView(int uid) {
        mRemoteView = RtcEngine.CreateRendererView(getBaseContext());
        mRemoteContainer.addView(mRemoteView);
        VideoCanvas remoteVideoCanvas = new VideoCanvas(mRemoteView, VideoCanvas.RENDER_MODE_HIDDEN, uid);
        mRtcEngine.setupRemoteVideo(remoteVideoCanvas);
    }

    private void initializeEngine() {
        try {
            mRtcEngine = RtcEngine.create(getBaseContext(), getString(R.string.private_broadcasting_app_id), mRtcEngineEventHandler);
            mRtcEngine.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING);
            mRtcEngine.enableDualStreamMode(true);
            mRtcEngine.setVideoEncoderConfiguration(new VideoEncoderConfiguration(VideoEncoderConfiguration.VD_640x480,
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30, VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE));
            mRtcEngine.setClientRole(Constants.CLIENT_ROLE_BROADCASTER);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setUpLocalView() {
        mRtcEngine.enableVideo();
        mLocalView = RtcEngine.CreateRendererView(getBaseContext());
        mLocalContainer.addView(mLocalView);
        mLocalView.setZOrderMediaOverlay(true);

        VideoCanvas localVideoCanvas = new VideoCanvas(mLocalView, VideoCanvas.RENDER_MODE_HIDDEN, 0);
        mRtcEngine.setupLocalVideo(localVideoCanvas);
    }

    private void joinChannel() {
        mRtcEngine.joinChannel(null, channelName, "", 0);
        dataChannel = mRtcEngine.createDataStream(true, true);
    }

    public void onCallClicked(View view) {
        if (isCalling) {
            isCalling = false;
            removeRemoteView();
            removeLocalView();
            leaveChannel();
        }else {
            isCalling = true;
            setUpLocalView();
            joinChannel();
        }
    }

    private void leaveChannel() {
        mRtcEngine.leaveChannel();
    }

    private void removeLocalView() {
        if (mLocalView != null) {
            mLocalContainer.removeView(mLocalView);
        }
        mLocalView = null;
    }

    public void onSwitchCameraClicked(View view) {
        mRtcEngine.switchCamera();
    }

    public void onLocalAudioMuteClicked(View view) {
        isMuted = !isMuted;
        mRtcEngine.muteLocalAudioStream(isMuted);
        mMuteBtn.setImageResource(isMuted ? R.drawable.btn_mute : R.drawable.btn_unmute);
    }
}
