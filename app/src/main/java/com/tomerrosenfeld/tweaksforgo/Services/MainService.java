package com.tomerrosenfeld.tweaksforgo.Services;

import android.app.ActivityManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.tomerrosenfeld.tweaksforgo.Constants;
import com.tomerrosenfeld.tweaksforgo.Globals;
import com.tomerrosenfeld.tweaksforgo.Prefs;
import com.tomerrosenfeld.tweaksforgo.Receivers.ScreenReceiver;

import java.io.IOException;
import java.util.List;

public class MainService extends Service {
    private Prefs prefs;
    private PowerManager.WakeLock wl;
    private boolean isGoOpen = false;
    private WindowManager windowManager;
    private LinearLayout black;
    private WindowManager.LayoutParams windowParams;
    private SensorManager sensorManager;
    private ScreenReceiver screenReceiver;
    private IntentFilter filter;
    private int originalBrightness;
    private int originalLocationMode;
    private int originalBrightnessMode;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(MainService.class.getSimpleName(), "Main service started");
        prefs = new Prefs(this);
        initOriginalStates();
        initAccelerometer();
        initScreenHolder();
        initScreenReceiver();
        checkIfGoIsCurrentApp();
    }

    private void initOriginalStates() {
        if (prefs.getBoolean(Prefs.dim, false) || prefs.getBoolean(Prefs.maximize_brightness, false)) {
            originalBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 100);
            originalBrightnessMode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        }
    }

    private void initScreenHolder() {
        if (prefs.getBoolean(Prefs.keepAwake, true))
            wl = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.FULL_WAKE_LOCK, "Tweaks For GO Tag");
    }

    private void checkIfGoIsCurrentApp() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            final long INTERVAL = 1000;
            final long end = System.currentTimeMillis();
            final long begin = end - INTERVAL;
            UsageStatsManager manager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            final UsageEvents usageEvents = manager.queryEvents(begin, end);
            while (usageEvents.hasNextEvent()) {
                UsageEvents.Event event = new UsageEvents.Event();
                usageEvents.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (event.getPackageName().equals(Constants.GOPackageName)) {
                        if (!isGoOpen)
                            GOLaunched();
                    } else {
                        if (isGoOpen)
                            GOClosed();
                    }
                }
            }
        } else {
            ActivityManager am = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> taskInfo = am.getRunningTasks(1);
            ComponentName componentInfo = taskInfo.get(0).topActivity;
            if (componentInfo.getPackageName().equals(Constants.GOPackageName)) {
                if (!isGoOpen)
                    GOLaunched();
            } else {
                if (isGoOpen)
                    GOClosed();
            }
        }
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                checkIfGoIsCurrentApp();
            }
        }, 1000);
    }

    private void GOLaunched() {
        Log.d(MainService.class.getSimpleName(), "GO launched");
        if (prefs.getBoolean(Prefs.batterySaver, false))
            setBatterySaver(true);
        if (prefs.getBoolean(Prefs.keepAwake, true))
            wl.acquire();
        if (prefs.getBoolean(Prefs.overlay, false))
            registerAccelerometer();
        if (prefs.getBoolean(Prefs.kill_background_processes, false))
            killBackgroundProcesses();
        if (prefs.getBoolean(Prefs.extreme_battery_saver, false)) {
            originalLocationMode = Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0);
            extremeBatterySaver(true);
        }
        if (prefs.getBoolean(Prefs.maximize_brightness, false))
            maximizeBrightness(true);

        setNotification(true);
        isGoOpen = true;
    }

    private void GOClosed() {
        Log.d(MainService.class.getSimpleName(), "GO closed");
        unregisterAccelerator();
        if (prefs.getBoolean(Prefs.batterySaver, false))
            setBatterySaver(false);
        if (wl.isHeld())
            wl.release();
        if (prefs.getBoolean(Prefs.extreme_battery_saver, false) && originalLocationMode != 2)
            extremeBatterySaver(false);
        if (prefs.getBoolean(Prefs.maximize_brightness, false))
            maximizeBrightness(false);

        setNotification(false);
        isGoOpen = false;
    }

    private void initScreenReceiver() {
        filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        screenReceiver = new ScreenReceiver();
    }

    private void unregisterScreenReceiver() {
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
    }

    private void initAccelerometer() {
        if (prefs.getBoolean(Prefs.overlay, false) || prefs.getBoolean(Prefs.dim, false)) {
            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            black = new LinearLayout(getApplicationContext());
            black.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            black.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), android.R.color.black));
            Globals.blackLayout = black;
            windowParams = new WindowManager.LayoutParams(-1, -1, 2003, 65794, -2);
            windowParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        }
    }

    private void registerAccelerometer() {
        if (prefs.getBoolean(Prefs.overlay, false) || prefs.getBoolean(Prefs.dim, false)) {
            if (sensorManager == null)
                initAccelerometer();
            List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
            Sensor accelerometerSensor;
            if (sensorList.size() > 0) {
                accelerometerSensor = sensorList.get(0);
                sensorManager.registerListener(accelerometerListener, accelerometerSensor, 2000);
            } else {
                Toast.makeText(MainService.this, "Device doesn't have a supported accelerometer sensor", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void unregisterAccelerator() {
        try {
            sensorManager.unregisterListener(accelerometerListener);
        } catch (Exception ignored) {
        }
    }

    private void setBatterySaver(boolean status) {
        try {
            if (!isConnected()) {
                Settings.Global.putInt(getContentResolver(), "low_power", status ? 1 : 0);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "settings put global low_power " + (status ? 1 : 0)});
                process.waitFor();
            } catch (InterruptedException | IOException e1) {
                e1.printStackTrace();
            }
        }
    }

    private void killBackgroundProcesses() {
        Log.d(MainService.class.getSimpleName(), "Killing background processes");
        List<ApplicationInfo> packages = getPackageManager().getInstalledApplications(0);
        ActivityManager mActivityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ApplicationInfo packageInfo : packages) {
            if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 1) continue;
            if (packageInfo.packageName.contains("com.tomer")) continue;
            mActivityManager.killBackgroundProcesses(packageInfo.packageName);
        }
    }

    private void extremeBatterySaver(boolean state) {
        try {
            if (state)
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_BATTERY_SAVING);
            else
                Settings.Secure.putInt(getContentResolver(), Settings.Secure.LOCATION_MODE, originalLocationMode);
        } catch (Exception ignored) {
            prefs.set(Prefs.extreme_battery_saver, false);
        }
    }

    private void maximizeBrightness(boolean state) {
        Log.d(MainService.class.getSimpleName(), "Changing screen brightness");
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, state ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : originalBrightnessMode);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, state ? 255 : originalBrightness);
    }

    private void darkenTheScreen(boolean state) {
        if (state && isGoOpen) {
            windowManager.addView(black, windowParams);
            registerReceiver(screenReceiver, filter);
        } else {
            try {
                windowManager.removeView(black);
                unregisterScreenReceiver();
            } catch (Exception ignored) {
                Log.d("Receiver", "View is not attached");
            }
        }
    }

    private void dimScreen(boolean state) {
        if (prefs.getBoolean(Prefs.dim, false)) {
            Log.d("Original brightness is ", String.valueOf(originalBrightness));
            if (!state && prefs.getBoolean(Prefs.maximize_brightness, false)) {
                maximizeBrightness(true);
                return;
            }
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, state ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : originalBrightnessMode);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, state ? 0 : originalBrightness);

        }
    }

    private void setNotification(boolean state) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.tomer.poke.notifier", "com.tomer.poke.notifier.Services.MainService"));
            if (state)
                startService(i);
            else
                stopService(i);
        } catch (Exception ignored) {
            Log.d(MainService.class.getSimpleName(), "Notifications for GO is not installed");
        }
    }

    private boolean isConnected() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        assert intent != null;
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        return plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB;
    }

    private SensorEventListener accelerometerListener = new SensorEventListener() {

        @Override
        public void onAccuracyChanged(Sensor arg0, int arg1) {
        }

        boolean isBlack;

        @Override
        public void onSensorChanged(SensorEvent arg0) {
            float y_value = arg0.values[1];
            if (y_value < -5 && y_value > -15) {
                if (!isBlack) {
                    isBlack = true;
                    darkenTheScreen(true);
                    dimScreen(true);
                }
            } else if (isBlack) {
                isBlack = false;
                darkenTheScreen(false);
                dimScreen(false);
            }
        }
    };

}
