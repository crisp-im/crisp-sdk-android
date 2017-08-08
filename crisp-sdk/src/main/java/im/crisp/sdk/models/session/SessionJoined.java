package im.crisp.sdk.models.session;

import com.google.gson.annotations.SerializedName;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import im.crisp.sdk.models.Settings;
import im.crisp.sdk.models.message.Message;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class SessionJoined {
    @SerializedName("session_id")
    public String sessionId;

    @SerializedName("active_operators")
    public List<ActiveOperator> activeOperators;

    @SerializedName("avatar")
    public String avatar;

    @SerializedName("avatar_buster")
    public Long avatarBuster;

    @SerializedName("domain")
    public String domain;

    @SerializedName("email")
    public String email;

    @SerializedName("nickname")
    public String nickname;

    @SerializedName("phone")
    public String phone;

    @SerializedName("data")
    public JSONObject data;

    //@SerializedName("plugins")
    //public HashMap<String, Object> plugins;

    @SerializedName("response_metrics")
    public ResponseMetrics responseMetrics;

    @SerializedName("segments")
    public List<String> segments;

    @SerializedName("settings")
    public Settings settings;

    @SerializedName("users_available")
    public String usersAvailable;

    @SerializedName("website")
    public String website;

    @SerializedName("sync")
    public SessionJoinedSync sync;


    public class SessionJoinedSync {
        @SerializedName("messages")
        public ArrayList<Message> messages = new ArrayList<>();
    }
}
