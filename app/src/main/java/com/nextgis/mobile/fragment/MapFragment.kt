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
package com.nextgis.mobile.fragment

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Vibrator
import android.preference.PreferenceManager
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.getbase.floatingactionbutton.FloatingActionButton
import com.getbase.floatingactionbutton.FloatingActionsMenu
import com.google.android.material.snackbar.Snackbar
import com.nextgis.maplib.api.GpsEventListener
import com.nextgis.maplib.api.IGISApplication
import com.nextgis.maplib.api.ILayer
import com.nextgis.maplib.api.ILayerView
import com.nextgis.maplib.datasource.Feature
import com.nextgis.maplib.datasource.GeoEnvelope
import com.nextgis.maplib.datasource.GeoGeometry
import com.nextgis.maplib.datasource.GeoGeometryFactory
import com.nextgis.maplib.datasource.GeoLineString
import com.nextgis.maplib.datasource.GeoLinearRing
import com.nextgis.maplib.datasource.GeoMultiLineString
import com.nextgis.maplib.datasource.GeoMultiPoint
import com.nextgis.maplib.datasource.GeoMultiPolygon
import com.nextgis.maplib.datasource.GeoPoint
import com.nextgis.maplib.datasource.GeoPolygon
import com.nextgis.maplib.display.SimpleFeatureRenderer
import com.nextgis.maplib.location.GpsEventSource
import com.nextgis.maplib.map.Layer
import com.nextgis.maplib.map.LayerGroup
import com.nextgis.maplib.map.MLP.MLGeometryEditClass
import com.nextgis.maplib.map.MPLFeaturesUtils
import com.nextgis.maplib.map.MPLFeaturesUtils.id_name
import com.nextgis.maplib.map.MapDrawable
import com.nextgis.maplib.map.MaplibreMapInteraction
import com.nextgis.maplib.map.VectorLayer
import com.nextgis.maplib.util.Constants
import com.nextgis.maplib.util.Constants.MESSAGE_INTENT_RELOAD
import com.nextgis.maplib.util.Constants.MESSAGE_INTENT_STYLING
import com.nextgis.maplib.util.FileUtil
import com.nextgis.maplib.util.GeoConstants
import com.nextgis.maplib.util.LocationUtil
import com.nextgis.maplib.util.MapUtil
import com.nextgis.maplibui.GISApplication
import com.nextgis.maplibui.api.EditEventListener
import com.nextgis.maplibui.api.ILayerUI
import com.nextgis.maplibui.api.IVectorLayerUI
import com.nextgis.maplibui.api.MapViewEventListener
import com.nextgis.maplibui.dialog.ChooseLayerDialog
import com.nextgis.maplibui.fragment.CompassFragment
import com.nextgis.maplibui.mapui.MapViewOverlays
import com.nextgis.maplibui.overlay.CurrentLocationOverlay
import com.nextgis.maplibui.overlay.CurrentTrackOverlay
import com.nextgis.maplibui.overlay.EditLayerOverlay
import com.nextgis.maplibui.overlay.RulerOverlay
import com.nextgis.maplibui.overlay.RulerOverlay.OnRulerChanged
import com.nextgis.maplibui.overlay.UndoRedoOverlay
import com.nextgis.maplibui.service.TrackerService
import com.nextgis.maplibui.service.WalkEditService
import com.nextgis.maplibui.util.ConstantsUI
import com.nextgis.maplibui.util.ControlHelper
import com.nextgis.maplibui.util.NotificationHelper
import com.nextgis.maplibui.util.SettingsConstantsUI
import com.nextgis.mobile.MainApplication
import com.nextgis.mobile.R
import com.nextgis.mobile.activity.MainActivity
import com.nextgis.mobile.util.AppConstants
import com.nextgis.mobile.util.AppSettingsConstants
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.module.http.HttpRequestImpl
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.MultiPoint
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Main map fragment
 */
public class MapFragment

    : Fragment(), MapViewEventListener, GpsEventListener, EditEventListener,
    View.OnClickListener, OnRulerChanged,
    OnMapReadyCallback ,
    MaplibreMapInteraction,
    MapLibreMap.OnCameraIdleListener
{
    protected var mTolerancePX: Float = 0f

    protected var mPreferences: SharedPreferences? = null
    protected var mApp: MainApplication? = null
    protected var mActivity: MainActivity? = null

    lateinit var mMapRef: WeakReference<MapViewOverlays>


    protected var mivZoomIn: FloatingActionButton? = null
    protected var mivZoomOut: FloatingActionButton? = null
    protected var mRuler: FloatingActionButton? = null
    protected var mAddNewGeometry: FloatingActionButton? = null
    protected var mAddPointButton: FloatingActionButton? = null

    protected var mStatusSource: TextView? = null
    protected var mStatusAccuracy: TextView? = null
    protected var mStatusSpeed: TextView? = null
    protected var mStatusAltitude: TextView? = null
    protected var mStatusLatitude: TextView? = null
    protected var mStatusLongitude: TextView? = null
    protected var mZoom: TextView? = null
    protected var mStatusPanel: FrameLayout? = null
    protected var mScaleRulerLayout: LinearLayout? = null
    protected var mScaleRulerText: TextView? = null

    //, mZoomLevel;
    protected var mScaleRuler: ImageView? = null

    var stylingProgrerss: View? = null;
    var textStylingProgrerss: TextView? = null;

    private var mMessageStyling: MessageStyling? = null
    private var mMessageReload: MessageReloadLayer? = null


    protected var mMapRelativeLayout: RelativeLayout? = null
    protected var mGpsEventSource: GpsEventSource? = null
    protected var mMainButton: FloatingActionsMenu? = null
    var mode: Int = 0
        protected set
    protected var mCurrentLocationOverlay: CurrentLocationOverlay? = null
    protected var mCurrentTrackOverlay: CurrentTrackOverlay? = null
    var editLayerOverlay: EditLayerOverlay? = null
        protected set
    var undoRedoOverlay: UndoRedoOverlay? = null
        protected set
    protected var mRulerOverlay: RulerOverlay? = null
    protected var mCurrentCenter: GeoPoint? = null
    protected var mSelectedLayer: VectorLayer? = null

    protected var mCoordinatesFormat: Int = 0
    protected var mCoordinatesFraction: Int = 0

    protected var mChooseLayerDialogRef: WeakReference<ChooseLayerDialog> = WeakReference(null)

    protected var mGPSDialog: AlertDialog? = null
    protected var mVibrator: Vibrator? = null

    protected var mIsCompassDragging: Boolean = false
    protected var mStatusPanelMode: Int = 0
    protected var mModeListener: onModeChange? = null
    public var mFinishListener: View.OnClickListener? = null

    protected val ADD_CURRENT_LOC: Int = 1
    protected val ADD_GEOMETRY_BY_WALK: Int = 3
    protected val ADD_POINT_BY_TAP: Int = 4
    private var mNeedSave = false

    var tmpFirstLocation: Location? = null

    var longClickProcessed = false




    interface onModeChange {
        fun onModeChangeListener()
    }

    fun setOnModeChangeListener(listener: onModeChange?) {
        mModeListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        mActivity = activity as MainActivity?
        mTolerancePX = mActivity!!.resources.displayMetrics.density * ConstantsUI.TOLERANCE_DP

        mPreferences = PreferenceManager.getDefaultSharedPreferences(mActivity)
        mApp = mActivity!!.application as MainApplication
        mVibrator = mActivity!!.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        mGpsEventSource = mApp!!.gpsEventSource

        mMapRef = WeakReference(MapViewOverlays(mActivity, mApp!!.map as MapDrawable))

        mMapRef.get()!!.id = R.id.map_view

        editLayerOverlay = EditLayerOverlay(mActivity, mMapRef.get())

        mMessageStyling = MessageStyling()
        mMessageReload = MessageReloadLayer()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle? ): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)

        mCurrentLocationOverlay = CurrentLocationOverlay(mActivity, mMapRef.get())
        mCurrentLocationOverlay!!.setStandingMarker(R.mipmap.ic_location_standing)
        mCurrentLocationOverlay!!.setMovingMarker(R.mipmap.ic_location_moving)
        mCurrentLocationOverlay!!.setAutopanningEnabled(true)

        mCurrentTrackOverlay = CurrentTrackOverlay(mActivity, mMapRef.get())
        mRulerOverlay = RulerOverlay(mActivity, mMapRef.get())
        undoRedoOverlay = UndoRedoOverlay(mActivity, mMapRef.get())

        mMapRef.get()!!.addOverlay(mCurrentTrackOverlay)
        mMapRef.get()!!.addOverlay(mCurrentLocationOverlay)
        mMapRef.get()!!.addOverlay(editLayerOverlay)
        mMapRef.get()!!.addOverlay(undoRedoOverlay)
        mMapRef.get()!!.addOverlay(mRulerOverlay)

        stylingProgrerss = view.findViewById(R.id.stylingProgress)
        textStylingProgrerss= view.findViewById(R.id.textStyling)

        stylingProgrerss?.setOnClickListener { null }

        //search relative view of map, if not found - add it
        mMapRelativeLayout = view.findViewById(R.id.maprl)
        if (mMapRelativeLayout != null) {
            mMapRelativeLayout!!.addView(
                mMapRef.get(), 0, RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                )
            )
        }


        var mapZoom = try {
            mPreferences!!.getFloat(SettingsConstantsUI.KEY_PREF_ZOOM_LEVEL, mMapRef.get()!!.minZoom)
        } catch (e: ClassCastException) {
            mMapRef.get()!!.minZoom
        }

        var mapScrollX: Double
        var mapScrollY: Double
        try {
            mapScrollX = java.lang.Double.longBitsToDouble(
                mPreferences!!.getLong(
                    SettingsConstantsUI.KEY_PREF_SCROLL_X,
                    0
                )
            )
            mapScrollY = java.lang.Double.longBitsToDouble(
                mPreferences!!.getLong(
                    SettingsConstantsUI.KEY_PREF_SCROLL_Y,
                    0
                )
            )
        } catch (e: ClassCastException) {
            mapScrollX = 0.0
            mapScrollY = 0.0
        }
        mMapRef.get()!!.setZoomAndCenter(mapZoom, GeoPoint(mapScrollX, mapScrollY))

        mMainButton = view.findViewById(R.id.multiple_actions)
        mAddPointButton = view.findViewById(R.id.add_point_by_tap)
        mAddPointButton?.setOnClickListener(this)

        val addCurrentLocation = view.findViewById<View>(R.id.add_current_location)
        addCurrentLocation.setOnClickListener(this)

        mAddNewGeometry = view.findViewById(R.id.add_new_geometry)
        mAddNewGeometry?.setOnClickListener(this)
        mRuler = view.findViewById(R.id.action_ruler)
        mRuler?.setOnClickListener(this)

        val addGeometryByWalk = view.findViewById<View>(R.id.add_geometry_by_walk)
        addGeometryByWalk.setOnClickListener(this)

        mivZoomIn = view.findViewById(R.id.action_zoom_in)
        mivZoomIn?.setOnClickListener(this)

        mivZoomOut = view.findViewById(R.id.action_zoom_out)
        mivZoomOut?.setOnClickListener(this)

        mStatusPanel = view.findViewById(R.id.fl_status_panel)
        mScaleRuler = view.findViewById(R.id.iv_ruler)
        mScaleRulerText = view.findViewById(R.id.tv_ruler)
        mScaleRulerText?.setText(rulerText)

        //        mZoomLevel = view.findViewById(R.id.tv_zoom_level);
//        mZoomLevel.setText(getZoomText());
        if (mZoom != null) mZoom!!.text = zoomText

        mScaleRulerLayout = view.findViewById(R.id.ll_ruler)
        drawScaleRuler()

        return view
    }

    override fun changeProgress(show: Boolean) {
        if (show)
            stylingProgrerss?.visibility = View.VISIBLE
        else {
            stylingProgrerss?.visibility = View.GONE
            textStylingProgrerss?.text = ""
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mapViewMaplibre = view.findViewById(R.id.mapViewMaplibre) as org.maplibre.android.maps.MapView

        mMapRef.get()!!.map!!.maplibreMapView = mapViewMaplibre




        mapViewMaplibre.onCreate(savedInstanceState)

        mapViewMaplibre.getMapAsync(this)
    }

    override fun onMapReady(mapboxMap: MapLibreMap) {
        mMapRef.get()!!.map!!.setMapFragment(this)

        val  interceptor = (mApp as IGISApplication).getAuthInterceptor();

        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dispatcher(getDispatcher())
            .build()

        // set global http client for raster auth
        HttpRequestImpl.setOkHttpClient(client)

        val mapboxMaplibre = mapboxMap
        mMapRef.get()!!.map!!.maplibreMap = mapboxMaplibre

        mapboxMaplibre.uiSettings.isRotateGesturesEnabled = false
        mapboxMaplibre.uiSettings.isCompassEnabled = false

        mapboxMaplibre.addOnCameraIdleListener(this)

        val styleJson = loadJsonFromAssets(requireContext(), "ngwstyle.json")
        val vectorLayers = mMapRef.get()!!.getVectorLayersByType(GeoConstants.GTAnyCheck)
        val layersTrack =  mMapRef.get()!!.getLayersByType(Constants.LAYERTYPE_TRACKS)
        vectorLayers.addAll(layersTrack);

        val allLayers = mMapRef.get()!!.getAllLayers()

        mMapRef.get()!!.map!!.loadLayersToMaplibreMap(styleJson, allLayers, true, true)
    }

    override fun loadLayersLite(){
        val allLayers = mMapRef.get()!!.getAllLayers()
        mMapRef.get()!!.map!!.loadLayersToMaplibreMapLite(allLayers, false)
    }

    override fun getLongLongClickProcesses(): Boolean {
        return longClickProcessed
    }

    override fun setLongLongClickProcesses(longLongCLickPrecesses: Boolean) {
        this.longClickProcessed = longLongCLickPrecesses;
    }

    private fun getDispatcher(): Dispatcher {
        val dispatcher = Dispatcher()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Matches core limit set on
            // https://github.com/mapbox/mapbox-gl-native/blob/master/platform/android/src/http_file_source.cpp#L192
            dispatcher.maxRequestsPerHost = 20
        } else {
            // Limiting concurrent request on Android 4.4, to limit impact of SSL handshake platform library crash
            // https://github.com/mapbox/mapbox-gl-native/issues/14910
            dispatcher.maxRequestsPerHost = 10
        }
        return dispatcher
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        if (undoRedoOverlay != null) undoRedoOverlay!!.defineUndoRedo()

        if (editLayerOverlay != null) editLayerOverlay!!.setHasEdits(editLayerOverlay!!.hasEdits())
    }

    fun restartGpsListener() {
        mGpsEventSource!!.removeListener(this)
        mGpsEventSource!!.addListener(this)
    }

    val isEditMode: Boolean
        get() = mode == MODE_EDIT || mode == MODE_EDIT_BY_WALK || mode == MODE_EDIT_BY_TOUCH

    fun onOptionsItemSelected(id: Int): Boolean {
        val result: Boolean
        when (id) {
            android.R.id.home -> {
                cancelEdits()
                return true
            }

            0 -> {
                mMapRef.get()!!.isLockMap = false
                setNewMode(MODE_EDIT)
                return true
            }



            com.nextgis.maplibui.R.id.menu_edit_undo, com.nextgis.maplibui.R.id.menu_edit_redo -> {
                result = undoRedoOverlay!!.onOptionsItemSelected(id)
                if (result) {
                    val undoRedoFeature = undoRedoOverlay!!.feature
                    val feature = editLayerOverlay!!.selectedFeature
                    feature.geometry = undoRedoFeature.geometry
                    editLayerOverlay!!.fillDrawItems(undoRedoFeature.geometry)

                    val original = mSelectedLayer!!.getGeometryForId(feature.id)
                    val hasEdits = original != null && undoRedoFeature.geometry == original

                    editLayerOverlay!!.setHasEdits(!hasEdits)
                    mMapRef.get()!!.map!!.replaceGeometryFromHistoryChanges(feature.geometry)
                    mMapRef.get()!!.map!!.updateMarkerByEditObject();
                    mMapRef.get()!!.buffer()
                    mMapRef.get()!!.postInvalidate()
                }
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_by_touch -> {
                setNewMode(MODE_EDIT_BY_TOUCH)
                result = editLayerOverlay!!.onOptionsItemSelected(id)
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_by_walk -> {
                setNewMode(MODE_EDIT_BY_WALK)

                result = editLayerOverlay!!.onOptionsItemSelected(id)
                if (result)
                    undoRedoOverlay!!.saveToHistory(editLayerOverlay!!.selectedFeature)


                (mApp!!.map as MapDrawable).updateHistoryByWalkEnd()
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_delete_point  ->{
                val result = mMapRef.get()!!.map!!.deleteCurrentPoint();
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_delete_line  ->{
                val result = mMapRef.get()!!.map!!.deleteCurrentLine();
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_add_new_line  ->{
                val center = mMapRef.get()!!.map!!.maplibreMap.cameraPosition.target
                val result = mMapRef.get()!!.map!!.addNewLine(center, mMapRef.get()!!.map!!.maplibreMap.getProjection());
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_add_new_point  ->{
                val center = mMapRef.get()!!.map!!.maplibreMap.cameraPosition.target
                val result = mMapRef.get()!!.map!!.addNewPoint(center);
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_add_new_inner_ring  ->{
                val center = mMapRef.get()!!.map!!.maplibreMap.cameraPosition.target
                val result = mMapRef.get()!!.map!!.addHole( center, mMapRef.get()!!.map!!.maplibreMap.getProjection());
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_delete_inner_ring  ->{
                val result = mMapRef.get()!!.map!!.deleteCurrentHole();
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_delete_polygon  ->{
                val result = mMapRef.get()!!.map!!.deleteCurrentPolygon();
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_add_new_polygon  ->{
                val center = mMapRef.get()!!.map!!.maplibreMap.cameraPosition.target
                val result = mMapRef.get()!!.map!!.addNewPolygon(center, mMapRef.get()!!.map!!.maplibreMap.getProjection());
                return result
            }

            com.nextgis.maplibui.R.id.menu_edit_move_point_to_center  ->{
                val center = mMapRef.get()!!.map!!.maplibreMap.cameraPosition.target
                return mMapRef.get()!!.map!!.moveToPoint(center);
            }

            com.nextgis.maplibui.R.id.menu_edit_move_point_to_current_location  ->{

                if (mCurrentCenter != null) {
                    val latlng = convert3857To4326(mCurrentCenter!!.x, mCurrentCenter!!.y)
                    return mMapRef.get()!!.map!!.moveToPoint(LatLng(latlng[1], latlng[0]));
                }
                return false;
            }


            else -> {
                result = editLayerOverlay!!.onOptionsItemSelected(id)
                if (result) undoRedoOverlay!!.saveToHistory(editLayerOverlay!!.selectedFeature)
                return result
            }
        }
        return false
    }


    fun saveEdits(): Boolean {
        val feature = editLayerOverlay!!.selectedFeature
        var featureId = Constants.NOT_FOUND.toLong()
        var geometry: GeoGeometry? = null

        if (mode == MODE_EDIT_BY_WALK) {
            editLayerOverlay!!.stopGeometryByWalk()
            setNewMode(MODE_EDIT)

            (mApp!!.map as MapDrawable).updateHistoryByWalkEnd()
//            undoRedoOverlay!!.clearHistory()
            undoRedoOverlay!!.defineUndoRedo()

            return true
        }

        if (feature != null) {
            geometry = feature.geometry
            featureId = feature.id
        }

        if (geometry == null || !geometry.isValid) {
            Toast.makeText(
                context,
                com.nextgis.maplibui.R.string.not_enough_points,
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        //MapUtil.isGeometryIntersects(context, geometry);
            //return false

        mMapRef.get()!!.isLockMap = false
        editLayerOverlay!!.setHasEdits(false)

        if (mode == MODE_EDIT_BY_TOUCH) {
            setNewMode(MODE_EDIT)
            undoRedoOverlay!!.clearHistory()
            undoRedoOverlay!!.defineUndoRedo()
        }

        if (mSelectedLayer != null) {
            if (featureId == Constants.NOT_FOUND.toLong()) {
                //show attributes edit activity
                val vectorLayerUI = mSelectedLayer as IVectorLayerUI
                vectorLayerUI.showEditForm(mActivity, featureId, geometry, -1)
            } else {
                var uri =  Uri.parse("content://" + mApp!!.authority + "/" + mSelectedLayer!!.path.name)
                uri = ContentUris.withAppendedId(uri!!, featureId)
                val values = ContentValues()

                try {
                    values.put(Constants.FIELD_GEOM, geometry.toBlob())
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                mActivity!!.contentResolver.update(uri, values, null, null)

                mMapRef.get()!!.map!!.cancelFeatureEdit(false)
                setNewMode(MODE_SELECT_ACTION)

            }
        }

        return true
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (mode == MODE_INFO || resultCode != Activity.RESULT_OK) {
            editLayerOverlay!!.setHasEdits(true)
            if (resultCode == Activity.RESULT_OK){
                /// need reload to maplibre feature
                if (requestCode == IVectorLayerUI.MODIFY_REQUEST && data != null) {
                    val id = data.getLongExtra(ConstantsUI.KEY_FEATURE_ID, Constants.NOT_FOUND.toLong())

                    if (id != Constants.NOT_FOUND.toLong()) {
                        mMapRef.get()!!.map!!.reloadFeatureToMaplibre(
                        id,
                        selectedLayer!! )
                    }
                }
            } else if (mode == MODE_EDIT){
                cancelEdits()
                setNewMode(MODE_NORMAL)
            }
            return
        }

        if (requestCode == IVectorLayerUI.MODIFY_REQUEST && data != null) {
            val id = data.getLongExtra(ConstantsUI.KEY_FEATURE_ID, Constants.NOT_FOUND.toLong())

            if (id != Constants.NOT_FOUND.toLong()) {
                editLayerOverlay!!.setSelectedFeature(id)
                if (mSelectedLayer != null) mSelectedLayer!!.showFeature(id)
                setNewMode(MODE_SELECT_ACTION)

                if (mMapRef.get() == null || mMapRef.get()!!.map == null)
                    return;
                mMapRef.get()!!.map!!.finishCreateNewFeature(
                    id,
                    selectedLayer!! )
                mMapRef.get()!!.map!!.reloadFeatureToMaplibre(id, selectedLayer)
                mMapRef.get()!!.map!!.updateSelectedMarker()
                mMapRef.get()!!.map.hideSelectedDotSource()
            }
        } else if (editLayerOverlay!!.selectedFeatureGeometry != null) editLayerOverlay!!.setHasEdits(
            true
        )
    }

    fun hasEdits(): Boolean {
        return editLayerOverlay != null && editLayerOverlay!!.hasEdits()
    }


    fun cancelEdits() {
//        if (mEditLayerOverlay.hasEdits()) TODO prompt dialog
//            return;

        // restore

        editLayerOverlay!!.panStop()
        panStop()

        editLayerOverlay!!.setHasEdits(false)
        if (mode == MODE_EDIT_BY_WALK) {
            editLayerOverlay!!.stopGeometryByWalk() // TODO toast?
            setNewMode(MODE_EDIT)
            undoRedoOverlay!!.clearHistory()
            undoRedoOverlay!!.defineUndoRedo()
        }
        val featureId = editLayerOverlay!!.selectedFeatureId
        editLayerOverlay!!.setSelectedFeature(featureId)
        mMapRef.get()!!.map!!.cancelFeatureEdit(featureId != -1L)
        setNewMode(MODE_SELECT_ACTION)
    }

    fun setNewMode(mode: Int, vararg readOnly: Boolean) {

        var askPerm = false
        if (mMapRef.get()!!.map!!.checkMeasurment(mode)){
            mRulerOverlay!!.stopMeasuring()
            showMainButton()
            showRulerButton()
            hideAddByTapButton()
            mAddPointButton!!.setIcon(com.nextgis.maplibui.R.drawable.ic_action_add_point)
            mActivity!!.title = mActivity!!.appName
            mActivity!!.setSubtitle(null)
            mMapRef.get()!!.map.stoptMeasuring()


        }
        var promt = ""
        when(mode){
            0 ->  promt=  "MODE_NORMAL"
            1 ->  promt=  "MODE_SELECT_ACTION"
            2 ->  promt=  "MODE_EDIT"
            3 ->  promt=  "MODE_INFO"
            4 ->  promt=  "MODE_EDIT_BY_WALK"
            5 ->  promt=  "MODE_EDIT_BY_TOUCH"
            6 ->  promt=  "MODE_SELECT_FOR_VIEW"
            else -> promt=  "MODE_ELSE"
        }
//        Log.e("MMOODDEE", "mode set to " + promt);

        this.mode = mode

        hideMainButton()
        hideAddByTapButton()
        hideRulerButton()

        val toolbar = mActivity!!.bottomToolbar
        toolbar.background.alpha = 128
        toolbar.visibility = View.VISIBLE
        mActivity!!.showDefaultToolbar()

        if (mStatusPanelMode != 3) mStatusPanel!!.visibility = View.INVISIBLE

        when (mode) {
            MODE_NORMAL -> {
                if (mSelectedLayer != null) mSelectedLayer!!.isLocked = false

                mSelectedLayer = null
                toolbar.visibility = View.GONE
                showMainButton()
                showRulerButton()
                if (mStatusPanelMode != 0) mStatusPanel!!.visibility = View.VISIBLE
                editLayerOverlay!!.showAllFeatures()
                editLayerOverlay!!.mode = EditLayerOverlay.MODE_NONE
                undoRedoOverlay!!.clearHistory()
                mMapRef.get()!!.map!!.unselectFeatureFromView()
            }

            MODE_EDIT -> {
                if (mSelectedLayer == null) {
                    setNewMode(MODE_NORMAL)
                    return
                }

                mSelectedLayer!!.isLocked = true
                mActivity!!.showEditToolbar()
                editLayerOverlay!!.mode = EditLayerOverlay.MODE_EDIT
                toolbar.setOnMenuItemClickListener { menuItem ->
                    onOptionsItemSelected(
                        menuItem.itemId
                    )
                }
                mMapRef.get()!!.map!!.showVertex()
                mMapRef.get()!!.map!!.showMarker();
            }

            MODE_EDIT_BY_WALK -> {
                mSelectedLayer!!.isLocked = true
                mActivity!!.showEditToolbar()
                editLayerOverlay!!.mode = EditLayerOverlay.MODE_EDIT_BY_WALK
                undoRedoOverlay!!.clearHistory()

                mMapRef.get()!!.map!!.unselectFeatureFromEdit(false, true)
                mMapRef.get()!!.map!!.hideVertex()

                mMapRef.get()!!.map!!.hideMarker()

                askPerm = true


            }

            MODE_EDIT_BY_TOUCH -> {
                mSelectedLayer!!.isLocked = true
                mActivity!!.showEditToolbar()
                editLayerOverlay!!.mode = EditLayerOverlay.MODE_EDIT_BY_TOUCH
                toolbar.setOnMenuItemClickListener { menuItem ->
                    onOptionsItemSelected(menuItem.itemId)
                }

                mMapRef.get()!!.map!!.unselectFeatureFromEdit(false, true)
                mMapRef.get()!!.map!!.hideVertex()
                mMapRef.get()!!.map!!.hideMarker()

            }

            MODE_SELECT_ACTION -> {
                if (mSelectedLayer == null) {
                    setNewMode(MODE_NORMAL)
                    return
                }

                mSelectedLayer!!.isLocked = true
                toolbar.title = null
                toolbar.menu.clear()
                toolbar.inflateMenu(R.menu.select_action)
                toolbar.menu.findItem(R.id.menu_feature_edit).setEnabled(false)
                toolbar.setNavigationIcon(com.nextgis.maplibui.R.drawable.ic_action_cancel_dark)

                mFinishListener = View.OnClickListener {
                    setNewMode(MODE_NORMAL)
                }
                toolbar.setNavigationOnClickListener(mFinishListener)

                toolbar.setOnMenuItemClickListener(
                    Toolbar.OnMenuItemClickListener { item ->
                        if (mSelectedLayer == null) return@OnMenuItemClickListener false
                        when (item.itemId) {
                            R.id.menu_feature_add -> {

                                editLayerOverlay!!.selectedFeature = Feature()
                                editLayerOverlay!!.createNewGeometry()
                                undoRedoOverlay!!.clearHistory()
                                setNewMode(MODE_EDIT)
                                // skip - because next save from maplibre correct
                                //undoRedoOverlay!!.saveToHistory(editLayerOverlay!!.selectedFeature)
                                editLayerOverlay!!.setHasEdits(true)

                                    mMapRef.get()!!.map!!.startFeatureSelectionForEdit(
                                        mSelectedLayer,
                                        mSelectedLayer!!.geometryType,
                                        editLayerOverlay!!.selectedFeature,
                                        true,
                                        mSelectedLayer!!.defaultStyleNoExcept,
                                        false )

                                // update rudiment code - created geometry on old pre-maplibre code
                                // on editing it updates on MotionEvent.ACTION_UP actions
                                updateGeometryFromMaplibre(
                                    mMapRef.get()!!.map!!.editingObject.editingFeature,
                                    mMapRef.get()!!.map!!.originalSelectedFeature,
                                    mMapRef.get()!!.map!!.editingObject
                                )


                            }

                            R.id.menu_feature_edit -> {

                                if (mMapRef.get()!!.map!!.getLayerFeatures(mSelectedLayer!!) == null){
                                    Toast.makeText(context,
                                        com.nextgis.maplibui.R.string.edit_invisible,
                                        Toast.LENGTH_LONG
                                    ).show()

                                } else {


                                    setNewMode(MODE_EDIT)
                                    undoRedoOverlay!!.saveToHistory(editLayerOverlay!!.selectedFeature)
                                    editLayerOverlay!!.setHasEdits(false)
                                    if(mSelectedLayer!= null)
                                        mMapRef.get()!!.map!!.startFeatureSelectionForEdit(mSelectedLayer, mSelectedLayer!!.geometryType,
                                            editLayerOverlay!!.selectedFeature, false, mSelectedLayer!!.defaultStyleNoExcept,
                                            false)
                                }
                            }

                            R.id.menu_feature_delete -> deleteFeature()
                            R.id.menu_feature_attributes -> setNewMode(MODE_INFO)
                        }
                        true
                    })

                editLayerOverlay!!.mode = EditLayerOverlay.MODE_HIGHLIGHT
                undoRedoOverlay!!.clearHistory()
            }

            MODE_SELECT_FOR_VIEW -> {
                if (mSelectedLayer == null) {
                    setNewMode(MODE_NORMAL)
                    return
                }

                //mSelectedLayer.setLocked(true);
                toolbar.title = null
                toolbar.menu.clear()
                toolbar.inflateMenu(R.menu.select_action)
                toolbar.menu.findItem(R.id.menu_feature_edit).setEnabled(false)
                toolbar.setNavigationIcon(com.nextgis.maplibui.R.drawable.ic_action_cancel_dark)

                mFinishListener = View.OnClickListener { setNewMode(MODE_NORMAL) }
                toolbar.setNavigationOnClickListener(mFinishListener)

                toolbar.setOnMenuItemClickListener(
                    Toolbar.OnMenuItemClickListener { item ->
                        if (mSelectedLayer == null) return@OnMenuItemClickListener false
                        when (item.itemId) {
                            R.id.menu_feature_attributes -> setNewMode(
                                MODE_INFO,
                                true
                            )
                        }
                        true
                    })

                editLayerOverlay!!.mode = EditLayerOverlay.MODE_HIGHLIGHT
                undoRedoOverlay!!.clearHistory()
            }

            MODE_INFO -> {
                if (mSelectedLayer == null) {
                    setNewMode(MODE_NORMAL)
                    return
                }
                var readOnlyModeValue = false
                if (readOnly.size > 0) readOnlyModeValue = readOnly[0]

                mSelectedLayer!!.isLocked = if (readOnlyModeValue) false else true
                val tabletSize = resources.getBoolean(R.bool.isTablet)
                val fragmentManager = mActivity!!.supportFragmentManager
                val fragmentTransaction = fragmentManager.beginTransaction()
                //get or create fragment
                val attributesFragment = getAttributesFragment(fragmentManager)

                val attrBundle = Bundle()
                attrBundle.putBoolean(AttributesFragment.KEY_READ_ONLY, readOnlyModeValue)
                attributesFragment.arguments = attrBundle
                attributesFragment.isTablet = tabletSize
                var container = R.id.mainview

                if (attributesFragment.isTablet) {
                    container = R.id.fl_attributes
                } else {
                    val hide = fragmentManager.findFragmentById(R.id.map)
                    fragmentTransaction.hide(hide!!)
                }

                if (!attributesFragment.isAdded) {
                    fragmentTransaction.add(container, attributesFragment, "ATTRIBUTES")
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)

                    if (!attributesFragment.isTablet) fragmentTransaction.addToBackStack(null)
                }

                if (!attributesFragment.isVisible) {
                    fragmentTransaction.show(attributesFragment)
                }

                fragmentTransaction.commit()

                attributesFragment.setSelectedFeature(
                    mSelectedLayer,
                    editLayerOverlay!!.selectedFeatureId
                )
                attributesFragment.setToolbar(toolbar, editLayerOverlay, readOnlyModeValue)

                mFinishListener = View.OnClickListener { view ->
                    (activity as MainActivity).finishFragment()
                    if (attributesFragment.isTablet) activity!!.supportFragmentManager.beginTransaction()
                        .remove(attributesFragment).commit()
                    if (view == null) setNewMode(MODE_NORMAL)
                }

                toolbar.setNavigationIcon(com.nextgis.maplibui.R.drawable.ic_action_cancel_dark)
                toolbar.setNavigationOnClickListener(mFinishListener)
            }
        }

        if (mModeListener != null) mModeListener!!.onModeChangeListener()

        setMarginsToPanel()
        defineMenuItems()

        if (askPerm)
            Handler().postDelayed(Runnable(){
                mActivity?.askBackgroundPerm(null)
            }, 1000)

    }

    private fun getAttributesFragment(fragmentManager: FragmentManager): AttributesFragment {
        var attributesFragment =
            fragmentManager.findFragmentByTag("ATTRIBUTES") as AttributesFragment?
        if (null == attributesFragment) attributesFragment = AttributesFragment()

        return attributesFragment
    }

    protected fun defineMenuItems() {
        if (mode == MODE_NORMAL || mode == MODE_INFO) return

        if (mSelectedLayer == null) {
            setNewMode(MODE_NORMAL)
            return
        }


        val noFeature = editLayerOverlay!!.selectedFeatureGeometry == null
        val featureId = editLayerOverlay!!.selectedFeatureId

        var featureName: String? =
            String.format(getString(com.nextgis.maplibui.R.string.feature_n), featureId)
        val labelField = mSelectedLayer!!.preferences.getString(
            SettingsConstantsUI.KEY_PREF_LAYER_LABEL,
            Constants.FIELD_ID
        )!!
        if ((labelField != Constants.FIELD_ID) && !noFeature && featureId != Constants.NOT_FOUND.toLong()) {
            val feature = mSelectedLayer!!.getFeature(featureId)
            if (feature != null) featureName = feature.getFieldValueAsString(labelField)
        }

        featureName =
            if (noFeature) getString(com.nextgis.maplibui.R.string.nothing_selected) else if (featureId == Constants.NOT_FOUND.toLong()) getString(
                com.nextgis.maplibui.R.string.new_feature
            ) else featureName
        mActivity!!.title = featureName
        mActivity!!.setSubtitle(mSelectedLayer!!.name)

        val hasSelectedFeature = editLayerOverlay!!.selectedFeature != null && !noFeature
        val toolbar = mActivity!!.bottomToolbar
        for (i in 0..<toolbar.menu.size()) {
            var item = toolbar.menu.findItem(R.id.menu_feature_delete)
            if (item != null) ControlHelper.setEnabled(
                item,
                hasSelectedFeature && mode != MODE_SELECT_FOR_VIEW
            )

            item = toolbar.menu.findItem(R.id.menu_feature_edit)
            if (item != null) ControlHelper.setEnabled(
                item,
                hasSelectedFeature && mode != MODE_SELECT_FOR_VIEW
            )

            item = toolbar.menu.findItem(R.id.menu_feature_attributes)
            if (item != null) ControlHelper.setEnabled(item, hasSelectedFeature)

            item = toolbar.menu.findItem(R.id.menu_feature_add)
            if (mode == MODE_SELECT_FOR_VIEW) ControlHelper.setEnabled(item, false)
        }
    }


    override fun onDestroyView() {
        if (mMapRef.get() != null) {
            mMapRef.get()!!.removeListener(this)
            mMapRef.get()!!.map.clearMapListeners()
            if (mMapRelativeLayout != null) {
                mMapRelativeLayout!!.removeView(mMapRef.get())
            }
        }

        editLayerOverlay?.mBottomToolbar?.setOnClickListener(null)
        editLayerOverlay?.mBottomToolbar = null

        super.onDestroyView()
    }


    protected fun drawScaleRuler() {
        val px =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 10f, resources.displayMetrics)
                .toInt()
        val notch =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1f, resources.displayMetrics)
                .toInt()
        val ruler = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(ruler)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ContextCompat.getColor(activity!!, com.nextgis.maplibui.R.color.primary_dark)
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(0f, px.toFloat(), px.toFloat(), px.toFloat(), paint)
        canvas.drawLine(0f, px.toFloat(), 0f, 0f, paint)
        canvas.drawLine(0f, 0f, notch.toFloat(), 0f, paint)
        canvas.drawLine(px.toFloat(), px.toFloat(), px.toFloat(), (px - notch).toFloat(), paint)
        mScaleRuler!!.setImageBitmap(ruler)
    }


    protected fun showMapButtons(
        show: Boolean,
        rl: RelativeLayout?
    ) {
        if (null == rl) {
            return
        }
        var v = rl.findViewById<View>(R.id.action_zoom_out)
        if (null != v) {
            if (show) {
                v.visibility = View.VISIBLE
            } else {
                v.visibility = View.GONE
            }
        }

        v = rl.findViewById(R.id.action_zoom_in)
        if (null != v) {
            if (show) {
                v.visibility = View.VISIBLE
            } else {
                v.visibility = View.GONE
            }
        }
    }


    override fun onLayerAdded(id: Int) {
    }


    override fun onLayerDeleted(id: Int) {
        setNewMode(MODE_NORMAL)
    }


    override fun onLayerChanged(id: Int) {

        // remove - moved update to MapViewOverlays
//        Log.e("","");
//
//        //mMapRef.get()!!.map!!.reloadLayerByID(id); // todo change to update ony item - feature id
//        mMapRef.get()!!.map!!.reloadFillLayerStyleToMaplibre(id); // todo change to update ony item - feature id
//
//        mMapRef.get()!!.map!!.checkLayerVisibility(id);

    }

    override fun onLayerVisibleChanged(id: Int) {
        // no need
    }

    override fun onLayerChangedFeatureId(
        oldFeatureId: Long,
        newFeatureId: Long,
        layerId: Int) {
        //// remove - moved update to MapViewOverlays
        //mMapRef.get()!!.map!!.changeFeatureId(oldFeatureId,newFeatureId, layerId);
    }


    override fun onExtentChanged(
        zoom: Float,
        center: GeoPoint
    ) {
        setZoomInEnabled(mMapRef.get()!!.canZoomIn())
        setZoomOutEnabled(mMapRef.get()!!.canZoomOut())
        mScaleRulerText!!.text = rulerText
        if (mZoom != null)
            mZoom!!.text = zoomText
    }


//    protected val zoomText: String
//        get() = String.format("%.0fz", mMapRef.get()!!.zoomLevel)

    protected val zoomText: String
        get() = getZText()  //

    fun getZText(): String {

        val mapLibreMap = mMapRef.get()!!.map.maplibreMap
        if (mapLibreMap != null) {
            //return "${mapLibreMap.zoom.toInt()}z"
            return "%.1fz".format(Locale.US, mapLibreMap.zoom)
        }
        else
            return ".z"
    }

    fun getCurrentZoom(): Int {
        val mapLibreMap = mMapRef.get()!!.map.maplibreMap
        if (mapLibreMap != null)
            return mapLibreMap.zoom.toInt()
        else
            return -1

    }

    protected val rulerText: String
        get() {
            var p1 =
                GeoPoint(mScaleRuler!!.left.toDouble(), mScaleRuler!!.bottom.toDouble())
            var p2 =
                GeoPoint(mScaleRuler!!.right.toDouble(), mScaleRuler!!.bottom.toDouble())
            p1 = mMapRef.get()!!.map.screenToMap(p1)
            p2 = mMapRef.get()!!.map.screenToMap(p2)
            p1.crs = GeoConstants.CRS_WEB_MERCATOR
            p2.crs = GeoConstants.CRS_WEB_MERCATOR
            val s = GeoLineString()
            s.add(p1)
            s.add(p2)

            val result = LocationUtil.formatLength(context, s.length, 1)
            if (result == null)
                return "";
            return result;
        }


    override fun onLayersReordered() {




    }


    override fun onLayerDrawFinished(
        id: Int,
        percent: Float
    ) {
        //Log.d(Constants.TAG, "onLayerDrawFinished: " + id + " percent " + percent);
        /*if (percent >= 1.0)
            mLayerDrawn++;
        MainActivity activity = (MainActivity) mActivity;
        if (null != activity){
            if (percent >= 1.0) {
                if (id == mMap.getTopVisibleLayerId()) {
                    activity.onRefresh(false, 0);
                } else {
                    activity.onRefresh(true, (mLayerDrawn * 100) / mMap.getVisibleLayerCount());
                }
            }
        }*/
        if (percent >= 1.0 && id == Constants.DRAW_FINISH_ID) {
            /** mMap.getMap().getId()) {finish id come at end */
            if (null != mActivity) {
                mActivity!!.onRefresh(false)
            }
        }
    }


    override fun onLayerDrawStarted() {
        if (null != mActivity) {
            mActivity!!.onRefresh(true)
        }
    }


    protected fun setZoomInEnabled(bEnabled: Boolean) {
        if (mivZoomIn == null) {
            return
        }

        mivZoomIn!!.isEnabled = bEnabled
    }


    protected fun setZoomOutEnabled(bEnabled: Boolean) {
        if (mivZoomOut == null) {
            return
        }
        mivZoomOut!!.isEnabled = bEnabled
    }

    protected fun setMapLibreZoomOutEnabled() {
        val mapLibreMap = mMapRef.get()!!.map.maplibreMap
        if (mapLibreMap != null){
            if (mapLibreMap.zoom <= mapLibreMap.minZoomLevel) {
                mivZoomOut!!.isEnabled = false
                return
            }
        }
        mivZoomOut!!.isEnabled = true
    }


    protected fun setMapLibreZoomInEnabled() {
        val mapLibreMap = mMapRef.get()!!.map.maplibreMap
        if (mapLibreMap != null){
            if (mapLibreMap.zoom  >= mapLibreMap.maxZoomLevel) {
                mivZoomIn!!.isEnabled = false
                return
            }
        }
        mivZoomIn!!.isEnabled = true
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(BUNDLE_KEY_IS_MEASURING, mRulerOverlay!!.isMeasuring)
        outState.putInt(KEY_MODE, mode)
        outState.putInt(
            BUNDLE_KEY_LAYER,
            if (null == mSelectedLayer) Constants.NOT_FOUND else mSelectedLayer!!.id
        )

        val feature = editLayerOverlay!!.selectedFeature
        outState.putLong(
            BUNDLE_KEY_FEATURE_ID,
            feature?.id ?: Constants.NOT_FOUND.toLong()
        )

        if (null != feature && feature.geometry != null) {
            try {
                outState.putByteArray(BUNDLE_KEY_SAVED_FEATURE, feature.geometry.toBlob())
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }


    override fun onViewStateRestored(
        savedInstanceState: Bundle?
    ) {
        super.onViewStateRestored(savedInstanceState)
        if (null == savedInstanceState) {
            mode = MODE_NORMAL
        } else {
            mode = savedInstanceState.getInt(KEY_MODE)

            val layerId = savedInstanceState.getInt(BUNDLE_KEY_LAYER)
            val layer = mMapRef.get()!!.getLayerById(layerId)
            var feature: Feature? = null

            if (null != layer && layer is VectorLayer) {
                mSelectedLayer = layer

                if (savedInstanceState.containsKey(BUNDLE_KEY_SAVED_FEATURE)) {
                    var geometry: GeoGeometry? = null

                    try {
                        geometry = GeoGeometryFactory.fromBlob(
                            savedInstanceState.getByteArray(
                                BUNDLE_KEY_SAVED_FEATURE
                            )
                        )
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    feature = Feature()
                    feature.id =
                        savedInstanceState.getLong(BUNDLE_KEY_FEATURE_ID)
                    feature.geometry = geometry
                }
            }

            editLayerOverlay!!.setSelectedLayer(mSelectedLayer)
            editLayerOverlay!!.selectedFeature = feature
        }

        if (WalkEditService.isServiceRunning(context)) {
            val preferences = context!!.getSharedPreferences(
                WalkEditService.TEMP_PREFERENCES,
                Context.MODE_MULTI_PROCESS
            )
            val layerId = preferences.getInt(ConstantsUI.KEY_LAYER_ID, Constants.NOT_FOUND)
            val featureId =
                preferences.getLong(ConstantsUI.KEY_FEATURE_ID, Constants.NOT_FOUND.toLong())
            val layer = mMapRef.get()!!.map.getLayerById(layerId)
            if (layer != null && layer is VectorLayer) {
                mSelectedLayer = layer
                editLayerOverlay!!.setSelectedLayer(mSelectedLayer)

                if (featureId > Constants.NOT_FOUND) editLayerOverlay!!.setSelectedFeature(featureId)
                else editLayerOverlay!!.newGeometryByWalk()

                val geometry = GeoGeometryFactory.fromWKT(
                    preferences.getString(ConstantsUI.KEY_GEOMETRY, ""),
                    GeoConstants.CRS_WEB_MERCATOR )
                if (geometry != null) editLayerOverlay!!.setGeometryFromWalkEdit(geometry)
                //need start ByWalking editing on maplibre

                mMapRef.get()?.map?.startEditByWalkFromRestore(
                    mSelectedLayer,
                    editLayerOverlay!!.selectedFeature)

                mode = MODE_EDIT_BY_WALK
            }
        }

        if (mode == MODE_EDIT_BY_WALK) {
            setNewMode(mode)
            // start fill data from service
        }
        else
            setNewMode(mode)

        if (savedInstanceState != null && savedInstanceState.getBoolean(
                BUNDLE_KEY_IS_MEASURING,
                false )
        ) startMeasuring()
    }


    override fun onPause() {
        if (null != mCurrentLocationOverlay) {
            mCurrentLocationOverlay!!.stopShowingCurrentLocation()
        }
        if (null != mGpsEventSource) {
            mGpsEventSource!!.removeListener(this)
        }
        if (null != editLayerOverlay) {
            editLayerOverlay!!.removeListener(this)
            editLayerOverlay!!.onPause()
        }

        val edit = mPreferences!!.edit()
        if (null != mMapRef.get()) {
            if (mMapRef.get()!!.map!!.maplibreMap != null) {
                edit.putFloat(SettingsConstantsUI.KEY_PREF_ZOOM_LEVEL,
                    mMapRef.get()!!.map!!.maplibreMap.cameraPosition.zoom.toFloat())

                val point2 = mMapRef.get()!!.map.getMaplibreCenter()
                edit.putLong(
                    SettingsConstantsUI.KEY_PREF_SCROLL_X,
                    java.lang.Double.doubleToRawLongBits(point2.x))
                edit.putLong(
                    SettingsConstantsUI.KEY_PREF_SCROLL_Y,
                    java.lang.Double.doubleToRawLongBits(point2.y))
            } else {
                edit.putFloat(SettingsConstantsUI.KEY_PREF_ZOOM_LEVEL, mMapRef.get()!!.zoomLevel)
                val point = mMapRef.get()!!.mapCenter

                edit.putLong(
                    SettingsConstantsUI.KEY_PREF_SCROLL_X,
                    java.lang.Double.doubleToRawLongBits(point.x))
                edit.putLong(
                    SettingsConstantsUI.KEY_PREF_SCROLL_Y,
                    java.lang.Double.doubleToRawLongBits(point.y))
            }
            mMapRef.get()!!.removeListener(this)
        }
        edit.apply()

        mActivity?.unregisterReceiver(mMessageStyling)
        mActivity?.unregisterReceiver(mMessageReload)

        super.onPause()
    }


    override fun onResume() {
        super.onResume()

        var showControls =
            mPreferences!!.getBoolean(AppSettingsConstants.KEY_PREF_SHOW_ZOOM_CONTROLS, true)
        showMapButtons(showControls, mMapRelativeLayout)

        if (Constants.DEBUG_MODE) Log.d(
            Constants.TAG,
            "KEY_PREF_SHOW_ZOOM_CONTROLS: " + (if (showControls) "ON" else "OFF")
        )

        showControls =
            mPreferences!!.getBoolean(AppSettingsConstants.KEY_PREF_SHOW_SCALE_RULER, true)
        if (showControls) mScaleRulerLayout!!.visibility = View.VISIBLE
        else mScaleRulerLayout!!.visibility = View.GONE

        showControls = mPreferences!!.getBoolean(AppSettingsConstants.KEY_PREF_SHOW_ZOOM, false)
        if (showControls) {
//            mZoomLevel.setVisibility(View.VISIBLE);
            if (mZoom != null) mZoom!!.visibility = View.VISIBLE
        } else {
//            mZoomLevel.setVisibility(View.GONE);
            if (mZoom != null) mZoom!!.visibility = View.GONE
        }

        showControls =
            mPreferences!!.getBoolean(AppSettingsConstants.KEY_PREF_SHOW_MEASURING, false)
        if (showControls) mRuler!!.visibility = View.VISIBLE
        else mRuler!!.visibility = View.GONE

        if (null != mMapRef.get()) {
            mMapRef.get()!!.map.setBackground(mApp!!.mapBackground)
            mMapRef.get()!!.addListener(this)
        }

        val coordinatesFormat =
            mPreferences!!.getString(
                SettingsConstantsUI.KEY_PREF_COORD_FORMAT,
                Location.FORMAT_DEGREES.toString() + ""
            )!!
        mCoordinatesFormat =
            if (FileUtil.isIntegerParseInt(coordinatesFormat)) coordinatesFormat.toInt()
            else Location.FORMAT_DEGREES
        mCoordinatesFraction = mPreferences!!.getInt(
            SettingsConstantsUI.KEY_PREF_COORD_FRACTION,
            AppConstants.DEFAULT_COORDINATES_FRACTION_DIGITS
        )

        if (null != mCurrentLocationOverlay) {
            mCurrentLocationOverlay!!.updateMode(
                mPreferences!!.getString(
                    SettingsConstantsUI.KEY_PREF_SHOW_CURRENT_LOC,
                    "3"
                )
            )
            mCurrentLocationOverlay!!.startShowingCurrentLocation()
        }
        if (null != mGpsEventSource) {
            mGpsEventSource!!.addListener(this)
            if (mGPSDialog == null || !mGPSDialog!!.isShowing) mGPSDialog =
                NotificationHelper.showLocationInfo(
                    activity
                )
        }

        if (null != editLayerOverlay) {
            editLayerOverlay!!.addListener(this)
            editLayerOverlay!!.onResume()
        }

        try {
            val statusPanelModeStr =
                mPreferences!!.getString(SettingsConstantsUI.KEY_PREF_SHOW_STATUS_PANEL, "1")!!
            mStatusPanelMode =
                if (FileUtil.isIntegerParseInt(statusPanelModeStr)) statusPanelModeStr.toInt()
                else 0
        } catch (e: ClassCastException) {
            mStatusPanelMode = 0
            if (Constants.DEBUG_MODE) Log.d(
                Constants.TAG,
                "Previous version of KEY_PREF_SHOW_STATUS_PANEL of bool type. Let set it to 0"
            )
        }

        if (null != mStatusPanel) {
            if (mStatusPanelMode != 0) {
                mStatusPanel!!.visibility = View.VISIBLE
                fillStatusPanel(mGpsEventSource!!.lastKnownLocation)

                if (mode != MODE_NORMAL && mStatusPanelMode != 3) mStatusPanel!!.visibility =
                    View.INVISIBLE
            } else {
                mStatusPanel!!.removeAllViews()
            }

            setMarginsToPanel()
            updateLastLocation()
        }

        val showCompass =
            mPreferences!!.getBoolean(AppSettingsConstants.KEY_PREF_SHOW_COMPASS, true)
        checkCompass(showCompass)

        if (mGpsEventSource != null && mGpsEventSource!!.lastKnownLocation != null) {
            mCurrentCenter = GeoPoint()

            mCurrentCenter!!.setCoordinates(mGpsEventSource!!.lastKnownLocation.longitude, mGpsEventSource!!.lastKnownLocation.latitude)
            mCurrentCenter!!.crs = GeoConstants.CRS_WGS84

            if (!mCurrentCenter!!.project(GeoConstants.CRS_WEB_MERCATOR))
                mCurrentCenter = null

            // old convert
            //                val  newPoint = convert4326To3857 (mGpsEventSource!!.lastKnownLocation.longitude, mGpsEventSource!!.lastKnownLocation.latitude)
            //            mCurrentCenter!!.setCoordinates(newPoint.get(0), newPoint.get(1))
        }
        else
            mCurrentCenter = null

        if (GISApplication.needUpdateBackground){
            try {
                GISApplication.needUpdateBackground = false
                if (mMapRef.get() != null) {
                    mMapRef.get()!!.map.updateMapBackground()
                }
            } catch (exception : Exception) {

            }

        }
        //updateLastLocation()
        val intentFilter = IntentFilter()
        intentFilter.addAction( MESSAGE_INTENT_STYLING)

        val intentFilterReload = IntentFilter()
        intentFilterReload.addAction( MESSAGE_INTENT_RELOAD)
//        intentFilter.addAction( MESSAGE_INTENT_STYLING_RASTER)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mActivity?.registerReceiver( mMessageStyling, intentFilter, RECEIVER_NOT_EXPORTED)
            mActivity?.registerReceiver( mMessageReload, intentFilterReload, RECEIVER_NOT_EXPORTED)
        } else {
            mActivity?.registerReceiver(mMessageStyling, intentFilter)
            mActivity?.registerReceiver(mMessageReload, intentFilterReload)
        }

        val progressStyling = (getContext()!!.getApplicationContext() as IGISApplication).getingStyleInProgress
        changeProgress(progressStyling)

        // check for walking was
        if (WalkEditService.isServiceRunning(context) && mode == MODE_EDIT_BY_WALK) {
            // need getFeature from old overlay and update in maplibre logic
                mMapRef.get()?.map?.updateWalkingFeature(editLayerOverlay!!.selectedFeature)
        }


        val listOfLayers = (context?.applicationContext  as IGISApplication).getlayersToRefresh()
        if (listOfLayers!= null)
            for (layerId in listOfLayers)
            (context?.applicationContext  as IGISApplication).removeLayerToRefresh(layerId)
        if (listOfLayers!= null)
            for (layerId in listOfLayers){
                if (mMapRef.get()!= null && mMapRef.get()!!.map!= null && layerId != -1){
                    val targetlayer = LayerGroup.getVectorLayersById(mMapRef.get()?.map, id)
                    if (targetlayer != null) {
                        val isVisible = (targetlayer as Layer).isVisible()
                        if (isVisible) {
                            if (mMapRef.get()!!.map!!.getLayerVisible(layerId)) {
                                Handler().postDelayed({
                                    mMapRef.get()!!.map!!.refreshLayerVisibility(layerId, false)
                                }, 300)

                                Handler().postDelayed({
                                    mMapRef.get()!!.map!!.refreshLayerVisibility(layerId, true)
                                }, 600)
                            }
                        }
                    }
                }
            }
    }

    protected fun setMarginsToPanel() {
        val toolbar = mActivity!!.bottomToolbar

        toolbar.post {
            val isToolbarVisible = toolbar.visibility == View.VISIBLE
            val isPanelVisible = mStatusPanel!!.visibility == View.VISIBLE
            val toolbarHeight = toolbar.measuredHeight

            val lp = mStatusPanel!!.layoutParams as RelativeLayout.LayoutParams
            var bottom = if (isToolbarVisible && isPanelVisible) toolbarHeight
            else 0

            lp.setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, bottom)
            mStatusPanel!!.layoutParams = lp

            bottom = if (isToolbarVisible && !isPanelVisible) toolbarHeight
            else 0

            mStatusPanel!!.minimumHeight = bottom
            mStatusPanel!!.requestLayout()
        }
    }


    protected fun checkCompass(showCompass: Boolean) {
        val compassContainer = R.id.fl_compass
        val compass = mMapRelativeLayout!!.findViewById<FrameLayout>(compassContainer)

        if (!showCompass) {
            compass.visibility = View.GONE
            return
        }

        val fragmentManager = mActivity!!.supportFragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        //get or create fragment
        var compassFragment =
            fragmentManager.findFragmentByTag("NEEDLE_COMPASS") as CompassFragment?
        if (null == compassFragment) compassFragment = CompassFragment()

        compass.isClickable = false
        compassFragment.setStyle(true)
        if (!compassFragment.isAdded) fragmentTransaction.add(
            compassContainer,
            compassFragment,
            "NEEDLE_COMPASS"
        )
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)

        if (!compassFragment.isVisible) {
            fragmentTransaction.show(compassFragment)
        }

        fragmentTransaction.commit()

        compass.visibility = View.VISIBLE
        compass.setOnClickListener(this)
        compass.setOnLongClickListener {
            mIsCompassDragging = true
            mVibrator!!.vibrate(5)
            true
        }
        // Thanks to http://javatechig.com/android/how-to-drag-a-view-in-android
        compass.setOnTouchListener(object : OnTouchListener {
            private var _xDelta = 0
            private var _yDelta = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val X = event.rawX.toInt()
                val Y = event.rawY.toInt()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val lParams = v.layoutParams as RelativeLayout.LayoutParams
                        _xDelta = X - lParams.leftMargin
                        _yDelta = Y - lParams.topMargin
                        return false
                    }

                    MotionEvent.ACTION_UP -> {
                        mIsCompassDragging = false
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!mIsCompassDragging) return false

                        val layoutParams = v.layoutParams as RelativeLayout.LayoutParams
                        val width = v.width
                        val height = v.height
                        var toolbarHeight = 0
                        if (mActivity!!.supportActionBar != null) toolbarHeight =
                            mActivity!!.supportActionBar!!
                                .height
                        if (X > width / 3 && X < v.rootView.width - width / 3) layoutParams.leftMargin =
                            X - _xDelta
                        if (Y > height / 2 + toolbarHeight && Y < v.rootView.height - height / 2) layoutParams.topMargin =
                            Y - _yDelta

                        v.layoutParams = layoutParams
                    }
                }
                mMapRelativeLayout!!.invalidate()
                return true
            }
        })
    }


    protected fun addNewGeometry() {
        mApp!!.sendEvent(ConstantsUI.GA_LAYER, ConstantsUI.GA_EDIT, ConstantsUI.GA_FAB)

        //show select layer dialog if several layers, else start default or custom form
        val layers = removeHideLayers( mMapRef.get()!!.getVectorLayersByType(
            GeoConstants.GTPointCheck or GeoConstants.GTMultiPointCheck or
                    GeoConstants.GTLineStringCheck or GeoConstants.GTMultiLineStringCheck or
                    GeoConstants.GTPolygonCheck or GeoConstants.GTMultiPolygonCheck))

        if (layers.isEmpty()) {
            Toast.makeText(mActivity, getString(R.string.warning_no_edit_layers), Toast.LENGTH_LONG)
                .show()
        } else if (layers.size == 1) {
            //open form
            val layer = layers[0] as VectorLayer

            mSelectedLayer = layer
            editLayerOverlay!!.setSelectedLayer(layer)
            setNewMode(MODE_SELECT_ACTION)

            Toast.makeText(
                mActivity,
                String.format(getString(R.string.edit_layer), layer.name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            if (isDialogShown) return
            //open choose edit layer dialog
            mChooseLayerDialogRef = WeakReference(ChooseLayerDialog(false, false))
            mChooseLayerDialogRef.get()!!.setLayerList(layers)
                .setCode(EDIT_LAYER)
                .setTitle(getString(com.nextgis.maplibui.R.string.choose_layers))
                .setTheme(mActivity!!.themeId) //.show(mActivity.getSupportFragmentManager(), "choose_layer");
                .show(childFragmentManager, ChooseLayerDialog.TAG)
        }
    }


    protected fun addPointByTap() {
        if (mSelectedLayer != null) mSelectedLayer!!.isLocked = false

        //show select layer dialog if several layers, else start default or custom form
        val layers = removeHideLayers(mMapRef.get()!!.getVectorLayersByType(GeoConstants.GTPointCheck
                or GeoConstants.GTMultiPointCheck))

        if (layers.isEmpty()) {
            Toast.makeText(
                mActivity, getString(R.string.warning_no_edit_layers), Toast.LENGTH_LONG
            )
                .show()
        } else if (layers.size == 1) {
            //open form
            val layer = layers[0] as VectorLayer

            mSelectedLayer = layer
            editLayerOverlay!!.setSelectedLayer(layer)
            createPointFromOverlay(false)

            Toast.makeText(
                mActivity,
                String.format(getString(R.string.edit_layer), layer.name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            if (isDialogShown) return
            //open choose edit layer dialog
            mChooseLayerDialogRef = WeakReference(ChooseLayerDialog(false, false))
            mChooseLayerDialogRef.get()!!.setLayerList(layers)
                .setCode(ADD_POINT_BY_TAP)
                .setTitle(getString(com.nextgis.maplibui.R.string.choose_layers))
                .setTheme(mActivity!!.themeId)
                .show(mActivity!!.supportFragmentManager, ChooseLayerDialog.TAG)
        }
    }

    protected fun createPointFromOverlay(isFillByWalking: Boolean) {
        editLayerOverlay!!.selectedFeature = Feature()

        if (mCurrentCenter != null)
            editLayerOverlay!!.selectedFeature.geometry = GeoPoint(mCurrentCenter!!.x, mCurrentCenter!!.y)
        else
            editLayerOverlay!!.selectedFeature.geometry = GeoPoint()
        setNewMode(MODE_EDIT)
        undoRedoOverlay!!.clearHistory()
        val mapLibreMap = mMapRef.get()!!.map!!.maplibreMap
        editLayerOverlay!!.createPointFromOverlay()
        editLayerOverlay!!.setHasEdits(true)
        undoRedoOverlay!!.saveToHistory(editLayerOverlay!!.selectedFeature)

        mMapRef.get()!!.map!!.startFeatureSelectionForEdit(mSelectedLayer,
            mSelectedLayer!!.geometryType,
            editLayerOverlay!!.selectedFeature, true,mSelectedLayer!!.defaultStyleNoExcept,
            isFillByWalking)
    }

    // useCreatePouintFromOverlay - need to call if create by click R.id.add_current_location button
    protected fun addCurrentLocation(useCreatePointFromOverlay: Boolean) {
        //show select layer dialog if several layers, else start default or custom form
        val layers = removeHideLayers (mMapRef.get()!!.getVectorLayersByType(
            GeoConstants.GTMultiPointCheck or GeoConstants.GTPointCheck))


        if (layers.isEmpty()) {
            Toast.makeText(
                mActivity, getString(R.string.warning_no_edit_layers), Toast.LENGTH_LONG
            )
                .show()
        } else if (layers.size == 1) {
            //open form
            val vectorLayer = layers[0]
            if (vectorLayer is ILayerUI) {
                mSelectedLayer = vectorLayer as VectorLayer
                editLayerOverlay!!.setSelectedLayer(mSelectedLayer)

                if (useCreatePointFromOverlay)
                    createPointFromOverlay(false)

                val vectorLayerUI = vectorLayer as IVectorLayerUI
                vectorLayerUI.showEditForm(mActivity, Constants.NOT_FOUND.toLong(), null, -1)

                Toast.makeText(
                    mActivity,
                    String.format(getString(R.string.edit_layer), vectorLayer.getName()),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    mActivity, getString(R.string.warning_no_edit_layers),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            if (isDialogShown) return
            //open choose dialog
            mChooseLayerDialogRef = WeakReference(ChooseLayerDialog(true, false))
            mChooseLayerDialogRef.get()!!.setLayerList(layers)
                .setCode(ADD_CURRENT_LOC)
                .setTitle(getString(com.nextgis.maplibui.R.string.choose_layers))
                .setTheme(mActivity!!.themeId)
                .show(mActivity!!.supportFragmentManager, ChooseLayerDialog.TAG)
        }
    }

    protected fun removeHideLayers(layerList: MutableList<ILayer>): MutableList<ILayer> {
        var i = 0
        while (i < layerList.size) {
            val layerView = layerList[i] as ILayerView
            if (null != layerView) {
                if (!layerView.isVisible) {
                    layerList.removeAt(i)
                    i--
                }
            }
            i++
        }

        return layerList
    }


    protected fun addGeometryByWalk() {
        //show select layer dialog if several layers, else start default or custom form
        val layers = removeHideLayers (mMapRef.get()!!.getVectorLayersByType(
            (GeoConstants.GTLineStringCheck or GeoConstants.GTPolygonCheck
                    or GeoConstants.GTMultiLineStringCheck or GeoConstants.GTMultiPolygonCheck)))

        if (layers.isEmpty()) {
            Toast.makeText(mActivity, getString(R.string.warning_no_edit_layers), Toast.LENGTH_LONG)
                .show()
        } else if (layers.size == 1) {
            //open form
            val layer = layers[0] as VectorLayer
            mSelectedLayer = layer
            editLayerOverlay!!.setSelectedLayer(layer)

            editLayerOverlay!!.newGeometryByWalk()

            createPointFromOverlay(true)
            editLayerOverlay!!.newGeometryByWalk()
            setNewMode(MODE_EDIT_BY_WALK)


            Toast.makeText(
                mActivity,
                String.format(getString(R.string.edit_layer), layer.name),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            if (isDialogShown) return
            //open choose edit layer dialog
            mChooseLayerDialogRef = WeakReference(ChooseLayerDialog(true, true))
            mChooseLayerDialogRef.get()!!.setLayerList(layers)
                .setCode(ADD_GEOMETRY_BY_WALK)
                .setTitle(getString(com.nextgis.maplibui.R.string.choose_layers))
                .setTheme(mActivity!!.themeId)
                .show(mActivity!!.supportFragmentManager, ChooseLayerDialog.TAG)
        }
    }


    fun onFinishChooseLayerDialog(
        code: Int,
        layer: ILayer?,
        useCreatePointFromOverlay: Boolean,
        startFillByWalk: Boolean
    ) {
        val vectorLayer = layer as VectorLayer?
        if (layer == null) return  // TODO toast?


        if (mSelectedLayer != null) mSelectedLayer!!.isLocked = false

        mSelectedLayer = vectorLayer
        editLayerOverlay!!.setSelectedLayer(vectorLayer)


        if (useCreatePointFromOverlay)
            createPointFromOverlay(startFillByWalk)

        if (code == ADD_CURRENT_LOC) {
            if (layer is ILayerUI) {
                val layerUI = layer as IVectorLayerUI
                layerUI.showEditForm(mActivity, Constants.NOT_FOUND.toLong(), null, -1)
            }
        } else if (code == EDIT_LAYER) {
            setNewMode(MODE_SELECT_ACTION)
            //if (editLayerOverlay.selectedFeature == )
            if (useCreatePointFromOverlay)
                createPointFromOverlay(false)
        } else if (code == ADD_GEOMETRY_BY_WALK) {
            editLayerOverlay!!.newGeometryByWalk()
            setNewMode(MODE_EDIT_BY_WALK)
        } else if (code == ADD_POINT_BY_TAP) {
            createPointFromOverlay(false)
        }
    }


    override fun processMapLongClick(clickeEnelope: GeoEnvelope, clickPoint: PointF): Boolean {
        return  onLongPressFromMaplibre(clickeEnelope, clickPoint)
        return true
    }

    override fun processMapClick(screenx: Float, screeny: Float): Boolean {    // x y - screen coordinates
//        Log.e("CCCLLIICK", "screenX: " + screenx + " - " + " screeny: " + screeny)
        onSingleTapUpFromMaplibre(screenx, screeny)
        return true
    }

    fun onLongPressFromMaplibre(clickeEnelope: GeoEnvelope, clickPoint : PointF): Boolean {

        if (!(mode == MODE_NORMAL || mode == MODE_SELECT_ACTION) || mRulerOverlay!!.isMeasuring) {
            return false
        }

        if (null == clickeEnelope)
            return false
        val point = GeoPoint(clickeEnelope.center.x, clickeEnelope.center.y)
        point.crs = GeoConstants.CRS_WEB_MERCATOR

        //show actions dialog
        val layers = mMapRef.get()!!.getVectorLayersByType(GeoConstants.GTAnyCheck)
        var items: List<Long>

        var vectorLayer: VectorLayer? = null
        var selectedSingleVectorLayer: VectorLayer? = null
        var selectedSingleFeatureId: Long = -1

        val mSelectedLayers = ArrayList<String>()
        var geometry: GeoGeometry? = null
        var featureId: Long = -1

        val selectedVectorLayer: MutableList<VectorLayer> = ArrayList()
        val selectedGeometry: MutableList<GeoGeometry?> = ArrayList()
        val selectedFeatures: MutableList<Feature> = ArrayList()

        var originalFeatureForSelect : Feature? = null

        layersLoop@ for (layer in layers) {
            //if (!layer.isValid) continue

            if (!(layer as ILayerView).isVisible) continue
            vectorLayer = layer as VectorLayer

//            Log.e("CCLICK", "on long:")
//            Log.e("CCLICK", clickeEnelope.toString())
            items = vectorLayer.query(clickeEnelope)
            for (i in items.indices) {    // FIXME hack for bad RTree cache
                featureId = items[i]
                geometry = vectorLayer.getGeometryForId(featureId)

//                Log.e("CCLICK", "on long check contains point:" + point.toString())
//                Log.e("CCLICK", "on long check contains poly:" + geometry.toString())
                if (EditLayerOverlay.notContains(geometry, point)) {
                    continue
                }
                val feature = vectorLayer.getFeature(featureId)
                if (feature == null) {
                    Toast.makeText(mActivity, "not feature for " + featureId, Toast.LENGTH_LONG).show();
                    continue
                }
                originalFeatureForSelect = vectorLayer.getFeature(featureId)
                if (originalFeatureForSelect != null) {
                    val valueForHint = getHintText(vectorLayer, feature)

                    if (feature != null){
                        if (valueForHint == null)
                            mSelectedLayers.add(layer.getName() + ": " + featureId)
                        else
                            mSelectedLayers.add(layer.getName() + ": " + valueForHint)

                        selectedSingleVectorLayer = layer
                        selectedSingleFeatureId = featureId

                        selectedVectorLayer.add(vectorLayer)
                        selectedGeometry.add(geometry)
                        selectedFeatures.add(feature)
                    }
                } else {
                    mSelectedLayers.add(layer.getName() + ": " + featureId + " is null")
                }
            }
        }

        if (mSelectedLayers.size > 1 || selectedFeatures.size > 1) {
            showOverlayPointMultiChoise(
                clickPoint.x.toDouble(), clickPoint.y.toDouble(), mSelectedLayers,
                selectedVectorLayer,
                selectedGeometry,
                selectedFeatures,
                true)
            return  true

        } else if (mSelectedLayers.size == 1 || selectedFeatures.size == 1) {


            if (mSelectedLayer != null)
                mSelectedLayer!!.isLocked = false

            mSelectedLayer = selectedSingleVectorLayer
            editLayerOverlay!!.setSelectedLayer(selectedSingleVectorLayer)

            if (geometry != null && mSelectedLayer != null) {

                editLayerOverlay!!.setSelectedFeature(selectedSingleFeatureId)
                mMapRef.get()!!.map!!.startFeatureSelectionForView(mSelectedLayer, originalFeatureForSelect)
                defineMenuItems()
                if (mode != MODE_SELECT_ACTION)
                    setNewMode(MODE_SELECT_ACTION)
            }


            return true
        } else {
            showOverlayPoint(clickPoint.x.toDouble(), clickPoint.y.toDouble())
        }
        //set select action mode
        //mMapRef.get()!!.postInvalidate()

        return true
    }

    override fun onLongPress(event: MotionEvent) {
//        if (!(mode == MODE_NORMAL || mode == MODE_SELECT_ACTION) || mRulerOverlay!!.isMeasuring) {
//            return
//        }
//
//        val dMinX = (event.x - mTolerancePX).toDouble()
//        val dMaxX = (event.x + mTolerancePX).toDouble()
//        val dMinY = (event.y - mTolerancePX).toDouble()
//        val dMaxY = (event.y + mTolerancePX).toDouble()
//
//        val mapEnv = mMapRef.get()!!.screenToMap(GeoEnvelope(dMinX, dMaxX, dMinY, dMaxY)) ?: return
//
//        var exactEnv: GeoEnvelope? = GeoEnvelope(
//            event.x.toDouble(),
//            event.x.toDouble(),
//            event.y.toDouble(),
//            event.y.toDouble()
//        )
//        exactEnv = mMapRef.get()!!.screenToMap(exactEnv)
//        if (null == exactEnv) return
//        val point = GeoPoint(exactEnv.maxX, exactEnv.minY)
//        point.crs = GeoConstants.CRS_WEB_MERCATOR
//
//        //show actions dialog
//        val layers = mMapRef.get()!!.getVectorLayersByType(GeoConstants.GTAnyCheck)
//        var items: List<Long>
//
//
//        var vectorLayer: VectorLayer? = null
//        var selectedSingleVectorLayer: VectorLayer? = null
//        var selectedSingleFeatureId: Long = -1
//
//        val mSelectedLayers = ArrayList<String>()
//        var geometry: GeoGeometry? = null
//        var featureId: Long = -1
//
//        val selectedVectorLayer: MutableList<VectorLayer> = ArrayList()
//        val selectedGeometry: MutableList<GeoGeometry?> = ArrayList()
//        val selectedFeaturesList: MutableList<Feature> = ArrayList()
//
//        layersLoop@ for (layer in layers) {
//            //if (!layer.isValid) continue
//
//            if (!(layer as ILayerView).isVisible) continue
//
//            vectorLayer = layer as VectorLayer
//            items = vectorLayer.query(mapEnv)
//
//            for (i in items.indices) {    // FIXME hack for bad RTree cache
//                featureId = items[i]
//                geometry = vectorLayer.getGeometryForId(featureId)
//                if (EditLayerOverlay.notContains(geometry, point)) {
//                    continue
//                }
//
//                val feature = vectorLayer.getFeature(featureId)
//                val valueForHint = getHintText(vectorLayer, feature)
//
//                if (feature != null){
//                    if (valueForHint == null)
//                        mSelectedLayers.add(layer.getName() + ": " + featureId)
//                    else
//                        mSelectedLayers.add(layer.getName() + ": " + valueForHint)
//                }
//
//                selectedSingleVectorLayer = layer
//                selectedSingleFeatureId = featureId
//
//                selectedVectorLayer.add(vectorLayer)
//                selectedGeometry.add(geometry)
//                selectedFeaturesList.add(feature)
//            }
//        }
//
//        if (mSelectedLayers.size > 1)
//            showOverlayPointMultiChoise(
//                event.x.toDouble(), event.y.toDouble(), mSelectedLayers,
//                selectedVectorLayer,
//                selectedGeometry,
//                selectedFeaturesList,
//                true)
//        else {
//            if (mSelectedLayer != null)
//                mSelectedLayer!!.isLocked = false
//
//            mSelectedLayer = selectedSingleVectorLayer
//            editLayerOverlay!!.setSelectedLayer(selectedSingleVectorLayer)
//
//            if (geometry != null) editLayerOverlay!!.setSelectedFeature(selectedSingleFeatureId)
//
//            setMode(MODE_SELECT_ACTION)
//            showOverlayPoint(event.x.toDouble(), event.y.toDouble())
//        }
//        //set select action mode
//        mMapRef.get()!!.postInvalidate()
        // old odd code
    }

    fun showAddByTapButton() {
        mAddPointButton!!.visibility = View.VISIBLE
    }

    fun hideAddByTapButton() {
        mAddPointButton!!.visibility = View.GONE
    }


    fun showRulerButton() {
        if (mPreferences!!.getBoolean(
                AppSettingsConstants.KEY_PREF_SHOW_MEASURING,
                false
            )
        ) mRuler!!.visibility =
            View.VISIBLE
    }


    fun hideRulerButton() {
        mRuler!!.visibility = View.GONE
    }


    fun showMainButton() {
        if (mode == MODE_EDIT_BY_WALK) return

        mAddNewGeometry!!.iconDrawable.alpha = 255
        mMainButton!!.visibility = View.VISIBLE
    }


    fun hideMainButton() {
        mMainButton!!.visibility = View.GONE
    }


    fun hideOverlayPoint() {
        editLayerOverlay!!.hideOverlayPoint()
        mMapRef.get()!!.postInvalidate()

        hideAddByTapButton()
        showMainButton()
        mMapRef.get()!!.map.clearPressedPoint()

    }


    fun showOverlayPoint(screenX : Double, screenY: Double) {
        hideMainButton()
        showAddByTapButton()
        editLayerOverlay!!.setOverlayPoint(screenX, screenY)

        val pointf = PointF(screenX.toFloat(), screenY.toFloat())
        val latLng: LatLng = mMapRef.get()!!.map!!.maplibreMap.getProjection().fromScreenLocation(pointf)

        mMapRef.get()!!.map.addPressedPoint(latLng)
    }

    fun showOverlayPointMultiChoise(
        x : Double,  y: Double,
        featureNames: List<String>,
        vectorLayer: List<VectorLayer>,
        geometry: List<GeoGeometry?>,
        features: List<Feature> ,
        editMode : Boolean) {

        val items = featureNames.toTypedArray<String>()
        val builder = AlertDialog.Builder(            context!!        )
        builder.setTitle(R.string.choose_object)
        builder.setItems(items) { dialog, which -> //String selectedItem = items[which];
            // remove after some time
            if (mSelectedLayer != null) mSelectedLayer!!.isLocked = false

            mSelectedLayer = vectorLayer[which]
            editLayerOverlay!!.setSelectedLayer(vectorLayer[which])

            if (geometry[which] != null) {
                showViewModeForFeature(mSelectedLayer!!,
                    features[which],
                    mSelectedLayer!!,
                    geometry[which],
                            features[which].id,
                    features[which].id ,
                    editMode)


//                editLayerOverlay!!.setSelectedFeature(features[which].id)
//                if (editMode)
//                    mMapRef.get()!!.map!!.startFeatureSelectionForEdit(
//                        mSelectedLayer, mSelectedLayer!!.geometryType,
//                        features[which],
//                        false, mSelectedLayer!!.defaultStyleNoExcept)
//                else {
//                    mMapRef.get()!!.map!!.startFeatureSelectionForView(
//                        mSelectedLayer,
//                        features[which])
//                    if (mode != MODE_SELECT_ACTION)
//                        setMode(MODE_SELECT_ACTION)
//                }
            } else {

//                setMode(MODE_SELECT_ACTION)
//                //showOverlayPoint(x,y)
//
//                hideMainButton()
//                showAddByTapButton()
//                editLayerOverlay!!.setOverlayPoint(x,y)
            }
        }
        builder.create().show()
    }

    override fun onSingleTapUp(event: MotionEvent) {
        if (mRulerOverlay!!.isMeasuring) return
        when (mode) {
            MODE_EDIT -> {
                if (editLayerOverlay!!.selectGeometryInScreenCoordinates(
                        event.x,
                        event.y
                    )
                ) undoRedoOverlay!!.saveToHistory(
                    editLayerOverlay!!.selectedFeature
                )
                defineMenuItems()
            }

            MODE_SELECT_ACTION -> {
                editLayerOverlay!!.selectGeometryInScreenCoordinates(event.x, event.y)
                defineMenuItems()
            }

            MODE_INFO -> {
                editLayerOverlay!!.selectGeometryInScreenCoordinates(event.x, event.y)

                if (null != editLayerOverlay) {
                    val attributesFragment =
                        mActivity!!.supportFragmentManager.findFragmentByTag("ATTRIBUTES") as AttributesFragment?
                    if (attributesFragment != null) {
                        attributesFragment.setSelectedFeature(
                            mSelectedLayer,
                            editLayerOverlay!!.selectedFeatureId
                        )
                        mMapRef.get()!!.postInvalidate()
                    }
                }
            }

            else -> if (mode == MODE_NORMAL || mode == MODE_SELECT_FOR_VIEW) {
                // check objects on map to select
                val dMinX = (event.x - mTolerancePX).toDouble()
                val dMaxX = (event.x + mTolerancePX).toDouble()
                val dMinY = (event.y - mTolerancePX).toDouble()
                val dMaxY = (event.y + mTolerancePX).toDouble()

                val mapEnv = mMapRef.get()!!.screenToMap(GeoEnvelope(dMinX, dMaxX, dMinY, dMaxY)) ?: return

                var exactEnv: GeoEnvelope? = GeoEnvelope(
                    event.x.toDouble(),
                    event.x.toDouble(),
                    event.y.toDouble(),
                    event.y.toDouble()
                )
                exactEnv = mMapRef.get()!!.screenToMap(exactEnv)
                if (null == exactEnv) return
                val point = GeoPoint(exactEnv.maxX, exactEnv.minY)
                point.crs = GeoConstants.CRS_WEB_MERCATOR

                //show actions dialog
                val layers = mMapRef.get()!!.getVectorLayersByType(GeoConstants.GTAnyCheck)
                var items: List<Long>


                var vectorLayer: VectorLayer? = null
                var selectedSingleVectorLayer: VectorLayer? = null
                var selectedSingleFeatureId: Long = -1

                val mSelectedLayers = ArrayList<String>()
                var geometry: GeoGeometry? = null
                var featureId: Long = -1

                val selectedVectorLayer: MutableList<VectorLayer> = ArrayList()
                val selectedGeometry: MutableList<GeoGeometry?> = ArrayList()
                val selectedFeatures: MutableList<Feature> = ArrayList()


                layersLoop@ for (layer in layers) {
                    //if (!layer.isValid) continue

                    if (!(layer as ILayerView).isVisible) continue

                    vectorLayer = layer as VectorLayer
                    items = vectorLayer.query(mapEnv)

                    var i = 0
                    while (i < items.size) {
                        // FIXME hack for bad RTree cache
                        featureId = items[i]
                        geometry = vectorLayer.getGeometryForId(featureId)
                        if (EditLayerOverlay.notContains(geometry, point)) {
                            i++
                            continue
                        }



                        val feature = vectorLayer.getFeature(featureId)
                        val valueForHint = getHintText(vectorLayer, feature)

                        if (feature != null){
                            if (valueForHint == null)
                                mSelectedLayers.add(layer.getName() + ": " + featureId)
                            else
                                mSelectedLayers.add(layer.getName() + ": " + valueForHint)
                        }

                        selectedSingleVectorLayer = layer
                        selectedSingleFeatureId = featureId

                        selectedVectorLayer.add(vectorLayer)
                        selectedGeometry.add(geometry)
                        selectedFeatures.add(feature)

                        mMapRef.get()!!.map!!.startFeatureSelectionForView(mSelectedLayer, feature)

                        i++
                    }
                }

                if (mSelectedLayers.size == 0 && mode == MODE_SELECT_FOR_VIEW) {
                    // need select none
                    setNewMode(MODE_NORMAL)
                } else {
                    if (mSelectedLayers.size > 1 || selectedFeatures.size >1)
                        showOverlayPointMultiChoise(
                        event.x.toDouble(), event.y.toDouble(), mSelectedLayers,
                        selectedVectorLayer,
                        selectedGeometry,
                        selectedFeatures,
                        false )
                    else {
                        if (mSelectedLayer != null) mSelectedLayer!!.isLocked = false

                        mSelectedLayer = selectedSingleVectorLayer
                        editLayerOverlay!!.setSelectedLayer(selectedSingleVectorLayer)

                        if (geometry != null) editLayerOverlay!!.setSelectedFeature(
                            selectedSingleFeatureId
                        )

                        setNewMode(MODE_SELECT_FOR_VIEW)
                        //showOverlayPoint(event);
                    }
                }
                //set select action mode
                mMapRef.get()!!.postInvalidate()
            } else if (!mRulerOverlay!!.isMeasuring) hideOverlayPoint()
        }
    }

    fun getClickEnelope(clickPoint: PointF, maplibreMap:MapLibreMap): GeoEnvelope {
        val TOLERANCE_DP = 20
        val mTolerancePX = getContext()!!.getResources().getDisplayMetrics().density * TOLERANCE_DP

        val minP = PointF(clickPoint.x - mTolerancePX, clickPoint.y - mTolerancePX)
        val maxP = PointF(clickPoint.x + mTolerancePX, clickPoint.y + mTolerancePX)

        val minL: LatLng = maplibreMap.getProjection().fromScreenLocation(minP)
        val maxL: LatLng = maplibreMap.getProjection().fromScreenLocation(maxP)

        val minPoints = MPLFeaturesUtils.convert4326To3857(minL.longitude, minL.latitude)
        val maxPoints = MPLFeaturesUtils.convert4326To3857(maxL.longitude, maxL.latitude)

        var minx = minPoints[0]
        var maxx = maxPoints[0]
        var miny = minPoints[1]
        var maxy = maxPoints[1]

        if (minx > maxx) {
            minx = maxPoints[0]
            maxx = minPoints[0]
        }
        if (miny > maxy) {
            miny = maxPoints[1]
            maxy = minPoints[1]
        }

        //val exactEnv: GeoEnvelope = GeoEnvelope(pointsMin[0],  pointsMax[0], pointsMin[1], pointsMax[1])
        val exactEnv = GeoEnvelope(minx, maxx, miny, maxy)
        return exactEnv
    }


    fun onSingleTapUpFromMaplibre(screenx: Float, screeny :Float) {
        if (mRulerOverlay!!.isMeasuring) return
        when (mode) {
            MODE_EDIT -> { }
            MODE_INFO -> {
                if (null != editLayerOverlay) {
                    val attributesFragment =
                        mActivity!!.supportFragmentManager.findFragmentByTag("ATTRIBUTES") as AttributesFragment?
                    if (attributesFragment != null) {
                        attributesFragment.setSelectedFeature(
                            mSelectedLayer,
                            editLayerOverlay!!.selectedFeatureId
                        )
                        mMapRef.get()!!.postInvalidate()
                    }
                }
            }

            else -> if (mode == MODE_NORMAL || mode == MODE_SELECT_FOR_VIEW || mode == MODE_SELECT_ACTION) {
                // check objects on map to select
                val dMinX = (screenx - mTolerancePX)
                val dMaxX = (screenx + mTolerancePX)
                val dMinY = (screeny - mTolerancePX)
                val dMaxY = (screeny + mTolerancePX)

                val screenPointMin = PointF(dMinX, dMinY)
                val screenPointMax = PointF(dMaxX, dMaxY)

                val minPoint = mMapRef.get()!!.map!!.maplibreMap.getProjection().fromScreenLocation(screenPointMin)
                val maxPoint = mMapRef.get()!!.map!!.maplibreMap.getProjection().fromScreenLocation(screenPointMax)

//                Log.e("CCCLLIICK", " click at: " + screenx + " - " + " screeny: " + screeny)
//                Log.e("CCCLLIICK", "points lnglong " + minPoint.longitude + " : " +  minPoint.latitude + " : "
//                        + maxPoint.longitude + " : " + maxPoint.latitude )

                val pointsMin = convert4326To3857(minPoint.longitude, minPoint.latitude)
                val pointsMax = convert4326To3857(maxPoint.longitude, maxPoint.latitude);

                var minx =   pointsMin[0];
                var maxx =   pointsMax[0];
                var miny =   pointsMin[1];
                var maxy =   pointsMax[1];

                if (minx > maxx){
                    minx =   pointsMax[0]
                    maxx =   pointsMin[0]
                }
                if (miny > maxy){
                    miny =   pointsMax[1]
                    maxy =   pointsMin[1]
                }
                //val exactEnv: GeoEnvelope = GeoEnvelope(minx,maxx , miny, maxy)
                val pointClick = PointF(screenx, screeny)
                val exactEnv: GeoEnvelope = getClickEnelope(pointClick, mMapRef.get()!!.map!!.maplibreMap)

                val point = GeoPoint(exactEnv.center.x, exactEnv.center.y)
                point.crs = GeoConstants.CRS_WEB_MERCATOR


                //show actions dialog
                var layers = mMapRef.get()!!.getVectorLayersByType(GeoConstants.GTAnyCheck)
                var items: List<Long>


                var vectorLayer: VectorLayer? = null
                var selectedSingleVectorLayer: VectorLayer? = null
                var selectedSingleFeatureId: Long = -1

                val mSelectedLayers = ArrayList<String>()
                var geometry: GeoGeometry? = null
                var featureId: Long = -1

                val selectedVectorLayer: MutableList<VectorLayer> = ArrayList()
                val selectedGeometry: MutableList<GeoGeometry?> = ArrayList()
                val selectedFeatures: MutableList<Feature> = ArrayList()


             if (mode == MODE_SELECT_ACTION && selectedLayer != null){
                 layers = mutableListOf<ILayer>()
                 layers.add(selectedLayer)
             }

                layersLoop@ for (layer in layers) {
                    //if (!layer.isValid) continue

                    if (!(layer as ILayerView).isVisible) continue

                    vectorLayer = layer as VectorLayer
//                    Log.e("CCLICK", "on tapUp:")
//                    Log.e("CCLICK", exactEnv.toString())
                    items = vectorLayer.query(exactEnv)

                    var i = 0
                    while (i < items.size) {
                        // FIXME hack for bad RTree cache
                        featureId = items[i]
                        geometry = vectorLayer.getLargeGeometryForId(featureId)

//                        Log.e("CCLICK", "on Up check contains point:" + point.toString())
//                        Log.e("CCLICK", "on Up check contains poly:" + geometry.toString())

                        if (EditLayerOverlay.notContains(geometry, point)) {
                            i++
                            continue
                        }

                        val feature = vectorLayer.getFeature(featureId)
                        val valueForHint = getHintText(vectorLayer, feature)

                        if (feature != null){
                            if (valueForHint == null)
                                mSelectedLayers.add(layer.getName() + ": " + featureId)
                            else
                                mSelectedLayers.add(layer.getName() + ": " + valueForHint)

//                            selectedFeatures.add(feature)

                            selectedSingleVectorLayer = layer
                            selectedSingleFeatureId = featureId

                            selectedVectorLayer.add(vectorLayer)
                            selectedGeometry.add(geometry)
                            selectedFeatures.add(feature)
                        } else {
                            i++
                            continue
                        }
                        mSelectedLayer = layer
                        i++
                    }
                }

                if (mSelectedLayers.size == 0 && mode == MODE_SELECT_FOR_VIEW) {
                    // need select none
                    setNewMode(MODE_NORMAL)
                } else {
                    if (mSelectedLayers.size > 1 || selectedFeatures.size > 1)
                        showOverlayPointMultiChoise(
                            screenx.toDouble(),
                            screeny.toDouble(),
                            mSelectedLayers,
                        selectedVectorLayer,
                        selectedGeometry,
                        selectedFeatures,
                        false )
                    else if (mSelectedLayers.size == 1 || selectedFeatures.size == 1){

                        showViewModeForFeature(selectedVectorLayer.get(0),
                            selectedFeatures.get(0),
                            selectedSingleVectorLayer,
                            geometry,
                            selectedSingleFeatureId,
                            featureId ,
                            mode == MODE_SELECT_ACTION)


//                        if (mSelectedLayer != null)
//                            mSelectedLayer!!.isLocked = false
//
//                        mSelectedLayer = selectedSingleVectorLayer
//                        editLayerOverlay!!.setSelectedLayer(selectedSingleVectorLayer)
//
//                        if (geometry != null) editLayerOverlay!!.setSelectedFeature(selectedSingleFeatureId)
//
//                        if (mode != MODE_SELECT_ACTION)
//                            setMode(MODE_SELECT_FOR_VIEW)
//                        //showOverlayPoint(event);
//
//                        if (featureId != -1L && mSelectedLayer != null) {
//                            mMapRef.get()!!.map!!.startFeatureSelectionForView(
//                                selectedVectorLayer.get(0),
//                                selectedFeatures.get(0))
//                        }
                    } else {
                        if (mAddPointButton!!.visibility == View.VISIBLE) {
                            editLayerOverlay!!.hideOverlayPoint()
                            //mMapRef.get()!!.postInvalidate()

                            hideAddByTapButton()
                            showMainButton()

                            mMapRef.get()!!.map.clearPressedPoint()
                        }
                    }
                }
                //set select action mode
                //mMapRef.get()!!.postInvalidate()
            } else if (!mRulerOverlay!!.isMeasuring) hideOverlayPoint()
        }
    }

    fun showViewModeForFeature(layerd: ILayer,
                               originalSelectedFeature: Feature,
                               selectedSingleVectorLayer: VectorLayer?,
                               geometry: GeoGeometry?,
                               selectedSingleFeatureId: Long ,
                               featureId: Long,
                               editMode : Boolean){
        if (mSelectedLayer != null)
            mSelectedLayer!!.isLocked = false

        mSelectedLayer = selectedSingleVectorLayer
        editLayerOverlay!!.setSelectedLayer(selectedSingleVectorLayer)

        if (geometry != null)
            editLayerOverlay!!.setSelectedFeature(selectedSingleFeatureId)

        if (editMode) {
            if (mode != MODE_SELECT_ACTION)
                setNewMode(MODE_SELECT_ACTION)
        } else {
            if (mode != MODE_SELECT_ACTION)
                setNewMode(MODE_SELECT_FOR_VIEW)

        }
        //showOverlayPoint(event);
        /*
        * final ILayer  ilayerd, Integer layerGeoType,
                                             Feature originalSelectedFeature, boolean createNew,
                                             com.nextgis.maplib.display.Style ngstyle*/

        if (featureId != -1L && mSelectedLayer != null) {
            if (mode == MODE_SELECT_ACTION)
                editLayerOverlay!!.setSelectedFeature(selectedSingleFeatureId)

            mMapRef.get()!!.map!!.startFeatureSelectionForView(
                    layerd,
                    originalSelectedFeature)
            defineMenuItems()
        }
    }


    override fun panStart(e: MotionEvent) {
        if (editLayerOverlay!!.mode == EditLayerOverlay.MODE_CHANGE) mNeedSave = true
    }

    override fun panMoveTo(e: MotionEvent) {
    }

    override fun panStop() {
        if (mode == MODE_EDIT_BY_TOUCH || mNeedSave) {
            mNeedSave = false
            undoRedoOverlay!!.saveToHistory(editLayerOverlay!!.selectedFeature)
        }
    }


    override fun onLocationChanged(location: Location?) {
        if (location != null) {

            if (tmpFirstLocation != null && tmpFirstLocation!!.latitude == location.latitude &&
                tmpFirstLocation!!.longitude == location.longitude )
                return
            else
                tmpFirstLocation = location


            if (mCurrentCenter == null) {
                mCurrentCenter = GeoPoint()
            }

            mCurrentCenter!!.setCoordinates(location.longitude, location.latitude)
            mCurrentCenter!!.crs = GeoConstants.CRS_WGS84

            if (!mCurrentCenter!!.project(GeoConstants.CRS_WEB_MERCATOR)) {
                mCurrentCenter = null
            }

            val isStanding =
                location == null || !location.hasBearing() || !location.hasSpeed() || location.getSpeed() == 0f

            mMapRef.get()!!.map!!.updateLocation(
                Point.fromLngLat(location.longitude, location.latitude),
                isStanding,
                location.bearing)

//            Log.e("TTRR", "location at" + ": " + location.longitude + " : " + location.latitude)


            if (TrackerService.hasUnfinishedTracks(context))
                mMapRef.get()!!.map!!.reloadCurrentTrackToMap()

//            Log.e("TTRR", "end olLocChange update---------------" )

            if (mode == MODE_EDIT_BY_WALK){
                if (location != null)
                    mMapRef.get()!!.map!!.addPointByWalk(LatLng(location.latitude, location.longitude));
            }
        }

        fillStatusPanel(location)
    }


    public fun reloadTracks(){

        if (mMapRef.get()!!.map!!.maplibreMap==null)
            return
        mMapRef.get()!!.map!!.reloadCurrentTrackToMap()
        mMapRef.get()!!.map!!.reloadTrackListToMap()


    }

    fun updateLastLocation(){

        if (mGpsEventSource == null)
            return
        val loc = mGpsEventSource!!.lastKnownLocation
        if (loc ==  null)
            return
        val isStanding = loc == null || !loc.hasBearing() || !loc.hasSpeed() || loc.getSpeed() == 0f

        mMapRef.get()!!.map!!.updateLocation(Point.fromLngLat(loc.longitude, loc.latitude),
            isStanding,
            loc.bearing
        )
    }

    override fun onBestLocationChanged(location: Location) {
    }

    private fun fillStatusPanel(location: Location?) {
        if (mStatusPanelMode == 0) return

        var panel = mStatusPanel!!.getChildAt(0)
        if (panel == null) {
            panel = mActivity!!.layoutInflater.inflate(R.layout.status_panel, mStatusPanel, false)
            defineTextViews(panel)
            fillTextViews(location)
            mStatusPanel!!.removeAllViews()
            panel.background.alpha = 128
            mStatusPanel!!.addView(panel)
        } else
            fillTextViews(location)
    }

    private fun fillTextViews(location: Location?) {
        if (null == location) {
            setDefaultTextViews()
        } else {
            if (location.provider == LocationManager.GPS_PROVIDER) {
                var text = ""
                val satellites = if (location.extras != null) location.extras!!
                    .getInt("satellites") else 0
                if (satellites > 0) text += satellites

                mStatusSource!!.text = text
                mStatusSource!!.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        activity!!,
                        com.nextgis.maplibui.R.drawable.ic_location
                    ), null, null, null
                )
            } else {
                mStatusSource!!.text = ""
                mStatusSource!!.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        activity!!,
                        com.nextgis.maplibui.R.drawable.ic_signal_wifi
                    ), null, null, null
                )
            }

            mStatusAccuracy!!.text = String.format(
                Locale.getDefault(),
                "%.1f %s", location.accuracy, getString(com.nextgis.maplib.R.string.unit_meter)
            )
            mStatusAltitude!!.text = String.format(
                Locale.getDefault(),
                "%.1f %s", location.altitude, getString(com.nextgis.maplib.R.string.unit_meter)
            )
            mStatusSpeed!!.text = String.format(
                Locale.getDefault(),
                "%.1f %s/%s",
                location.speed * 3600 / 1000,
                getString(com.nextgis.maplib.R.string.unit_kilometer),
                getString(com.nextgis.maplib.R.string.unit_hour)
            )
            mStatusLatitude!!.text =
                formatCoordinate(
                    location.latitude,
                    com.nextgis.maplibui.R.string.latitude_caption_short
                )
            mStatusLongitude!!.text =
                formatCoordinate(
                    location.longitude,
                    com.nextgis.maplibui.R.string.longitude_caption_short
                )
        }
    }


    private fun formatCoordinate(value: Double, appendix: Int): String {
        return LocationUtil.formatCoordinate(
            value,
            mCoordinatesFormat,
            mCoordinatesFraction
        ) + " " + getString(appendix)
    }


    private fun setDefaultTextViews() {
        mStatusSource!!.setCompoundDrawables(null, null, null, null)
        mStatusSource!!.text = ""
        mStatusAccuracy!!.text = getString(com.nextgis.maplibui.R.string.n_a)
        mStatusAltitude!!.text = getString(com.nextgis.maplibui.R.string.n_a)
        mStatusSpeed!!.text = getString(com.nextgis.maplibui.R.string.n_a)
        mStatusLatitude!!.text = getString(com.nextgis.maplibui.R.string.n_a)
        mStatusLongitude!!.text = getString(com.nextgis.maplibui.R.string.n_a)
    }


    private val isFitOneLine: Boolean
        get() {
            mStatusLongitude!!.measure(0, 0)
            mStatusLatitude!!.measure(0, 0)
            mStatusAltitude!!.measure(0, 0)
            mStatusSpeed!!.measure(0, 0)
            mStatusAccuracy!!.measure(0, 0)
            mStatusSource!!.measure(0, 0)

            val totalWidth =
                mStatusSource!!.measuredWidth + mStatusLongitude!!.measuredWidth +
                        mStatusLatitude!!.measuredWidth + mStatusAccuracy!!.measuredWidth +
                        mStatusSpeed!!.measuredWidth + mStatusAltitude!!.measuredWidth

            val metrics = DisplayMetrics()
            mActivity!!.windowManager.defaultDisplay.getMetrics(metrics)

            return totalWidth < metrics.widthPixels
            //        return totalWidth < mStatusPanel.getWidth();
        }


    private fun defineTextViews(panel: View) {
        mStatusSource = panel.findViewById(R.id.tv_source)
        mStatusAccuracy = panel.findViewById(R.id.tv_accuracy)
        mStatusSpeed = panel.findViewById(R.id.tv_speed)
        mStatusAltitude = panel.findViewById(R.id.tv_altitude)
        mStatusLatitude = panel.findViewById(R.id.tv_latitude)
        mStatusLongitude = panel.findViewById(R.id.tv_longitude)
        mZoom = panel.findViewById(R.id.tv_zoom)
        if (mZoom != null) mZoom!!.visibility = if (mPreferences!!.getBoolean(
                AppSettingsConstants.KEY_PREF_SHOW_ZOOM,
                false
            )
        ) View.VISIBLE else View.GONE
    }


    override fun onGpsStatusChanged(event: Int) {
    }


    override fun onStartEditSession() {
    }


    override fun onFinishEditSession() {
        setNewMode(MODE_NORMAL)
    }

    override fun onFinishEditByWalkSession() {
    }


    fun hideBottomBar() {
        mActivity!!.bottomToolbar.visibility = View.GONE
    }


    fun restoreBottomBar(mode: Int) {
        setNewMode(if (mode != -1) mode else this.mode)
    }


    fun addLocalTMSLayer(uri: Uri?) {
        if (null != mMapRef.get()) {
            mMapRef.get()!!.addLocalTMSLayer(uri)
        }
    }


    fun addLocalVectorLayer(uri: Uri?) {
        if (null != mMapRef.get()) {
            mMapRef.get()!!.addLocalVectorLayer(uri)
        }
    }


    fun addLocalVectorLayerWithForm(uri: Uri?) {
        if (null != mMapRef.get()) {
            mMapRef.get()!!.addLocalVectorLayerWithForm(uri)
        }
    }

    fun locateCurrentPosition() {
        if (mCurrentCenter != null && mMapRef.get()!!.map.maplibreMap!=null) {
            mMapRef.get()!!.panTo(mCurrentCenter)

            val lonLat = convert3857To4326(mCurrentCenter!!.x, mCurrentCenter!!.y);

            val targetPosition = CameraPosition.Builder()
                .target(LatLng(lonLat[1], lonLat[0]))
                .zoom(mMapRef.get()!!.map.maplibreMap.cameraPosition.zoom)
                .bearing(0.0)
                .tilt(0.0)
                .build()

                mMapRef.get()!!.map.maplibreMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(targetPosition),
                2000)
        } else {
            Toast.makeText(
                mActivity,
                com.nextgis.maplibui.R.string.error_no_location,
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    fun addNGWLayer() {
        if (null != mMapRef.get()) {
            mMapRef.get()!!.addNGWLayer()
        }
    }


    fun addRemoteLayer() {
        if (null != mMapRef.get()) {
            mMapRef.get()!!.addRemoteLayer()
        }
    }


    fun refresh() {
        if (null != mMapRef.get()) {
            mMapRef.get()!!.drawMapDrawable()
        }
    }

    val isDialogShown: Boolean
        get() = mChooseLayerDialogRef.get() != null && mChooseLayerDialogRef.get()!!.isResumed

    protected fun showFullCompass() {
        val fragmentManager = mActivity!!.supportFragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        val compassFragment = FullCompassFragment()
        compassFragment.setClickable(true)

        val container = R.id.mainview
        fragmentTransaction.add(container, compassFragment, "COMPASS_FULL")
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .addToBackStack(null)
            .commit()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.fl_compass -> showFullCompass()
            R.id.add_current_location -> {
                if (v.isEnabled) addCurrentLocation(true)
                    mMainButton!!.collapse()
            }
            R.id.add_new_geometry -> {
                if (v.isEnabled) addNewGeometry()
                    mMainButton!!.collapse()
            }
            R.id.add_geometry_by_walk -> {
                if (v.isEnabled)
                    addGeometryByWalk()
                mMainButton!!.collapse()
            }

            R.id.action_zoom_in -> {
                //if (v.isEnabled) mMapRef.get()!!.zoomIn() // old
                // test
                //mMapRef.get()!!.map!!.changePointColor()

                val currentZoom =  mMapRef.get()!!.map.maplibreMap.cameraPosition.zoom
                var newZoom = currentZoom + 1.0
                if (newZoom > mMapRef.get()!!.map.maplibreMap.maxZoomLevel)
                    newZoom = mMapRef.get()!!.map.maplibreMap.maxZoomLevel
                val cameraUpdate = CameraUpdateFactory.zoomTo(newZoom)
                mMapRef.get()!!.map.maplibreMap.animateCamera(cameraUpdate)
            }
            R.id.action_zoom_out -> {
                //if (v.isEnabled) mMapRef.get()!!.zoomOut()
                val currentZoom = mMapRef.get()!!.map.maplibreMap.cameraPosition.zoom
                var newZoom = currentZoom - 1.0
                if (newZoom < mMapRef.get()!!.map.maplibreMap.minZoomLevel)
                    newZoom = mMapRef.get()!!.map.maplibreMap.minZoomLevel
                val cameraUpdate = CameraUpdateFactory.zoomTo(newZoom)
                mMapRef.get()!!.map.maplibreMap.animateCamera(cameraUpdate)
            }

            R.id.add_point_by_tap -> if (mRulerOverlay!!.isMeasuring) {
                mRulerOverlay!!.stopMeasuring()
                showMainButton()
                showRulerButton()
                hideAddByTapButton()
                mAddPointButton!!.setIcon(com.nextgis.maplibui.R.drawable.ic_action_add_point)
                mActivity!!.title = mActivity!!.appName
                mActivity!!.setSubtitle(null)
                mMapRef.get()!!.map.stoptMeasuring()

            } else addPointByTap()

            R.id.action_ruler -> {
                startMeasuring()
                Toast.makeText(context, R.string.tap_to_measure, Toast.LENGTH_SHORT).show()
            }
        }
    }

    protected fun startMeasuring() {
        mRulerOverlay!!.startMeasuring(this, mCurrentCenter)
        hideOverlayPoint()
        hideMainButton()
        hideRulerButton()
        showAddByTapButton()
        mAddPointButton!!.setIcon(com.nextgis.maplibui.R.drawable.ic_action_apply_dark)
        mMapRef.get()!!.map.startMeasuring()
    }

    override fun onLengthChanged(length: Double) {
        mActivity!!.title = LocationUtil.formatLength(context, length, 3)
    }

    override fun onAreaChanged(area: Double) {
        mActivity!!.setSubtitle(LocationUtil.formatArea(context, area))
    }

    public companion object {
        const val MODE_NORMAL: Int = 0
        const val MODE_SELECT_ACTION: Int = 1
        const val MODE_EDIT: Int = 2
        const val MODE_INFO: Int = 3
        const val MODE_EDIT_BY_WALK: Int = 4
        public const val MODE_EDIT_BY_TOUCH: Int = 5
        const val MODE_SELECT_FOR_VIEW: Int = 6


        protected const val KEY_MODE: String = "mode"
        protected const val BUNDLE_KEY_LAYER: String = "layer"
        protected const val BUNDLE_KEY_FEATURE_ID: String = "feature"
        protected const val BUNDLE_KEY_SAVED_FEATURE: String = "feature_blob"
        protected const val BUNDLE_KEY_IS_MEASURING: String = "is_measuring"
        const val EDIT_LAYER: Int = 2
    }

    fun deleteFeature() {
        val selectedFeatureId = editLayerOverlay!!.selectedFeatureId
        val layer = mSelectedLayer

        val builder = AlertDialog.Builder(activity!!)
            .setTitle(com.nextgis.maplibui.R.string.delete_confirm_feature)
            .setMessage(com.nextgis.maplibui.R.string.delete_feature)
            .setPositiveButton(com.nextgis.maplibui.R.string.menu_delete) {
                dialog: DialogInterface?, which: Int ->
                val snackbar = Snackbar.make(activity!!.findViewById<View>(R.id.mainview), activity!!.getString(com.nextgis.maplibui.R.string.delete_item_done), Snackbar.LENGTH_LONG)
                    .setAction(com.nextgis.maplibui.R.string.undo) { v: View? ->
                        layer!!.showFeature(selectedFeatureId)
                        editLayerOverlay!!.setSelectedFeature(selectedFeatureId)
                        defineMenuItems()
                        mMapRef.get()!!.map.showFeatureFromHide(selectedFeatureId, layer!!.id,
                            mMapRef.get()!!.map!!.hiddedFeature)
                    }
                    .addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(snackbar: Snackbar, event: Int) {
                            super.onDismissed(snackbar, event)
                            if (event == DISMISS_EVENT_MANUAL)
                                return
                            if (event != DISMISS_EVENT_ACTION) {
                                layer!!.deleteAddChanges(selectedFeatureId)
                                mMapRef.get()!!.map!!.deleteFeature(selectedFeatureId, layer.id)
                            }
                        }

                        override fun onShown(snackbar: Snackbar) {
                            super.onShown(snackbar)
                        }
                    })
                mSelectedLayer!!.hideFeature(selectedFeatureId)
                mMapRef.get()!!.map.hideFeature(selectedFeatureId, layer!!.id)
                editLayerOverlay?.setSelectedFeature(null)
                defineMenuItems()

                val view = snackbar.view
                val textView = view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                textView.setTextColor(ContextCompat.getColor(mActivity!!, com.nextgis.maplibui.R.color.color_white))
                snackbar.show()
            }
            .setNegativeButton(
                com.nextgis.maplibui.R.string.cancel
            ) { dialog: DialogInterface?, which: Int -> }.create()
        builder.show()
    }

    fun loadJsonFromAssets(context: Context, fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            String(buffer, Charsets.UTF_8)
        } catch (ex: IOException) {
            ex.printStackTrace()
            null
        }
    }

    override
    fun setHasEdit() {
        editLayerOverlay!!.setHasEdits(true)
    }

    override
    fun updateActions(editObject: MLGeometryEditClass?){
        editLayerOverlay!!.updateActions(editObject)
    }

    override fun getMode(): Int {
        return mode;
    }

    override
    fun updateGeometryFromMaplibre(feature: org.maplibre.geojson.Feature?,
                                   originalSelectedFeature: Feature?,
                                   editObject: MLGeometryEditClass?) {
        if (feature == null || originalSelectedFeature == null)
            return
        originalSelectedFeature.geometry = getGeometryFromMaplibreGeometry(feature)
        editLayerOverlay!!.updateGeometryFromMaplibre(originalSelectedFeature.geometry)
        editLayerOverlay!!.fillDrawItems(originalSelectedFeature.geometry)
        /*
         val undoRedoFeature = undoRedoOverlay!!.feature
                    val feature = editLayerOverlay!!.selectedFeature
                    feature.geometry = undoRedoFeature.geometry
                    editLayerOverlay!!.fillDrawItems(undoRedoFeature.geometry)

                    val original = mSelectedLayer!!.getGeometryForId(feature.id)
                    val hasEdits = original != null && undoRedoFeature.geometry == original

        * */
        editLayerOverlay!!.updateActions(editObject)
        undoRedoOverlay!!.saveToHistory(originalSelectedFeature)
    }

    override fun getSelectedLayer(): VectorLayer? {
        return mSelectedLayer
    }

    override fun getGeometryFromMaplibreGeometry(feature: org.maplibre.geojson.Feature?) : GeoGeometry? {

        if (feature == null)
            return null;


        if (feature.geometry()!= null && feature.geometry() is MultiPolygon){
            val multipoly = feature.geometry() as MultiPolygon
            val geomultiPolygon = GeoMultiPolygon()
            for (poly in multipoly.coordinates()){
                val geoPolygon = GeoPolygon()
                geoPolygon.crs = GeoConstants.CRS_WEB_MERCATOR

                var iter = 0
                for (outer in poly){

                    if (iter == 0){ // outer ring
                        for (outer2 in outer){
                            val points: DoubleArray = convert4326To3857(outer2.longitude(), outer2.latitude())
                            val geopoint = GeoPoint(points[0], points[1])
                            geopoint.crs = GeoConstants.CRS_WEB_MERCATOR
                            geoPolygon.add(geopoint)
                        }
                    } else {
                        // inner
                        val ring = GeoLinearRing()

                        for (outer2 in outer){
                            val points: DoubleArray = convert4326To3857(outer2.longitude(), outer2.latitude())
                            val geopoint = GeoPoint(points[0], points[1])
                            geopoint.crs = GeoConstants.CRS_WEB_MERCATOR
                            ring.add(geopoint)
                        }
                        geoPolygon.addInnerRing(ring)
                    }
                    iter++
                }

                geomultiPolygon.add(geoPolygon)
            }
            return geomultiPolygon
        }



        if (feature.geometry()!= null && feature.geometry() is Polygon){
            val poly = feature.geometry() as Polygon

            val geoPolygon = GeoPolygon()
            geoPolygon.crs = GeoConstants.CRS_WEB_MERCATOR

            var iter = 0
            for (outer in poly.coordinates()){

                if (iter == 0){ // outer ring
                    for (outer2 in outer){
                        val points: DoubleArray = convert4326To3857(outer2.longitude(), outer2.latitude())
                        val geopoint = GeoPoint(points[0], points[1])
                        geopoint.crs = GeoConstants.CRS_WEB_MERCATOR
                        geoPolygon.add(geopoint)
                    }
                } else {
                    // inner
                    val ring = GeoLinearRing()

                    for (outer2 in outer){
                        val points: DoubleArray = convert4326To3857(outer2.longitude(), outer2.latitude())
                        val geopoint = GeoPoint(points[0], points[1])
                        geopoint.crs = GeoConstants.CRS_WEB_MERCATOR
                        ring.add(geopoint)
                    }
                        geoPolygon.addInnerRing(ring)
                }
                iter++
            }
            return geoPolygon
        }
        if (feature.geometry()!= null && feature.geometry() is Point){
            val point = feature.geometry() as Point

            val geoPoint = GeoPoint()
            geoPoint.crs = GeoConstants.CRS_WEB_MERCATOR
            val points: DoubleArray = convert4326To3857(point.longitude(), point.latitude())
            geoPoint.setCoordinates(points[0], points[1])
            return geoPoint
        }
        if (feature.geometry()!= null && feature.geometry() is MultiPoint){
            val geoPoint = GeoMultiPoint()

            val multiPoint = feature.geometry() as MultiPoint
            for ( point in multiPoint.coordinates()){
                val newPoint = GeoPoint()

                val points: DoubleArray = convert4326To3857(point.longitude(), point.latitude())
                newPoint.setCoordinates(points[0], points[1])
                newPoint.crs = GeoConstants.CRS_WEB_MERCATOR
                geoPoint.add(newPoint)
            }
            geoPoint.crs = GeoConstants.CRS_WEB_MERCATOR
            return geoPoint
        }


        if (feature.geometry()!= null && feature.geometry() is LineString){

            val geoLineObj = GeoLineString()
            geoLineObj.crs = GeoConstants.CRS_WEB_MERCATOR

            val poly = feature.geometry() as LineString

            val geoLine = GeoLineString()
            geoLine.crs = GeoConstants.CRS_WEB_MERCATOR

            for (outer in poly.coordinates()){
                val points: DoubleArray = convert4326To3857(outer.longitude(), outer.latitude())
                val geopoint = GeoPoint(points[0], points[1])
                geopoint.crs = GeoConstants.CRS_WEB_MERCATOR
                geoLineObj.add(geopoint)
            }
            return geoLineObj
        }

        if (feature.geometry()!= null && feature.geometry() is MultiLineString){
            val geoMultiLineObj = GeoMultiLineString()
            geoMultiLineObj.crs = GeoConstants.CRS_WEB_MERCATOR

            val geoMultiLine = feature.geometry() as MultiLineString
            for (line in geoMultiLine.lineStrings()){

                val geoLineObj = GeoLineString()
                geoLineObj.crs = GeoConstants.CRS_WEB_MERCATOR

                val poly = line as LineString

                val geoLine = GeoLineString()
                geoLine.crs = GeoConstants.CRS_WEB_MERCATOR

                for (outer in poly.coordinates()){
                    val points: DoubleArray = convert4326To3857(outer.longitude(), outer.latitude())
                    val geopoint = GeoPoint(points[0], points[1])
                    geopoint.crs = GeoConstants.CRS_WEB_MERCATOR
                    geoLineObj.add(geopoint)
                }
                geoMultiLineObj.add(geoLineObj)
            }
            return geoMultiLineObj
        }

        return null;
    }


    private fun convert4326To3857(lon: Double, lat: Double): DoubleArray {
        val x = lon * 20037508.34 / 180
        val y = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * 20037508.34 / Math.PI
        return doubleArrayOf(x, y)
    }

    fun convert3857To4326(x: Double, y: Double): DoubleArray {
        val lon = x * 180 / 20037508.34
        val lat = Math.toDegrees(atan(sinh(y * Math.PI / 20037508.34)))
        return doubleArrayOf(lon, lat)
    }

    fun getHintText(vectorLayer: VectorLayer, feature: Feature?):String? {

        if (feature == null)
            return null

        var fieldToDisplay = ((vectorLayer.renderer as SimpleFeatureRenderer).style).field

        if (!TextUtils.isEmpty(fieldToDisplay) && fieldToDisplay.equals(id_name)){ // id of feature
            return feature.id.toString()
        }

        if (TextUtils.isEmpty(fieldToDisplay)) {
            fieldToDisplay = if (vectorLayer.fields.size > 0 && vectorLayer.fields[0] != null )  vectorLayer.fields [0].name else ""
        }

        val objectValueForHint = feature.getFieldValue((fieldToDisplay))
        var valueForHint = "";
        if (objectValueForHint == null ) {
            valueForHint = feature.id.toString()
        } else
            valueForHint = objectValueForHint.toString()

        return valueForHint
    }

    override fun onCameraIdle() {

        val zoom = getCurrentZoom()
        setMapLibreZoomInEnabled()
        setMapLibreZoomOutEnabled()
        mScaleRulerText!!.text = rulerText
        if (mZoom != null)
            mZoom!!.text = zoomText
    }

    private inner class MessageStyling : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent ){
            if (intent.action == MESSAGE_INTENT_STYLING) {
                val textmessage = intent.getStringExtra(ConstantsUI.KEY_MESSAGE);
                textStylingProgrerss?.setText(textmessage)
            }
        }
        //            if (intent.action == MESSAGE_INTENT_STYLING_RASTER) {
//                // raster
//                val layerId: Int? = intent.getIntExtra(LAYER_ID_KEY, -1)
//                val alpha = intent.getIntExtra(FIELD_ALPHA, 0)
//                val contrast = intent.getFloatExtra(FIELD_CONTRAST, 0f)
//                val brightnessMin = intent.getFloatExtra(FIELD_BRIGHTNESS_MIN, 0f)
//                val brightnessMax = intent.getFloatExtra(FIELD_BRIGHTNESS_MAX, 1f)
//
//
//
//                mMapRef.get()!!.map!!.updateRasterLayerProperties(layerId as Int?, alpha, contrast, brightnessMin,
//                    brightnessMax)
//            }
    }


    private inner class MessageReloadLayer : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent ){
            if (intent.action == MESSAGE_INTENT_RELOAD) {
                val layerid = intent.getIntExtra(ConstantsUI.KEY_LAYER_ID, -1);
                (context.applicationContext  as IGISApplication).removeLayerToRefresh(layerid)
                if (mMapRef.get()!= null && mMapRef.get()!!.map!= null && layerid != -1){

                    if (mMapRef.get()!!.map!!.getLayerVisible(layerid) == true) {
                        Handler().postDelayed({
                            mMapRef.get()!!.map!!.refreshLayerVisibility(layerid, false)
                        }, 300)

                        Handler().postDelayed({
                            mMapRef.get()!!.map!!.refreshLayerVisibility(layerid, true)
                        }, 600)
                    }
                }
            }
        }
    }

    public fun getLayerFeaturesML(layer: VectorLayer): List<org.maplibre.geojson.Feature>? {
            return mMapRef.get()!!.map!!.getLayerFeatures(layer)
        }




}
