package im.crisp.sdk.models.message;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by baptistejamin on 05/05/2017.
 */

public class MessageAcknowledge {
    @SerializedName("origin")
    public String origin;

    @SerializedName("fingerprints")
    public List<Long> fingerprints;
}
