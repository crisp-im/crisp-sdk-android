package im.crisp.sdk.services.components;

/**
 * Created by baptistejamin on 01/05/2017.
 */

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.orhanobut.logger.Logger;

import org.greenrobot.eventbus.EventBus;

import java.util.Date;
import java.util.List;

import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.message.MessageAcknowledge;
import im.crisp.sdk.models.message.MessageCompose;
import im.crisp.sdk.services.Socket;
import im.crisp.sdk.utils.CrispJson;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class Message {
    SharedCrisp crisp;
    Socket socket;

    public Message(SharedCrisp crisp, Socket socket) {
        this.crisp = crisp;
        this.socket = socket;
        setupEvents();
    }

    public void sendTextMessage(String text) {
        im.crisp.sdk.models.message.Message textMessage = new im.crisp.sdk.models.message.Message();
        textMessage.type = "text";
        textMessage.origin = "chat";
        textMessage.from = "user";
        textMessage.content = new JsonPrimitive(text);
        textMessage.timestamp = new Date();
        textMessage.fingerprint = generateFingerprint();

        crisp.getContextStore().addMessage(textMessage, false);

        socket.emit("message:send", CrispJson.GsonToJSON(textMessage));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(CrispJson.GsonToJSON(textMessage)));
    }

    public void sendFileMessage(JsonObject file) {
        im.crisp.sdk.models.message.Message fileMessage = new im.crisp.sdk.models.message.Message();
        fileMessage.type = "file";
        fileMessage.origin = "chat";
        fileMessage.from = "user";
        fileMessage.content = file;
        fileMessage.timestamp = new Date();
        fileMessage.fingerprint = generateFingerprint();

        crisp.getContextStore().addMessage(fileMessage, false);

        socket.emit("message:send", CrispJson.GsonToJSON(fileMessage));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(CrispJson.GsonToJSON(fileMessage)));
    }

    public void sendAnimationMessage(String gif) {
        im.crisp.sdk.models.message.Message animationMessage = new im.crisp.sdk.models.message.Message();
        animationMessage.type = "animation";
        animationMessage.origin = "chat";
        animationMessage.from = "user";
        animationMessage.content = new JsonObject();
        animationMessage.content.getAsJsonObject().add("url", new JsonPrimitive(gif));
        animationMessage.content.getAsJsonObject().add("type", new JsonPrimitive("image/gif"));
        animationMessage.timestamp = new Date();
        animationMessage.fingerprint = generateFingerprint();

        crisp.getContextStore().addMessage(animationMessage, false);

        socket.emit("message:send", CrispJson.GsonToJSON(animationMessage));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(CrispJson.GsonToJSON(animationMessage)));
    }

    public void composeSend(String type, String excerpt) {
        MessageCompose compose = new MessageCompose();
        compose.type = type;
        compose.excerpt = excerpt;
        socket.emit("message:compose:send", CrispJson.GsonToJSON(compose));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(CrispJson.GsonToJSON(compose)));
    }

    public void acknowledgeMessagesPending(List<Long> fingerprints) {
        if (fingerprints.size() == 0) {
            return;
        }
        MessageAcknowledge payload = new MessageAcknowledge();
        payload.origin = "chat";
        payload.fingerprints = fingerprints;

        socket.emit("message:acknowledge:pending", CrispJson.GsonToJSON(payload));
        socket.emit("message:acknowledge:delivered", CrispJson.GsonToJSON(payload));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(CrispJson.GsonToJSON(payload)));

    }

    public void acknowledgeMessagesRead(List<Long> fingerprints) {
        if (fingerprints.size() == 0) {
            return;
        }
        MessageAcknowledge payload = new MessageAcknowledge();
        payload.origin = "chat";
        payload.fingerprints = fingerprints;

        socket.emit("message:acknowledge:read:received", CrispJson.GsonToJSON(payload));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(CrispJson.GsonToJSON(payload)));

        EventBus.getDefault().post(payload);

    }

    private void setupEvents() {
        socket.getIo().on("message:received", args -> {
            im.crisp.sdk.models.message.Message event = CrispJson.getGson().fromJson((args[0]).toString(), im.crisp.sdk.models.message.Message.class);
            messageReceived(event);
        });

        socket.getIo().on("message:sent", args -> {
            im.crisp.sdk.models.message.Message event = CrispJson.getGson().fromJson((args[0]).toString(), im.crisp.sdk.models.message.Message.class);
            messageSent(event);
        });

        socket.getIo().on("message:compose:received", args -> {
            MessageCompose event = new Gson().fromJson((args[0]).toString(), MessageCompose.class);
            messageCompose(event);
        });

        socket.getIo().on("message:acknowledge:read:send", args -> {
            MessageAcknowledge event = new Gson().fromJson((args[0]).toString(), MessageAcknowledge.class);
            messagesRead(event);
        });
    }

    private long generateFingerprint() {
        int RANDOM_PAD_FACTOR = 100;
        int TARGET_SIZE = 15;

        // Generate fingerprint number as string
        String fingerprint = (
                (new Date().getTime() + "")
        );

        // Convert fingerprint to a ~64-bit number
        return Long.parseLong(fingerprint);
    }

    private void messageReceived(im.crisp.sdk.models.message.Message message) {
        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(message));
        SharedCrisp.getInstance().getContextStore().addMessage(message, true);
    }

    private void messageSent(im.crisp.sdk.models.message.Message message) {
        if (SharedCrisp.getInstance().isLogEnabled())
            SharedCrisp.getInstance().getContextStore().messageSent(message);
        Logger.json(new GsonBuilder().create().toJson(message));
    }

    private void messageCompose(im.crisp.sdk.models.message.MessageCompose event) {
        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(event));
        EventBus.getDefault().post(event);
    }

    private void messagesRead(MessageAcknowledge messages) {
        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(messages));
        SharedCrisp.getInstance().getContextStore().messagesRead(messages);
    }
}
