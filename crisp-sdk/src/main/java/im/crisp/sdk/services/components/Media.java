package im.crisp.sdk.services.components;

/**
 * Created by baptistejamin on 02/05/2017.
 */

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.orhanobut.logger.Logger;

import org.greenrobot.eventbus.EventBus;

import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.media.MediaAnimationListPage;
import im.crisp.sdk.models.media.MediaAnimationListed;
import im.crisp.sdk.services.Socket;
import im.crisp.sdk.utils.CrispJson;

public class Media {
    SharedCrisp crisp;
    Socket socket;

    public Media(SharedCrisp crisp, Socket socket) {
        this.crisp = crisp;
        this.socket = socket;
        setupEvents();
    }

    private void setupEvents() {
        socket.getIo().on("media:animation:listed", args -> {
            MediaAnimationListed event = new Gson().fromJson((args[0]).toString(), MediaAnimationListed.class);
            mediaAnimationListed(event);
        });
    }

    public void listAnimations() {
        listAnimations(null);
    }

    public void listAnimations(String query) {
        im.crisp.sdk.models.media.MediaAnimationList list = new im.crisp.sdk.models.media.MediaAnimationList();

        list.list = new MediaAnimationListPage();
        list.list.query = query;

        socket.emit("media:animation:list", CrispJson.GsonToJSON(list));

        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(CrispJson.GsonToJSON(list)));
    }

    private void mediaAnimationListed(MediaAnimationListed event) {
        EventBus.getDefault().post(event);
        if (SharedCrisp.getInstance().isLogEnabled())
            Logger.json(new GsonBuilder().create().toJson(event));
    }
}
