/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * ****************************************************************************
 * Copyright (c) 2021 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextgis.mobile.util;

import android.content.Context;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplibui.util.HyperLogCrashHandler;

public final class Logger {
    public static void initialize(Context context) {

        //By default, seven days older logs will get delete automatically from the database. You can change the expiry period of logs by defining expiryTimeInSeconds.
        HyperLog.initialize(context);
        HyperLog.setLogLevel(Log.VERBOSE);
        HyperLog.setLogFormat(new CustomLogMessageFormat(context));

        Thread.setDefaultUncaughtExceptionHandler(
                new HyperLogCrashHandler()
        );


    }
}
