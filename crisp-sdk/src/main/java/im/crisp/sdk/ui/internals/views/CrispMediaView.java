package im.crisp.sdk.ui.internals.views;

import android.animation.Animator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;

import com.daimajia.androidanimations.library.Techniques;
import com.daimajia.androidanimations.library.YoYo;
import com.jakewharton.rxbinding2.widget.RxTextView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.concurrent.TimeUnit;

import im.crisp.sdk.R;
import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.media.MediaAnimationListed;
import im.crisp.sdk.ui.internals.adapters.CrispMediaGifsAdapter;
import im.crisp.sdk.ui.internals.adapters.CrispMediaSmileysAdapter;
import io.reactivex.Observable;

/**
 * Created by baptistejamin on 01/05/2017.
 */

public class CrispMediaView extends LinearLayout {
    Button mButtonSmiley;
    Button mButtonGifs;
    ViewPager mViewPager;
    LinearLayout mMediaLayout;
    EditText mInputGifs;
    CrispMediaViewCallback mediaViewCallback;
    CrispMediaGifsAdapter mediaGifsAdapter;
    CrispMediaSmileysAdapter mediaSmileysAdapter;
    String primaryColor;

    public CrispMediaView(Context context) {
        super(context);
        inflate();
    }

    public CrispMediaView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate();

    }

    public CrispMediaView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        inflate();

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    private void inflate() {
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.crisp_view_media, this, true);

        mButtonSmiley = (Button) findViewById(R.id.crisp_media_button_smileys);
        mButtonSmiley.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleView("smileys", true);
            }
        });
        mButtonSmiley.setSelected(true);

        mButtonGifs = (Button) findViewById(R.id.crisp_media_button_gifs);
        mButtonGifs.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleView("gifs", true);
            }
        });

        bindViewPager();

        mMediaLayout = (LinearLayout) findViewById(R.id.crisp_media_layout);
        mMediaLayout.setVisibility(GONE);

        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });

        mediaGifsAdapter = new CrispMediaGifsAdapter(getContext());
        mediaSmileysAdapter = new CrispMediaSmileysAdapter(getContext());

        mInputGifs = (EditText) findViewById(R.id.crisp_media_input_gifs);


        if (!isInEditMode()) {
            Observable<String> obs = RxTextView.textChanges(mInputGifs)
                    .debounce(300, TimeUnit.MILLISECONDS)
                    .map(charSequence -> charSequence.toString());

            obs.subscribe(string -> {
                SharedCrisp.getInstance().getSocket().getMedia().listAnimations(string);
            });
        }
    }

    private void bindViewPager() {
        mViewPager = (ViewPager) findViewById(R.id.crisp_media_pager);

        if (!isInEditMode()) {
            mViewPager.setAdapter(new ViewPagerAdapter());
            mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

                }

                @Override
                public void onPageSelected(int position) {
                    if (position == 0) {
                        toggleView("smileys", false);
                    } else {
                        toggleView("gifs", false);
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {

                }
            });
        }
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;

        Drawable mButtonDrawable = DrawableCompat.wrap(mButtonSmiley.getBackground());

        DrawableCompat.setTint(mButtonDrawable, Color.parseColor(primaryColor));

        mButtonDrawable = DrawableCompat.wrap(mButtonGifs.getBackground());

        DrawableCompat.setTint(mButtonDrawable, Color.parseColor(primaryColor));
    }

    public void display() {
        YoYo.with(Techniques.SlideInUp)
                .duration(250).withListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                setVisibility(VISIBLE);
                mMediaLayout.setVisibility(VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animator animation) {

            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        }).playOn(mMediaLayout);
    }

    public void hide() {
        setVisibility(GONE);
        if (mediaViewCallback != null)
            mediaViewCallback.onMediaHidden();
    }

    public void setMediaViewCallback(CrispMediaViewCallback callback) {
        this.mediaViewCallback = callback;
    }

    private void toggleView(String type, Boolean feedback) {
        if (type.equals("gifs")) {
            mButtonGifs.setSelected(true);
            mButtonSmiley.setSelected(false);
            if (feedback)
                mViewPager.setCurrentItem(1);
        } else if (type.equals("smileys")) {
            mButtonGifs.setSelected(false);
            mButtonSmiley.setSelected(true);
            if (feedback)
                mViewPager.setCurrentItem(0);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(MediaAnimationListed event) {
        mediaGifsAdapter.setResults(event.results);
        mediaGifsAdapter.notifyDataSetChanged();
    }

    public class ViewPagerAdapter extends PagerAdapter {
        @Override
        public Object instantiateItem(ViewGroup collection, int position) {
            View view = null;
            switch (position) {
                case 0:
                    view = findViewById(R.id.crisp_media_grid_smileys);

                    mediaSmileysAdapter.setOnClickListener(smileyValue -> {
                        if (mediaViewCallback != null)
                            mediaViewCallback.onSmileySelected(smileyValue);
                    });
                    ((GridView) view).setAdapter(mediaSmileysAdapter);
                    break;
                case 1:
                    view = findViewById(R.id.crisp_media_layout_gifs);
                    GridView gifsGrid = (GridView) view.findViewById(R.id.crisp_media_grid_gifs);

                    mediaGifsAdapter.setOnClickListener(gifValue -> {
                        if (mediaGifsAdapter != null)
                            mediaViewCallback.onGifSelected(gifValue);
                    });

                    gifsGrid.setAdapter(mediaGifsAdapter);
                    SharedCrisp.getInstance().getSocket().getMedia().listAnimations();
                    break;
            }
            return view;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
    }

    public interface CrispMediaViewCallback {
        void onMediaHidden();

        void onSmileySelected(String smiley);

        void onGifSelected(String gif);
    }
}
