package im.crisp.sdk.ui.internals.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import java.util.ArrayList;

import im.crisp.sdk.R;

/**
 * Created by baptistejamin on 01/05/2017.
 */

public class CrispMediaSmileysAdapter extends BaseAdapter {

    LayoutInflater layoutInflater;
    Context context;
    SmileyListener smileyListener = null;

    ArrayList<String[]> smileys = new ArrayList<String[]>() {{
        add(new String[]{"small_smile", ":)"});
        add(new String[]{"cool", "8)"});
        add(new String[]{"blushing", ":$"});
        add(new String[]{"happy", ":D"});
        add(new String[]{"heart", "<3"});
        add(new String[]{"thumbs_up", "+1"});
        add(new String[]{"winking", ";)"});
        add(new String[]{"big_smile", ":D"});
        add(new String[]{"laughing", ":'D"});
        add(new String[]{"angry", ":("});
        add(new String[]{"confused", "x)"});
        add(new String[]{"crying", ":'("});
        add(new String[]{"embarrased", "x)"});
        add(new String[]{"sad", ":("});
        add(new String[]{"sick", ":S"});
        add(new String[]{"surprised", ":o"});
        add(new String[]{"tongue", ":P"});
    }};

    public CrispMediaSmileysAdapter(Context context) {
        this.context = context;
        layoutInflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return smileys.size();
    }

    @Override
    public String[] getItem(int i) {
        return smileys.get(i);
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public View getView(final int i, View view, ViewGroup viewGroup) {
        view = layoutInflater.inflate(R.layout.crisp_item_smiley, null);

        ImageView smiley = (ImageView) view.findViewById(R.id.crisp_image_smiley);
        int id = context.getResources().getIdentifier("crisp_smiley_" + getItem(i)[0], "drawable", context.getPackageName());
        smiley.setBackground(context.getResources().getDrawable(id));

        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (smileyListener != null)
                    smileyListener.onSelect(getItem(i)[1]);
            }
        });

        return view;
    }

    public void setOnClickListener(SmileyListener listener) {
        this.smileyListener = listener;
    }

    public interface SmileyListener {
        void onSelect(String smileyValue);
    }
}
