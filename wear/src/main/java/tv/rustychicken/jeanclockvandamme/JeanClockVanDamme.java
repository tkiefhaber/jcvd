/*
 * Copyright (C) 2014 The Android Open Source Project
 *
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

package tv.rustychicken.jeanclockvandamme;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class JeanClockVanDamme extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mFilterPaint;
        Paint mHandPaint;
        private Bitmap backgroundBitmap;
        private Bitmap jeanClaudeBitmap;
        private Bitmap hourHandBitmap;
        private Bitmap minuteHandBitmap;
        private int mWidth = -1;
        private int mHeight = -1;
        private float mScale;
        private float mCenterX;
        private float mCenterY;
        private int mMinutes;
        private float mMinDeg;
        private float mHrDeg;
        private Bitmap mFigure;
        private Bitmap mMinHand;
        private Bitmap mHrHand;
        private Bitmap mFace;
        private float mScaledXOffset;
        private float mScaledXAdditionalOffset;
        private float mScaledYOffset;
        private long mTimeElapsed;
        private int mLoop;
        private float mRadius;


        boolean mAmbient;
        Time mTime;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(JeanClockVanDamme.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = JeanClockVanDamme.this.getResources();

            backgroundBitmap = ((BitmapDrawable) resources.getDrawable(R.drawable.background)).getBitmap();
            jeanClaudeBitmap = ((BitmapDrawable) resources.getDrawable(R.drawable.jean)).getBitmap();
            hourHandBitmap = ((BitmapDrawable) resources.getDrawable(R.drawable.hour)).getBitmap();
            minuteHandBitmap = ((BitmapDrawable) resources.getDrawable(R.drawable.minute)).getBitmap();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mFilterPaint = new Paint();
            mFilterPaint.setFilterBitmap(true);

            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect rect) {
            mTime.setToNow();
            backgroundBitmap = Bitmap.createScaledBitmap(backgroundBitmap, canvas.getWidth(), canvas.getHeight(), false);
            jeanClaudeBitmap = Bitmap.createScaledBitmap(jeanClaudeBitmap, canvas.getWidth(), canvas.getHeight(), false);
            minuteHandBitmap = Bitmap.createScaledBitmap(minuteHandBitmap, canvas.getWidth(), canvas.getHeight(), false);
            hourHandBitmap = Bitmap.createScaledBitmap(hourHandBitmap, canvas.getWidth(), canvas.getHeight(), false);

            //Draw background.
            canvas.drawRect(0, 0, mWidth, mHeight, mBackgroundPaint);
            canvas.drawBitmap(backgroundBitmap, 0, 0, mFilterPaint);

            //Draw figure.
//            canvas.drawBitmap(jeanClaudeBitmap,
//                    mCenterX - jeanClaudeBitmap.getWidth() / 2 +
//                            mScaledXAdditionalOffset,
//                    mCenterY - jeanClaudeBitmap.getHeight() / 2 + mScaledYOffset,
//                    mFilterPaint);

//            if (mAmbient) {
//                // Draw a black box as the peek card background
//                canvas.drawRect(mCardBounds, mAmbientBackgroundPaint);
//            }

            mMinutes = mTime.minute;
            mMinDeg = mMinutes * 6;
            mHrDeg = ((mTime.hour + (mMinutes / 60f)) * 30);

            canvas.save();

            // Draw the minute hand
            canvas.rotate(mMinDeg, mCenterX, mCenterY);
            mMinHand = minuteHandBitmap;
            canvas.drawBitmap(mMinHand, mCenterX - mMinHand.getWidth() / 2f - mScaledXOffset,
                    mCenterY - mMinHand.getHeight() - mScaledYOffset, mFilterPaint);

            // Draw the hour hand
            canvas.rotate(360 - mMinDeg + mHrDeg, mCenterX, mCenterY);
            mHrHand = hourHandBitmap;
            canvas.drawBitmap(mHrHand, mCenterX - mHrHand.getWidth() / 2f - mScaledXOffset,
                    mCenterY - mHrHand.getHeight() - mScaledYOffset,
                    mFilterPaint);

            canvas.restore();

            // Draw face.  (We do this last so it's not obscured by the arms.)
            mFace = jeanClaudeBitmap;
            canvas.drawBitmap(mFace,
                    mCenterX,
                    mCenterY,
                    mFilterPaint);

            // While watch face is active, immediately request next animation frame.
            if (isVisible() && !isInAmbientMode()) {
                invalidate();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            JeanClockVanDamme.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            JeanClockVanDamme.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<JeanClockVanDamme.Engine> mWeakReference;

        public EngineHandler(JeanClockVanDamme.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            JeanClockVanDamme.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
