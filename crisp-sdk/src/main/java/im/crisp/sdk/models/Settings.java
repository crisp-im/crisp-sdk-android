package im.crisp.sdk.models;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class Settings {
    @SerializedName("activity_metrics")
    public boolean activityMetrics;

    @SerializedName("availability_tooltip")
    public boolean availabilityTooltip;

    @SerializedName("blocked_locales")
    public List<String> blockedLocales;

    @SerializedName("blocked_pages")
    public List<String> blockedPages;

    @SerializedName("check_domain")
    public boolean checkDomain;

    @SerializedName("color_theme")
    public String colorTheme;

    @SerializedName("email_visitors")
    public boolean emailVisitors;

    @SerializedName("force_identify")
    public boolean forceIdentify;

    @SerializedName("hide_on_away")
    public boolean hideOnAway;

    @SerializedName("ignore_privacy")
    public boolean ignorePrivacy;

    @SerializedName("last_operator_face")
    public boolean lastOperatorFace;

    @SerializedName("locale")
    public String locale;

    @SerializedName("logo")
    public String logo;

    @SerializedName("position_reverse")
    public boolean positionReverse;

    @SerializedName("rating")
    public boolean rating;

    @SerializedName("text_theme")
    public String textTheme;

    @SerializedName("tile")
    public String tile;

    @SerializedName("transcript")
    public boolean transcript;

    @SerializedName("welcome_message")
    public String welcomeMessage;
}
