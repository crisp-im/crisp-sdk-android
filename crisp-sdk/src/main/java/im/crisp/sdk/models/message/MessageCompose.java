package im.crisp.sdk.models.message;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 03/05/2017.
 */

public class MessageCompose {
    @SerializedName("type")
    public String type;

    @SerializedName("excerpt")
    public String excerpt;
}