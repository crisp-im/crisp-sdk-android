package im.crisp.sdk;

import android.content.Context;

import im.crisp.sdk.services.wrappers.Chat;
import im.crisp.sdk.services.wrappers.Session;
import im.crisp.sdk.services.wrappers.User;

/**
 * Created by baptistejamin on 14/05/2017.
 */

public class Crisp {
    public Crisp(Context context, String websiteId) {
        SharedCrisp.initialize(context, websiteId);
    }

    public static void initialize(Context context, String websiteId) {
        SharedCrisp.initialize(context, websiteId);
    }

    public static Chat getChat() {
        return SharedCrisp.getInstance().getChat();
    }

    public static User getUser() {
        return SharedCrisp.getInstance().getUser();
    }

    public static Session getSession() {return SharedCrisp.getInstance().getSession();}
    public boolean isLogEnabled() {
        return SharedCrisp.getInstance().isLogEnabled();
    }

    public void setLogEnabled(boolean logEnabled) {
        SharedCrisp.getInstance().setLogEnabled(logEnabled);
    }
}
