package im.crisp.sdk.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ImageSpan;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by baptistejamin on 03/05/2017.
 */


public class SmileySpannable {

    public static HashMap<String, Pattern> smileyMap = new LinkedHashMap<String, Pattern>() {{
        put("angry", Pattern.compile("(:(?:-)?@)($|\\s)"));
        put("blushing", Pattern.compile("(:(?:-)?\\$)($|\\s)"));
        put("cool", Pattern.compile("(8(?:-)?\\))($|\\s)"));
        put("confused", Pattern.compile("(x(?:-)?\\))($|\\s)"));
        put("crying", Pattern.compile("(:'(?:-)?\\()($|\\s)"));
        put("embarrased", Pattern.compile("(:(?:-)?\\/)($|\\s)"));
        put("happy", Pattern.compile("(:(?:-)?D)($|\\s)"));
        put("heart", Pattern.compile("(\\<3)($|\\s)"));
        put("laughing", Pattern.compile("(:(?:-)?'D)($|s)"));
        put("sad", Pattern.compile("(:(?:-)?(?:\\(|\\|))($|\\s)"));
        put("sick", Pattern.compile("(:(?:-)?S)($|\\s)"));
        put("small_smile", Pattern.compile("(:(?:-)?\\))($|\\s)"));
        put("big_smile", Pattern.compile("(=(?:-)?\\))($|\\s)"));
        put("surprised", Pattern.compile("(:(?:-)?o)($|\\s)"));
        put("tongue", Pattern.compile("(:(?:-)?P)($|\\s)"));
        put("winking", Pattern.compile("(;(?:-)?\\))($|\\s)"));
        put("thumbs_up", Pattern.compile("(\\+1)($|\\s)"));
    }};

    private static boolean CompileEmojiPattern(Context context, Spannable spannable, Pattern pattern, String resname, int height) {
        boolean hasChanges = false;

        Matcher matcher = pattern.matcher(spannable);
        while (matcher.find()) {
            boolean set     = true;
            boolean isLarge = true;

            isLarge = spannable.subSequence(0, spannable.length()).equals(spannable.subSequence(matcher.start(), matcher.end()));
            int      id     = context.getResources().getIdentifier("crisp_smiley_" + resname, "drawable", context.getPackageName());
            Drawable smiley = context.getResources().getDrawable(id);
            if (set) {
                hasChanges = true;
                //if we have mutliple chars
                if (isLarge) {
                    smiley.setBounds(0, 0, 90, 90);
                    spannable.setSpan(new ImageSpan(smiley), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {

                    smiley.setBounds(0, 0, height, height);

                    if (matcher.end() == spannable.length())
                        spannable.setSpan(new ImageSpan(smiley), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    else
                        spannable.setSpan(new ImageSpan(smiley), matcher.start(), matcher.end() - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                }
            }
        }

        return hasChanges;
    }

    private static final Spannable.Factory spannableFactory = Spannable.Factory.getInstance();

    public static Spannable getSpannable(Context context, CharSequence text, int height) {
        Spannable spannable = spannableFactory.newSpannable(text);

        for (Map.Entry<String, Pattern> entry : smileyMap.entrySet()) {
            CompileEmojiPattern(context, spannable, entry.getValue(), entry.getKey(), height);
        }
        return spannable;
    }
}
