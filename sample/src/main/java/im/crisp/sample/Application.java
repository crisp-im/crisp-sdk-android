package im.crisp.sample;

import androidx.multidex.MultiDexApplication;

import im.crisp.client.Crisp;

/**
 * Created by baptistejamin on 23/05/2017.
 */

public class Application extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();

        Crisp.configure(getApplicationContext(), "7598bf86-9ebb-46bc-8c61-be8929bbf93d");
    }
}
