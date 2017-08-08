package im.crisp.sdk.services;

import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

import im.crisp.sdk.*;
import im.crisp.sdk.services.components.Bucket;
import im.crisp.sdk.services.components.Media;
import im.crisp.sdk.services.components.Message;
import im.crisp.sdk.services.components.Session;
import im.crisp.sdk.services.components.Storage;
import io.socket.client.IO;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class Socket {
    private Session session;
    private Message message;
    private Media media;
    private Bucket bucket;
    private Storage storage;
    private boolean isConnected = false;
    Queue eventQueue = new LinkedList();

    private io.socket.client.Socket io;

    public Socket(SharedCrisp crisp) {
        bindSocket();
        this.session = new Session(crisp, this);
        this.message = new Message(crisp, this);
        this.media = new Media(crisp, this);
        this.storage = new Storage(crisp, this);
        this.bucket = new Bucket(crisp, this);
    }

    private void bindSocket() {
        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        opts.reconnection = false;
        opts.multiplex = false;
        opts.transports = new String[]{"websocket"};
        try {
            io = IO.socket(Config.CRISP_SOCKET_CLIENT, opts);
        } catch (URISyntaxException e) {
        }
    }
    
    public io.socket.client.Socket getIo() {
        return io;
    }

    public Session getSession() {
        return session;
    }

    public Message getMessage() {
        return message;
    }

    public Media getMedia() {
        return media;
    }

    public Bucket getBucket() {
        return bucket;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setConnected(boolean connected) {
        isConnected = connected;
    }

    public void emitAync(String event, Object... args) {
        if (isConnected) {
            emit(event, args);
            return;
        }

        eventQueue.add(new pendingEvent(event, args));
    }

    public void emit(String event, Object... args) {
        getIo().emit(event, args);
    }

    public void flush() {
        Iterator<pendingEvent> iterator = eventQueue.iterator();
        while (iterator.hasNext()) {
            pendingEvent pendingEvent = iterator.next();
            emit(pendingEvent.event, pendingEvent.args);
        }
        eventQueue.clear();
    }

    class pendingEvent {
        private String event;
        private Object[] args;

        public pendingEvent(String event, Object... args) {
            this.event = event;
            this.args = args;
        }
    }
}
