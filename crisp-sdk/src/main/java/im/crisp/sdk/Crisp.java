package im.crisp.sdk;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

import im.crisp.sdk.ui.CrispFragment;

/**
 * Created by baptistejamin on 14/05/2017.
 */

public class Crisp {
    private static String _pkg = "im.crisp.sdk";
    private static Crisp instance;
    private String websiteId;
    private String tokenId;
    private String locale;

    private Context context;

    public Crisp(Context context) {
        this.context = context;
        generateTokenId();
    }

    public static void initialize(Context context) {
        instance = new Crisp(context);
    }


    public static Crisp getInstance() {
        if (instance == null) {
            Log.e(_pkg, "No instance for Crisp SDK. Please call add Crisp.initialize(\"getContext()\")");
        }
        return instance;
    }

    public void generateTokenId() {
        tokenId = context.getSharedPreferences(_pkg, Context.MODE_PRIVATE).getString("crisp_token_id", null);
        if (tokenId != null) {
            return;
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(_pkg, Context.MODE_PRIVATE).edit();

        tokenId = UUID.randomUUID().toString();

        editor.putString("crisp_token_id", tokenId);

        editor.apply();
    }

    public void setWebsiteId(String websiteId) {
        this.websiteId = websiteId;
    }

    public String getWebsiteId() {
        return websiteId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getLocale() {
        return locale;
    }

    public Context getContext() {
        return context;
    }

    static public class User {
        public static void setEmail(String email) {
            CrispFragment.execute("window.$crisp.push([\"set\", \"user:email\", [\"" + email + "\"]])");
        }

        public static void setNickname(String nickname) {
            CrispFragment.execute("window.$crisp.push([\"set\", \"user:nickname\", [\"" + nickname + "\"]])");
        }

        public static void setPhone(String phone) {
            CrispFragment.execute("window.$crisp.push([\"set\", \"user:phone\", [\"" + phone + "\"]])");
        }

        public static void setAvatar(String avatar) {
            CrispFragment.execute("window.$crisp.push([\"set\", \"user:avatar\", [\"" + avatar + "\"]])");
        }
    }

    static public class Session {
        public static void setData(String key, String value) {
            CrispFragment.execute("window.$crisp.push([\"set\", \"session:data\", [\"" + key + "\", \"" + value + "\"]])");
        }

        public static void setData(Map<String, String> data) {
            String pendingData = "";
            int index = 0;

            for(Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                if (index == 0) {
                    pendingData = "[\"" + key + "\", \"" + value + "\"]";
                } else {
                    pendingData = pendingData + ",[\"" + key + "\", \"" + value + "\"]";
                }
                index += 1;
            }

            CrispFragment.execute("window.$crisp.push([\"set\", \"session:data\", [[" + pendingData + "]]])");
        }

        public static void setSegments(String segment) {
            CrispFragment.execute("window.$crisp.push([\"set\", \"session:segments\", [[\"" + segment + "\"]]])");
        }



        @TargetApi(Build.VERSION_CODES.KITKAT)
        public static void setSegments(String... segments) {
            JSONArray jsonSegments;
            try {
                jsonSegments = new JSONArray(segments);
                CrispFragment.execute("window.$crisp.push([\"set\", \"session:segments\", [" + jsonSegments.toString() + "]])");
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        public static void pushEvent(String name, JSONObject data, String color) {
            CrispFragment.execute("window.$crisp.push([\"set\", \"session:event\", [[['" + name + "', " + data + ", '" +color +"']]]])");
        }

        public static void pushEvent(String name, JSONObject data) {
            Crisp.Session.pushEvent(name, data, "blue");
        }

        public static void reset() {
            getInstance().context.getSharedPreferences(_pkg, Context.MODE_PRIVATE).edit().remove("crisp_token_id").apply();
            getInstance().generateTokenId();
            CrispFragment.execute("window.location.reload()");
            CrispFragment.isLoaded = false;
            CrispFragment.load();
        }
    }
}
