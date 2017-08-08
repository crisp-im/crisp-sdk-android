package im.crisp.sdk.models.session;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class SessionCreate {
    @SerializedName("website_id")
    public String websiteId;

    @SerializedName("token_id")
    public String tokenId;

    @SerializedName("website_domain")
    public String websiteDomain;

    @SerializedName("useragent")
    public String useragent;

    @SerializedName("locales")
    public List<String> locales;

    @SerializedName("timezone")
    public int timezone;
}
