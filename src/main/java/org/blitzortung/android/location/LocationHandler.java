package org.blitzortung.android.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import org.blitzortung.android.app.Main;
import org.blitzortung.android.app.R;
import org.blitzortung.android.app.view.PreferenceKey;
import org.blitzortung.android.protocol.Consumer;
import org.blitzortung.android.protocol.ConsumerContainer;

import java.util.HashMap;
import java.util.Map;

public class LocationHandler implements SharedPreferences.OnSharedPreferenceChangeListener, android.location.LocationListener, GpsStatus.Listener {

    private final Context context;

    public static enum Provider {
        NETWORK(LocationManager.NETWORK_PROVIDER),
        GPS(LocationManager.GPS_PROVIDER),
        PASSIVE(LocationManager.PASSIVE_PROVIDER),
        MANUAL("manual");

        private String type;

        private Provider(String type) {
            this.type = type;
        }

        public String getType() {
            return type;
        }

        private static Map<String, Provider> stringToValueMap = new HashMap<String, Provider>();

        static {
            for (Provider key : Provider.values()) {
                String keyString = key.getType();
                if (stringToValueMap.containsKey(keyString)) {
                    throw new IllegalStateException(String.format("key value '%s' already defined", keyString));
                }
                stringToValueMap.put(keyString, key);
            }
        }

        public static Provider fromString(String string) {
            return stringToValueMap.get(string);
        }
    }

    private ConsumerContainer<LocationEvent> consumerContainer = new ConsumerContainer<LocationEvent>() {
        @Override
        public void addedFirstConsumer() {
            enableProvider(provider);
            Log.d(Main.LOG_TAG, "LocationHandler enable provider");
        }

        @Override
        public void removedLastConsumer() {
            locationManager.removeUpdates(LocationHandler.this);
            Log.d(Main.LOG_TAG, "LocationHandler disable provider");
        }
    };

    private final LocationManager locationManager;

    private Provider provider;

    private final Location location;

    public LocationHandler(Context context, SharedPreferences sharedPreferences) {
        this.context = context;
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        locationManager.addGpsStatusListener(this);
        location = new Location("");
        invalidateLocation();

        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.LOCATION_MODE);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onLocationChanged(android.location.Location location) {
        this.location.set(location);
        sendLocationUpdate();
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {
    }

    @Override
    public void onProviderEnabled(String s) {
    }

    @Override
    public void onProviderDisabled(String s) {
        invalidateLocation();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String keyString) {
        onSharedPreferenceChanged(sharedPreferences, PreferenceKey.fromString(keyString));
    }

    private void onSharedPreferenceChanged(SharedPreferences sharedPreferences, PreferenceKey key) {
        switch (key) {
            case LOCATION_MODE:
                Provider newProvider = Provider.fromString(sharedPreferences.getString(key.toString(), Provider.PASSIVE.getType()));
                if (newProvider != provider) {
                    updateProvider(newProvider, sharedPreferences);
                }
                break;

            case LOCATION_LONGITUDE:
                updateManualLongitude(sharedPreferences);
                break;

            case LOCATION_LATITUDE:
                updateManualLatitude(sharedPreferences);
                break;

        }
    }

    private void updateManualLatitude(SharedPreferences sharedPreferences) {
        try {
            location.setLatitude(Double.valueOf(sharedPreferences.getString(PreferenceKey.LOCATION_LATITUDE.toString(), "49.0")));
            sendLocationUpdate();
        } catch (NumberFormatException e) {
            Log.v(Main.LOG_TAG, "LocationHandler: bad number format for manual latitude setting");
        }
    }

    private void updateManualLongitude(SharedPreferences sharedPreferences) {
        try {
            location.setLongitude(Double.valueOf(sharedPreferences.getString(PreferenceKey.LOCATION_LONGITUDE.toString(), "11.0")));
            sendLocationUpdate();
        } catch (NumberFormatException e) {
            Log.v(Main.LOG_TAG, "LocationHandler: bad number format for manual longitude setting");
        }
    }

    private void updateProvider(Provider newProvider, SharedPreferences sharedPreferences) {
        if (newProvider == Provider.MANUAL) {
            locationManager.removeUpdates(this);
            updateManualLongitude(sharedPreferences);
            updateManualLatitude(sharedPreferences);
            location.setProvider(newProvider.getType());
        } else {
            invalidateLocationAndSendLocationUpdate();
        }
        enableProvider(newProvider);
    }

    private void invalidateLocationAndSendLocationUpdate() {
        sendLocationUpdateToListeners(null);
        invalidateLocation();
    }

    private void invalidateLocation() {
        location.setLongitude(Double.NaN);
        location.setLatitude(Double.NaN);
    }

    private void enableProvider(Provider newProvider) {
        locationManager.removeUpdates(this);
        if (newProvider != null && newProvider != Provider.MANUAL) {
            if (!locationManager.getAllProviders().contains(newProvider.getType())) {
                Toast toast = Toast.makeText(context, String.format(context.getResources().getText(R.string.location_provider_not_available).toString(), newProvider.toString()), 5000);
                toast.show();
                return;
            }
            locationManager.requestLocationUpdates(newProvider.getType(), provider == Provider.GPS ? 1000 : 10000, 10, this);
        }
        provider = newProvider;
    }

    private void sendLocationUpdate() {
        sendLocationUpdateToListeners(locationIsValid() ? location : null);
    }

    private void sendLocationUpdateToListeners(Location location) {
        consumerContainer.broadcast(new LocationEvent(location));
    }

    public void requestUpdates(Consumer<LocationEvent> locationConsumer) {
        if (!consumerContainer.contains(locationConsumer)) {
            consumerContainer.addConsumer(locationConsumer);

            if (locationIsValid()) {
                locationConsumer.consume(new LocationEvent(location));
            }
        }
    }

    public void removeUpdates(Consumer<LocationEvent> locationEventConsumer) {
        consumerContainer.removeConsumer(locationEventConsumer);
    }

    private boolean locationIsValid() {
        return location != null && !Double.isNaN(location.getLongitude()) && !Double.isNaN(location.getLatitude());
    }

    @Override
    public void onGpsStatusChanged(int event) {

        if (provider == Provider.GPS) {
            switch (event) {
                case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                    Location lastKnownGpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (lastKnownGpsLocation != null) {
                        long secondsElapsedSinceLastFix = (System.currentTimeMillis() - lastKnownGpsLocation.getTime()) / 1000;

                        if (secondsElapsedSinceLastFix < 10) {
                            if (!locationIsValid()) {
                                location.set(lastKnownGpsLocation);
                                onLocationChanged(location);
                            }
                            break;
                        }
                    }
                    if (locationIsValid()) {
                        invalidateLocationAndSendLocationUpdate();
                    }
                    break;
            }
        }
    }

}
