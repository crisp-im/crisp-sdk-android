package im.crisp.sdk.models.message;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
