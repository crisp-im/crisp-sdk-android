package im.crisp.sdk.models.message;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Date;

/**
 * Created by baptistejamin on 28/04/2017.
 */


public class Message {

    @SerializedName("timestamp")
    public Date timestamp = new Date();

    @SerializedName("fingerprint")
    public long fingerprint = 0;

    @SerializedName("from")
    public String from = "operator";

    @SerializedName("read")
    public String read;

    @SerializedName("origin")
    public String origin;

    @SerializedName("delivered")
    public String delivered;

    @SerializedName("type")
    public String type;

    @SerializedName("stamped")
    public boolean stamped;

    @SerializedName("user")
    public MessageUser user;

    @SerializedName("content")
    public JsonElement content;

    public String messageText;
    public MessageFile messageFile;

    @SerializedName("preview")
    public ArrayList<MessagePreview> preview;

    public Boolean hasMedia() {
        return ((type.equals("file") && content.isJsonObject() &&
                content.getAsJsonObject().get("type").getAsString().toLowerCase().contains("jpg") ||
                content.getAsJsonObject().get("type").getAsString().toLowerCase().contains("jpeg") ||
                content.getAsJsonObject().get("type").getAsString().toLowerCase().contains("gif") ||
                content.getAsJsonObject().get("type").getAsString().toLowerCase().contains("png")));
    }
}
