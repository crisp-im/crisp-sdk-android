package im.crisp.sdk.services.wrappers;

import im.crisp.sdk.SharedCrisp;

/**
 * Created by baptistejamin on 21/07/2017.
 */

public class Session {
    SharedCrisp crisp;

    public Session(SharedCrisp crisp) {
        this.crisp = crisp;
    }

    public void reset() {
        crisp.getContextStore().reset();
        crisp.getSocket().flush();
        crisp.getSocket().setConnected(false);
        crisp.getSocket().getSession().reset();
    }

    public String getSessionIdentifier() {
        if (crisp.getContextStore() == null) {
            return null;
        }
        return crisp.getContextStore().getSessionId();
    }
}
