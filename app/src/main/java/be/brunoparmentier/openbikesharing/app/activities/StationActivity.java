/*
 * Copyright (c) 2014-2015 Bruno Parmentier.
 * Copyright (c) 2020 François FERREIRA DE SOUSA.
 *
 * This file is part of BikeSharingHub.
 * BikeSharingHub incorporates a modified version of OpenBikeSharing
 *
 * BikeSharingHub is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BikeSharingHub is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BikeSharingHub.  If not, see <http://www.gnu.org/licenses/>.
 */

package be.brunoparmentier.openbikesharing.app.activities;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.modules.SqlTileWriter;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicyException;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import be.brunoparmentier.openbikesharing.app.R;
import be.brunoparmentier.openbikesharing.app.db.StationsDataSource;
import be.brunoparmentier.openbikesharing.app.models.Station;
import be.brunoparmentier.openbikesharing.app.models.StationStatus;
import be.brunoparmentier.openbikesharing.app.widgets.StationsListAppWidgetProvider;

import fr.fdesousa.bikesharinghub.tilesource.CustomTileSource;

public class StationActivity extends Activity {

    private static final String TAG = "StationActivity";

    private static final String PREF_KEY_MAP_CACHE_MAX_SIZE = "pref_map_tiles_cache_max_size";
    private static final String PREF_KEY_MAP_CACHE_TRIM_SIZE = "pref_map_tiles_cache_trim_size";
    private static final String PREF_KEY_MAP_LAYER = "pref_map_layer";
    private static final String KEY_STATION = "station";
    private static final String MAP_LAYER_MAPNIK = "mapnik";
    private static final String MAP_LAYER_CYCLEMAP = "cyclemap";
    private static final String MAP_LAYER_OSMPUBLICTRANSPORT = "osmpublictransport";

    private SharedPreferences settings;
    private Station station;
    private MapView map;
    private IMapController mapController;
    private MenuItem favStar;
    private StationsDataSource stationsDataSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_station);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        stationsDataSource = new StationsDataSource(this);

        settings = PreferenceManager.getDefaultSharedPreferences(this);

        station = (Station) getIntent().getSerializableExtra(KEY_STATION);

        final Context context = getApplicationContext();
        long systemCacheMaxBytes = 1024 * 1024 * Long.valueOf(settings.getString(PREF_KEY_MAP_CACHE_MAX_SIZE, "100"));
        long systemCacheTrimBytes = 1024 * 1024 * Long.valueOf(settings.getString(PREF_KEY_MAP_CACHE_TRIM_SIZE, "100"));
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(systemCacheMaxBytes);
        Configuration.getInstance().setTileFileSystemCacheTrimBytes(systemCacheTrimBytes);
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context));

        map = (MapView) findViewById(R.id.mapView);
        final GeoPoint stationLocation = new GeoPoint((int) (station.getLatitude() * 1000000),
                (int) (station.getLongitude() * 1000000));

        try {
            CacheManager mCacheManager = new CacheManager(map);
            long cacheUsed = mCacheManager.currentCacheUsage();

            // If map cache is too big, launch cleaning in another thread because it may take a lot of time
            if(cacheUsed > Configuration.getInstance().getTileFileSystemCacheMaxBytes()) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        StationActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(StationActivity.this,
                                    getString(R.string.map_cache_cleaning_started),
                                    Toast.LENGTH_LONG).show();
                            }
                        });
                        SqlTileWriter sqlTileWriter = new SqlTileWriter();
                        sqlTileWriter.runCleanupOperation();
                        sqlTileWriter.onDetach();
                        StationActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(StationActivity.this,
                                    getString(R.string.map_cache_cleaning_done),
                                    Toast.LENGTH_SHORT).show();
                            }
                        });
                        Log.d(TAG, "Map cache has been cleaned");
                    }
                }).start();
            }
        } catch (TileSourcePolicyException e) {
            Log.e(TAG, "Enable to access cache manager, map cache could not be cleaned.");
            e.printStackTrace();
        }

        mapController = map.getController();
        mapController.setZoom(16);

        /* map tile source */
        String mapLayer = settings.getString(PREF_KEY_MAP_LAYER, "");
        switch (mapLayer) {
            case MAP_LAYER_MAPNIK:
                map.setTileSource(TileSourceFactory.MAPNIK);
                break;
            case MAP_LAYER_CYCLEMAP:
                map.setTileSource(CustomTileSource.CYCLOSM);
                break;
            case MAP_LAYER_OSMPUBLICTRANSPORT:
                map.setTileSource(CustomTileSource.OPNVKARTE);
                break;
            default:
                CustomTileSource customFactory = new CustomTileSource();
                map.setTileSource(customFactory.getDefaultTileSource());
                break;
        }

        map.setMultiTouchControls(true);

        /* Station marker */
        Marker marker = new Marker(map);
        marker.setPosition(stationLocation);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setOnMarkerClickListener(new Marker.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(Marker marker, MapView mapView) {
                return false;
            }
        });

        /* Marker icon */
        int emptySlots = station.getEmptySlots();
        int freeBikes = station.getFreeBikes();

        if ((emptySlots == 0 && freeBikes == 0) || station.getStatus() == StationStatus.CLOSED) {
            marker.setIcon(getResources().getDrawable(R.drawable.ic_station_marker_unavailable));
        } else {
            double ratio = (double) freeBikes / (double) (freeBikes + emptySlots);
            if (freeBikes == 0) {
                marker.setIcon(getResources().getDrawable(R.drawable.ic_station_marker0));
            } else if (freeBikes >= 1 && ratio <= 0.3) {
                marker.setIcon(getResources().getDrawable(R.drawable.ic_station_marker25));
            } else if (ratio > 0.3 && ratio < 0.7) {
                marker.setIcon(getResources().getDrawable(R.drawable.ic_station_marker50));
            } else if (ratio >= 0.7 && emptySlots >= 1) {
                marker.setIcon(getResources().getDrawable(R.drawable.ic_station_marker75));
            } else if (emptySlots == 0 || emptySlots == -1) {
                marker.setIcon(getResources().getDrawable(R.drawable.ic_station_marker100));
            }
        }

        map.getOverlays().add(marker);
        map.getOverlays().add(new CopyrightOverlay(context));

        TextView stationName = (TextView) findViewById(R.id.stationName);
        TextView stationEmptySlots = (TextView) findViewById(R.id.stationEmptySlots);
        TextView stationFreeBikes = (TextView) findViewById(R.id.stationFreeBikes);

        stationName.setText(station.getName());
        setLastUpdateText(station.getLastUpdate());
        stationFreeBikes.setText(String.valueOf(station.getFreeBikes()));
        if (station.getEmptySlots() == -1) {
            ImageView stationEmptySlotsLogo = (ImageView) findViewById(R.id.stationEmptySlotsLogo);
            stationEmptySlots.setVisibility(View.GONE);
            stationEmptySlotsLogo.setVisibility(View.GONE);
        } else {
            stationEmptySlots.setText(String.valueOf(station.getEmptySlots()));
        }

        if (station.getAddress() != null) {
            TextView stationAddress = (TextView) findViewById(R.id.stationAddress);
            stationAddress.setText(station.getAddress());
            stationAddress.setVisibility(View.VISIBLE);
        }

        /* extra info on station */
        Boolean isBankingStation = station.isBanking();
        Boolean isBonusStation = station.isBonus();
        StationStatus stationStatus = station.getStatus();
        Integer stationEBikes = station.getEBikes();

        if (isBankingStation != null) {
            ImageView stationBanking = (ImageView) findViewById(R.id.stationBanking);
            stationBanking.setVisibility(View.VISIBLE);
            if (isBankingStation) {
                stationBanking.setImageDrawable(getResources().getDrawable(R.drawable.ic_banking_on));
                stationBanking.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Toast.makeText(getApplicationContext(),
                                getString(R.string.cards_accepted),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        if (isBonusStation != null) {
            ImageView stationBonus = (ImageView) findViewById(R.id.stationBonus);
            stationBonus.setVisibility(View.VISIBLE);
            if (isBonusStation) {
                stationBonus.setImageDrawable(getResources().getDrawable(R.drawable.ic_bonus_on));
                stationBonus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Toast.makeText(getApplicationContext(),
                                getString(R.string.is_bonus_station),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        if ((stationStatus != null) && stationStatus == StationStatus.CLOSED) {
            stationName.setPaintFlags(stationName.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }

        if (stationEBikes != null) {
            ImageView eBikesLogo = (ImageView) findViewById(R.id.stationEBikesLogo);
            ImageView regularBikesLogo = (ImageView) findViewById(R.id.stationFreeBikesLogo);
            TextView stationEBikesValue = (TextView) findViewById(R.id.stationEBikesValue);
            int ebikes = station.getEBikes();

            eBikesLogo.setVisibility(View.VISIBLE);
            stationEBikesValue.setVisibility(View.VISIBLE);
            stationEBikesValue.setText(String.valueOf(ebikes));
            regularBikesLogo.setImageResource(R.drawable.ic_regular_bike);
            stationFreeBikes.setText(String.valueOf(freeBikes - ebikes));   //display regular bikes only
        }

        mapController.setCenter(stationLocation);
    }

    private void setLastUpdateText(String rawLastUpdateISO8601) {
        long timeDifferenceInSeconds;
        TextView stationLastUpdate = (TextView) findViewById(R.id.stationLastUpdate);
        SimpleDateFormat timestampFormatISO8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        timestampFormatISO8601.setTimeZone(TimeZone.getTimeZone("UTC"));

        try {
            long lastUpdate = timestampFormatISO8601.parse(rawLastUpdateISO8601).getTime();
            long currentDateTime = System.currentTimeMillis();
            timeDifferenceInSeconds = (currentDateTime - lastUpdate) / 1000;

            if (timeDifferenceInSeconds < 60) {
                stationLastUpdate.setText(getString(R.string.updated_just_now));
            } else if (timeDifferenceInSeconds >= 60 && timeDifferenceInSeconds < 3600) {
                int minutes = (int) timeDifferenceInSeconds / 60;
                stationLastUpdate.setText(getResources().getQuantityString(R.plurals.updated_minutes_ago,
                        minutes, minutes));
            } else if (timeDifferenceInSeconds >= 3600 && timeDifferenceInSeconds < 86400) {
                int hours = (int) timeDifferenceInSeconds / 3600;
                stationLastUpdate.setText(getResources().getQuantityString(R.plurals.updated_hours_ago,
                        hours, hours));
            } else if (timeDifferenceInSeconds >= 86400) {
                int days = (int) timeDifferenceInSeconds / 86400;
                stationLastUpdate.setText(getResources().getQuantityString(R.plurals.updated_days_ago,
                        days, days));
            }

            stationLastUpdate.setTypeface(null, Typeface.ITALIC);
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.station, menu);

        favStar = menu.findItem(R.id.action_favorite);
        if (isFavorite()) {
            favStar.setIcon(R.drawable.ic_menu_favorite);
        } else {
            favStar.setIcon(R.drawable.ic_menu_favorite_outline);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_directions:
                Intent intent = new Intent(Intent.ACTION_VIEW, getStationLocationUri());
                PackageManager packageManager = getPackageManager();
                List<ResolveInfo> activities = packageManager.queryIntentActivities(intent, 0);
                boolean isIntentSafe = activities.size() > 0;
                if (isIntentSafe) {
                    startActivity(intent);
                } else {
                    Toast.makeText(this, getString(R.string.no_nav_application), Toast.LENGTH_LONG).show();
                }
                return true;
            case R.id.action_favorite:
                setFavorite(!isFavorite());
                return true;
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private Uri getStationLocationUri() {
        return Uri.parse("geo:" + station.getLatitude() + "," + station.getLongitude());
    }

    private boolean isFavorite() {
        return stationsDataSource.isFavoriteStation(station.getId());
    }

    private void setFavorite(boolean favorite) {
        if (favorite) {
            stationsDataSource.addFavoriteStation(station.getId());
            favStar.setIcon(R.drawable.ic_menu_favorite);
            Toast.makeText(StationActivity.this,
                    getString(R.string.station_added_to_favorites), Toast.LENGTH_SHORT).show();
        } else {
            stationsDataSource.removeFavoriteStation(station.getId());
            favStar.setIcon(R.drawable.ic_menu_favorite_outline);
            Toast.makeText(StationActivity.this,
                    getString(R.string.stations_removed_from_favorites), Toast.LENGTH_SHORT).show();
        }

        /* Refresh widget with new favorite */
        Intent refreshWidgetIntent = new Intent(getApplicationContext(),
                StationsListAppWidgetProvider.class);
        refreshWidgetIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        refreshWidgetIntent.putExtra(StationsListAppWidgetProvider.EXTRA_REFRESH_LIST_ONLY, true);
        sendBroadcast(refreshWidgetIntent);
    }
}
