package im.crisp.sdk.services.components;

import android.util.Log;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.logger.Logger;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.message.Message;
import im.crisp.sdk.models.session.SessionCreate;
import im.crisp.sdk.models.session.SessionCreated;
import im.crisp.sdk.models.session.SessionError;
import im.crisp.sdk.models.session.SessionHeartbeat;
import im.crisp.sdk.models.session.SessionJoin;
import im.crisp.sdk.models.session.SessionJoined;
import im.crisp.sdk.services.Socket;
import im.crisp.sdk.utils.CrispJson;
import io.reactivex.Observable;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Consumer;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class Session {
    SharedCrisp crisp;
    Socket socket;

    private boolean error = false;
    private int heartbeatSoft = 240;  // 4 minutes
    private int heartbeatHard = 300; // 5 minutes

    public Session(SharedCrisp crisp, Socket socket) {
        this.crisp = crisp;
        this.socket = socket;
        setupEvents();

        if (!crisp.getContextStore().isLoaded()) {
            create();
        } else {
            join(crisp.getContextStore().getSessionId());
        }
    }

    private void setupEvents() {
        socket.getIo().connect();
        socket.getIo().on("session:created", args -> {
            SessionCreated event = CrispJson.getGson().fromJson((args[0]).toString(), SessionCreated.class);
            handleSessionCreated(event);
        });

        socket.getIo().on("session:joined", args -> {
            Logger.d((args[0]).toString());
            SessionJoined event = CrispJson.getGson().fromJson((args[0]).toString(), SessionJoined.class);
            handleSessionJoined(event);
        });

        socket.getIo().on("session:error", args -> {
            EventBus.getDefault().post(new SessionError());
            reset();
        });

        socket.getIo().on("connect", args -> {
            Log.d("SharedCrisp", "connected");
        });
    }

    private void bindKeepAlive() {
        Observable
                .interval(heartbeatSoft, TimeUnit.SECONDS)
                .subscribe(new Consumer<Long>() {
                    @Override
                    public void accept(@NonNull Long aLong) throws Exception {
                        keepAlive();
                    }
                });
        keepAlive();
    }

    public void reset() {
        socket.getIo().disconnect();

        Observable
                .timer(1, TimeUnit.SECONDS)
                .subscribe(new Consumer<Long>() {
                    @Override
                    public void accept(@NonNull Long aLong) throws Exception {
                        socket.getIo().connect();
                        create();
                    }
                });
    }

    private void create() {
        SessionCreate create = new SessionCreate();
        create.websiteId = crisp.getInstance().getWebsiteId();
        create.websiteDomain = "crisp.chat";
        create.tokenId = crisp.getInstance().getTokenId();
        socket.emit("session:create", CrispJson.GsonToJSON(create));
    }

    private void join(String sessionId) {
        SessionJoin join = new SessionJoin();
        join.sessionId = sessionId;
        join.storage = true;
        join.sync = true;
        join.locales = crisp.getDevice().getLocale();
        join.timezone = crisp.getDevice().getTimezone();
        socket.emit("session:join", CrispJson.GsonToJSON(join));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(join));
    }

    private void keepAlive() {
        SessionHeartbeat heartbeat = new SessionHeartbeat();
        heartbeat.availability.time._for = heartbeatHard;

        socket.emit("session:heartbeat", CrispJson.GsonToJSON(heartbeat));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(heartbeat));
    }

    private void handleSessionCreated(SessionCreated event) {
        if (event.sessionId != null)
            join(event.sessionId);
        if (event.error != null) {
            error = true;
            EventBus.getDefault().post(new SessionError());
        }
        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(event));
    }

    private void handleSessionJoined(SessionJoined event) {

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(event));

        if (event.sync != null && event.sync.messages != null) {
            //Sync Storage
            crisp.getSocket().getStorage().update("message", CrispJson.getGson().toJsonTree(event.sync.messages, new TypeToken<List<Message>>() {
            }.getType()));

            Observable
                    .fromArray(event.sync.messages)
                    .flatMapIterable(list -> list)
                    .map(item -> item.fingerprint)
                    .toList()
                    .subscribe(fingerprints -> {
                        crisp.getSocket().getMessage().acknowledgeMessagesPending(fingerprints);
                    });
            crisp.getContextStore().addMessages(event.sync.messages, true);
        }

        EventBus.getDefault().post(event);

        crisp.getContextStore().setSessionId(event.sessionId);
        crisp.getContextStore().setSession(event);

        crisp.getSocket().setConnected(true);

        crisp.getSocket().flush();

        bindKeepAlive();
    }

    public void setEmail(String email) {
        JsonObject data = new JsonObject();
        data.addProperty("email", email);
        socket.emitAync("session:set_email", CrispJson.GsonToJSON(data));
    }

    public void setPhone(String phone) {
        JsonObject data = new JsonObject();
        data.addProperty("phone", phone);
        socket.emitAync("session:set_phone", CrispJson.GsonToJSON(data));
    }

    public void setNickname(String nickname) {
        JsonObject data = new JsonObject();
        data.addProperty("nickname", nickname);
        socket.emitAync("session:set_nickname", CrispJson.GsonToJSON(data));
    }

    public void setAvatar(String avatar) {
        JsonObject data = new JsonObject();
        data.addProperty("avatar", avatar);
        socket.emitAync("session:set_avatar", CrispJson.GsonToJSON(data));
    }

    public void setData(JSONObject _data) {
        JSONObject data = new JSONObject();
        try {
            data.put("data", _data);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        socket.emitAync("session:set_data", data);
    }

    public void setSegments(JSONArray segments) {
        JSONObject data = new JSONObject();
        try {
            data.put("segments", segments);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        socket.emitAync("session:set_segments", data);
    }


    public boolean isError() {
        return error;
    }
}
