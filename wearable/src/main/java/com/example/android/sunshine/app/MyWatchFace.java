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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
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

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextClockPaint;
        Paint mTextColonPaint;
        Paint mTextClockBoldPaint;
        Paint mTextDatePaint;
        Paint mTextTempPaint;
        Paint mTextTempBoldPaint;
        Paint mDividerPaint;

        // Wear sync
        final String TAG = "Wear Sync";
        String maxTemp = "";
        String minTemp = "";
        Bitmap mWeatherBitmap;
        private static final String TEMP_ICON_KEY = "com.example.android.sunshine.key.icon";
        private static final String TEMP_MIN_KEY = "com.example.android.sunshine.key.temp.min";
        private static final String TEMP_MAX_KEY = "com.example.android.sunshine.key.temp.max";

        private GoogleApiClient mGoogleApiClient;
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        //int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        DataApi.DataListener dataListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                Log.v(TAG, "HIT!");
                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        // DataItem changed
                        DataItem item = event.getDataItem();
                        if (item.getUri().getPath().compareTo("/weather") == 0) {
                            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                            minTemp = Math.round(dataMap.getDouble(TEMP_MIN_KEY)) + "°";
                            maxTemp = Math.round(dataMap.getDouble(TEMP_MAX_KEY)) + "°";
                            Asset profileAsset = dataMap.getAsset(TEMP_ICON_KEY);
                            mWeatherBitmap = loadBitmapFromAsset(profileAsset);
                            //Log.v(TAG, "Testing 1 2 3" + dataMap.getInt(COUNT_KEY));
                        }
                    } else if (event.getType() == DataEvent.TYPE_DELETED) {
                        // DataItem deleted
                    }
                }
            }

            public Bitmap loadBitmapFromAsset(Asset asset) {
                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(5000, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }
                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();
                // Argh don't discount the watch!
                //mGoogleApiClient.disconnect();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    //.setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextClockPaint = new Paint();
            mTextClockPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextColonPaint = new Paint();
            mTextColonPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextClockBoldPaint = new Paint();
            mTextClockBoldPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextDatePaint = new Paint();
            mTextDatePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextTempPaint = new Paint();
            mTextTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextTempBoldPaint = new Paint();
            mTextTempBoldPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDividerPaint = new Paint();
            mDividerPaint.setColor(resources.getColor(R.color.digital_text));

            mTime = new Time();

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            mCalendar.setTimeZone(TimeZone.getDefault());
            mDateFormat = new SimpleDateFormat("ccc, MMM d yyyy");
            mDateFormat.setCalendar(mCalendar);

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(Bundle connectionHint) {
                            Wearable.DataApi.addListener(mGoogleApiClient, dataListener);
                        }

                        @Override
                        public void onConnectionSuspended(int cause) {
                            Log.d(TAG, "onConnectionSuspended: " + cause);
                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(ConnectionResult result) {
                            Log.d(TAG, "onConnectionFailed: " + result);
                        }
                    })
                    // Request access only to the Wearable API
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
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
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            int clockHeight = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 40);
            int dateHeight = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 16);
            int tempHeight = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 24);


            mTextClockPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            mTextColonPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

            mTextColonPaint.setTextAlign(Paint.Align.CENTER);

            mTextClockBoldPaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            mTextTempBoldPaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            mTextClockBoldPaint.setTextAlign(Paint.Align.RIGHT);

            mTextDatePaint.setTextAlign(Paint.Align.CENTER);

            mTextClockPaint.setTextSize(clockHeight);
            mTextColonPaint.setTextSize(clockHeight);
            mTextClockBoldPaint.setTextSize(clockHeight);

            mTextDatePaint.setTextSize(dateHeight);

            mTextTempBoldPaint.setTextSize(tempHeight);
            mTextTempPaint.setTextSize(tempHeight);

            mDividerPaint.setStrokeWidth(0.5f);
            mDividerPaint.setAntiAlias(true);
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
                    mTextClockPaint.setAntiAlias(!inAmbientMode);
                    mTextColonPaint.setAntiAlias(!inAmbientMode);
                    mTextClockBoldPaint.setAntiAlias(!inAmbientMode);
                    mTextDatePaint.setAntiAlias(!inAmbientMode);
                    mTextTempPaint.setAntiAlias(!inAmbientMode);
                    mTextTempBoldPaint.setAntiAlias(!inAmbientMode);

                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // UI center based on total height of UI elements
            int centerX = bounds.centerX();
            int centerY = bounds.centerY() + (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 17.75);

            int dp6 = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 6);
            int dp9 = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 9);
            int dp18 = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 18);
            int dp22_5 = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 22.5);
            int dp36 = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 36);
            int dp40 = (int) com.example.android.sunshine.app.Utils.convertDpToPixel((float) 40);
            int clockY = centerY - dp40;
            int dateY = centerY - dp18;
            int tempY = centerY + dp22_5 + dp9;

            mTime.setToNow();
            String hour = String.format("%02d", mTime.hour);
            String colon = ":";
            String minute = String.format("%02d", mTime.minute);

            //String date = "FRI, JUL 14 2015";
            String date = mDateFormat.format(mDate).toUpperCase();

            int oneTenth = bounds.width() / 10;

            Rect colonBounds = new Rect();
            mTextColonPaint.getTextBounds(colon, 0, 1, colonBounds);
            int colonWidth = colonBounds.width();

            Rect maxTempBounds = new Rect();
            mTextTempBoldPaint.getTextBounds(maxTemp, 0, maxTemp.length(), maxTempBounds);
            int maxTempWidth = maxTempBounds.width();

            Rect minTempBounds = new Rect();
            mTextTempPaint.getTextBounds(minTemp, 0, minTemp.length(), minTempBounds);
            int minTempWidth = minTempBounds.width();


            int tempLineWidth;
            if (mAmbient) {
                tempLineWidth = maxTempWidth + dp6 + minTempWidth;
            } else {
                // The dp6 at the start to visually line up better
                tempLineWidth = dp36 + dp6 + maxTempWidth + dp6 + minTempWidth + dp9;
            }

            canvas.drawText(hour, centerX - colonWidth, clockY, mTextClockBoldPaint);
            canvas.drawText(colon, centerX, clockY, mTextColonPaint);
            canvas.drawText(minute, centerX + colonWidth, clockY, mTextClockPaint);
            canvas.drawText(date, centerX, dateY, mTextDatePaint);

            canvas.drawLine(centerX - oneTenth, centerY, centerX + oneTenth, centerY, mDividerPaint);

            if (mWeatherBitmap != null) {
                if (!mAmbient) {
                    canvas.drawBitmap(mWeatherBitmap,
                            null,
                            new Rect(
                                    centerX - tempLineWidth / 2,
                                    centerY + dp22_5 - dp18,
                                    centerX - tempLineWidth / 2 + dp36,
                                    centerY + dp22_5 + dp18
                            ),
                            null
                    );
                    canvas.drawText(maxTemp, centerX - tempLineWidth / 2 + dp36 + dp9, tempY, mTextTempBoldPaint);
                    canvas.drawText(minTemp, centerX - tempLineWidth / 2 + dp36 + dp9 + maxTempWidth + dp6, tempY, mTextTempPaint);
                } else {
                    canvas.drawText(maxTemp, centerX - tempLineWidth / 2, tempY, mTextTempBoldPaint);
                    canvas.drawText(minTemp, centerX - tempLineWidth / 2 + maxTempWidth + dp6, tempY, mTextTempPaint);
                }
            } else {
                canvas.drawText("No Weather Data", centerX, tempY, mTextDatePaint);
            }
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
}