package crisp.im.example;

import im.crisp.sdk.Crisp;
import im.crisp.sdk.SharedCrisp;

/**
 * Created by baptistejamin on 23/05/2017.
 */

public class Application extends android.app.Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Crisp.with(this)
                .setWebsiteId("1721455c-8126-40d5-a5be-f42164d85432")
                .setTokenId("test")
                .initialize();

        //SharedCrisp.getInstance().setLogEnabled(true);
        //Crisp.getChat().setPrimaryColor("#9012FE");
        //Crisp.getChat().setPrimaryDarkColor("#9012FE");
        //Crisp.getUser().setEmail("test@gmail.com");

        SharedCrisp.getInstance().setLogEnabled(true);
    }
}
