package im.crisp.sdk.models.media;

import com.google.gson.annotations.SerializedName;

import java.util.Date;

/**
 * Created by baptistejamin on 02/05/2017.
 */

public class MediaAnimationList {
    @SerializedName("from")
    public String from = "visitor";

    @SerializedName("id")
    public Long id = new Date().getTime();

    @SerializedName("list")
    public MediaAnimationListPage list = new MediaAnimationListPage();
}
