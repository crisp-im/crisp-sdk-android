package im.crisp.sdk;

import android.content.Context;

import im.crisp.sdk.services.wrappers.Chat;
import im.crisp.sdk.services.wrappers.Session;
import im.crisp.sdk.services.wrappers.User;

/**
 * Created by baptistejamin on 14/05/2017.
 */

public class Crisp {
    @Deprecated
    public static void initialize(Context context, String websiteId) {
        Crisp.Builder build = new Crisp.Builder(context);
        build.setWebsiteId(websiteId);
        build.initialize();
    }

    public static Crisp.Builder with(Context context) {
        return new Crisp.Builder(context);
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

    public static class Builder {
        Context context;
        public String websiteId;
        public String tokenId;
        Builder(Context context) {
            this.context = context;
        }

        public Context getContext() {
            return this.context;
        }

        public String getWebsiteId() {
            return websiteId;
        }

        public Builder setWebsiteId(String websiteId) {
            this.websiteId = websiteId;
            return this;
        }

        public String getTokenId() {
            return tokenId;
        }

        public Builder setTokenId(String tokenId) {
            this.tokenId = tokenId;
            return this;
        }

        public void initialize() {
            SharedCrisp.initialize(this);
        }
    }
}
