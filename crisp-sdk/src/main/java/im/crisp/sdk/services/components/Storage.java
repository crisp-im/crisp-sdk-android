package im.crisp.sdk.services.components;

/**
 * Created by baptistejamin on 05/05/2017.
 */

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.orhanobut.logger.Logger;

import java.util.Date;

import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.services.Socket;
import im.crisp.sdk.utils.CrispJson;

public class Storage {
    SharedCrisp crisp;
    Socket socket;

    int rayEntropySeed = 1;
    int rayEntropyIncrement = 0;
    int rayEntropyFactor = 10000;

    public Storage(SharedCrisp crisp, Socket socket) {
        this.crisp = crisp;
        this.socket = socket;
        setupEvents();
    }

    public void update(String type, JsonElement data) {
        JsonObject payload = new JsonObject();
        payload.add("type", new JsonPrimitive(type));
        payload.add("ray", new JsonPrimitive(generateRay(type)));
        payload.add("data", data);

        socket.emit("storage:sync:update", CrispJson.GsonToJSON(payload));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(payload));
    }

    private void setupEvents() {
        socket.getIo().on("storage:sync:updated", args -> {
            Logger.d((args[0]).toString());
        });
    }

    private String generateRay(String type) {
        double x, increment;
        x = (Math.sin(this.rayEntropySeed++) * this.rayEntropyFactor);
        x = (x - Math.floor(x));

        increment = (this.rayEntropyIncrement++);

        return type + "/" + new Date().getTime() + "/" + x + "/" + increment;
    }
}
