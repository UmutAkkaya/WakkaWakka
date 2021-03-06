package hackthe6ix.wakkawakka;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;

import org.json.JSONException;
import org.json.JSONObject;

import hackthe6ix.wakkawakka.callbacks.NotificationEvent;
import hackthe6ix.wakkawakka.callbacks.PlayerUpdateRecievedCallback;
import hackthe6ix.wakkawakka.callbacks.PositionUpdateCallback;
import hackthe6ix.wakkawakka.eventbus.EventBus;

/**
 * Created by uba19_000 on 1/15/2016.
 */
public class Player implements PositionUpdateCallback, PlayerUpdateRecievedCallback {
    public static Player localplayer;
    public double longitude;
    public double latitude;
    public double accuracy;
    public int type;
    public int score;
    public String name;
    public long lastUpdateTime;
    public String devid;
    public long invulnerable;
    public long cooldown;
    public long prevCooldown;
    public Marker marker;
    public Circle accuracyCircle;

    private int prevType;
    public final boolean local;
    private int presenceAck;

    public Player(boolean local, String devid) {
        this.local = local;
        this.devid = devid;
        prevType = -1;
        prevCooldown = -1;
        presenceAck = 0;
    }

    @Override
    public void OnPositionUpdate(LatLng ev) {
        latitude = ev.latitude;
        longitude = ev.longitude;
        //Notify(PlayerType.getDrawableID(0), "Hello!");
    }


    public void Update(JSONObject jsonObject) {
        try {
            name = jsonObject.getString("username");
            cooldown = jsonObject.getLong("cooldown");

            if (!local) {
                JSONObject location = jsonObject.getJSONObject("location");
                latitude = Double.parseDouble(location.getString("y"));
                longitude = Double.parseDouble(location.getString("x"));
                accuracy = Double.parseDouble(location.getString("Acc"));
            } else if (prevCooldown != cooldown && prevCooldown != -1) {

                EventBus.NOTIFICATION_EVENTBUS.broadcast(new NotificationEvent.NotificationInfo(
                        type, "You've been eaten!"
                ));
            }

            score = jsonObject.getInt("points");
            invulnerable = jsonObject.getLong("invulnerable");

            type = Integer.parseInt(jsonObject.getString("type"));

            if (marker != null && accuracyCircle != null) {
                UpdateMarker();
            }

            prevType = type;
            prevCooldown = cooldown;
        } catch (JSONException e) {
            e.printStackTrace();
        }

    }

    public void UpdateMarker() {
        marker.setTitle((isInvoln() ? "Powered up " : "") + PlayerType.getTypeString(type) + " " + (local ? "You" : name));
        int drawableID = PlayerType.getDrawableID(type);

        Bitmap bmp = BitmapFactory.decodeResource(Game.getAppContext().getResources(), drawableID);

        if (bmp == null)
        {
            return;
        }

        double aspect = bmp.getWidth() / (double) bmp.getHeight();
        marker.setIcon(BitmapDescriptorFactory.fromBitmap(Bitmap.createScaledBitmap(bmp, (int) (100 * aspect), 100, false)));
        marker.setAlpha(isCooldown() ? 0.5f : 1f);
        marker.setVisible(true);
        marker.setPosition(new LatLng(latitude, longitude));
        marker.setSnippet("Score: " + score);
        accuracyCircle.setCenter(new LatLng(latitude, longitude));
        accuracyCircle.setRadius(accuracy);
    }

    public boolean isInvoln() {
        return System.currentTimeMillis() - invulnerable < Game.INVULN_TIME;
    }

    public boolean isCooldown() {
        return System.currentTimeMillis() - cooldown < Game.COOLDOWN_TIME;
    }

    @Override
    public void OnPlayersUpdated(Integer num) {

        if (isCooldown()) {
            return;
        }

        //Interactions check
        for (final Player plr : Game.getInstance().players.values()) {
            if (plr.isCooldown()) {
                continue;
            }
            boolean targetInvuln = plr.isInvoln();
            double dist = LatLonDist(plr.latitude, latitude, plr.longitude, longitude);
            if (dist < Game.INTERACTION_RANGE && PlayerType.CanInteract(type, isInvoln(), plr.type, targetInvuln)) {
                WakkaWebClient.getInstance().Interact(plr.devid, new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        //Notify Interact success
                        if (!response.equals("COOLDOWN")) {
                            EventBus.NOTIFICATION_EVENTBUS.broadcast(
                                    new NotificationEvent.NotificationInfo(type,
                                            "You ate " + plr.name + " the " +
                                                    PlayerType.getTypeString(plr.type) + "!"));
                        }

                    }
                }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e("VolleyError", "Could not interact with " + plr.devid);
                        Toast.makeText(Game.getAppContext(), "A network error occured - could not interact.", Toast.LENGTH_SHORT);
                    }
                });
            } else if (dist < Game.THREAT_RANGE && PlayerType.CanInteract(plr.type, targetInvuln, type, isInvoln())) {
                //Notify of threat
                //Notify(plr.type, plr.name + " the " + PlayerType.getTypeString(plr.type) + " is nearby, and can eat you!");
                if (plr.presenceAck != 1) {
                    plr.presenceAck = 1;
                    EventBus.NOTIFICATION_EVENTBUS.broadcast(new NotificationEvent.NotificationInfo(plr.type,
                            plr.name + " the " + PlayerType.getTypeString(plr.type) + " is nearby, and can eat you!"));
                }

            } else {
                plr.presenceAck = 0;
            }
        }
    }

    //find distance between coords
    public static double LatLonDist(double lat1, double lat2, double lon1, double lon2) {
        double R = 6371000; // metres
        double Lat1 = lat1 * Math.PI / 180;
        double Lat2 = lat2 * Math.PI / 180;
        double deltalat = (lat2 - lat1) * Math.PI / 180;
        double deltaAlpha = (lon2 - lon1) * Math.PI / 180;

        double a = Math.sin(deltalat / 2) * Math.sin(deltalat / 2) +
                Math.cos(Lat1) * Math.cos(Lat2) *
                        Math.sin(deltaAlpha / 2) * Math.sin(deltaAlpha / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double d = R * c;
        return d;
    }
}
