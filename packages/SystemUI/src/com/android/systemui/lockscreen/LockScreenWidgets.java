/*
     Copyright (C) 2024 the risingOS Android Project
     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at
          http://www.apache.org/licenses/LICENSE-2.0
     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
*/
package com.android.systemui.lockscreen;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.media.session.MediaController;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.AttributeSet;
import android.os.UserHandle;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.Toast;
import android.view.View;
import androidx.annotation.StringRes;

import com.android.settingslib.Utils;

import com.android.systemui.res.R;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.tuner.TunerService;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LockScreenWidgets extends LinearLayout implements TunerService.Tunable {

    private static final String LOCKSCREEN_WIDGETS =
            "system:lockscreen_widgets";

    private static final String LOCKSCREEN_WIDGETS_EXTRAS =
            "system:lockscreen_widgets_extras";

    private static final int[] MAIN_WIDGETS_VIEW_IDS = {
            R.id.main_kg_item_placeholder1,
            R.id.main_kg_item_placeholder2
    };

    private static final int[] WIDGETS_VIEW_IDS = {
            R.id.kg_item_placeholder1,
            R.id.kg_item_placeholder2,
            R.id.kg_item_placeholder3,
            R.id.kg_item_placeholder4
    };

    private ActivityStarter mActivityStarter;
    private ConfigurationController mConfigurationController;
    private FlashlightController mFlashlightController;
    private StatusBarStateController mStatusBarStateController;

    private Context mContext;
    private ImageView mWidget1, mWidget2, mWidget3, mWidget4, mediaButton, torchButton, weatherButton;
    private ExtendedFloatingActionButton mediaButtonFab, torchButtonFab, weatherButtonFab;
    private int mDarkColor, mDarkColorActive, mLightColor, mLightColorActive;

    private CameraManager mCameraManager;
    private String mCameraId;
    private boolean isFlashOn = false;

    private String mMainLockscreenWidgetsList;
    private String mSecondaryLockscreenWidgetsList;
    private ExtendedFloatingActionButton[] mMainWidgetViews;
    private ImageView[] mSecondaryWidgetViews;
    private List<String> mMainWidgetsList = new ArrayList<>();
    private List<String> mSecondaryWidgetsList = new ArrayList<>();
    private String mWidgetImagePath;
    
    private Handler mHandler = new Handler();

    private AudioManager mAudioManager;
    private Metadata mMetadata = new Metadata();
    private RemoteController mRemoteController;
    private boolean mMediaActive = false;
    private boolean mClientLost = true;
    
    private boolean mDozing;
    
    private boolean mIsInflated = false;
    private GestureDetector mGestureDetector;

    final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onUiModeChanged() {
            updateWidgetViews();
        }

        @Override
        public void onThemeChanged() {
            updateWidgetViews();
        }
    };

    public LockScreenWidgets(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mRemoteController = new RemoteController(mContext, mRCClientUpdateListener);
        mAudioManager.registerRemoteController(mRemoteController);
        mDarkColor = mContext.getResources().getColor(R.color.lockscreen_widget_background_color_dark);
        mLightColor = mContext.getResources().getColor(R.color.lockscreen_widget_background_color_light);
        mDarkColorActive = mContext.getResources().getColor(R.color.lockscreen_widget_active_color_dark);
        mLightColorActive = mContext.getResources().getColor(R.color.lockscreen_widget_active_color_light);
        try {
            mCameraId = mCameraManager.getCameraIdList()[0];
        } catch (Exception e) {}
        Dependency.get(TunerService.class).addTunable(this, LOCKSCREEN_WIDGETS, LOCKSCREEN_WIDGETS_EXTRAS);
    }

    public void initDependencies(
            ActivityStarter activityStarter,
            ConfigurationController configurationController,
            FlashlightController flashlightController,
            StatusBarStateController statusBarStateController
        ) {
        mActivityStarter = activityStarter;
        mConfigurationController = configurationController;
        mFlashlightController = flashlightController;
	    mStatusBarStateController = statusBarStateController;

        if (mConfigurationController != null) {
            mConfigurationController.addCallback(mConfigurationListener);
        }
        if (mFlashlightController != null) {
            mFlashlightController.addCallback(mFlashlightCallback);
        }
	    if (mStatusBarStateController != null) {
	        mStatusBarStateController.addCallback(mStatusBarStateListener);
	        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
        }
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {}

        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) {
                return;
            }
            mDozing = dozing;
            updateContainerVisibility();
        }
    };

    private final FlashlightController.FlashlightListener mFlashlightCallback =
            new FlashlightController.FlashlightListener() {

        @Override
        public void onFlashlightChanged(boolean enabled) {
            isFlashOn = enabled;
            updateFlashLightButtonState();
        }

        @Override
        public void onFlashlightError() {
        }

        @Override
        public void onFlashlightAvailabilityChanged(boolean available) {
            isFlashOn = mFlashlightController.isEnabled() && available;
            updateFlashLightButtonState();
        }
    };

   private RemoteController.OnClientUpdateListener mRCClientUpdateListener =
            new RemoteController.OnClientUpdateListener() {

        @Override
        public void onClientChange(boolean clearing) {
            if (clearing) {
                mMetadata.clear();
                mMediaActive = false;
                mClientLost = true;
            }
            updateMediaPlaybackState();
        }

        @Override
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            mClientLost = false;
            playbackStateUpdate(state);
        }

        @Override
        public void onClientPlaybackStateUpdate(int state) {
            mClientLost = false;
            updateMediaPlaybackState();
        }

        @Override
        public void onClientMetadataUpdate(RemoteController.MetadataEditor data) {
            mMetadata.trackTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_TITLE,
                    mMetadata.trackTitle);
            mClientLost = false;
            updateMediaPlaybackState();
        }

        @Override
        public void onClientTransportControlUpdate(int transportControlFlags) {
        }
    };

    class Metadata {
        private String trackTitle;

        public void clear() {
            trackTitle = null;
        }

        public String getTrackTitle() {
            return trackTitle;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateWidgetViews();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mConfigurationController != null) {
            mConfigurationController.removeCallback(mConfigurationListener);
        }
        if (mFlashlightController != null) {
            mFlashlightController.removeCallback(mFlashlightCallback);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMainWidgetViews = new ExtendedFloatingActionButton[MAIN_WIDGETS_VIEW_IDS.length];
        for (int i = 0; i < mMainWidgetViews.length; i++) {
            mMainWidgetViews[i] = findViewById(MAIN_WIDGETS_VIEW_IDS[i]);
        }
        mSecondaryWidgetViews = new ImageView[WIDGETS_VIEW_IDS.length];
        for (int i = 0; i < mSecondaryWidgetViews.length; i++) {
            mSecondaryWidgetViews[i] = findViewById(WIDGETS_VIEW_IDS[i]);
        }
        mIsInflated = true;
        updateWidgetViews();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case LOCKSCREEN_WIDGETS:
                mMainLockscreenWidgetsList = (String) newValue;
                if (mMainLockscreenWidgetsList != null) {
                    mMainWidgetsList = Arrays.asList(mMainLockscreenWidgetsList.split(","));
                }
                updateWidgetViews();
                break;
            case LOCKSCREEN_WIDGETS_EXTRAS:
                mSecondaryLockscreenWidgetsList = (String) newValue;
                if (mSecondaryLockscreenWidgetsList != null) {
                    mSecondaryWidgetsList = Arrays.asList(mSecondaryLockscreenWidgetsList.split(","));
                }
                updateWidgetViews();
                break;
            default:
                break;
        }
    }

    private void playbackStateUpdate(int state) {
        if (mediaButton == null && mediaButtonFab == null) return;
        boolean active;
        switch (state) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
                active = true;
                break;
            case RemoteControlClient.PLAYSTATE_ERROR:
            case RemoteControlClient.PLAYSTATE_PAUSED:
            default:
                active = false;
                break;
        }
        if (active != mMediaActive) {
            mMediaActive = active;
            updateMediaPlaybackState();
        }
    }

    private void updateContainerVisibility() {
        final boolean isMainWidgetsEmpty = TextUtils.isEmpty(mMainLockscreenWidgetsList) 
        	|| mMainLockscreenWidgetsList == null;
        final boolean isSecondaryWidgetsEmpty = TextUtils.isEmpty(mSecondaryLockscreenWidgetsList) 
        	|| mSecondaryLockscreenWidgetsList == null;
        final boolean isEmpty = isMainWidgetsEmpty && isSecondaryWidgetsEmpty;
        final boolean lockscreenWidgetsEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                "lockscreen_widgets_enabled",
                0,
                UserHandle.USER_CURRENT) != 0;
        final View mainWidgetsContainer = findViewById(R.id.main_widgets_container);
        if (mainWidgetsContainer != null) {
            mainWidgetsContainer.setVisibility(isMainWidgetsEmpty ? View.GONE : View.VISIBLE);
        }
        final View secondaryWidgetsContainer = findViewById(R.id.secondary_widgets_container);
        if (secondaryWidgetsContainer != null) {
            secondaryWidgetsContainer.setVisibility(isSecondaryWidgetsEmpty ? View.GONE : View.VISIBLE);
        }
        final boolean shouldHideContainer = isEmpty || mDozing || !lockscreenWidgetsEnabled;
        setVisibility(shouldHideContainer ? View.GONE : View.VISIBLE);
    }

    public void updateWidgetViews() {
        if (!mIsInflated) return;
        if (mMainWidgetViews != null && mMainWidgetsList != null) {
            for (int i = 0; i < mMainWidgetViews.length; i++) {
                if (mMainWidgetViews[i] != null) {
                    mMainWidgetViews[i].setVisibility(i < mMainWidgetsList.size() ? View.VISIBLE : View.GONE);
                }
            }
            for (int i = 0; i < Math.min(mMainWidgetsList.size(), mMainWidgetViews.length); i++) {
                String widgetType = mMainWidgetsList.get(i);
                if (widgetType != null && i < mMainWidgetViews.length && mMainWidgetViews[i] != null) {
                    setUpWidgetWiews(null, mMainWidgetViews[i], widgetType);
                    updateMainWidgetResources(mMainWidgetViews[i], false);
                }
            }
        }
        if (mSecondaryWidgetViews != null && mSecondaryWidgetsList != null) {
            for (int i = 0; i < mSecondaryWidgetViews.length; i++) {
                if (mSecondaryWidgetViews[i] != null) {
                    mSecondaryWidgetViews[i].setVisibility(i < mSecondaryWidgetsList.size() ? View.VISIBLE : View.GONE);
                }
            }
            for (int i = 0; i < Math.min(mSecondaryWidgetsList.size(), mSecondaryWidgetViews.length); i++) {
                String widgetType = mSecondaryWidgetsList.get(i);
                if (widgetType != null && i < mSecondaryWidgetViews.length && mSecondaryWidgetViews[i] != null) {
                    setUpWidgetWiews(mSecondaryWidgetViews[i], null, widgetType);
                    updateWidgetsResources(mSecondaryWidgetViews[i]);
                }
            }
        }
        updateContainerVisibility();
        updateMediaPlaybackState();
    }

    private void updateMainWidgetResources(ExtendedFloatingActionButton efab, boolean active) {
        if (efab == null) return;
        efab.setElevation(0);
        setButtonActiveState(null, efab, false);
    }

    private void updateWidgetsResources(ImageView iv) {
        if (iv == null) return;
        iv.setBackgroundResource(R.drawable.lockscreen_widget_background_circle);
        setButtonActiveState(iv, null, false);
    }

    private boolean isNightMode() {
        final Configuration config = mContext.getResources().getConfiguration();
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setUpWidgetWiews(ImageView iv, ExtendedFloatingActionButton efab, String type) {
        switch (type) {
            case "torch":
                if (iv != null) {
                    torchButton = iv;
                }
                if (efab != null) {
                    torchButtonFab = efab;
                }
                setUpWidgetResources(iv, efab, v -> toggleFlashlight(), R.drawable.ic_flashlight_off, R.string.quick_settings_flashlight_label);
                break;
            case "timer":
                setUpWidgetResources(iv, efab, v -> launchTimer(), R.drawable.ic_alarm, R.string.clock_timer);
                break;
            case "calculator":
                setUpWidgetResources(iv, efab, v -> launchCalculator(), R.drawable.ic_calculator, R.string.calculator);
                break;
            case "media":
                if (iv != null) {
                    mediaButton = iv;
                }
                if (efab != null) {
                    mediaButtonFab = efab;
                }
                setUpWidgetResources(iv, efab, v -> toggleMediaPlaybackState(), R.drawable.ic_media_play, R.string.controls_media_button_play);
                break;
            default:
                break;
        }
    }

    private void setUpWidgetResources(ImageView iv, ExtendedFloatingActionButton efab, View.OnClickListener cl, int drawableRes, int stringRes){
        if (efab != null) {
            efab.setOnClickListener(cl);
            efab.setIcon(mContext.getDrawable(drawableRes));
            efab.setText(mContext.getResources().getString(stringRes));
            if (mediaButtonFab == efab) {
                attachSwipeGesture(efab);
            }
        }
        if (iv != null) {
            iv.setOnClickListener(cl);
            iv.setImageResource(drawableRes);
        }
    }

    private void attachSwipeGesture(ExtendedFloatingActionButton efab) {
        final GestureDetector gestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                        updateMediaPlaybackState();
                    } else {
                        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
                        updateMediaPlaybackState();
                    }
                    return true;
                }
                return false;
            }
        });
        efab.setOnTouchListener((v, event) -> {
            boolean isClick = gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !isClick) {
                v.performClick();
            }
            return true;
        });
    }

    private void setButtonActiveState(ImageView iv, ExtendedFloatingActionButton efab, boolean active) {
        int bgTint;
        int tintColor;
        if (active) {
            bgTint = isNightMode() ? mDarkColorActive : mLightColorActive;
            tintColor = isNightMode() ? mDarkColor : mLightColor;
        } else {
            bgTint = isNightMode() ? mDarkColor : mLightColor;
            tintColor = isNightMode() ? mLightColor : mDarkColor;
        }
        if (iv != null) {
            iv.setBackgroundTintList(ColorStateList.valueOf(bgTint));
            if (iv != weatherButton) {
            	iv.setImageTintList(ColorStateList.valueOf(tintColor));
            } else {
            	iv.setImageTintList(null);
            }
        }
        if (efab != null) {
            efab.setBackgroundTintList(ColorStateList.valueOf(bgTint));
            if (efab != weatherButtonFab) {
            	efab.setIconTint(ColorStateList.valueOf(tintColor));
            } else {
            	efab.setIconTint(null);
            }
            efab.setTextColor(tintColor);
        }
    }
    
    private boolean isInfoExpired() {
        return !mMediaActive || mClientLost;
    }

    private void toggleMediaPlaybackState() {
        dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        updateMediaPlaybackState();
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(final int keycode) {
        final MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper == null) {
            return;
        }
        KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
        helper.sendMediaButtonEvent(event, true);
        event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
        helper.sendMediaButtonEvent(event, true);
    }

    private void updateMediaPlaybackState() {;
        int stateIcon = mMediaActive
                ? R.drawable.ic_media_pause
                : R.drawable.ic_media_play;
        if (mediaButton != null) {
            mediaButton.setImageResource(stateIcon);
            setButtonActiveState(mediaButton, null, mMediaActive);
        }
        if (mediaButtonFab != null) {
            final boolean canShowTrackTitle = !isInfoExpired() || mMetadata.trackTitle != null;
            mediaButtonFab.setIcon(mContext.getDrawable(mMediaActive ? R.drawable.ic_media_pause : R.drawable.ic_media_play));
            mediaButtonFab.setText(canShowTrackTitle ? mMetadata.trackTitle : mContext.getResources().getString(R.string.controls_media_button_play));
            setButtonActiveState(null, mediaButtonFab, mMediaActive);
        }
        updateMetadata(!isInfoExpired());
    }
    
    private void updateMetadata(boolean playing) {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (playing) {
                    updateMediaPlaybackState();
                }
            }
        }, 1000);
    }

    private void launchAppIfAvailable(Intent launchIntent, @StringRes int appTypeResId) {
        final PackageManager packageManager = mContext.getPackageManager();
        final List<ResolveInfo> apps = packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (!apps.isEmpty() && mActivityStarter != null) {
            mActivityStarter.startActivity(launchIntent, true);
        } else {
            showNoDefaultAppFoundToast(appTypeResId);
        }
    }

    private void launchTimer() {
        final Intent launchIntent = new Intent(AlarmClock.ACTION_SET_TIMER);
        launchAppIfAvailable(launchIntent, R.string.clock_timer);
    }

    private void launchCalculator() {
        final Intent launchIntent = new Intent();
        launchIntent.setAction(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_APP_CALCULATOR);
        launchAppIfAvailable(launchIntent, R.string.calculator);
    }

    private void toggleFlashlight() {
        if (torchButton == null && torchButtonFab == null) return;
        try {
            mCameraManager.setTorchMode(mCameraId, !isFlashOn);
            isFlashOn = !isFlashOn;
            updateFlashLightButtonState();
        } catch (Exception e) {}
    }

    private void updateFlashLightButtonState() {
        post(new Runnable() {
            @Override
            public void run() {
                if (torchButton != null) {
                    torchButton.setImageResource(isFlashOn ? R.drawable.ic_flashlight_on : R.drawable.ic_flashlight_off);
                    setButtonActiveState(torchButton, null, isFlashOn);
                }
                if (torchButtonFab != null) {
                    torchButtonFab.setIcon(mContext.getDrawable(isFlashOn ? R.drawable.ic_flashlight_on : R.drawable.ic_flashlight_off));
                    setButtonActiveState(null, torchButtonFab, isFlashOn);
                }
            }
        });
    }

    private void showNoDefaultAppFoundToast(@StringRes int appTypeResId) {
        final String appType = mContext.getString(appTypeResId);
        final String message = mContext.getString(R.string.no_default_app_found, appType);
        Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
    }
}
