package im.crisp.sdk.models.session;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 03/05/2017.
 */

public class SessionHeartbeat {
    @SerializedName("last_active")
    public int lastActive = 0;

    @SerializedName("availability")
    public SessionHeartbeatAvailability availability = new SessionHeartbeatAvailability();

    public class SessionHeartbeatAvailability {
        @SerializedName("type")
        public String type = "online";

        @SerializedName("time")
        public SessionHeartbeatAvailabilityTime time = new SessionHeartbeatAvailabilityTime();
    }

    public class SessionHeartbeatAvailabilityTime {
        @SerializedName("for")
        public int _for = 0;
    }
}