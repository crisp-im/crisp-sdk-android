package im.crisp.sdk.utils;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.support.v4.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import im.crisp.sdk.SharedCrisp;

/**
 * Created by baptistejamin on 24/04/2017.
 */

public class Device {
    SharedCrisp crisp;

    public Device(SharedCrisp crisp) {
        this.crisp = crisp;
    }

    @TargetApi(Build.VERSION_CODES.N)
    public List<String> getLocale() {
        ArrayList<String> locales = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int length = crisp.getContext().getResources().getConfiguration().getLocales().size();
            for (int i = 0; i < length; i++) {
                locales.add(crisp.getContext().getResources().getConfiguration().getLocales().get(i).getISO3Language());
            }
        } else {
            locales.add(Locale.getDefault().getISO3Language());
        }
        return locales;
    }

    public int getTimezone() {
        return TimeZone.getDefault().getRawOffset();
    }
}
