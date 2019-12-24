/*
 * Copyright (c) 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.acra.util;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.acra.ACRA;
import org.acra.builder.LastActivityManager;
import org.acra.config.CoreConfiguration;
import org.acra.sender.JobSenderService;
import org.acra.sender.LegacySenderService;

import java.util.List;

import static org.acra.ACRA.LOG_TAG;

/**
 * Takes care of cleaning up a process and killing it.
 *
 * @author F43nd1r
 * @since 4.9.2
 */

public final class ProcessFinisher {
    private final Context context;
    private final CoreConfiguration config;
    private final LastActivityManager lastActivityManager;

    public ProcessFinisher(@NonNull Context context, @NonNull CoreConfiguration config, @NonNull LastActivityManager lastActivityManager) {
        this.context = context;
        this.config = config;
        this.lastActivityManager = lastActivityManager;
    }

    public void endApplication() {
        stopServices();
        doRestart(context);
        killProcessAndExit();
    }

    public void finishLastActivity(@Nullable Thread uncaughtExceptionThread) {
        if (ACRA.DEV_LOGGING)
            ACRA.log.d(LOG_TAG, "Finishing activities prior to killing the Process");
        boolean wait = false;
        for (Activity activity : lastActivityManager.getLastActivities()) {
            final boolean isMainThread = uncaughtExceptionThread == activity.getMainLooper().getThread();
            final Runnable finisher = () -> {
                activity.finish();
                if (ACRA.DEV_LOGGING) ACRA.log.d(LOG_TAG, "Finished " + activity.getClass());
            };
            if (isMainThread) {
                finisher.run();
            } else {
                // A crashed activity won't continue its lifecycle. So we only wait if something else crashed
                wait = true;
                activity.runOnUiThread(finisher);
            }
        }
        if (wait) {
            lastActivityManager.waitForAllActivitiesDestroy(100);
        }
        lastActivityManager.clearLastActivities();
    }

    private void stopServices() {
        if (config.stopServicesOnCrash()) {
            try {
                final ActivityManager activityManager = SystemServices.getActivityManager(context);
                final List<ActivityManager.RunningServiceInfo> runningServices = activityManager.getRunningServices(Integer.MAX_VALUE);
                final int pid = Process.myPid();
                for (ActivityManager.RunningServiceInfo serviceInfo : runningServices) {
                    if (serviceInfo.pid == pid && !LegacySenderService.class.getName().equals(serviceInfo.service.getClassName()) && !JobSenderService.class.getName().equals(serviceInfo.service.getClassName())) {
                        try {
                            final Intent intent = new Intent();
                            intent.setComponent(serviceInfo.service);
                            context.stopService(intent);
                        } catch (SecurityException e) {
                            if (ACRA.DEV_LOGGING)
                                ACRA.log.d(LOG_TAG, "Unable to stop Service " + serviceInfo.service.getClassName() + ". Permission denied");
                        }
                    }
                }
            } catch (SystemServices.ServiceNotReachedException e) {
                ACRA.log.e(LOG_TAG, "Unable to stop services", e);
            }
        }
    }

    private void killProcessAndExit() {
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private void doRestart(Context c) {
        try {
            //check if the context is given
            if (c != null) {
                //fetch the packagemanager so we can get the default launch activity
                // (you can replace this intent with any other activity if you want
                PackageManager pm = c.getPackageManager();
                //check if we got the PackageManager
                if (pm != null) {
                    //create the intent with the default start activity for your application
                    Intent mStartActivity = pm.getLaunchIntentForPackage(
                            c.getPackageName()
                    );
                    if (mStartActivity != null) {
                        mStartActivity.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        //create a pending intent so the application is restarted after System.exit(0) was called.
                        // We use an AlarmManager to call this intent in 100ms
                        int mPendingIntentId = 223344;
                        PendingIntent mPendingIntent = PendingIntent
                                .getActivity(c, mPendingIntentId, mStartActivity,
                                        PendingIntent.FLAG_CANCEL_CURRENT);
                        AlarmManager mgr = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent);
                        //kill the application
                        // System.exit(0);
                    } else {
                        Log.e(LOG_TAG, "Was not able to restart application, mStartActivity null");
                    }
                } else {
                    Log.e(LOG_TAG, "Was not able to restart application, PM null");
                }
            } else {
                Log.e(LOG_TAG, "Was not able to restart application, Context null");
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG, "Was not able to restart application");
        }
    }
}
