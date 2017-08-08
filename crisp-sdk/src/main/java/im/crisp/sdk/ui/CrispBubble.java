package im.crisp.sdk.ui;

import android.animation.Animator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.daimajia.androidanimations.library.Techniques;
import com.daimajia.androidanimations.library.YoYo;
import com.orhanobut.logger.Logger;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.w3c.dom.Text;

import java.util.List;

import im.crisp.sdk.Crisp;
import im.crisp.sdk.R;
import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.message.Message;
import im.crisp.sdk.models.message.MessageAcknowledge;
import im.crisp.sdk.models.session.SessionError;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by baptistejamin on 23/05/2017.
 */

public class CrispBubble extends RelativeLayout {
    String primaryColor;
    View mViewLayout;
    View mViewBubbleReply;
    View mViewBubbleError;
    TextView mTextBubbleReply;
    View mViewBubbleNotification;
    TextView mTextBubbleNotification;
    boolean hasError = false;
    AppCompatActivity context;

    public CrispBubble(Context context) {
        super(context);
        inflate();
    }

    public CrispBubble(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate();

    }

    public CrispBubble(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        inflate();

    }


    private void inflate() {
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.crisp_bubble, this, true);

        setOnClickListener(v -> {
            if (!hasError)
                Crisp.getChat().open((AppCompatActivity) getActivity());
        });


        mViewLayout = findViewById(R.id.crisp_bubble_layout);
        mViewBubbleNotification = findViewById(R.id.crisp_bubble_notification);
        mViewBubbleNotification.setVisibility(INVISIBLE);

        mTextBubbleNotification = (TextView) findViewById(R.id.crisp_bubble_notification_text);

        mViewBubbleReply = findViewById(R.id.crisp_bubble_reply);
        mViewBubbleReply.setVisibility(GONE);

        mViewBubbleError = findViewById(R.id.crisp_bubble_error);

        mTextBubbleReply = (TextView) findViewById(R.id.crisp_bubble_reply_text);

        primaryColor = Crisp.getChat().getPrimaryColor();
        bindTheme();
        bindErrorLayout();
    }

    private Activity getActivity() {
        if (this.context != null) {
            return this.context;
        }
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public void setContext(AppCompatActivity context) {
        this.context = context;
    }

    void bindTheme() {
        if (primaryColor != null) {
            Drawable drawable = DrawableCompat.wrap(mViewLayout.getBackground());

            DrawableCompat.setTint(drawable, Color.parseColor(primaryColor));

            mViewLayout.setBackground(drawable);
        }
    }

    void bindErrorLayout() {
        if (!SharedCrisp.getInstance().getSocket().getSession().isError()) {
            return;
        }

        hasError = true;

        Drawable drawable = DrawableCompat.wrap(mViewLayout.getBackground());

        DrawableCompat.setTint(drawable, getResources().getColor(R.color.crisp_dark_grey));

        mViewLayout.setBackground(drawable);

        mViewBubbleReply.setVisibility(GONE);
        mViewBubbleError.setVisibility(VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        EventBus.getDefault().register(this);
        updateUnread();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    void updateUnread() {
        List<Long> unread = SharedCrisp.getInstance().getContextStore().getUnreadMessages();
        if (unread.size() > 0) {
            mTextBubbleNotification.setText(unread.size() + "");
            if (mViewBubbleNotification.getVisibility() == INVISIBLE)
                showNotification();
            if (mViewBubbleReply.getVisibility() == GONE)
                showReply();
            if (unread.size() == 1) {
                mTextBubbleReply.setText(R.string.minimized_tooltip_unread_singular);
            } else {
                mTextBubbleReply.setText(R.string.minimized_tooltip_unread_plural);
            }
        } else {
            mViewBubbleNotification.setVisibility(INVISIBLE);
            mViewBubbleReply.setVisibility(GONE);
        }
    }

    private void showNotification() {
        YoYo.with(Techniques.SlideInUp)
                .duration(350).withListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                mViewBubbleNotification.setVisibility(VISIBLE);
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
        }).playOn(mViewBubbleNotification);
    }

    private void showReply() {
        YoYo.with(Techniques.FadeInUp)
                .duration(350).withListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                mViewBubbleReply.setVisibility(VISIBLE);
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
        }).playOn(mViewBubbleNotification);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(List<Message> event) {
        updateUnread();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Message event) {
        updateUnread();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(MessageAcknowledge event) {
        updateUnread();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(SessionError event) {
        bindErrorLayout();
    }

}
