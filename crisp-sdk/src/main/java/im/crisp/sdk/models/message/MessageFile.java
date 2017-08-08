package im.crisp.sdk.models.message;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 28/04/2017.
 */

public class MessageFile {

    @SerializedName("timestamp")
    private String type;

    @SerializedName("url")
    private String url;

    @SerializedName("name")
    private String name;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
