package im.crisp.sdk.models.session;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;

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
