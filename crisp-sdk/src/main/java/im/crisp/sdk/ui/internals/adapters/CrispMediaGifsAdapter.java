package im.crisp.sdk.ui.internals.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.gif.GifDrawable;

import java.util.ArrayList;

import im.crisp.sdk.R;
import im.crisp.sdk.models.media.MediaAnimationResult;
import im.crisp.sdk.utils.ImageRoute;
import im.crisp.sdk.utils.RoundedCornersTransformation;

/**
 * Created by baptistejamin on 02/05/2017.
 */

public class CrispMediaGifsAdapter extends BaseAdapter {

    private Context context;
    private LayoutInflater layoutInflater;
    private ArrayList<MediaAnimationResult> results = new ArrayList<>();
    GifListener gifListener = null;

    public CrispMediaGifsAdapter(Context context) {
        this.context = context;
        layoutInflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return results.size();
    }

    @Override
    public MediaAnimationResult getItem(int position) {
        return results.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        view = layoutInflater.inflate(R.layout.crisp_item_gif, null);

        ImageView smileyView = (ImageView) view.findViewById(R.id.crisp_image_gif);

        MediaAnimationResult media = getItem(position);

        Glide.with(context)
                .load(ImageRoute.PreserveFormat(media.url))
                .transform(new Transformation[]{new RoundedCornersTransformation(context, 10, 0)})
                .centerCrop()
                .into(smileyView);

        view.setOnClickListener(v -> {
            if (gifListener != null) {
                gifListener.onSelect(media.url);
            }
        });

        return view;
    }

    public void setResults(ArrayList<MediaAnimationResult> results) {
        this.results = results;
    }

    public void setOnClickListener(GifListener listener) {
        this.gifListener = listener;
    }

    public interface GifListener {
        void onSelect(String gifValue);
    }
}
