/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2015-2020 NextGIS, info@nextgis.com
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

package com.nextgis.mobile.fragment;

import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import android.text.Html;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.keenfin.easypicker.AttachInfo;
import com.keenfin.easypicker.DownloadPhotoIntentService;
import com.keenfin.easypicker.PhotoPicker;
import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoGeometryFactory;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoMultiLineString;
import com.nextgis.maplib.datasource.GeoMultiPoint;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.datasource.ngw.Connections;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.map.VectorLayer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.LocationUtil;
import com.nextgis.maplibui.GISApplication;
import com.nextgis.maplibui.api.IVectorLayerUI;
import com.nextgis.maplibui.control.PhotoGallery;
import com.nextgis.maplibui.dialog.SelectNGWResourceDialog;
import com.nextgis.maplibui.fragment.BottomToolbar;
import com.nextgis.maplibui.overlay.EditLayerOverlay;
import com.nextgis.maplibui.util.ControlHelper;
import com.nextgis.maplibui.util.SettingsConstantsUI;
import com.nextgis.mobile.R;
import com.nextgis.mobile.activity.MainActivity;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.keenfin.easypicker.DownloadPhotoIntentService.DOWNLOAD_ACTION;
import static com.keenfin.easypicker.DownloadPhotoIntentService.getReceiverIntent;
import static com.nextgis.maplib.util.Constants.MESSAGE_ALERT_INTENT;
import static com.nextgis.maplib.util.GeoConstants.CRS_WEB_MERCATOR;
import static com.nextgis.maplib.util.GeoConstants.CRS_WGS84;
import static com.nextgis.maplib.util.GeoConstants.FTDate;
import static com.nextgis.maplib.util.GeoConstants.FTDateTime;
import static com.nextgis.maplib.util.GeoConstants.FTTime;
import static com.nextgis.maplib.util.GeoConstants.GTLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPoint;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPolygon;
import static com.nextgis.maplib.util.GeoConstants.GTPoint;
import static com.nextgis.maplib.util.GeoConstants.GTPolygon;
import static com.nextgis.maplib.util.NetworkUtil.URL_PATTERN;
import static com.nextgis.maplib.util.NetworkUtil.getHttpConnection;
import static com.nextgis.mobile.util.AppConstants.DEFAULT_COORDINATES_FRACTION_DIGITS;


public class AttributesFragment
        extends Fragment
{
    protected static final String KEY_ITEM_ID       = "item_id";
    protected static final String KEY_ITEM_POSITION = "item_pos";
    protected static final String KEY_READ_ONLY     = "read_only";

    private LinearLayout    mAttributes;
    private VectorLayer     mLayer;
    private List<Long>      mFeatureIDs;

    private long        mItemId;
    private int         mItemPosition;
    private boolean     mIsTablet;

    protected EditLayerOverlay mEditLayerOverlay;
    protected Menu mBottomMenu;

    private boolean readOnly = true;

    MessageReceiver messageReceiver;
    WeakReference<PhotoPicker> photoPickerWeakReference = new WeakReference<>(null);

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState) {
        if (mLayer == null) {
            getActivity().getSupportFragmentManager().popBackStack();
            Toast.makeText(getContext(), com.nextgis.maplibui.R.string.error_layer_not_inited, Toast.LENGTH_SHORT).show();
            return null;
        }

        if (messageReceiver == null)
            messageReceiver = new MessageReceiver(){
                @Override
            public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(DOWNLOAD_ACTION)) {
                        if (photoPickerWeakReference != null &&  photoPickerWeakReference.get() != null){
                            photoPickerWeakReference.get().updateStatus(intent);
                        }
                    }
                }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(messageReceiver, getReceiverIntent(), Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(messageReceiver, getReceiverIntent());
        }

        readOnly = getArguments().getBoolean(KEY_READ_ONLY,  true);

        getActivity().setTitle(mLayer.getName());
        setHasOptionsMenu(!isTablet());

        int resId = isTablet() ? R.layout.fragment_attributes_tab : R.layout.fragment_attributes;
        View view = inflater.inflate(resId, container, false);

        if (isTablet()) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(view.getLayoutParams());
            Display display = getActivity().getWindowManager().getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getMetrics(metrics);
            lp.width = metrics.widthPixels / 2;

            int[] attrs = {android.R.attr.actionBarSize};
            TypedArray ta = getActivity().obtainStyledAttributes(attrs);
            lp.bottomMargin = ta.getDimensionPixelSize(0, 0);
            ta.recycle();

            view.setLayoutParams(lp);
        }

        mAttributes = view.findViewById(R.id.ll_attributes);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        setAttributes();
        ((MainActivity) getActivity()).setActionBarState(isTablet());
    }

    @Override
    public void onPause() {
        if (messageReceiver != null) {
            getContext().unregisterReceiver(messageReceiver);
            messageReceiver = null;
        }
        super.onPause();
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu)
    {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (item.getItemId() == R.id.menu_about || item.getItemId() == R.id.menu_settings) {
                continue;
            }
            item.setVisible(false);
        }
        super.onPrepareOptionsMenu(menu);
    }


    @Override
    public void onDestroyView()
    {
        ((MainActivity) getActivity()).restoreBottomBar(readOnly ? MapFragment.MODE_SELECT_FOR_VIEW : MapFragment.MODE_SELECT_ACTION);
        super.onDestroyView();
    }


    public void setSelectedFeature(
            VectorLayer selectedLayer,
            long selectedItemId)
    {
        mItemId = selectedItemId;
        mLayer = selectedLayer;

        if (mLayer == null)
            return;

        mFeatureIDs = mLayer.query(null); // get all feature IDs

        for (int i = 0; i < mFeatureIDs.size(); i++) {
            if (mFeatureIDs.get(i) == mItemId) {
                mItemPosition = i;
                break;
            }
        }

        setAttributes();
    }


    private void setAttributes()
    {
        if (mAttributes == null)
            return;

        mAttributes.removeAllViews();

        int[] attrs = new int[] {android.R.attr.textColorPrimary};
        TypedArray ta = getContext().obtainStyledAttributes(attrs);
        String textColor = Integer.toHexString(ta.getColor(0, Color.BLACK)).substring(2);
        ta.recycle();

        final WebView webView = new WebView(getContext());
        webView.setVerticalScrollBarEnabled(false);
        String data = "<!DOCTYPE html><html><head><meta charset='utf-8'><style>body{word-wrap:break-word;color:#" + textColor + ";font-family:Roboto Light,sans-serif;font-weight:300;line-height:1.15em}.flat-table{table-layout:fixed;margin-bottom:20px;width:100%;border-collapse:collapse;border:none;box-shadow:inset 1px -1px #ccc,inset -1px 1px #ccc}.flat-table td{box-shadow:inset -1px -1px #ccc,inset -1px -1px #ccc;padding:.5em}.flat-table tr{-webkit-transition:background .3s,box-shadow .3s;-moz-transition:background .3s,box-shadow .3s;transition:background .3s,box-shadow .3s}</style></head><body><table class='flat-table'><tbody>";

        FragmentActivity activity = getActivity();
        if (null == activity)
            return;

        ((MainActivity) activity).setSubtitle(String.format(getString(R.string.features_count_attributes), mItemPosition + 1, mFeatureIDs.size()));
        checkNearbyItems();

        try {
            data = parseAttributes(data);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }

        data += "</tbody></table></body></html>";
        webView.loadDataWithBaseURL(null, data, "text/html", "UTF-8", null);
        mAttributes.addView(webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                webView.setBackgroundColor(Color.TRANSPARENT);
            }
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                try {
                    view.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(view.getContext(), "No app found to open this link", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        IGISApplication app = (GISApplication) getActivity().getApplication();

        final List<AttachInfo> mAttaches = new ArrayList<>();
        PhotoGallery.getOfflineAttaches(app, mLayer, mItemId, mAttaches, false, null);

        Map<String, AttachInfo> onlineAttaches =  PhotoGallery.getOnlineAttaches(app, mLayer, mItemId);

        if (mAttaches.size() > 0 || onlineAttaches.size()>0 ) {

            AccountManager accountManager = AccountManager.get(getActivity());
            Connections connections = SelectNGWResourceDialog.fillConnections(getActivity(), accountManager);
            Connection found = null;
            if (mLayer instanceof  NGWVectorLayer) {
                for (int i = 0; i < connections.getChildrenCount(); i++) {
                    if (connections.getChild(i).getName().equals((((NGWVectorLayer) mLayer).getAccountName()))) {
                        found = (Connection) connections.getChild(i);
                    }
                }
            }

            final PhotoPicker gallery = new PhotoPicker(getActivity(), true, found== null? "" :found.getLogin(), found== null? "" :found.getPassword());
            int px = ControlHelper.dpToPx(16, getResources());
            gallery.setDefaultPreview(true);
            gallery.setPadding(px, 0, px, 0);
            gallery.post(new Runnable() {
                @Override
                public void run() {
                    ArrayList<String> item = DownloadPhotoIntentService.getProgressList();
                    gallery.updateInProgressList(item);
                    gallery.restoreImages(mAttaches, onlineAttaches);
                }
            });

            photoPickerWeakReference = new WeakReference<>(gallery);

            mAttributes.addView(gallery);
        }
    }

    private String parseAttributes(String data) throws RuntimeException {
        String selection = Constants.FIELD_ID + " = ?";
        Cursor attributes = mLayer.query(null, selection, new String[]{mItemId + ""}, null, null);
        if (null == attributes || attributes.getCount() == 0)
            return data;

        if (attributes.moveToFirst()) {
            StringBuilder dataBuilder = new StringBuilder(data);
            for (int i = 0; i < attributes.getColumnCount(); i++) {
                String column = attributes.getColumnName(i);
                String text, alias;

                if (column.startsWith(Constants.FIELD_GEOM_))
                    continue;

                if (column.equals(Constants.FIELD_GEOM)) {
                    switch (mLayer.getGeometryType()) {
                        case GTPoint:
                            try {
                                GeoPoint pt = (GeoPoint) GeoGeometryFactory.fromBlob(attributes.getBlob(i));
                                dataBuilder.append(getRow(getString(R.string.coordinates), formatCoordinates(pt)));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            continue;
                        case GTMultiPoint:
                            try {
                                GeoMultiPoint mpt = (GeoMultiPoint) GeoGeometryFactory.fromBlob(attributes.getBlob(i));
                                dataBuilder.append(getRow(getString(R.string.center), formatCoordinates(mpt.getEnvelope().getCenter())));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            continue;
                        case GTLineString:
                            try {
                                GeoLineString line = (GeoLineString) GeoGeometryFactory.fromBlob(attributes.getBlob(i));
                                dataBuilder.append(getRow(getString(R.string.length), LocationUtil.formatLength(getContext(), line.getLength(), 3)));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            continue;
                        case GTMultiLineString:
                            try {
                                GeoMultiLineString multiline = (GeoMultiLineString) GeoGeometryFactory.fromBlob(attributes.getBlob(i));
                                dataBuilder.append(getRow(getString(R.string.length), LocationUtil.formatLength(getContext(), multiline.getLength(), 3)));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            continue;
                        case GTPolygon:
                            try {
                                GeoPolygon polygon = (GeoPolygon) GeoGeometryFactory.fromBlob(attributes.getBlob(i));
                                dataBuilder.append(getRow(getString(R.string.perimeter), LocationUtil.formatLength(getContext(), polygon.getPerimeter(), 3)));
                                dataBuilder.append(getRow(getString(R.string.area), LocationUtil.formatArea(getContext(), polygon.getArea())));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            continue;
                        case GTMultiPolygon:
                            try {
                                GeoMultiPolygon polygon = (GeoMultiPolygon) GeoGeometryFactory.fromBlob(attributes.getBlob(i));
                                dataBuilder.append(getRow(getString(R.string.perimeter), LocationUtil.formatLength(getContext(), polygon.getPerimeter(), 3)));
                                dataBuilder.append(getRow(getString(R.string.area), LocationUtil.formatArea(getContext(), polygon.getArea())));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            continue;
                        default:
                            continue;
                    }
                }

                Field field = mLayer.getFieldByName(column);
                int fieldType = field != null ? field.getType() : Constants.NOT_FOUND;
                switch (fieldType) {
                    case GeoConstants.FTInteger:
                        text = attributes.getInt(i) + "";
                        break;
                    case GeoConstants.FTLong:
                        text = attributes.getLong(i) + "";
                        break;
                    case GeoConstants.FTReal:
                        NumberFormat nf = NumberFormat.getInstance();
                        nf.setMaximumFractionDigits(4);
                        nf.setGroupingUsed(false);
                        Double value = attributes.getDouble(i);
                        if (value.isNaN())
                            continue;
                        text = nf.format(value);
                        break;
                    case GeoConstants.FTDate:
                    case GeoConstants.FTTime:
                    case GeoConstants.FTDateTime:
                        text = formatDateTime(attributes.getLong(i), fieldType);
                        break;
                    default:
                        text = toString(attributes.getString(i));
                        Pattern pattern = Pattern.compile(URL_PATTERN);
                        Matcher match = pattern.matcher(text);
                        while (match.matches()) {
                            String url = text.substring(match.start(), match.end());
                            text = text.replaceFirst(URL_PATTERN, "<a href = '" + url + "'>" + url + "</a>");
                            match = pattern.matcher(text.substring(match.start() + url.length() * 2 + 17));
                        }
                        break;
                }

                if (field != null)
                    alias = field.getAlias();
                else if (column.equals(Constants.FIELD_ID))
                    alias = Constants.FIELD_ID;
                else
                    alias = "";

                dataBuilder.append(getRow(alias, text));
            }
            data = dataBuilder.toString();
        }

        attributes.close();
        return data;
    }


    protected String getRow(String column, String text) {
        column = column == null ? "" : toString(column);
        text = text == null ? "" : text;
        return String.format("<tr><td>%s</td><td>%s</td></tr><tr>", column, text);
    }


    protected String toString(String text) {
        return text == null ? "" : Html.fromHtml(text).toString();
    }


    protected String formatCoordinates(GeoPoint pt) {
        pt.setCRS(CRS_WEB_MERCATOR);
        pt.project(CRS_WGS84);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        int format = Integer.parseInt(prefs.getString(SettingsConstantsUI.KEY_PREF_COORD_FORMAT, Location.FORMAT_DEGREES + ""));
        int fraction = prefs.getInt(SettingsConstantsUI.KEY_PREF_COORD_FRACTION, DEFAULT_COORDINATES_FRACTION_DIGITS);

        String lat = getString(com.nextgis.maplibui.R.string.latitude_caption_short) + ": " +
                LocationUtil.formatLatitude(pt.getY(), format, fraction, getResources());
        String lon = getString(com.nextgis.maplibui.R.string.longitude_caption_short) + ": " +
                LocationUtil.formatLongitude(pt.getX(), format, fraction, getResources());

        return lat + "<br \\>" + lon;
    }


    public static String formatDateTime(long millis, int type) {
        String result = millis + "";
        SimpleDateFormat sdf = null;

        switch (type) {
            case FTDate:
                sdf = (SimpleDateFormat) DateFormat.getDateInstance();
                break;
            case FTTime:
                sdf = (SimpleDateFormat) DateFormat.getTimeInstance();
                break;
            case FTDateTime:
                sdf = (SimpleDateFormat) DateFormat.getDateTimeInstance();
                break;
        }

        if (sdf != null)
            try {
                result = sdf.format(new Date(millis));
            } catch (Exception e) {
                e.printStackTrace();
            }

        return result;
    }


    private void checkNearbyItems() {
        boolean hasNext = mItemPosition + 1 <= mFeatureIDs.size() - 1;
        boolean hasPrevious = mItemPosition - 1 >= 0;

        if (mBottomMenu != null) {
            ControlHelper.setEnabled(mBottomMenu.findItem(R.id.menu_prev), hasPrevious);
            ControlHelper.setEnabled(mBottomMenu.findItem(R.id.menu_next), hasNext);
        }
    }


    public void selectItem(boolean isNext)
    {
        boolean hasItem = false;

        if (isNext) {
            if (mItemPosition < mFeatureIDs.size() - 1) {
                mItemPosition++;
                hasItem = true;
            }
        } else {
            if (mItemPosition > 0) {
                mItemPosition--;
                hasItem = true;
            }
        }

        if (hasItem) {
            mItemId = mFeatureIDs.get(mItemPosition);
            setAttributes();
            if (null != mEditLayerOverlay) {
                mEditLayerOverlay.setSelectedFeature(mItemId);
            }
        }
    }


    @Override
    public void onSaveInstanceState(Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putLong(KEY_ITEM_ID, mItemId);
        outState.putInt(KEY_ITEM_POSITION, mItemPosition);
    }


    @Override
    public void onViewStateRestored(
            @Nullable
            Bundle savedInstanceState)
    {
        super.onViewStateRestored(savedInstanceState);

        if (savedInstanceState != null) {
            mItemId = savedInstanceState.getLong(KEY_ITEM_ID);
            mItemPosition = savedInstanceState.getInt(KEY_ITEM_POSITION);
        }
    }


    public void setTablet(boolean tablet)
    {
        mIsTablet = tablet;
    }


    public boolean isTablet()
    {
        return mIsTablet;
    }


    public void setToolbar(
            final BottomToolbar toolbar,
            EditLayerOverlay overlay,
            boolean readOnly)
    {
        if (null == mLayer)
            return;

        mEditLayerOverlay = overlay;

        if (!isTablet())
            toolbar.getBackground().setAlpha(255);

        mBottomMenu = toolbar.getMenu();
        if (mBottomMenu != null)
            mBottomMenu.clear();

        toolbar.inflateMenu(R.menu.attributes);
        if (readOnly) {

//            toolbar.findViewById(R.id.menu_edit_attributes).setVisibility(View.GONE);
//            toolbar.getMenu().findItem(R.id.menu_edit_attributes).setEnabled(false);
            toolbar.getMenu().findItem(R.id.menu_edit_attributes).setVisible(false);

        }

        toolbar.setOnMenuItemClickListener(
                new BottomToolbar.OnMenuItemClickListener()
                {
                    @Override
                    public boolean onMenuItemClick(MenuItem menuItem)
                    {
                        if (null == mLayer) {
                            return false;
                        }
                        if (menuItem.getItemId() == R.id.menu_next) {
                            selectItem(true);
                            return true;
                        } else if (menuItem.getItemId() == R.id.menu_prev) {
                            selectItem(false);
                            return true;
                        } else if (menuItem.getItemId() == R.id.menu_edit_attributes && !readOnly) {
                            IVectorLayerUI vectorLayerUI = (IVectorLayerUI) mLayer;
                            if (null != vectorLayerUI)
                                vectorLayerUI.showEditForm(getActivity(), mItemId, null,  -1);
                            return true;
                        }

                        return true;
                    }
                });

        checkNearbyItems();
    }

    public class MessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {}
    }

}