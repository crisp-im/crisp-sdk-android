package im.crisp.sdk.models.session;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class SessionCreated {
    @SerializedName("session_id")
    public String sessionId;

    @SerializedName("error")
    public String error;
}
