package im.crisp.sdk.models.message;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 28/04/2017.
 */

public class MessageUser  {

    @SerializedName("avatar")
    private String avatar;

    @SerializedName("nickname")
    private String name;

    @SerializedName("user_id")
    private String userId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
