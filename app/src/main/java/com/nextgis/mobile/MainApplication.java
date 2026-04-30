/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2014-2019, 2021 NextGIS, info@nextgis.com
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

package com.nextgis.mobile;

import android.accounts.Account;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.analytics.ExceptionReporter;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.StandardExceptionParser;
import com.google.android.gms.analytics.Tracker;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapDrawable;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.map.VectorLayer;
import com.nextgis.maplib.util.AccountUtil;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.NGException;
import com.nextgis.maplib.util.NGWUtil;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.SettingsConstants;
import com.nextgis.maplibui.GISApplication;
import com.nextgis.maplibui.mapui.LayerFactoryUI;
import com.nextgis.maplibui.mapui.RemoteTMSLayerUI;
import com.nextgis.maplibui.mapui.TrackLayerUI;
import com.nextgis.maplibui.mapui.VectorLayerUI;
import com.nextgis.maplibui.service.TrackerService;
import com.nextgis.maplibui.util.SettingsConstantsUI;
import com.nextgis.mobile.activity.SettingsActivity;
import com.nextgis.mobile.util.Logger;
import com.nextgis.mobile.util.OfflineSyncIntentService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.nextgis.maplib.util.Constants.DEBUG_MODE;
import static com.nextgis.maplib.util.Constants.MAP_EXT;
import static com.nextgis.maplib.util.Constants.TAG;
import static com.nextgis.maplib.util.GeoConstants.TMSTYPE_OSM;
import static com.nextgis.maplibui.fragment.NGWSettingsFragment.setAccountSyncEnabled;
import static com.nextgis.mobile.util.AppSettingsConstants.AUTHORITY;
import static com.nextgis.mobile.util.AppSettingsConstants.KEY_PREF_APP_VERSION;
import static com.nextgis.mobile.util.AppSettingsConstants.KEY_PREF_GA;


import org.maplibre.android.MapLibre;
import org.maplibre.android.MapStrictMode;
import org.maplibre.android.WellKnownTileServer;

import io.sentry.Sentry;

/**
 * Main application class
 * The initial layers create here. Also upgrade db from previous version is here too.
 */
public class MainApplication extends GISApplication
{
    public static final String LAYER_OSM = "osm";
    public static final String LAYER_A = "vector_a";
    public static final String LAYER_B = "vector_b";
    public static final String LAYER_C = "vector_c";
    public static final String LAYER_TRACKS = "tracks";

    private Tracker mTracker;



    @Override
    public void onCreate() {
//        if (!BuildConfig.DEBUG)
//            Sentry.init(BuildConfig.SENTRY_DSN); // work if disable start
        //Sentry.captureMessage("NGM2 Sentry is init.", Sentry.SentryEventLevel.DEBUG);

        // set userAgent info
        try {
            NetworkUtil.setUserAgentPrefix(this, "NextGIS-Mobile/" + BuildConfig.VERSION_NAME,
                    TrackerService.getUid(this), BuildConfig.VERSION_CODE);
            NetworkUtil.setUserAgentPostfix(System.getProperty("http.agent"));
        } catch (Exception ex){
            Log.e(TAG, ex.getMessage());
        }

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (mSharedPreferences.getBoolean("save_log", false)) {
            Logger.initialize(this);
        }

        GoogleAnalytics.getInstance(this).setAppOptOut(!mSharedPreferences.getBoolean(KEY_PREF_GA, true));
        GoogleAnalytics.getInstance(this).setDryRun(DEBUG_MODE);
        getTracker();
        setExceptionHandler();

        super.onCreate();
        updateFromOldVersion();
        NGWUtil.NGUA = "ng_mobile";
        NGWUtil.UUID = TrackerService.getUid(this);
    }



    private void setExceptionHandler() {
        ExceptionReporter handler = new ExceptionReporter(getTracker(), Thread.getDefaultUncaughtExceptionHandler(), this);
        StandardExceptionParser exceptionParser =
                new StandardExceptionParser(getApplicationContext(), null) {
                    @Override
                    public String getDescription(String threadName, Throwable t) {
                        return "{" + threadName + "} " + Log.getStackTraceString(t);
                    }
                };

        handler.setExceptionParser(exceptionParser);
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    public synchronized Tracker getTracker() {
        if (mTracker == null)
            mTracker = GoogleAnalytics.getInstance(this).newTracker(R.xml.app_tracker);

        return mTracker;
    }

    @Override
    public void sendScreen(String name) {
        mTracker.setScreenName(name);
        mTracker.send(new HitBuilders.ScreenViewBuilder().build());
    }

    @Override
    public String getAccountsType() {
        return BuildConfig.nextgismobile_accounts_auth; // Constants.NGW_ACCOUNT_TYPE;
    }

    @Override
    public boolean isCollectorApplication() {
        return false;
    }

    @Override
    public MapBase getMapBase() {
        return getMap();
    }

    @Override
    public void startCreateNGWLayerSync(String lpath) {
        OfflineSyncIntentService.startActionFoo(this, lpath);
    }


    @Override
    public void sendEvent(String category, String action, String label) {
        HitBuilders.EventBuilder event = new HitBuilders.EventBuilder()
                .setCategory(category)
                .setAction(action)
                .setLabel(label);

        getTracker().send(event.build());
    }

    private void updateFromOldVersion() {
        try {
            int currentVersionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            int savedVersionCode = mSharedPreferences.getInt(KEY_PREF_APP_VERSION, 0);

            switch (savedVersionCode) {
                case 0:
                    int source;
                    if (mSharedPreferences.contains(SettingsConstants.KEY_PREF_LOCATION_SOURCE)) {
                        try {
                            source = mSharedPreferences.getInt(SettingsConstants.KEY_PREF_LOCATION_SOURCE, 3);
                        } catch (ClassCastException e) {
                            source = 3;
                        }
                        mSharedPreferences.edit()
                                .remove(SettingsConstants.KEY_PREF_LOCATION_SOURCE)
                                .remove(SettingsConstants.KEY_PREF_LOCATION_SOURCE + "_str")
                                .putString(SettingsConstants.KEY_PREF_LOCATION_SOURCE, source + "").apply();
                    }
                    if (mSharedPreferences.contains(SettingsConstants.KEY_PREF_TRACKS_SOURCE)) {
                        try {
                            source = mSharedPreferences.getInt(SettingsConstants.KEY_PREF_TRACKS_SOURCE, 1);
                        } catch (ClassCastException e) {
                            source = 3;
                        }
                        mSharedPreferences.edit()
                                .remove(SettingsConstants.KEY_PREF_TRACKS_SOURCE)
                                .remove(SettingsConstants.KEY_PREF_TRACKS_SOURCE + "_str")
                                .putString(SettingsConstants.KEY_PREF_TRACKS_SOURCE, source + "").apply();
                    }
                case 13:
                case 14:
                case 15:
                    mSharedPreferences.edit().remove(SettingsConstantsUI.KEY_PREF_SHOW_STATUS_PANEL)
                            .remove(SettingsConstantsUI.KEY_PREF_COORD_FORMAT + "_int")
                            .remove(SettingsConstantsUI.KEY_PREF_COORD_FORMAT).apply();
                default:
                    break;
            }

            if (savedVersionCode < 44) {
                if (!AccountUtil.isProUser(this)) {
                    if (isAccountManagerValid())
                        for (final Account account : mAccountManager.getAccountsByType(getAccountsType()))
                            setAccountSyncEnabled(account, getAuthority(), false);

                    for (int i = 0; i < mMap.getLayerCount(); i++) {
                        ILayer layer = mMap.getLayer(i);
                        if (layer instanceof NGWVectorLayer) {
                            NGWVectorLayer ngwLayer = (NGWVectorLayer) layer;
                            ngwLayer.setSyncType(Constants.SYNC_NONE);
                            ngwLayer.save();
                        }
                    }
                }
            }

            if(savedVersionCode < currentVersionCode) {
                mSharedPreferences.edit().putInt(KEY_PREF_APP_VERSION, currentVersionCode).apply();
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    public void checkTracksLayerExist()
    {
        List<ILayer> tracks = new ArrayList<>();
        LayerGroup.getLayersByType(mMap, Constants.LAYERTYPE_TRACKS, tracks);
        if (tracks.isEmpty()) {
            String trackLayerName = getString(com.nextgis.maplib.R.string.tracks);
            TrackLayerUI trackLayer =
                    new TrackLayerUI(getApplicationContext(), mMap.createLayerStorage(LAYER_TRACKS));
            trackLayer.setName(trackLayerName);
            trackLayer.setVisible(true);
            mMap.addLayer(trackLayer);
            mMap.save();
        }
    }


    @Override
    public String getAuthority()
    {
        return AUTHORITY;
    }

    @Override
    public void showSettings(String settings, int code, final Activity activity)
    {
        if(TextUtils.isEmpty(settings)) {
            settings = SettingsConstantsUI.ACTION_PREFS_GENERAL;
        }

        switch (settings) {
            case SettingsConstantsUI.ACTION_PREFS_GENERAL:
            case SettingsConstantsUI.ACTION_PREFS_LOCATION:
            case SettingsConstantsUI.ACTION_PREFS_TRACKING:
                break;
            default:
                return;
        }

        Intent intent = new Intent(this, SettingsActivity.class);
        intent.setAction(settings);
        //intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (activity != null && code != -1)
            activity.startActivityForResult(intent, code);
        else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    @Override
    protected void onFirstRun()
    {
        initBaseLayers();
    }


    public void initBaseLayers() {
        if (mMap.getLayerByPathName(LAYER_OSM) == null) {
            //add OpenStreetMap layer
            String layerName = getString(com.nextgis.maplibui.R.string.osm);
            String layerURL = SettingsConstantsUI.OSM_URL;
            final RemoteTMSLayerUI layer = new RemoteTMSLayerUI(getApplicationContext(), mMap.createLayerStorage(LAYER_OSM));
            layer.setName(layerName);
            layer.setURL(layerURL);
            layer.setTMSType(TMSTYPE_OSM);
            layer.setVisible(true);
            layer.setMinZoom(GeoConstants.DEFAULT_MIN_ZOOM);
            layer.setMaxZoom(19);

            mMap.addLayer(layer);
            mMap.moveLayer(0, layer);

            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        layer.fillFromZip(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.mapnik), null);
                    } catch (IOException | NGException | RuntimeException e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        // create empty layers for first experimental editing
        List<Field> fields = new ArrayList<>(2);
        fields.add(new Field(GeoConstants.FTInteger, "FID", "FID"));
        fields.add(new Field(GeoConstants.FTString, "TEXT", "TEXT"));

        if (mMap.getLayerByPathName(LAYER_A) == null)
            mMap.addLayer(createEmptyVectorLayer(getString(R.string.points_for_edit), LAYER_A, GeoConstants.GTPoint, fields));
        if (mMap.getLayerByPathName(LAYER_B) == null)
            mMap.addLayer(createEmptyVectorLayer(getString(R.string.lines_for_edit), LAYER_B, GeoConstants.GTLineString, fields));
        if (mMap.getLayerByPathName(LAYER_C) == null)
            mMap.addLayer(createEmptyVectorLayer(getString(R.string.polygons_for_edit), LAYER_C, GeoConstants.GTPolygon, fields));

        mMap.save();
    }


    public VectorLayer createEmptyVectorLayer(
            String layerName,
            String layerPath,
            int layerType,
            List<Field> fields)
    {
        VectorLayerUI vectorLayer = new VectorLayerUI(this, mMap.createLayerStorage(layerPath));
        vectorLayer.setName(layerName);
        vectorLayer.setVisible(true);
        vectorLayer.setMinZoom(GeoConstants.DEFAULT_MIN_ZOOM);
        vectorLayer.setMaxZoom(GeoConstants.DEFAULT_MAX_ZOOM);

        vectorLayer.create(layerType, fields);
        return vectorLayer;
    }
}
