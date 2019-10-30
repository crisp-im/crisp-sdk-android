package crisp.im.example;

import org.json.JSONObject;

import im.crisp.sdk.Crisp;

/**
 * Created by baptistejamin on 23/05/2017.
 */

public class Application extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Crisp.initialize(this);
        Crisp.getInstance().setWebsiteId("7598bf86-9ebb-46bc-8c61-be8929bbf93d");


        Crisp.User.setEmail("john.doe@gmail.com");
        Crisp.Session.pushEvent("test", new JSONObject());
    }
}
