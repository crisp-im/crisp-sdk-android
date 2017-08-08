package im.crisp.sdk.models.session;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class ResponseMetrics {
    @SerializedName("count")
    public long count;

    @SerializedName("last")
    public long last;

    @SerializedName("mean")
    public long mean;
}
