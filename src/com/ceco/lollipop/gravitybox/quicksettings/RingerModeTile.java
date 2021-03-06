/*
 * Copyright (C) 2015 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ceco.lollipop.gravitybox.quicksettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ceco.lollipop.gravitybox.GravityBoxSettings;
import com.ceco.lollipop.gravitybox.R;
import com.ceco.lollipop.gravitybox.Utils;

import de.robv.android.xposed.XSharedPreferences;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;

public class RingerModeTile extends QsTile {
    public static final String SETTING_VIBRATE_WHEN_RINGING = "vibrate_when_ringing";
    public static final String SETTING_ZEN_MODE = "zen_mode";

    // Define the available ringer modes
    private static final Ringer[] RINGERS = new Ringer[] {
        new Ringer(AudioManager.RINGER_MODE_SILENT, false, R.drawable.ic_qs_ring_off),
        new Ringer(AudioManager.RINGER_MODE_VIBRATE, true, R.drawable.ic_qs_vibrate_on),
        new Ringer(AudioManager.RINGER_MODE_NORMAL, false, R.drawable.ic_qs_ring_on),
        new Ringer(AudioManager.RINGER_MODE_NORMAL, true, R.drawable.ic_qs_ring_vibrate_on)
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                findCurrentState();
                refreshState();
            }
        }
    };

    private boolean mIsReceiving;
    private int mRingerIndex;
    private AudioManager mAudioManager;
    private boolean mHasVibrator;
    private Vibrator mVibrator;
    private SettingsObserver mSettingsObserver;

    public RingerModeTile(Object host, String key, XSharedPreferences prefs,
            QsTileEventDistributor eventDistributor) throws Throwable {
        super(host, key, prefs, eventDistributor);

        mState.label = mGbContext.getString(R.string.qs_tile_ringer_mode);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        mSettingsObserver = new SettingsObserver(new Handler());
    }

    @Override
    public void handleDestroy() {
        mSettingsObserver = null;
        mAudioManager = null;
        mVibrator = null;
        super.handleDestroy();
    }

    @Override
    public void handleClick() {
        toggleState();
        super.handleClick();
    }

    @Override
    public boolean handleLongClick(View view) {
        startSettingsActivity(android.provider.Settings.ACTION_SOUND_SETTINGS);
        return true;
    }

    @Override
    public void handleUpdateState(Object state, Object arg) {
        mState.visible = true;

        // The icon will change depending on index
        mState.icon = mGbContext.getDrawable(RINGERS[mRingerIndex].mDrawable);

        super.handleUpdateState(state, arg);
    }

    @Override
    public void setListening(boolean listening) {
        if (DEBUG) log(getKey() + ": setListening(" + listening + ")");
        if (listening && mEnabled) {
            registerReceiver();
            findCurrentState();
        } else {
            unregisterReceiver();
        }
    }

    @Override
    public boolean supportsHideOnChange() {
        return false;
    }

    @Override
    public void initPreferences() {
        if (Utils.hasVibrator(mContext)) {
            mHasVibrator = true;
            mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        }

        Set<String> smodes = mPrefs.getStringSet(
                GravityBoxSettings.PREF_KEY_RINGER_MODE_TILE_MODE,
                new HashSet<String>(Arrays.asList(new String[] { "0", "1", "2", "3" })));
        List<String> lmodes = new ArrayList<String>(smodes);
        Collections.sort(lmodes);
        int modes[] = new int[lmodes.size()];
        for (int i=0; i<lmodes.size(); i++) {
            modes[i] = Integer.valueOf(lmodes.get(i));
        }
        if (DEBUG) log(getKey() + ": onPreferenceInitialize: modes=" + Arrays.toString(modes));
        updateSettings(modes);

        super.initPreferences();
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        if (DEBUG) log(getKey() + ": received broadcast: " + intent.toString());

        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED)
                && intent.hasExtra(GravityBoxSettings.EXTRA_RMT_MODE)) {
            int[] modes = intent.getIntArrayExtra(GravityBoxSettings.EXTRA_RMT_MODE);
            if (DEBUG) log(getKey() + ": onBroadcastReceived: modes=" + Arrays.toString(modes));
            updateSettings(modes);
        }

        super.onBroadcastReceived(context, intent);
    }

    private void registerReceiver() {
        if (!mIsReceiving) {
            IntentFilter intentFilter = new IntentFilter(
                    AudioManager.RINGER_MODE_CHANGED_ACTION);
            mContext.registerReceiver(mBroadcastReceiver, intentFilter);
            mSettingsObserver.observe();
            mIsReceiving = true;
            if (DEBUG) log(getKey() + ": receiver registered");
        }
    }

    private void unregisterReceiver() {
        if (mIsReceiving) {
            mContext.unregisterReceiver(mBroadcastReceiver);
            mSettingsObserver.unobserve();
            mIsReceiving = false;
            if (DEBUG) log(getKey() + ": receiver unregistered");
        }
    }

    private void updateSettings(int[] modes) {
        // disable all first
        for (Ringer r : RINGERS) {
            r.mEnabled = false;
        }

        // enable only those present in the list taking into account if device has vibrator
        if (modes != null && modes.length > 0) {
            for (int i=0; i<modes.length; i++) {
                int index = modes[i];
                Ringer r = index < RINGERS.length ? RINGERS[index] : null;
                if (r != null && (mHasVibrator || !r.mVibrateWhenRinging)) {
                    r.mEnabled = true;
                }
            }
        }
    }

    private void toggleState() {
        // search for next suitable mode
        // loop will break as soon as new suitable index is found
        // or when we end up with the same index we started from (which means, no suitable index was found)
        final int startIndex = mRingerIndex;
        do {
            if (++mRingerIndex >= RINGERS.length) {
                mRingerIndex = 0;
            }
        } while(!RINGERS[mRingerIndex].mEnabled &&
                    mRingerIndex != startIndex);

        // toggle only if new ringer index found
        if (mRingerIndex != startIndex) {
            if (DEBUG) log(getKey() + ": Switching to ringerIndex: " + mRingerIndex);
            Ringer r = RINGERS[mRingerIndex];
    
            // If we are setting a vibrating state, vibrate to indicate it
            if (r.mVibrateWhenRinging && mHasVibrator) {
                mVibrator.vibrate(150);
            }
    
            // Set the desired state
            ContentResolver resolver = mContext.getContentResolver();
            Settings.System.putInt(resolver, SETTING_VIBRATE_WHEN_RINGING,
                    r.mVibrateWhenRinging ? 1 : 0);
            Settings.Global.putInt(resolver, SETTING_ZEN_MODE,
                    (r.mRingerMode == AudioManager.RINGER_MODE_SILENT) ? 1 : 0);
            mAudioManager.setRingerMode(r.mRingerMode);
        } else if (DEBUG) {
            log(getKey() + ": No suitable ringer mode for toggling found");
        }
    }

    private void findCurrentState() {
        ContentResolver cr = mContext.getContentResolver();
        boolean vibrateWhenRinging = Settings.System.getInt(cr, SETTING_VIBRATE_WHEN_RINGING, 0) == 1;
        boolean zenMode = Settings.Global.getInt(cr, SETTING_ZEN_MODE, 0) != 0;
        int ringerMode = mAudioManager.getRingerMode();

        mRingerIndex = 0;

        if (!zenMode) {
            for (int i = 0; i < RINGERS.length; i++) {
                Ringer r = RINGERS[i];
                if ((ringerMode == r.mRingerMode) && (ringerMode == AudioManager.RINGER_MODE_SILENT)) {
                    mRingerIndex = i;
                    break;
                }
                if ((ringerMode == r.mRingerMode) && (ringerMode == AudioManager.RINGER_MODE_VIBRATE)) {
                    mRingerIndex = i;
                    break;
                }
                if ((ringerMode == r.mRingerMode) && (ringerMode == AudioManager.RINGER_MODE_NORMAL) &&
                        (r.mVibrateWhenRinging == vibrateWhenRinging)) {
                    mRingerIndex = i;
                    break;
                }
            }
        }
        if (DEBUG) log(getKey() + ": Current ringerIndex=" + mRingerIndex + ", ringerMode=" + ringerMode);
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                        SETTING_VIBRATE_WHEN_RINGING), false, this);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                        SETTING_ZEN_MODE), false, this);
        }

        void unobserve() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            ContentResolver resolver = mContext.getContentResolver();
            boolean zenMode = Settings.Global.getInt(resolver, SETTING_ZEN_MODE, 0) == 1;
            int ringerMode = mAudioManager.getRingerMode();

            if (zenMode && (ringerMode != AudioManager.RINGER_MODE_SILENT)) {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            }

            findCurrentState();
            refreshState();
            if (DEBUG) log(getKey() + ": SettingsObserver onChange()");
        }
    }

    private static class Ringer {
        final boolean mVibrateWhenRinging;
        final int mRingerMode;
        final int mDrawable;
        boolean mEnabled;

        Ringer(int ringerMode, boolean vibrateWhenRinging, int drawable) {
            mVibrateWhenRinging = vibrateWhenRinging;
            mRingerMode = ringerMode;
            mDrawable = drawable;
            mEnabled = false;
        }
    }
}
