package im.crisp.sdk.models.media;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 03/05/2017.
 */

public class MediaAnimationListPage {
    @SerializedName("page")
    public int page = 1;

    @SerializedName("query")
    public String query;
}