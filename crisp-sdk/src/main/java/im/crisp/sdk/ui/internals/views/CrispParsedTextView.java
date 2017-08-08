package im.crisp.sdk.ui.internals.views;

import android.content.Context;
import android.text.Spannable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import im.crisp.sdk.utils.SmileySpannable;

/**
 * Created by baptistejamin on 03/05/2017.
 */

public class CrispParsedTextView extends TextView {
    public CrispParsedTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public CrispParsedTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CrispParsedTextView(Context context) {
        super(context);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        Spannable s = SmileySpannable.getSpannable(getContext(), text, this.getLineHeight());
        super.setText(s, BufferType.SPANNABLE);
    }
}
