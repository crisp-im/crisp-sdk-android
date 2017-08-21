package im.crisp.sdk;

import android.content.Context;

import im.crisp.sdk.services.Socket;
import im.crisp.sdk.services.wrappers.Chat;
import im.crisp.sdk.services.wrappers.Session;
import im.crisp.sdk.services.wrappers.User;
import im.crisp.sdk.stores.ContextStore;
import im.crisp.sdk.utils.DateFormat;
import im.crisp.sdk.utils.Device;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class SharedCrisp {
    private static SharedCrisp instance;
    private String websiteId;
    private String tokenId;
    private Context context;
    private Socket socket;
    private Device device;
    private DateFormat dateFormat;
    private Chat chat;
    private User user;
    private Session session;
    private ContextStore contextStore;
    private Config config = new Config();
    private boolean logEnabled = false;

    public SharedCrisp(Crisp.Builder builder) {
        instance = this;

        this.context = builder.getContext();
        this.websiteId = builder.getWebsiteId();
        this.tokenId = builder.getTokenId();

        contextStore = new ContextStore(this);
        dateFormat = new DateFormat(this);
        device = new Device(this);
        socket = new Socket(this);
        chat = new Chat(this);
        user = new User(this);
        session = new Session(this);
    }

    public static void initialize(Crisp.Builder builder) {
        instance = new SharedCrisp(builder);
    }

    public static SharedCrisp getInstance() {
        return instance;
    }

    public Socket getSocket() {
        return socket;
    }

    public String getWebsiteId() {
        return websiteId;
    }

    public Context getContext() {
        return context;
    }

    public Device getDevice() {
        return device;
    }

    public Chat getChat() {
        return chat;
    }

    public User getUser() {
        return user;
    }

    public Session getSession() {
        return session;
    }

    public ContextStore getContextStore() {
        return contextStore;
    }

    public DateFormat getDateFormat() {
        return dateFormat;
    }

    public boolean isLogEnabled() {
        return logEnabled;
    }

    public void setLogEnabled(boolean logEnabled) {
        this.logEnabled = logEnabled;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }
}
