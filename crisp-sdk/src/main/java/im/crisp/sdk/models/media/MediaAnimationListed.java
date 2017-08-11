package im.crisp.sdk.models.media;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;

/**
 * Created by baptistejamin on 02/05/2017.
 */

public class MediaAnimationListed {
    @SerializedName("id")
    public Long id;

    @SerializedName("results")
    public ArrayList<MediaAnimationResult> results;
}
