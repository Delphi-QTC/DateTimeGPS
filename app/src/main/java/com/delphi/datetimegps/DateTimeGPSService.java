/*===========================================================================*
 * Package Name
 *===========================================================================*/
package com.delphi.datetimegps;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.buzzhives.database.TimeZoneMapper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * This service will update the system Data/Time and the Time Zone
 */
public class DateTimeGPSService extends Service
{
/*===========================================================================*
 * Static Data Objects
 *===========================================================================*/
    /** Tag for DIReCt Clock & Alarm logs */
    private static final String LOG_TAG = "DateTimeGPSService";
    /** Time in milliseconds to update the GPS time */
    private static final int UPDATE_GPS_TIME = 10000;
    /** Restart service if GPS is not enabled */
    private static final int RESTART_SERVICE = 10000;
    /** Set initial year */
    private static final int INITIAL_YEAR = 2000;
    /** Nmea Constants http://www.gpsinformation.org/dale/nmea.htm **/
    private static final int  NMEA_TIME = 1;
    private static final int  NMEA_DATE = 9;
    private static final int  NMEA_MIN_VALID_PARTS = 10;

/*===========================================================================*
 * Data Objects
 *===========================================================================*/
    /** This string contains the latest GPRMC GPS data*/
    private String mGPRMCData;

/*===========================================================================*
 * Methods
 *===========================================================================*/
    public DateTimeGPSService()
    {
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    @Override
    public void onCreate()
    {
        configureGPSTime();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        return START_STICKY;
    }

    /**
     * Initialize the GPS to update the system time with GPS if J1939 or Network Time is
     * not available.
     */
    private void configureGPSTime()
    {
        final LocationManager locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        {
            Log.v(LOG_TAG, "GPS is enabled.");
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_GPS_TIME, 1, new LocationListener()
            {
                @Override
                public void onLocationChanged(Location location)
                {
                    int autoTimeZone = android.provider.Settings.Global.getInt(getContentResolver(),
                            android.provider.Settings.Global.AUTO_TIME_ZONE, 0);
                    if(0 < autoTimeZone)
                    {
                        AlarmManager am = (AlarmManager) getApplication().getSystemService(Context.ALARM_SERVICE);
                        String timeZone = TimeZoneMapper.latLngToTimezoneString(location.getLatitude(), location.getLongitude());
                        Log.v(LOG_TAG, "New time zone: " + timeZone + " For Latitude: " + location.getLatitude() +
                                            (" and Longitude: " + location.getLongitude()));
                        am.setTimeZone(timeZone);
                    }
                    else
                    {
                        Log.v(LOG_TAG, "Auto Time Zone is disabled.");
                    }

                    if(!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    {
                        Calendar calendarGPS = decodeGPSDateTime();
                        validateDateTime(calendarGPS);
                    }
                    else
                    {
                        Log.v(LOG_TAG, "Using Network provider.");
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onProviderDisabled(String provider) {}
            });

            locationManager.addNmeaListener(new GpsStatus.NmeaListener()
            {
                @Override
                public void onNmeaReceived(long timestamp, String nmea)
                {
                    if (nmea.contains("GPRMC"))
                    {
                        mGPRMCData = nmea;
                    }
                }
            });
        }
        else
        {
            Log.v(LOG_TAG, "GPS is disabled, verify again in one minute.");
            Intent intent = new Intent(getApplicationContext(), DateTimeGPSService.class);
            PendingIntent pIntent = PendingIntent.getService(getApplicationContext(), 0, intent, 0);
            AlarmManager alarm = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_SERVICE, pIntent);
            stopSelf();
        }
    }

    /**
     * Validate the provided date (received from GPS), if it is different
     * it will use it for system current Date & Time
     * @param dateTime Provided new Date & Time.
     */
    private void validateDateTime(Calendar dateTime)
    {
        if(null != dateTime)
        {
            Calendar now = Calendar.getInstance();

            now.set(Calendar.SECOND, dateTime.get(Calendar.SECOND));
            now.set(Calendar.MILLISECOND, dateTime.get(Calendar.MILLISECOND));

            if(0 != now.compareTo(dateTime))
            {
                long when = dateTime.getTimeInMillis();

                if (when / 1000 < Integer.MAX_VALUE)
                {
                    ((AlarmManager) getApplication().getSystemService(Context.ALARM_SERVICE)).setTime(when);
                }
                Log.v(LOG_TAG, "Date & Time updated from GPS!");
            }
        }
    }

    /**
     * Decode the Date & Time from the GPS
     * @return GPS Calendar
     */
    private Calendar decodeGPSDateTime()
    {
        Calendar calendar = null;
        int autoTime = android.provider.Settings.Global.getInt(getContentResolver(),
                android.provider.Settings.Global.AUTO_TIME, 0);
        if(0 < autoTime)
        {
            //Check if we have received nmea data before continue
            if(mGPRMCData==null) {
                Log.d(LOG_TAG,"No GPS data received yet.");
                return null; //cannot continue
            }
            //Now split
            String[] parts = mGPRMCData.split(",");
            //now check that we have a complete processable data
            if(parts.length<NMEA_MIN_VALID_PARTS) return null; //cannot continue

            SimpleDateFormat simpleDate = new SimpleDateFormat("ddMMyy HHmmss.SS");
            calendar = Calendar.getInstance();
            calendar.set(Calendar.YEAR, INITIAL_YEAR);
            simpleDate.set2DigitYearStart(calendar.getTime());

            try
            {
                simpleDate.setTimeZone(TimeZone.getTimeZone("GMT"));
                calendar.setTime(simpleDate.parse(parts[NMEA_DATE] + " " + parts[NMEA_TIME]));
            } catch (ParseException e)
            {
                e.printStackTrace();
            }

            Log.d(LOG_TAG, mGPRMCData);
            Log.d(LOG_TAG, "GPS GMT Date: " + calendar.get(Calendar.DAY_OF_MONTH) + "-" +
                    (calendar.get(Calendar.MONTH) + 1) + "-" + calendar.get(Calendar.YEAR));
            Log.d(LOG_TAG, "GPS GMT Time: " + calendar.get(Calendar.HOUR_OF_DAY) + ":" +
                    calendar.get(Calendar.MINUTE) + "." + calendar.get(Calendar.SECOND));
        }
        else
        {
            Log.v(LOG_TAG, "Auto Time is disabled.");
        }

        return calendar;
    }

/*===========================================================================*
 * Inner classes
 *===========================================================================*/
    /**
     * Broadcast receiver for the DIReCt Date/Time & Alarm
     */
    public static class Launcher extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            Log.d(LOG_TAG, "DateTimeGPS launched");
            Intent serviceIntent = new Intent(context, DateTimeGPSService.class);
            context.startService(serviceIntent);
        }
    }
}
