package im.crisp.sdk.models.session;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class ActiveOperator {
    @SerializedName("user_id")
    public String userId;

    @SerializedName("avatar")
    public String avatar;

    @SerializedName("nickname")
    public String nickname;

    @SerializedName("timestamp")
    public long timestamp;
}
