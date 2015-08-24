/*
 * Copyright (C) 2015 Emil Suleymanov <suleymanovemil8@gmail.com>
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

package sssemil.com.wifiapmanager;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import java.util.ArrayList;

import sssemil.com.wifiapmanager.Utils.ClientsList;
import sssemil.com.wifiapmanager.Utils.WifiApManager;

public class MainService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {

    private DoScan mDoScan;
    private Context mContext;
    private Looper mLooper;

    private int mLastWifiClientCount = -1;
    private HandlerThread mScanThread;
    private Handler mScanHandler;

    private Notification mTetheredNotification;

    private NotificationManager mNotificationManager;
    private IntentFilter mIntentFilter;
    private SharedPreferences mSharedPreferences;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.net.wifi.WIFI_AP_STATE_CHANGED".equals(action)) {
                handleWifiApStateChanged();
            }
        }
    };

    public MainService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mContext = this;

        mIntentFilter = new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED");
        mIntentFilter.addAction("android.net.conn.TETHER_STATE_CHANGED");
        mIntentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);

        mContext.registerReceiver(mReceiver, mIntentFilter);

        mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        mSharedPreferences = getSharedPreferences("sssemil.com.wifiapmanager_preferences",
                MODE_PRIVATE);

        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);

        mLooper = getMainLooper();
        mScanThread = new HandlerThread("WifiClientScanner");
        if (!mScanThread.isAlive()) {
            mScanThread.start();
            mScanHandler = new WifiClientScanner(mScanThread.getLooper());
            mScanHandler.sendEmptyMessage(0);
        }
    }

    private void handleWifiApStateChanged() {
        WifiApManager wifiApManager = new WifiApManager(mContext);

        if (wifiApManager.isWifiApEnabled()
                && mSharedPreferences.getBoolean("show_notification", false)) {
            showTetheredNotification(R.mipmap.ic_launcher);
            if (!mScanThread.isAlive()) {
                mScanThread = new HandlerThread("WifiClientScanner");
                mScanThread.start();
                mScanHandler = new WifiClientScanner(mScanThread.getLooper());
                mScanHandler.sendEmptyMessage(0);
            }
        } else {
            clearTetheredNotification();
            if (mScanThread.isAlive()) {
                mScanThread.quit();
            }
        }
    }

    private void showTetheredNotification(int icon) {
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager == null) {
            return;
        }

        if (mTetheredNotification != null) {
            if (mTetheredNotification.icon == icon) {
                return;
            }
            mNotificationManager.cancel(null, mTetheredNotification.icon);
        }

        Intent intent = new Intent();
        intent.setClassName("sssemil.com.wifiapmanager",
                "sssemil.com.wifiapmanager.MainActivity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0,
                null);

        CharSequence title = getText(R.string.tethered_notification_title);
        CharSequence message = getText(R.string.tethered_notification_no_device_message);

        if (mTetheredNotification == null) {
            mTetheredNotification = new Notification();
            mTetheredNotification.when = 0;
        }

        mTetheredNotification.icon = icon;
        mTetheredNotification.defaults &= ~Notification.DEFAULT_SOUND;
        mTetheredNotification.flags = Notification.FLAG_ONGOING_EVENT;
        mTetheredNotification.tickerText = title;
        mTetheredNotification.setLatestEventInfo(mContext, title, message, pi);

        mNotificationManager.notify(null, mTetheredNotification.icon,
                mTetheredNotification);
    }

    private void clearTetheredNotification() {
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager != null && mTetheredNotification != null) {
            mNotificationManager.cancel(null, mTetheredNotification.icon);
            mTetheredNotification = null;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        handleWifiApStateChanged();
    }

    private class WifiClientScanner extends Handler {

        public WifiClientScanner(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            mDoScan = new DoScan();
            mDoScan.execute();
            sendEmptyMessageDelayed(0, 2000);
        }
    }

    private class DoScan extends AsyncTask<String, Void, String> {

        private int mCurrentClientCount;

        @Override
        protected String doInBackground(String... params) {
            ArrayList<ClientsList.ClientScanResult> currentClientList
                    = ClientsList.get(true, mContext);
            mCurrentClientCount = currentClientList.size();
            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            new Handler(mLooper).post(new Runnable() {
                @Override
                public void run() {
                    if ((mLastWifiClientCount != mCurrentClientCount
                            || mLastWifiClientCount == -1)
                            && mTetheredNotification != null) {
                        mLastWifiClientCount = mCurrentClientCount;
                        Intent intent = new Intent();
                        intent.setClassName("sssemil.com.wifiapmanager",
                                "sssemil.com.wifiapmanager.MainActivity");
                        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

                        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0,
                                null);

                        Resources r = mContext.getResources();

                        CharSequence title =
                                r.getText(R.string.tethered_notification_title);
                        CharSequence message;
                        if (mCurrentClientCount == 0) {
                            message = r.getString(R.string.tethered_notification_no_device_message);
                        } else if (mCurrentClientCount == 1) {
                            message = r.getString(R.string.tethered_notification_one_device_message,
                                    mCurrentClientCount);
                        } else if (mCurrentClientCount > 1) {
                            message = r.getString(R.string.tethered_notification_multi_device_message,
                                    mCurrentClientCount);
                        } else {
                            message = r.getString(R.string.tethered_notification_no_device_message);
                        }
                        mTetheredNotification.setLatestEventInfo(mContext,
                                title, message, pi);
                        mNotificationManager.notify(null, mTetheredNotification.icon,
                                mTetheredNotification);
                    }
                }
            });
        }
    }
}