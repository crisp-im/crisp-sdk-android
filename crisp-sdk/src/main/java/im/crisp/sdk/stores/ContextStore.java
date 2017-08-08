package im.crisp.sdk.stores;

import com.anupcowkur.reservoir.Reservoir;
import com.google.gson.reflect.TypeToken;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.message.Message;
import im.crisp.sdk.models.message.MessageAcknowledge;
import im.crisp.sdk.models.session.SessionJoined;
import io.reactivex.Observable;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class ContextStore {
    private String sessionId;
    private SessionJoined session;
    private ArrayList<Message> messages = new ArrayList<>();
    private ArrayList<Long> unreadMessages = new ArrayList<Long>();
    private SharedCrisp crisp;

    public ContextStore(SharedCrisp crisp) {
        this.crisp = crisp;
        try {
            Reservoir.init(crisp.getContext(), 20000);
            restore();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isLoaded() {
        return sessionId != null;
    }

    public void restore() {
        try {
            if (Reservoir.contains("CrispSessionId")) {
                sessionId = Reservoir.get("CrispSessionId", String.class);
            }
        } catch (IOException e) {

        }

        try {
            if (Reservoir.contains("CrispSession")) {
                session = Reservoir.get("CrispSession", SessionJoined.class);
            }
        } catch (IOException e) {

        }

        try {
            if (Reservoir.contains("CrispMessages")) {
                messages = Reservoir.get("CrispMessages", new TypeToken<List<Message>>() {
                }.getType());
            }
        } catch (IOException e) {

        }

        try {
            if (Reservoir.contains("CrispUnreadMessages")) {
                unreadMessages = Reservoir.get("CrispUnreadMessages", new TypeToken<List<Long>>() {
                }.getType());
            }
        } catch (IOException e) {

        }
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
        try {
            if (sessionId != null) {
                Reservoir.put("CrispSessionId", sessionId);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSession(SessionJoined session) {
        this.session = session;
        saveSession();
    }

    public void saveSession() {
        try {
            if (session != null) {
                Reservoir.put("CrispSession", session);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public SessionJoined getSession() {
        return session;
    }

    public ArrayList<Message> getMessages() {
        if (messages == null) {
            return new ArrayList<>();
        }
        return messages;
    }

    public void addMessage(Message message, boolean isPending) {
        if (!messageExists(message)) {
            this.messages.add(message);
            if (isPending) {

                this.unreadMessages.add(message.fingerprint);
            }
            if (isPending)
                saveUnreadMessages();
            saveMessages();
            EventBus.getDefault().post(message);
        }
    }

    public void addMessages(List<Message> messages, boolean isPending) {
        for (Message message : messages) {
            if (!messageExists(message)) {
                this.messages.add(message);
                if (isPending)
                    this.unreadMessages.add(message.fingerprint);
            }
        }
        saveMessages();
        if (isPending)
            saveUnreadMessages();
        EventBus.getDefault().post(messages);
    }

    public void messageSent(Message message) {
        Message local = Observable
                .fromArray(messages)
                .flatMapIterable(list -> list)
                .filter(item -> item.fingerprint == message.fingerprint)
                .blockingFirst();
        local.stamped = true;
        saveMessages();
        EventBus.getDefault().post(message);
    }

    public void messagesRead(MessageAcknowledge acknowledge) {
        Observable
                .fromArray(this.messages)
                .flatMapIterable(list -> list)
                .forEach(message -> {
                    message.read = acknowledge.origin;
                });
        saveMessages();
        EventBus.getDefault().post(messages);
    }

    public void saveMessages() {
        try {
            if (messages != null) {
                Reservoir.put("CrispMessages", messages);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveUnreadMessages() {
        try {
            if (unreadMessages != null) {
                Reservoir.put("CrispUnreadMessages", unreadMessages);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ArrayList<Long> getUnreadMessages() {
        return unreadMessages;
    }

    public void clearUnreadMessages() {
        unreadMessages.clear();
        saveUnreadMessages();
    }

    private boolean messageExists(Message message) {
        return Observable
                .fromArray(messages)
                .flatMapIterable(list -> list)
                .map(item -> item.fingerprint)
                .contains(message.fingerprint).blockingGet();
    }

    public void reset() {
        clearUnreadMessages();
        messages.clear();
        saveMessages();
    }
}
