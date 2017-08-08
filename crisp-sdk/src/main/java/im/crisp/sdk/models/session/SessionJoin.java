package im.crisp.sdk.models.session;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class SessionJoin {
    @SerializedName("session_id")
    public String sessionId;

    @SerializedName("useragent")
    public String useragent;

    @SerializedName("locales")
    public List<String> locales;

    @SerializedName("timezone")
    public int timezone;

    @SerializedName("sync")
    public boolean sync;

    @SerializedName("storage")
    public boolean storage;
}
