package im.crisp.sdk.services.components;

/**
 * Created by baptistejamin on 03/05/2017.
 */

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.orhanobut.logger.Logger;

import org.greenrobot.eventbus.EventBus;

import java.util.Date;

import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.BucketGenerated;
import im.crisp.sdk.services.Socket;
import im.crisp.sdk.utils.CrispJson;

/**
 * Created by baptistejamin on 02/05/2017.
 */

public class Bucket {
    SharedCrisp crisp;
    Socket socket;

    public Bucket(SharedCrisp crisp, Socket socket) {
        this.crisp = crisp;
        this.socket = socket;
        setupEvents();
    }

    private void setupEvents() {
        socket.getIo().on("bucket:url:upload:generated", args -> {
            BucketGenerated event = new Gson().fromJson((args[0]).toString(), BucketGenerated.class);
            bucketGenerated(event);
        });
    }

    public void generateBucket(String name, String type) {
        JsonObject bucket = new JsonObject();
        JsonObject fileObject = new JsonObject();


        bucket.addProperty("id", (long) (new Date().getTime() / 1000));
        bucket.addProperty("from", "visitor");
        fileObject.addProperty("name", name);
        fileObject.addProperty("type", type);
        bucket.add("file", fileObject);

        socket.emit("bucket:url:upload:generate", CrispJson.GsonToJSON(bucket));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(bucket));
    }

    private void bucketGenerated(BucketGenerated bucketGenerated) {
        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(bucketGenerated));
        EventBus.getDefault().post(bucketGenerated);
    }
}
