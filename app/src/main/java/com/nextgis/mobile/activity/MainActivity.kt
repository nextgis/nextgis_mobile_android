/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2021 NextGIS, info@nextgis.com
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
package com.nextgis.mobile.activity

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SyncResult
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.provider.Settings
import android.text.SpannableString
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import com.google.android.material.snackbar.Snackbar
import com.hypertrack.hyperlog.HyperLog
import com.nextgis.maplib.api.GpsEventListener
import com.nextgis.maplib.api.IGISApplication
import com.nextgis.maplib.api.ILayer
import com.nextgis.maplib.datasource.GeoMultiPoint
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.datasource.ngw.Connection
import com.nextgis.maplib.datasource.ngw.Connection.NGWResourceTypeRasterLayer
import com.nextgis.maplib.datasource.ngw.Connection.NGWResourceTypeVectorLayer
import com.nextgis.maplib.datasource.ngw.INGWResource
import com.nextgis.maplib.datasource.ngw.LayerWithStyles
import com.nextgis.maplib.datasource.ngw.TokenContainer
import com.nextgis.maplib.datasource.ngw.WebMap
import com.nextgis.maplib.map.MapDrawable
import com.nextgis.maplib.map.NGWRasterLayer
import com.nextgis.maplib.map.NGWVectorLayer
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.AccountUtil
import com.nextgis.maplib.util.Constants
import com.nextgis.maplib.util.Constants.NGW_ACCOUNT_GUEST
import com.nextgis.maplib.util.FileUtil
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.maplib.util.MapUtil
import com.nextgis.maplib.util.NGWUtil
import com.nextgis.maplib.util.NetworkUtil
import com.nextgis.maplibui.activity.NGActivity
import com.nextgis.maplibui.api.IChooseLayerResult
import com.nextgis.maplibui.api.IVectorLayerUI
import com.nextgis.maplibui.dialog.SelectNGWResourceDialog.fillConnections
import com.nextgis.maplibui.fragment.BottomToolbar
import com.nextgis.maplibui.fragment.LayerFillProgressDialogFragment
import com.nextgis.maplibui.mapui.NGWRasterLayerUI
import com.nextgis.maplibui.mapui.NGWWebMapLayerUI
import com.nextgis.maplibui.mapui.TrackLayerUI.CODE_TRACK_LIST
import com.nextgis.maplibui.overlay.EditLayerOverlay
import com.nextgis.maplibui.service.HTTPLoader
import com.nextgis.maplibui.service.LayerFillService
import com.nextgis.maplibui.service.TrackerService
import com.nextgis.maplibui.service.TrackerService.BackgroundPermissionCallback
import com.nextgis.maplibui.util.ConstantsUI
import com.nextgis.maplibui.util.ConstantsUI.KEY_BATTERY
import com.nextgis.maplibui.util.ConstantsUI.KEY_TRACK_ACTION
import com.nextgis.maplibui.util.ConstantsUI.VALUE_TRACK_START
import com.nextgis.maplibui.util.ConstantsUI.VALUE_TRACK_STOP
import com.nextgis.maplibui.util.ControlHelper
import com.nextgis.maplibui.util.NGIDUtils
import com.nextgis.maplibui.util.SettingsConstantsUI
import com.nextgis.maplibui.util.UiUtil
import com.nextgis.maplibui.util.UiUtil.showSimpleOKAlert
import com.nextgis.maplibui.util.UiUtil.showSimpleToast
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.R
import com.nextgis.mobile.fragment.LayersFragment
import com.nextgis.mobile.fragment.MapFragment
import com.nextgis.mobile.util.AppSettingsConstants
import com.nextgis.mobile.util.SDCardUtils
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.CompletableFuture.runAsync
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Main activity. Map and drawer with layers list created here
 */
class MainActivity : NGActivity(), GpsEventListener, IChooseLayerResult,
    androidx.loader.app.LoaderManager.LoaderCallbacks<TokenContainer>{


//    lateinit var settingsFrame : FrameLayout


    var mapFragment: MapFragment? = null
        protected set
    public var mLayersFragment: LayersFragment? = null
    private var mMessageReceiver: MessageReceiver? = null
    protected var mToolbar: Toolbar? = null


    protected var mTrackReceiver: TrackStartStopReceiver? = null

    protected var mBackPressed: Long = 0
    protected var mTrackItem: MenuItem? = null

    var originalUrl = ""
    var urlByUrlGuest : String = ""
    var remoteId: String = ""
    protected var mLoader: Loader<TokenContainer>? = null
    var startNGWCreateforLaterCreation = false;
    //protected var mUrlWithProtocol: AtomicReference<String?> = AtomicReference<String?>()
    var isActivityVisible: Boolean = false
    var isLoaderCompelte = true


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // initialize the default settings
        PreferenceManager.setDefaultValues(this, R.xml.preferences_general, false)
        PreferenceManager.setDefaultValues(this, R.xml.preferences_map, false)
        PreferenceManager.setDefaultValues(this, R.xml.preferences_location, false)
        PreferenceManager.setDefaultValues(this, R.xml.preferences_tracks, false)

        if (!mPreferences.getBoolean(AppSettingsConstants.KEY_PREF_INTRO, false)) {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        mMessageReceiver = MessageReceiver()

        mTrackReceiver = TrackStartStopReceiver()

//        settingsFrame = findViewById(R.id.settingsFrame)

        mToolbar = findViewById(R.id.main_toolbar)
        setSupportActionBar(mToolbar)
        if (null != supportActionBar) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }

        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        drawerLayout.setStatusBarBackgroundColor(
            ControlHelper.getColor(
                this,
                android.R.attr.colorPrimaryDark
            )
        )

        val fm = supportFragmentManager
        mapFragment = fm.findFragmentById(R.id.map) as MapFragment?
        mapFragment!!.undoRedoOverlay?.setTopToolbar(mToolbar)
        mapFragment!!.editLayerOverlay?.setTopToolbar(mToolbar)
        mapFragment!!.editLayerOverlay?.setBottomToolbar(bottomToolbar)

        val app = application as MainApplication
        mLayersFragment = fm.findFragmentById(R.id.layers) as LayersFragment?

        if (mLayersFragment != null && null != mLayersFragment!!.view) {
            mLayersFragment?.view?.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    com.nextgis.maplibui.R.color.color_grey_050
                )
            )
            // Set up the drawer.
            mLayersFragment!!.setUp(R.id.layers, drawerLayout, app.map as MapDrawable)
        }

        var progressFragment =
            fm.findFragmentByTag(TAG_FRAGMENT_PROGRESS) as LayerFillProgressDialogFragment?
        if (progressFragment == null) {
            progressFragment = LayerFillProgressDialogFragment()
            fm.beginTransaction().add(progressFragment, TAG_FRAGMENT_PROGRESS).commit()
        }


        if (!hasLocationPermissions()) {
            Handler().postDelayed({ processAllPermisions(PERMISSIONS_REQUEST_ZERO) }, 1500)
        }

        //        if (!hasLocationPermissions()) {
//            List<String> permslist = new ArrayList<>();
//            permslist.add(Manifest.permission.ACCESS_COARSE_LOCATION);
//            permslist.add(Manifest.permission.ACCESS_FINE_LOCATION);
        /*            permslist.add(Manifest.permission.GET_ACCOUNTS);
        * /            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        * /                permslist.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        * /
        * /            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2)
        * /                permslist.add(Manifest.permission.POST_NOTIFICATIONS); */
//
//            new Handler().postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    requestPermissions(R.string.permissions, R.string.location_permissions, PERMISSIONS_REQUEST_LOC, permslist.toArray(new String[permslist.size()])); // list.toArray(new Foo[list.size()])
//                }
//            }, 5000);
//
//        }
        NGIDUtils.get(this) { response ->
            if (response.isOk) {
                var support = getExternalFilesDir(null)
                support = if (support == null) File(filesDir, Constants.SUPPORT)
                else File(support, Constants.SUPPORT)

                try {
                    val jsonString = FileUtil.readFromFile(support)
                    val json = JSONObject(jsonString)
                    if (json.optBoolean(Constants.JSON_SUPPORTED_KEY)) {
                        val id = json.getString(Constants.JSON_USER_ID_KEY)
                        NetworkUtil.setUserNGUID(id)
                    }
                } catch (exception: Exception) {
                }

                try {
                    FileUtil.writeToFile(support, response.responseBody)
                } catch (ignored: IOException) {
                }

                NetworkUtil.setIsPro(AccountUtil.isProUser(baseContext))
            }
            if (mapFragment!!.editLayerOverlay?.mode == EditLayerOverlay.MODE_NONE)
                mToolbar?.setTitle(appName)
            //                boolean isLoggedIn = !TextUtils.isEmpty(mPreferences.getString(NGIDUtils.PREF_ACCESS_TOKEN, ""));
            //                if (!isLoggedIn)
            //                    showSnack();
        }
    }

    private fun showSnack() {
        val snackbar = Snackbar.make(
            findViewById(R.id.mainview),
            getString(R.string.support_available),
            Snackbar.LENGTH_LONG
        )
            .setAction(R.string.more) {
                val pricing =
                    Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.pricing)))
                startActivity(pricing)
            }

        val view = snackbar.view
        val textView = view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.setTextColor(
            ContextCompat.getColor(
                view.context,
                com.nextgis.maplibui.R.color.color_white
            )
        )
        snackbar.show()
    }

    fun processAllPermisions(startlevel: Int) {
        if (startlevel == PERMISSIONS_REQUEST_ZERO) {
            if (!hasLocationPermissions()) {
                val permslist: MutableList<String> = ArrayList()
                permslist.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                permslist.add(Manifest.permission.ACCESS_FINE_LOCATION)
                requestPermissions(
                    this,
                    R.string.permissions,
                    R.string.location_permissions,
                    PERMISSIONS_REQUEST_LOC,
                    PERMISSIONS_REQUEST_LOC,
                    *permslist.toTypedArray<String>()
                ) // list.toArray(new Foo[list.size()])
                return
            } else processAllPermisions(PERMISSIONS_REQUEST_LOC)
            return
        }
        if (startlevel == PERMISSIONS_REQUEST_LOC) {
            if (!hasAccountCreatePermissions()) {
                val permslist: MutableList<String> = ArrayList()
                permslist.add(Manifest.permission.GET_ACCOUNTS)
                requestPermissions(
                    this,
                    R.string.permissions,
                    R.string.account_permissions,
                    PERMISSIONS_REQUEST_ACCOUNT,
                    -1,
                    *permslist.toTypedArray<String>()
                ) // list.toArray(new Foo[list.size()])
            } else processAllPermisions(PERMISSIONS_REQUEST_ACCOUNT)
            return
        }
        if (startlevel == PERMISSIONS_REQUEST_ACCOUNT) {
            if (!hasSDCARDWritePermissions()) {
                val permslist: MutableList<String> = ArrayList()
                permslist.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                requestPermissions(
                    this,
                    R.string.permissions,
                    R.string.memory_permissions,
                    PERMISSIONS_REQUEST_MEMORY,
                    PERMISSIONS_REQUEST_MEMORY,
                    *permslist.toTypedArray<String>()
                ) // list.toArray(new Foo[list.size()])
            } else processAllPermisions(PERMISSIONS_REQUEST_MEMORY)
            return
        }
        if (startlevel == PERMISSIONS_REQUEST_MEMORY) {
            if (!hasNotifyPermissions()) {
                val permslist: MutableList<String> = ArrayList()
                permslist.add(Manifest.permission.POST_NOTIFICATIONS)
                requestPermissions(
                    this, R.string.permissions, R.string.push_permissions, PERMISSIONS_REQUEST_PUSH,
                    -1,
                    *permslist.toTypedArray<String>()
                ) // list.toArray(new Foo[list.size()])
            }
        }
    }

    protected fun hasLocationPermissions(): Boolean {
        val permissions =
            isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permissions
    }

    protected fun hasAccountCreatePermissions(): Boolean {
        return isPermissionGranted(Manifest.permission.GET_ACCOUNTS)
    }

    protected fun hasSDCARDWritePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else true
    }

    protected fun hasNotifyPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        else true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray ) {
        when (requestCode) {
            TrackerService.PERMISSIONS_REQUEST_ZERO_LOCATION_POSPONDED -> if (hasLocationPermissions()) {
                var item: MenuItem? = null
                try {
                    item = mToolbar!!.menu.findItem(R.id.menu_track)
                } catch (ex: Exception) {
                }
                askBackgroundPerm(item)
            }

            PERMISSIONS_REQUEST_LOC -> {
                mapFragment!!.restartGpsListener()
                processAllPermisions(PERMISSIONS_REQUEST_LOC)
            }

            PERMISSIONS_REQUEST_ACCOUNT -> processAllPermisions(PERMISSIONS_REQUEST_ACCOUNT)
            PERMISSIONS_REQUEST_MEMORY -> processAllPermisions(PERMISSIONS_REQUEST_MEMORY)
            PERMISSIONS_REQUEST_PUSH ->                 // turn on sync notify
                if (permissions.size > 0) {
                    var i = 0
                    while (i < permissions.size) {
                        if (permissions[i] == Manifest.permission.POST_NOTIFICATIONS
                            && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                            PreferenceManager.getDefaultSharedPreferences(
                                applicationContext).edit()
                                .putBoolean(AppSettingsConstants.KEY_PREF_SHOW_SYNC, true)
                                .commit()
                        }
                        i++
                    }
                }

            LOCATION_BACKGROUND_REQUEST -> {
                if (mTrackItem != null)
                    controlTrack(mTrackItem)
                if (mTrackItem == null)
                    checkBatteryOptimize()
                mTrackItem = null
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    fun showEditToolbar() {
        //stopRefresh(mToolbar!!.menu.findItem(R.id.menu_refresh))
        mToolbar!!.menu.clear()
        mToolbar!!.inflateMenu(com.nextgis.maplibui.R.menu.edit_geometry)

        var item = mToolbar!!.menu.findItem(com.nextgis.maplibui.R.id.menu_edit_redo)
        val visible = mapFragment!!.mode != MapFragment.MODE_EDIT_BY_WALK
        item.setVisible(visible)
        item = mToolbar!!.menu.findItem(com.nextgis.maplibui.R.id.menu_edit_undo)
        item.setVisible(visible)

        mLayersFragment!!.isDrawerToggleEnabled = false
        mToolbar!!.setNavigationIcon(com.nextgis.maplibui.R.drawable.ic_action_cancel_dark)
    }


    fun showDefaultToolbar() {
        mToolbar?.title = appName
        mToolbar?.setSubtitle(null)
        mToolbar?.menu?.clear()
        mToolbar?.inflateMenu(R.menu.main)
        mLayersFragment!!.isDrawerToggleEnabled = true
        mLayersFragment!!.syncState()
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (null != mLayersFragment && !mLayersFragment!!.isDrawerOpen) {
            // Only show items in the action bar relevant to this screen
            // if the drawer is not showing. Otherwise, let the drawer
            // decide what to show in the action bar.
            menuInflater.inflate(R.menu.main, menu)

            //restoreActionBar();
            return true
        }
        return super.onCreateOptionsMenu(menu)
    }


    val bottomToolbar: BottomToolbar
        get() = findViewById<View>(com.nextgis.maplibui.R.id.bottom_toolbar) as BottomToolbar


    private fun controlTrack(item: MenuItem?) {
        if (item != null) {
            val iconAndTitle = TrackerService.start_stop_tracking_GetIconWithTitle(this)
            setTrackItem(item, iconAndTitle.second, iconAndTitle.first)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val app = application as IGISApplication

        when (item.itemId) {
            android.R.id.home -> if (hasFragments()) return finishFragment()
            else if (mapFragment!!.isEditMode)
                return mapFragment!!.onOptionsItemSelected(item.itemId)
            else {
                mLayersFragment!!.toggle()
                return true
            }

            R.id.menu_settings -> {
                app.showSettings(
                    SettingsConstantsUI.ACTION_PREFS_GENERAL, RELOAD_ACTIVITY_DATA,
                    this
                )
                return true
            }

            R.id.menu_about -> {
                val intentAbout = Intent(this, AboutActivity::class.java)
                startActivity(intentAbout)
                return true
            }

            R.id.menu_locate -> {
                locateCurrentPosition()
                return true
            }

            R.id.menu_track -> {
                askBackgroundPerm(item)
                return true
            }

//            R.id.menu_refresh -> {
//                if (null != mapFragment) {
//                    mapFragment!!.refreshSyncButtonAnimateState()
//                }
//                return true
//            }

            com.nextgis.maplibui.R.id.menu_edit_save -> return mapFragment!!.saveEdits()
            com.nextgis.maplibui.R.id.menu_edit_undo, com.nextgis.maplibui.R.id.menu_edit_redo -> return mapFragment!!.onOptionsItemSelected(
                item.itemId
            )

            R.id.menu_share_log -> {
                shareLog()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    public fun askBackgroundPerm(item: MenuItem?) {
        TrackerService.showBackgroundDialog(this, object : BackgroundPermissionCallback {
            override fun beforeAndroid10(hasBackgroundPermission: Boolean) {
                if (!hasBackgroundPermission) {
                    mTrackItem = item
                    val permissions = arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    requestPermissions(
                        this@MainActivity,
                        R.string.permissions,
                        R.string.location_permissions,
                        LOCATION_BACKGROUND_REQUEST,
                        -1,
                        *permissions
                    )
                } else {
                    controlTrack(item)
                    if (item == null) // byWalk
                        checkBatteryOptimize()
                }
            }

            @RequiresApi(api = Build.VERSION_CODES.Q)
            override fun onAndroid10(hasBackgroundPermission: Boolean) {
                if (!hasBackgroundPermission) {
                    mTrackItem = item
                    requestbackgroundLocationPermissions()
                } else {
                    controlTrack(item)
                    if (item == null) // byWalk
                        checkBatteryOptimize()
                }
            }

            @RequiresApi(api = Build.VERSION_CODES.Q)
            override fun afterAndroid10(hasBackgroundPermission: Boolean) {
                if (!hasBackgroundPermission) {
                    mTrackItem = item
                    requestbackgroundLocationPermissions()
                } else {
                    controlTrack(item)
                    if (item == null) // byWalk
                        checkBatteryOptimize()
                }
            }
        })
    }

    public fun checkBatteryOptimize(){
        val batteryOK = TrackerService.checkIsBatteryPermOK(this)
        if (!batteryOK) {

            Handler().postDelayed(Runnable () {
                val msg = Intent(ConstantsUI.MESSAGE_INTENT_TRACK)
                msg.setPackage(this.getPackageName())
                msg.putExtra(ConstantsUI.KEY_MESSAGE_TRACK, true)
                msg.putExtra(KEY_BATTERY, false)
                msg.putExtra(KEY_TRACK_ACTION, VALUE_TRACK_START)
                msg.setPackage(getPackageName())
                sendBroadcast(msg)
            }, 1000)

        }
    }


    @RequiresApi(api = Build.VERSION_CODES.Q)
    private fun requestbackgroundLocationPermissions() {
        val permissions = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ActivityCompat.requestPermissions(this, permissions, LOCATION_BACKGROUND_REQUEST)
    }


    private fun shareLog() {
        HyperLog.getDeviceLogsInFile(this)
        val dir = File(getExternalFilesDir(null), "LogFiles")
        val size = FileUtil.getDirectorySize(dir)
        if (size == 0L) {
            Toast.makeText(this, com.nextgis.maplib.R.string.error_empty_dataset, Toast.LENGTH_LONG)
                .show()
            return
        }

        val files = zipLogs(dir)
        val type = "text/plain"
        UiUtil.share(files, type, this, true)
    }

    private fun zipLogs(dir: File): File? {
        var temp = MapUtil.prepareTempDir(this, "shared_layers", false)
        val outdated = ArrayList<File>()
        try {
            val fileName = "ng-logs.zip"
            if (temp == null) {
                AlertDialog.Builder(this)
                    .setMessage(com.nextgis.maplibui.R.string.error_file_create)
                    .setPositiveButton(com.nextgis.maplibui.R.string.ok, null)
                    .create()
                    .show()
                //Toast.makeText(this, R.string.error_file_create, Toast.LENGTH_LONG).show();
            }

            temp = File(temp, fileName)
            temp.createNewFile()
            val fos = FileOutputStream(temp, false)
            val zos = ZipOutputStream(BufferedOutputStream(fos))

            val buffer = ByteArray(1024)
            var length: Int

            for (file in dir.listFiles()) {
                if (System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000) outdated.add(
                    file
                )
                try {
                    val fis = FileInputStream(file)
                    zos.putNextEntry(ZipEntry(file.name))

                    while ((fis.read(buffer).also { length = it }) > 0) zos.write(buffer, 0, length)

                    zos.closeEntry()
                    fis.close()
                } catch (ignored: Exception) {
                }
            }

            zos.close()
            fos.close()
        } catch (ignored: IOException) {
            temp = null
        }
        for (file in outdated) {
            file.delete()
        }
        return temp
    }

    private fun setTrackItem(item: MenuItem?, title: Int, icon: Int) {
        if (null != item) {
            item.setTitle(title)
            item.setIcon(icon)
        }
    }


    fun hasFragments(): Boolean {
        return supportFragmentManager.backStackEntryCount > 0
    }


    fun finishFragment(): Boolean {
        if (hasFragments()) {
            supportFragmentManager.popBackStack()
            setActionBarState(true)
            return true
        }

        return false
    }


    override fun isHomeEnabled(): Boolean {
        return false
    }


    @Synchronized
    fun onRefresh(isRefresh: Boolean) {
//        val refreshItem = mToolbar!!.menu.findItem(R.id.menu_refresh)
//        if (null != refreshItem) {
//            if (isRefresh) {
//                if (refreshItem.actionView == null) {
//                    refreshItem.setActionView(R.layout.layout_refresh)
//                    val progress =
//                        refreshItem.actionView!!.findViewById<ProgressBar>(R.id.refreshingProgress)
//                    progress?.indeterminateDrawable?.setColorFilter(
//                        ContextCompat.getColor(
//                            this,
//                            com.nextgis.maplibui.R.color.color_grey_200
//                        ), PorterDuff.Mode.SRC_IN
//                    )
//                }
//            } else stopRefresh(refreshItem)
//        }
    }

    protected fun stopRefresh(refreshItem: MenuItem?) {
        val handler = Handler(Looper.getMainLooper())
        val r = Runnable {
            if (refreshItem != null && refreshItem.actionView != null) {
                refreshItem.actionView!!.clearAnimation()
                refreshItem.setActionView(null)
            }
        }
        handler.post(r)
    }


    fun   addLocalLayer() {
        // ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file
        // browser.
        // https://developer.android.com/guide/topics/providers/document-provider.html#client
        val intent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            Intent(Intent.ACTION_GET_CONTENT)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT)
        }
        intent.setType("*/*")
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.select_file)), FILE_SELECT_CODE)
        } catch (ex: ActivityNotFoundException) {
            //TODO: open select local resource dialog
            // Potentially direct the user to the Market with a Dialog
            Toast.makeText(this, getString(R.string.warning_install_file_manager), Toast.LENGTH_SHORT).show()
        }
    }

    fun addLocalLayerByUrl() {
        // show alert for enter url
        val editText = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_by_url)
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val text = editText.text.toString()
                checkOpenByUrl(text, true)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun checkOpenByUrl(url: String, firstRun : Boolean){
        // check url
        if (TextUtils.isEmpty(url)){
            if (isActivityVisible)
                showSimpleOKAlert(this, getString(R.string.error_empty_url))
            else
                showSimpleToast(this, getString(R.string.error_empty_url))
            return
        }
        if (!NetworkUtil.isValidUri(url)) {
            if (isActivityVisible)
                showSimpleOKAlert(this, getString( com.nextgis.maplibui.R.string.error_invalid_url))
            else
                showSimpleToast(this, getString( com.nextgis.maplibui.R.string.error_invalid_url))
            return
        }


        mapFragment?.changeProgress(true, getString( R.string.check_resource))

        parseNextgisUrl(url).onSuccess {
            val accountManager = AccountManager.get(this);
            val connections = fillConnections(this, accountManager)
            var connectionExist = false

            for (i in 0 until connections.childrenCount){
                val connection = connections.getChild(i)
                if (connection.name.equals(it.host)){
                    connectionExist = true
                    val id = it.resourceId.toLong()
                    runAsync {
                        if ((connection as Connection).connect((Constants.NGW_ACCOUNT_GUEST.equals(connection.getLogin())), id)){
                            val resourceResult = connection.rootResource.reloadRoot()
                            if (resourceResult.respCode == 404){
                                runOnUiThread {
                                    if (isActivityVisible)
                                        showSimpleOKAlert(this, getString( R.string.resource_not_found_404))
                                    else
                                        showSimpleToast(this, getString( R.string.resource_not_found_404))
                                    mapFragment?.changeProgress(false, "")
                                }
                                return@runAsync
                            }

                            if (resourceResult.respCode == -1 || resourceResult.respCode == 403){
                                runOnUiThread {
                                    if (isActivityVisible)
                                        showSimpleOKAlert(this, getString( R.string.resource_no_perm))
                                    else
                                        showSimpleToast(this, getString( R.string.resource_no_perm))
                                    mapFragment?.changeProgress(false, "")
                                }
                                return@runAsync
                            }

                            if (resourceResult.resource == null){
                                runOnUiThread {
                                    if (isActivityVisible)
                                        showSimpleOKAlert(this, getString( R.string.resource_no_perm))
                                    else
                                        showSimpleToast(this, getString( R.string.resource_no_perm))
                                    mapFragment?.changeProgress(false, "")

                                }
                                return@runAsync
                            }

                            if (resourceResult.resource != null && resourceResult.resource.mPermissions!= null
                                && resourceResult.resource.mPermissions.has("data")
                                && resourceResult.resource.mPermissions.getJSONObject("data").has("read")){
                                //
                                val readPerm = resourceResult.resource.mPermissions.getJSONObject("data").get("read")
                                if (readPerm == null || readPerm == false){
                                    runOnUiThread {
                                        if (isActivityVisible)
                                            showSimpleOKAlert(this, getString( R.string.resource_no_perm))
                                        else
                                            showSimpleToast(this, getString( R.string.resource_no_perm))
                                        mapFragment?.changeProgress(false, "")
                                    }
                                    return@runAsync
                                } else {
                                    // start create

                                    val type= (resourceResult.resource as INGWResource).type
                                    val app = application as MainApplication
                                    if (type == NGWResourceTypeVectorLayer){
                                        runOnUiThread {
                                            val layer = resourceResult.resource as LayerWithStyles
                                            // create or connect to fill layer with features
                                            val intent = Intent(this, LayerFillService::class.java)
                                            intent.setAction(LayerFillService.ACTION_ADD_TASK)
                                            intent.putExtra(LayerFillService.KEY_NAME, layer.getName())
                                            intent.putExtra(LayerFillService.KEY_ACCOUNT, connection.getName())
                                            intent.putExtra(LayerFillService.KEY_REMOTE_ID, layer.getRemoteId())
                                            intent.putExtra(LayerFillService.KEY_LAYER_GROUP_ID, (app.map as MapDrawable).id) // mGroupLayer.getId())
                                            intent.putExtra(LayerFillService.KEY_INPUT_TYPE, LayerFillService.NGW_LAYER)

                                            if (layer.getFormCount() > 0) {
                                                val path = NGWUtil.getFormUrl(connection.getURL(), layer.getFormId(0))
                                                intent.putExtra(LayerFillService.KEY_URI, Uri.parse(path))
                                                intent.putExtra(LayerFillService.KEY_INPUT_TYPE, LayerFillService.VECTOR_LAYER_WITH_FORM)
                                            }
                                            LayerFillProgressDialogFragment.startFill(intent)
                                            mapFragment?.changeProgress(false, "")
                                        }
                                    } else if (type == NGWResourceTypeRasterLayer){

                                        runOnUiThread {
                                            //create raster
                                            if (resourceResult.resource is LayerWithStyles) {
                                                val layer = resourceResult.resource as LayerWithStyles
                                                // get first style
                                                //val connection = layer.getConnection()
                                                // create tiles url
                                                val layerURL = layer.getTMSUrl(0)

                                                if (layerURL == null) {
                                                    if (isActivityVisible)
                                                        showSimpleOKAlert(this, getString(com.nextgis.maplib.R.string.error_layer_create))
                                                    else
                                                        showSimpleToast(this, getString(com.nextgis.maplib.R.string.error_layer_create))
                                                    mapFragment?.changeProgress(false, "")
                                                    return@runOnUiThread
                                                }

                                                //create layer
                                                val layerName = layer.getName()

                                                val newLayer: NGWRasterLayer
                                                if (resourceResult.resource is WebMap) {
                                                    val webmap = NGWWebMapLayerUI((app.map as MapDrawable).getContext(), (app.map as MapDrawable).createLayerStorage())
                                                    webmap.setChildren((resourceResult.resource as WebMap).getChildren())
                                                    newLayer = webmap
                                                } else {
                                                    newLayer = NGWRasterLayerUI((app.map as MapDrawable).getContext(), (app.map as MapDrawable).createLayerStorage())
                                                    newLayer.getExtents().set(layer.getExtent())
                                                }

                                                newLayer.setName(layerName)
                                                newLayer.setRemoteId(layer.getRemoteId())
                                                newLayer.setURL(layerURL)
                                                newLayer.setTMSType(GeoConstants.TMSTYPE_OSM)
                                                newLayer.setVisible(true)
                                                newLayer.setAccountName(connection.getName())
                                                newLayer.setMinZoom(GeoConstants.DEFAULT_MIN_ZOOM.toFloat())
                                                newLayer.setMaxZoom(GeoConstants.DEFAULT_MAX_ZOOM.toFloat())

                                                (app.map as MapDrawable).addLayer(newLayer)
                                                (app.map as MapDrawable).save()

                                                mapFragment?.changeProgress(false, "")
                                            }
                                        }

                                    } else {
                                        if (isActivityVisible)
                                            showSimpleOKAlert(this, getString(R.string.unsupported_res_type) )
                                        else
                                            showSimpleToast(this, getString(R.string.unsupported_res_type))
                                    }
                                    mapFragment?.changeProgress(false, "")
                                }
                            } else {
                                runOnUiThread {
                                    if (isActivityVisible)
                                        showSimpleOKAlert(this, getString( R.string.resource_no_perm))
                                    else
                                        showSimpleToast(this, getString( R.string.resource_no_perm))
                                    mapFragment?.changeProgress(false, "")
                                }
                            }

                        } else {
                            runOnUiThread {
                                if (isActivityVisible)
                                    showSimpleOKAlert(this, "NO CONNECT")
                                else
                                    showSimpleToast(this, "NO CONNECT")
                                mapFragment?.changeProgress(false, "")
                            }
                        }
                    }
                    break
                }
            }
            if (!connectionExist && firstRun){
                // try create guest
                originalUrl = url
                urlByUrlGuest = it.host
                remoteId = it.resourceId

                mapFragment?.changeProgress(true, getString(R.string.start_open_layer))
                val id: Int = com.nextgis.maplibui.R.id.non_auth_token_loader  // else R.id.auth_token_loader

                if (null != mLoader && mLoader!!.isStarted()) {
                    mLoader = LoaderManager.getInstance(this)
                        .restartLoader(id, null, this)
                } else {
                    mLoader = LoaderManager.getInstance(this)
                        .initLoader(id, null, this)
                }
                isLoaderCompelte = false;
                startNGWCreateforLaterCreation = true
            }
            //mRootResource = ResourceGroup(0, this)
        }.onFailure {
            val msg = it.message
            if (isActivityVisible)
                showSimpleOKAlert(this, msg)
            else
                showSimpleToast(this, msg)
            mapFragment?.changeProgress(false, "")
        }
    }


    data class NextgisUrlParts(
        val host: String,        // например: "company.nextgis.com" или "test.nextgis.ru"
        val resourceId: String   // yyyy
    )

    fun parseNextgisUrl(url: String): Result<NextgisUrlParts> {
        // Регулярка: https://<поддомен>.nextgis.<любой_домен>/resource/<id>
        val regex = Regex(
            """^https://([a-zA-Z0-9-]+)\.nextgis\.([a-zA-Z0-9-]+)(?:/resource/([a-zA-Z0-9-]+))?$"""
        )

        val match = regex.matchEntire(url.trim()) ?: return Result.failure(IllegalArgumentException(
            "Неверный формат URL"))

        val subdomain = match.groupValues[1]
        val tld = match.groupValues[2]
        val resourceId = match.groupValues[3]

        // Проверка: если resourceId пустой — значит нет /resource/ части
        if (resourceId.isBlank()) {
            return Result.failure(IllegalArgumentException("URL не содержит /resource/yyyy"))
        }

        val fullHost = "$subdomain.nextgis.$tld"

        // Дополнительная проверка: после resourceId ничего не должно быть
        if (url.contains("/resource/$resourceId/") || url.endsWith("/resource/$resourceId/")) {
            return Result.failure(IllegalArgumentException("URL содержит лишний путь после resource/yyyy"))
        }

        return Result.success(NextgisUrlParts(fullHost, resourceId))
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?) {
        //http://stackoverflow.com/questions/10114324/show-dialogfragment-from-onactivityresult
        //http://stackoverflow.com/questions/16265733/failure-delivering-result-onactivityforresult/18345899
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {

            CODE_TRACK_LIST -> mapFragment!!.mMapRef.get()!!.map.reloadTrackListToMap()


            FILE_SELECT_CODE -> if (resultCode == RESULT_OK) {
                // Get the Uri of the selected file
                val uri = data?.data
                if (Constants.DEBUG_MODE) Log.d(
                    Constants.TAG, "File Uri: " + (uri?.toString()
                        ?: "")
                )
                //check the file type from extension
                val fileName = FileUtil.getFileNameByUri(this, uri, "")
                if (fileName.lowercase(Locale.getDefault()).endsWith("ngrc") ||
                    fileName.lowercase(Locale.getDefault()).endsWith("zip")
                ) { //create local tile layer
                    if (null != mapFragment) {
                        mapFragment!!.addLocalTMSLayer(uri)
                    }
                } else if (fileName.lowercase(Locale.getDefault())
                        .endsWith("geojson")
                ) { //create local vector layer
                    if (null != mapFragment) {
                        mapFragment!!.addLocalVectorLayer(uri)
                    }
                } else if (fileName.lowercase(Locale.getDefault())
                        .endsWith("ngfp")
                ) { //create local vector layer with form
                    if (null != mapFragment) {
                        mapFragment!!.addLocalVectorLayerWithForm(uri)
                    }
                } else {
                    Toast.makeText(
                        this, getString(R.string.error_file_unsupported),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            IVectorLayerUI.MODIFY_REQUEST -> mapFragment!!.onActivityResult(
                requestCode,
                resultCode,
                data
            )

            RELOAD_ACTIVITY_DATA -> if (resultCode == RESULT_OK) {
                finish()
                val intent = Intent(this, this.javaClass)
                startActivity(intent)
            }
        }
    }


    protected fun locateCurrentPosition() {
        if (!hasLocationPermissions()) {
            val permslist: MutableList<String> = ArrayList()
            permslist.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permslist.add(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(
                this,
                R.string.permissions,
                R.string.location_permissions,
                PERMISSIONS_REQUEST_LOC_SILENT,
                PERMISSIONS_REQUEST_LOC_SILENT,
                *permslist.toTypedArray<String>()
            ) //
        }

        if (null != mapFragment) {
            mapFragment!!.locateCurrentPosition()
        }
    }


    fun testSync() {
        val application = application as IGISApplication
        val map = application.map
        var ngwVectorLayer: NGWVectorLayer
        for (i in 0..<map.layerCount) {
            val layer = map.getLayer(i)
            if (layer is NGWVectorLayer) {
                ngwVectorLayer = layer
                val ver = NGWUtil.getNgwVersion(this, ngwVectorLayer.accountName)
                ngwVectorLayer.sync(application.authority, ver, SyncResult())
            }
        }
    }


    fun testUpdate() {
        //test sync
        val application = application as IGISApplication
        val map = application.map
        var ngwVectorLayer: NGWVectorLayer? = null
        for (i in 0..<map.layerCount) {
            val layer = map.getLayer(i)
            if (layer is NGWVectorLayer) {
                ngwVectorLayer = layer
            }
        }
        if (null != ngwVectorLayer) {
            val uri = Uri.parse(
                "content://" + AppSettingsConstants.AUTHORITY + "/" +
                        ngwVectorLayer.path.name
            )
            val updateUri = ContentUris.withAppendedId(uri, 29)
            val values = ContentValues()
            values.put("width", 4)
            values.put("azimuth", 8.0)
            values.put("status", "test4")
            values.put("temperatur", -10)
            values.put("name", "xxx")

            val calendar: Calendar = GregorianCalendar(2014, Calendar.JANUARY, 23)
            values.put("datetime", calendar.timeInMillis)
            try {
                val pt = GeoPoint(67.0, 65.0)
                pt.crs = GeoConstants.CRS_WGS84
                pt.project(GeoConstants.CRS_WEB_MERCATOR)
                val mpt = GeoMultiPoint()
                mpt.add(pt)
                values.put(Constants.FIELD_GEOM, mpt.toBlob())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            val result = contentResolver.update(updateUri, values, null, null)
            if (Constants.DEBUG_MODE) {
                if (result == 0) {
                    Log.d(Constants.TAG, "update failed")
                } else {
                    Log.d(Constants.TAG, "" + result)
                }
            }
        }
    }


    fun testAttachUpdate() {
        val application = application as IGISApplication
        /*MapBase map = application.getMap();
        NGWVectorLayer ngwVectorLayer = null;
        for(int i = 0; i < map.getLayerCount(); i++){
            ILayer layer = map.getLayer(i);
            if(layer instanceof NGWVectorLayer)
            {
                ngwVectorLayer = (NGWVectorLayer)layer;
            }
        }
        if(null != ngwVectorLayer) {
            Uri updateUri = Uri.parse("content://" + SettingsConstants.AUTHORITY + "/" +
                                      ngwVectorLayer.getPath().getName() + "/36/attach/1000");
        */
        val updateUri = Uri.parse(
            "content://" + AppSettingsConstants.AUTHORITY +
                    "/layer_20150210140455993/36/attach/2"
        )

        val values = ContentValues()
        values.put(VectorLayer.ATTACH_DISPLAY_NAME, "no_image.jpg")
        values.put(VectorLayer.ATTACH_DESCRIPTION, "simple update description")
        //    values.put(VectorLayer.ATTACH_ID, 999);
        val result = contentResolver.update(updateUri, values, null, null)
        if (Constants.DEBUG_MODE) {
            if (result == 0) {
                Log.d(Constants.TAG, "update failed")
            } else {
                Log.d(Constants.TAG, "" + result)
            }
        }
        //}
    }


    fun testAttachDelete() {
        val application = application as IGISApplication
        /*MapBase map = application.getMap();
        NGWVectorLayer ngwVectorLayer = null;
        for(int i = 0; i < map.getLayerCount(); i++){
            ILayer layer = map.getLayer(i);
            if(layer instanceof NGWVectorLayer)
            {
                ngwVectorLayer = (NGWVectorLayer)layer;
            }
        }
        if(null != ngwVectorLayer) {
            Uri deleteUri = Uri.parse("content://" + SettingsConstants.AUTHORITY + "/" +
                                ngwVectorLayer.getPath().getName() + "/36/attach/1000");
        */
        val deleteUri = Uri.parse(
            "content://" + AppSettingsConstants.AUTHORITY +
                    "/layer_20150210140455993/36/attach/1"
        )
        val result = contentResolver.delete(deleteUri, null, null)
        if (Constants.DEBUG_MODE) {
            if (result == 0) {
                Log.d(Constants.TAG, "delete failed")
            } else {
                Log.d(Constants.TAG, "" + result)
            }
        }
        //}
    }


    fun testAttachInsert() {
        val application = application as IGISApplication
        /*MapBase map = application.getMap();
        NGWVectorLayer ngwVectorLayer = null;
        for(int i = 0; i < map.getLayerCount(); i++){
            ILayer layer = map.getLayer(i);
            if(layer instanceof NGWVectorLayer)
            {
                ngwVectorLayer = (NGWVectorLayer)layer;
            }
        }
        if(null != ngwVectorLayer) {
            Uri uri = Uri.parse("content://" + SettingsConstants.AUTHORITY + "/" + ngwVectorLayer.getPath().getName() + "/36/attach");
        */
        val uri = Uri.parse(
            "content://" + AppSettingsConstants.AUTHORITY + "/layer_20150210140455993/36/attach"
        )
        val values = ContentValues()
        values.put(VectorLayer.ATTACH_DISPLAY_NAME, "test_image.jpg")
        values.put(VectorLayer.ATTACH_MIME_TYPE, "image/jpeg")
        values.put(VectorLayer.ATTACH_DESCRIPTION, "test image description")

        val result = contentResolver.insert(uri, values)
        if (result == null) {
            Log.d(Constants.TAG, "insert failed")
        } else {
            try {
                val outStream = contentResolver.openOutputStream(result)
                val sourceBitmap = BitmapFactory.decodeResource(
                    resources, com.nextgis.maplibui.R.drawable.bk_tile
                )
                sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outStream!!)
                outStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            if (Constants.DEBUG_MODE) Log.d(Constants.TAG, result.toString())
        }
        //}
    }


    fun testInsert() {
        //test sync
        val application = application as IGISApplication
        val map = application.map
        var ngwVectorLayer: NGWVectorLayer? = null
        for (i in 0..<map.layerCount) {
            val layer = map.getLayer(i)
            if (layer is NGWVectorLayer) {
                ngwVectorLayer = layer
            }
        }
        if (null != ngwVectorLayer) {
            val uri = Uri.parse(
                "content://" + AppSettingsConstants.AUTHORITY + "/" +
                        ngwVectorLayer.path.name
            )
            val values = ContentValues()
            //values.put(VectorLayer.FIELD_ID, 26);
            values.put("width", 1)
            values.put("azimuth", 2.0)
            values.put("status", "grot")
            values.put("temperatur", -13)
            values.put("name", "get")

            val calendar: Calendar = GregorianCalendar(2015, Calendar.JANUARY, 23)
            values.put("datetime", calendar.timeInMillis)

            try {
                val pt = GeoPoint(37.0, 55.0)
                pt.crs = GeoConstants.CRS_WGS84
                pt.project(GeoConstants.CRS_WEB_MERCATOR)
                val mpt = GeoMultiPoint()
                mpt.add(pt)
                values.put(Constants.FIELD_GEOM, mpt.toBlob())
            } catch (e: IOException) {
                e.printStackTrace()
            }
            val result = contentResolver.insert(uri, values)
            if (Constants.DEBUG_MODE) {
                if (result == null) {
                    Log.d(Constants.TAG, "insert failed")
                } else {
                    Log.d(Constants.TAG, result.toString())
                }
            }
        }
    }


    fun testDelete() {
        val application = application as IGISApplication
        val map = application.map
        var ngwVectorLayer: NGWVectorLayer? = null
        for (i in 0..<map.layerCount) {
            val layer = map.getLayer(i)
            if (layer is NGWVectorLayer) {
                ngwVectorLayer = layer
            }
        }
        if (null != ngwVectorLayer) {
            val uri = Uri.parse(
                "content://" + AppSettingsConstants.AUTHORITY + "/" +
                        ngwVectorLayer.path.name
            )
            val deleteUri = ContentUris.withAppendedId(uri, 27)
            val result = contentResolver.delete(deleteUri, null, null)
            if (Constants.DEBUG_MODE) {
                if (result == 0) {
                    Log.d(Constants.TAG, "delete failed")
                } else {
                    Log.d(Constants.TAG, "" + result)
                }
            }
        }
    }


    fun addNGWLayer() {
        if (null != mapFragment) {
            mapFragment!!.addNGWLayer()
        }
    }


    fun addRemoteLayer() {
        if (null != mapFragment) {
            mapFragment!!.addRemoteLayer()
        }
    }


    override fun onFinishChooseLayerDialog(
        code: Int,
        layer: ILayer
        , useCreatePoint: Boolean,
        startFillByWalk: Boolean) {
        if (null != mapFragment) {
            mapFragment!!.onFinishChooseLayerDialog(code, layer, useCreatePoint, startFillByWalk)
        }
    }


    private inner class MessageReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent ){

            if (intent.action == ConstantsUI.MESSAGE_INTENT) {
                Toast.makeText(
                    this@MainActivity, intent.extras!!.getString(
                        ConstantsUI.KEY_MESSAGE
                    ), Toast.LENGTH_SHORT
                ).show()
            }

            if (intent.action == Constants.MESSAGE_ALERT_INTENT) {
                val message = intent.extras!!.getString(Constants.MESSAGE_EXTRA)
                val title = intent.extras!!.getString(Constants.MESSAGE_TITLE_EXTRA)

                val s = SpannableString(message) // msg should have url to enable clicking
                Linkify.addLinks(s, Linkify.ALL)

                val builder = android.app.AlertDialog.Builder(this@MainActivity)
                builder.setMessage(s)
                    .setPositiveButton("ok", null)
                    .setTitle(title)
                val alertDialog = builder.create()
                alertDialog.show()

                (alertDialog.findViewById<View>(android.R.id.message) as TextView).movementMethod =
                    LinkMovementMethod.getInstance()
            }
        }
    }

    protected inner class TrackStartStopReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent ){
            if (intent.action == ConstantsUI.MESSAGE_INTENT_TRACK) {
                val tAction =  intent.getStringExtra(KEY_TRACK_ACTION)
                if (tAction.equals(VALUE_TRACK_START)){

                }
                if (tAction.equals(VALUE_TRACK_STOP)){
                    mapFragment!!.reloadTracks()
                }

                val batteryOK =  intent.getBooleanExtra(KEY_BATTERY, true)

                if (!batteryOK) {

                    val name = getPackageName() + "_preferences"
                    val mSharedPreferences = getSharedPreferences(name, MODE_MULTI_PROCESS)

                    val prefBatteryName =  "battery_dont_show_pref"
                    val dontShow = mSharedPreferences.getBoolean(prefBatteryName, false)

                    if (!dontShow) {
                        val container = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(50, 0, 50, 0)
                        }

                        val checkBox = CheckBox(this@MainActivity).apply {
                            text =
                                this@MainActivity.getString(com.nextgis.maplibui.R.string.do_not_ask_again)
                        }
                        container.addView(checkBox)

                        val builder = android.app.AlertDialog.Builder(this@MainActivity)
                        builder.setMessage(com.nextgis.maplibui.R.string.battery_optimization)
                            .setPositiveButton(com.nextgis.maplibui.R.string.battery_optimization_turnoff) { dialog, which ->
                                if (checkBox.isChecked) {
                                    mSharedPreferences.edit().putBoolean(prefBatteryName, true).apply()
                                }

                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.setData(Uri.parse("package:" + getPackageName()))
                                startActivity(intent)
                            }
                            .setTitle(com.nextgis.maplibui.R.string.battery_optimization_title)
                            .setNegativeButton(com.nextgis.maplibui.R.string.cancel) { dialog, which ->
                                if (checkBox.isChecked) {
                                    mSharedPreferences.edit().putBoolean(prefBatteryName, true).apply()
                                }
                            }
                            .setView(container)
                        val alertDialog = builder.create()
                        alertDialog.show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        isActivityVisible = true
        mToolbar!!.background.alpha = 128
        val intentFilter = IntentFilter()
        intentFilter.addAction(ConstantsUI.MESSAGE_INTENT)
        intentFilter.addAction(Constants.MESSAGE_ALERT_INTENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mMessageReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mMessageReceiver, intentFilter)
        }


        val intentFilterTrack = IntentFilter()
        intentFilterTrack.addAction(ConstantsUI.MESSAGE_INTENT_TRACK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mTrackReceiver, intentFilterTrack, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mTrackReceiver, intentFilterTrack)
        }

        mapFragment!!.reloadTracks()




        if (SDCardUtils.isSDCardUsedAndExtracted(this)) {
            val builder = android.app.AlertDialog.Builder(this@MainActivity)
            builder.setMessage(com.nextgis.maplibui.R.string.no_sd_card_attention)
                .setPositiveButton(com.nextgis.maplibui.R.string.ok, null)
                .setTitle(com.nextgis.maplibui.R.string.sd_card)
            val alertDialog = builder.create()
            alertDialog.show()
        }


        if (mLoader != null && mLoader?.isStarted == true && !isLoaderCompelte){
            mapFragment?.changeProgress(true, getString(R.string.start_open_layer))
        }

    }


    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (null != mLayersFragment && !mLayersFragment!!.isDrawerOpen) {
            val hasUnfinishedTracks = TrackerService.hasUnfinishedTracks(this)
            val title =
                if (hasUnfinishedTracks) com.nextgis.maplibui.R.string.track_stop else com.nextgis.maplibui.R.string.track_start
            val icon =
                if (hasUnfinishedTracks) com.nextgis.maplibui.R.drawable.ic_action_maps_directions_walk_rec else com.nextgis.maplibui.R.drawable.ic_action_maps_directions_walk
            setTrackItem(menu.findItem(R.id.menu_track), title, icon)
        }

        if (mapFragment!!.isEditMode) showEditToolbar()

        val log = menu.findItem(R.id.menu_share_log)
        log?.setVisible(mPreferences.getBoolean("save_log", false))

        return super.onPrepareOptionsMenu(menu)
    }


    override fun onPause() {
        try {
            if (mMessageReceiver != null) {
                unregisterReceiver(mMessageReceiver)
                //mMessageReceiver = null
            }

            if (mTrackReceiver != null) {
                unregisterReceiver(mTrackReceiver)
                //mTrackReceiver = null
            }

        } catch (ignored: Exception) {

        }

        isActivityVisible = false
        super.onPause()
    }

    override fun onBackPressed() {
        if (finishFragment()) return

        if (mBackPressed + 2000 > System.currentTimeMillis()) super.onBackPressed()
        else Toast.makeText(this, R.string.press_aback_again, Toast.LENGTH_SHORT).show()

        mBackPressed = System.currentTimeMillis()
    }

    override fun onLocationChanged(location: Location) {
    }

    override fun onBestLocationChanged(location: Location) {
    }


    override fun onGpsStatusChanged(event: Int) {
    }


    fun setActionBarState(state: Boolean) {
        mLayersFragment!!.isDrawerToggleEnabled = state

        if (state) {
            mToolbar!!.background.alpha = 128
            bottomToolbar.background.alpha = 128
        } else {
            mToolbar!!.background.alpha = 255
            bottomToolbar.background.alpha = 255
        }
    }


    fun hideBottomBar() {
        mapFragment!!.hideBottomBar()
    }


    fun restoreBottomBar(mode: Int) {
        if (mapFragment!!.isAdded) mapFragment!!.restoreBottomBar(mode)
    }

    fun setSubtitle(subtitle: String?) {
        mToolbar!!.subtitle = subtitle
    }

    fun requestPermissions(
        activity1: Activity, title: Int, message: Int, requestCode: Int,
        nextLevelOndeny: Int,
        vararg permissions: String?
    ) {
        val activity = activity1
        if (true) {
            val builder = AlertDialog.Builder(activity).setTitle(title)
                .setMessage(message)
                .setPositiveButton(
                    com.nextgis.maplibui.R.string.allow
                ) { dialog: DialogInterface?, which: Int ->
                    ActivityCompat.requestPermissions(
                        activity,
                        permissions,
                        requestCode
                    )
                }
                .setNegativeButton(
                    com.nextgis.maplibui.R.string.deny
                ) { dialog: DialogInterface?, which: Int ->
                    if (nextLevelOndeny > 0) processAllPermisions(nextLevelOndeny)
                }
                .create()
            builder.setCanceledOnTouchOutside(false)
            builder.show()
        }
    }

    companion object {
        protected const val PERMISSIONS_REQUEST_ZERO: Int = 0
        protected const val PERMISSIONS_REQUEST_LOC: Int = 1
        protected const val PERMISSIONS_REQUEST_ACCOUNT: Int = 2
        protected const val PERMISSIONS_REQUEST_MEMORY: Int = 3
        protected const val PERMISSIONS_REQUEST_PUSH: Int = 4

        protected const val PERMISSIONS_REQUEST_LOC_SILENT: Int = 6
        const val LOCATION_BACKGROUND_REQUEST: Int = 5
        protected const val TAG_FRAGMENT_PROGRESS: String = "layer_fill_dialog_fragment"

        protected const val FILE_SELECT_CODE: Int = 555
        protected const val RELOAD_ACTIVITY_DATA: Int = 777
    }

    override fun onDestroy() {
        mMessageReceiver = null
        mTrackReceiver = null
        super.onDestroy()
    }

    override fun refreshLayersFrarment() {
        super.refreshLayersFrarment()
        if (mLayersFragment != null)
            mLayersFragment?.onResume()
    }

    override fun refreshLayersFrarmentNew() {
        super.refreshLayersFrarment()
        if (mLayersFragment != null)
            mLayersFragment?.refreshLayersListItems()
    }

    public fun showToast( text: String ){
        Toast.makeText(this, text, Toast.LENGTH_LONG)
            .show()
    }

    override fun onCreateLoader(
        p0: Int,
        p1: Bundle?): Loader<TokenContainer?> {

        val mUrlWithProtocol = AtomicReference<String?>(urlByUrlGuest)
        return HTTPLoader(
            getApplicationContext(),
            mUrlWithProtocol,
            NGW_ACCOUNT_GUEST,
            "")
    }

    override fun onLoadFinished(
        loader: Loader<TokenContainer?>,
        token: TokenContainer?) {

        isLoaderCompelte = true;
        var accountName: String? = ""
        try {
            var url: String = urlByUrlGuest
            if (!url.startsWith("http")) {
                url = "http://" + url
            }
            val uri = URI(url)
            if (uri.getHost() != null && uri.getHost().length > 0) {
                accountName += uri.getHost()
            }
            if (uri.getPort() != 80 && uri.getPort() > 0) {
                accountName += ":" + uri.getPort()
            }
            if (uri.getPath() != null && uri.getPath().length > 0) {
                accountName += uri.getPath()
            }
        } catch (e: URISyntaxException) {
            accountName = urlByUrlGuest
        }

        //urlByUrlGuest = mUrlWithProtocol.get()!!
        val error404 = token != null && token.responseCode == HttpURLConnection.HTTP_NOT_FOUND
        if (loader.getId() == com.nextgis.maplibui.R.id.auth_token_loader) {
            if (token != null && token.token != null && token.token.length > 0)
                onTokenReceived(accountName,token.token)
            else {
                Toast.makeText(
                    this,
                    if (error404) com.nextgis.maplibui.R.string.error_webgis_not_found else com.nextgis.maplibui.R.string.error_login,
                    Toast.LENGTH_SHORT).show()
            }

        } else {
            if (loader.getId() == com.nextgis.maplibui.R.id.non_auth_token_loader) {
                if (token?.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    Toast.makeText(
                    this,
                    if (error404) com.nextgis.maplibui.R.string.error_webgis_not_found else com.nextgis.maplibui.R.string.error_login,
                    Toast.LENGTH_SHORT).show()
                    mapFragment?.changeProgress(false, "")
                }
                else
                    onTokenReceived(accountName, NGW_ACCOUNT_GUEST)
            }
        }

        runOnUiThread {
            LoaderManager.getInstance(this).destroyLoader(com.nextgis.maplibui.R.id.non_auth_token_loader)
            mLoader = null
            isLoaderCompelte = true
        }
    }

    fun onTokenReceived(
        accountName: String?,
        token: String ) {
        val app = application as IGISApplication
        var login: String? = NGW_ACCOUNT_GUEST
        var password: String? = ""
        if (token.equals( NGW_ACCOUNT_GUEST)) {
            login = NGW_ACCOUNT_GUEST
            password = null
        }

        val mForNewAccount = true
        if (mForNewAccount) {
            val accountAdded = app.addAccount(
                accountName,
                urlByUrlGuest,
                //mUrlText.lowercase(Locale.getDefault()),
                login,
                password,
                token
            )

            startNGWCreateforLaterCreation = false
            if (accountAdded){
                checkOpenByUrl( originalUrl,false)
            }
        }
    }

    override fun onLoaderReset(p0: Loader<TokenContainer?>) {
        // no code needed
        Log.d("zz", "zzz")
    }


}
