package im.crisp.sdk.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import im.crisp.sdk.Config;

/**
 * Created by baptistejamin on 02/05/2017.
 */


public class ImageRoute {

    public static String AvatarFormat(String type, String id, String avatarUrl) {
        return AvatarFormat(type, id, avatarUrl, "200");
    }

    public static String AvatarFormat(String type, String id, String avatarUrl, String size) {
        String avatarCachePath;

        if (avatarUrl != null) {
            try {
                avatarCachePath = URLEncoder.encode(avatarUrl, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                avatarCachePath = "unknown";
            }
        } else {
            avatarCachePath = "unknown";
        }
        return (Config.CRISP_IMAGE + "/avatar/" + type + "/" + id + "/" + size + "/?avatar=" + avatarCachePath);
    }

    public static String ImageFormat(String url) {
        return ImageFormat(url, "600");
    }

    public static String ImageFormat(String url, String size) {
        String urlEncodedPath = "";

        try {
            urlEncodedPath = URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return (Config.CRISP_IMAGE + "/process/resize/?url=" + urlEncodedPath + "&width=" + size + "&height=" + size);
    }

    public static String PreserveFormat(String url) {
        String urlEncodedPath = "";

        try {
            urlEncodedPath = URLEncoder.encode(url, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return (Config.CRISP_IMAGE + "/process/original/?url=" + urlEncodedPath);
    }
}
