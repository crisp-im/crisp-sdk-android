package im.crisp.sdk.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.support.v4.content.ContextCompat;

import com.orhanobut.logger.Logger;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import im.crisp.sdk.SharedCrisp;

/**
 * Created by baptistejamin on 23/05/2017.
 */

public class DateFormat {


    private static final long secondsInMilliseconds = 1000;
    private static final long minutesInHour = 60;
    private static final long hoursInDay = 24;
    private static final long secondsInMinute = 24;

    private static final long secondsInHour = (
            minutesInHour * secondsInMinute
    );

    private static final long secondsInDay = (
            hoursInDay * secondsInHour
    );

    SharedCrisp crisp;

    public DateFormat(SharedCrisp crisp) {
        this.crisp = crisp;
    }

    public class RenderSelection {
        public String type = "";
        public long data = 0;
    }

    String formatString(String prefix, RenderSelection selection) {
        int id = 0;

        if (selection.data == 1 || selection.data == 0) {
            id = crisp.getContext().getResources().getIdentifier(prefix + "_" + selection.type + "_singular", "string", crisp.getContext().getPackageName());
        } else {
            id = crisp.getContext().getResources().getIdentifier(prefix + "_" + selection.type + "_plural", "string", crisp.getContext().getPackageName());
        }

        if (id == 0) {
            id = crisp.getContext().getResources().getIdentifier(prefix + "_" + selection.type, "string", crisp.getContext().getPackageName());
        }

        if (id == 0) {
            return "";
        }
        return crisp.getContext().getString(id, selection.data);
    }

    public RenderSelection renderSelect(long milliseconds) {
        long gapSeconds = milliseconds / secondsInMilliseconds;
        RenderSelection selection = new RenderSelection();
        if (gapSeconds < 10) {
            // It just happened
            selection.type = "now";
        } else if (gapSeconds < secondsInMinute) {
            // Count in seconds
            selection.type = "second";
        } else if (gapSeconds < secondsInHour) {
            // Count in minutes
            selection.type = "minute";

            selection.data = (long) Math.floor(gapSeconds / secondsInMinute);
        } else if (gapSeconds < secondsInDay) {
            // Count in hours
            selection.type = "hour";

            selection.data = (long) Math.floor(gapSeconds / secondsInHour);
        }
        return selection;
    }

    public String duration(long milliseconds) {
        return formatString("duration", renderSelect(milliseconds));
    }

    public String date(Date date) {
        long diff = (new Date()).getTime() - date.getTime();

        String format = formatString("date", renderSelect(diff));
        if (format.length() == 0) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd'/'MM", Locale.US);
            return dateFormat.format(date);
        } else {
            return format;
        }
    }
}
