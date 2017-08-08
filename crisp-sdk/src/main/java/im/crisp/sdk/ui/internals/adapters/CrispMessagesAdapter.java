package im.crisp.sdk.ui.internals.adapters;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.CardView;
import android.support.v7.widget.RecyclerView;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.tooltip.Tooltip;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import im.crisp.sdk.R;
import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.models.message.Message;
import im.crisp.sdk.models.message.MessageCompose;
import im.crisp.sdk.services.Socket;
import im.crisp.sdk.ui.internals.views.CrispParsedTextView;
import im.crisp.sdk.utils.ImageRoute;
import de.hdodenhof.circleimageview.CircleImageView;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by baptistejamin on 04/05/2017.
 */

public class CrispMessagesAdapter extends RecyclerView.Adapter {

    ArrayList<Message> messages = new ArrayList<>();
    //private Bypass bypass = new Bypass();
    Queue tooltipQueue = new LinkedList();
    Context context;
    String primaryColor;
    MessageCompose compose;


    private static final int VIEW_TYPE_TEXT_VISITOR = 1;
    private static final int VIEW_TYPE_TEXT_OPERATOR = 2;
    private static final int VIEW_TYPE_IMAGE_VISITOR = 3;
    private static final int VIEW_TYPE_IMAGE_OPERATOR = 4;
    private static final int VIEW_TYPE_ANIMATION_VISITOR = 5;
    private static final int VIEW_TYPE_ANIMATION_OPERATOR = 6;
    private static final int VIEW_TYPE_FILE_VISITOR = 7;
    private static final int VIEW_TYPE_FILE_OPERATOR = 8;

    public CrispMessagesAdapter(Context context) {
        this.context = context;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public void setMessages(ArrayList<Message> messages) {
        this.messages = messages;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());


        if (viewType == VIEW_TYPE_TEXT_OPERATOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_operator, viewGroup, false);
            return new CrispMessagesAdapter.OperatorViewHolder(view);
        } else if (viewType == VIEW_TYPE_TEXT_VISITOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_visitor, viewGroup, false);
            return new CrispMessagesAdapter.VisitorViewHolder(view);
        } else if (viewType == VIEW_TYPE_IMAGE_OPERATOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_operator, viewGroup, false);
            return new CrispMessagesAdapter.OperatorViewHolder(view);
        } else if (viewType == VIEW_TYPE_IMAGE_VISITOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_visitor, viewGroup, false);
            return new CrispMessagesAdapter.VisitorViewHolder(view);
        } else if (viewType == VIEW_TYPE_ANIMATION_OPERATOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_operator, viewGroup, false);
            return new CrispMessagesAdapter.OperatorViewHolder(view);
        } else if (viewType == VIEW_TYPE_ANIMATION_VISITOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_visitor, viewGroup, false);
            return new CrispMessagesAdapter.VisitorViewHolder(view);
        } else if (viewType == VIEW_TYPE_FILE_OPERATOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_operator, viewGroup, false);
            return new CrispMessagesAdapter.OperatorViewHolder(view);
        } else if (viewType == VIEW_TYPE_FILE_VISITOR) {
            View view = inflater.inflate(R.layout.crisp_item_message_visitor, viewGroup, false);
            return new CrispMessagesAdapter.VisitorViewHolder(view);
        }

        return null;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {

        if (position == getItemCount() - 1 && isComposing()) {
            bindCompose((OperatorViewHolder) holder, position);
        } else {
            Message message = messages.get(position);

            if (message.from.equals("operator")) {
                bindOperator((OperatorViewHolder) holder, message, position);
            } else {
                bindVisitor((VisitorViewHolder) holder, message, position);
            }

            bindBackground((MessageViewHolder) holder, message, position);
            bindText((MessageViewHolder) holder, message, position);
            bindAnimation((MessageViewHolder) holder, message, position);
            bindPreview((MessageViewHolder) holder, message, position);
            bindDownload((MessageViewHolder) holder, message, position);
            bindImage((MessageViewHolder) holder, message, position);
            bindMargin((MessageViewHolder) holder, message, position);
            bindStamp((MessageViewHolder) holder, message, position);
        }
    }


    private void bindCompose(OperatorViewHolder holder, int position) {
        holder.mViewCompose.setVisibility(View.VISIBLE);
        holder.mParsedText.setVisibility(View.GONE);
        holder.mViewPreview.setVisibility(View.GONE);
        holder.mViewDownload.setVisibility(View.GONE);
        holder.mImageAvatar.setVisibility(View.INVISIBLE);
        holder.mViewWrapper.setCardBackgroundColor(Color.parseColor(primaryColor));
    }

    private void bindBackground(MessageViewHolder holder, Message message, int position) {
        // holder.m.setColorFilter(Color.parseColor(primaryColor));
        if (primaryColor != null && message.from.equals("operator")) {
            holder.mViewWrapper.setCardBackgroundColor(Color.parseColor(primaryColor));
        } else if (message.from.equals("user")) {
            holder.mViewWrapper.setCardBackgroundColor(Color.parseColor("#ffffff"));
        }


    }

    private void bindText(MessageViewHolder holder, Message message, int position) {
        if (!message.type.equals("text")) {
            holder.mParsedText.setVisibility(View.GONE);
            return;
        }

        if (message.from.equals("operator")) {
            holder.mViewCompose.setVisibility(View.GONE);
        }
        holder.mParsedText.setVisibility(View.VISIBLE);
        holder.mParsedText.setText(message.content.getAsString());

        // CharSequence string = bypass.markdownToSpannable(holder.mParsedText.getText().toString());
        // holder.mParsedText.setText(string);
        //holder.mParsedText.setMovementMethod(LinkMovementMethod.getInstance());
        //holder.mParsedText.invalidate();

        holder.mParsedText.setOnClickListener(v -> {
            showDate(message, holder);
        });

    }

    private void bindAnimation(MessageViewHolder holder, Message message, int position) {
        if (!message.type.equals("animation")) {
            holder.mViewGif.setVisibility(View.GONE);
            return;
        }
        holder.mViewWrapper.setVisibility(View.GONE);
        holder.mViewGif.setVisibility(View.VISIBLE);

        Glide.with(context)
                .load(ImageRoute.PreserveFormat(message.content.getAsJsonObject().get("url").getAsJsonPrimitive().getAsString()))
                .asGif()
                .override(250, 150)
                .into(holder.mImageGif);
    }

    private void bindImage(MessageViewHolder holder, Message message, int position) {
        if (!message.type.equals("file") || !message.hasMedia()) {
            holder.mViewImage.setOnClickListener(null);
            holder.mViewImage.setVisibility(View.GONE);
            return;
        }

        holder.mViewWrapper.setVisibility(View.GONE);
        holder.mViewImage.setVisibility(View.VISIBLE);

        Glide.with(context)
                .load(ImageRoute.ImageFormat(message.content.getAsJsonObject().get("url").getAsString(), "250"))
                .override(250, 150)
                .fitCenter()
                .into(holder.mImageImage);
    }

    private void bindDownload(MessageViewHolder holder, Message message, int position) {
        if (!message.type.equals("file") || message.hasMedia() || message.content == null) {
            holder.mViewDownload.setVisibility(View.GONE);
            return;
        }
        holder.mViewDownload.setVisibility(View.VISIBLE);

        if (message.from.equals("operator")) {
            holder.mTextDownload.setTextColor(Color.parseColor(primaryColor));
        }

        String title = "file";
        if (message.content.getAsJsonObject().get("title") != null) {
            title = message.content.getAsJsonObject().get("title").getAsString();
        }
        holder.mTextDownload.setText(title);
        holder.mButtonDownload.setOnClickListener(v -> {
            if (message.content.getAsJsonObject().get("url") != null) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(message.content.getAsJsonObject().get("url").getAsString()));
                context.startActivity(browserIntent);
            }
        });
    }

    private void bindVisitor(VisitorViewHolder holder, Message message, int position) {

    }

    private void bindMargin(MessageViewHolder holder, Message message, int position) {
        if (position == 0 || position > 0 && !messages.get(position - 1).from.contentEquals(message.from)) {
            holder.mViewMargin.setVisibility(View.VISIBLE);
        } else {
            holder.mViewMargin.setVisibility(View.GONE);
        }
    }

    private void bindPreview(MessageViewHolder holder, Message message, int position) {
        holder.mViewPreview.setVisibility(View.GONE);

        if (message.preview == null)
            return;

        holder.mViewPreview.setVisibility(View.VISIBLE);

        if (message.preview.get(0) == null) {
            return;
        }

        if (message.from.equals("operator")) {
            holder.mTextPreview.setTextColor(Color.parseColor(primaryColor));
        }
        holder.mTextPreview.setText(message.preview.get(0).getTitle());
        holder.mTextPreview.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(message.preview.get(0).getUrl()));
            context.startActivity(browserIntent);
        });
    }


    private void bindOperator(OperatorViewHolder holder, Message message, int position) {
        holder.mImageAvatar.setVisibility(View.VISIBLE);

        String avatar = "";

        if (message.user != null && message.user.getAvatar() != null) {
            avatar = message.user.getAvatar();
        } else if (message.user != null && message.user.getUserId() != null) {
            avatar = ImageRoute.AvatarFormat("operator", message.user.getUserId(), null);
        }
        String lastAvatar = "";

        if (position < messages.size() - 1 && messages.get(position + 1).from.contentEquals(message.from)) {
            holder.mImageAvatar.setVisibility(View.INVISIBLE);
            if (messages.get(position + 1).user != null && messages.get(position + 1).user.getAvatar() != null) {
                lastAvatar = messages.get(position + 1).user.getAvatar();
            } else if (messages.get(position + 1).user != null && messages.get(position + 1).user.getUserId() != null) {
                lastAvatar = ImageRoute.AvatarFormat("operator", messages.get(position + 1).user.getUserId(), null);
            }

            if (!lastAvatar.equals(avatar)) {
                holder.mImageAvatar.setVisibility(View.VISIBLE);
            }
        }

        if (avatar.length() > 0) {
            Glide.with(context)
                    .load(avatar)
                    .dontAnimate()
                    .into(holder.mImageAvatar);
        }
    }


    private void bindStamp(MessageViewHolder holder, Message message, int position) {
        if (message.from.equals("operator")) {
            return;
        }
        if (position < messages.size() - 1) {
            holder.mViewStamp.setVisibility(View.INVISIBLE);
            return;
        }
        holder.mViewStamp.setVisibility(View.VISIBLE);

        if (message.read != null) {
            holder.mViewStampStatus.setVisibility(View.VISIBLE);
            holder.mViewStampSending.setVisibility(View.GONE);
            holder.mViewStampStatus.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.crisp_stamp_read));
            return;
        }

        if (message.stamped == true) {
            holder.mViewStampStatus.setVisibility(View.VISIBLE);
            holder.mViewStampSending.setVisibility(View.GONE);
            holder.mViewStampStatus.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.crisp_stamp_sent));
            return;
        }

        holder.mViewStampSending.setVisibility(View.VISIBLE);
        holder.mViewStampStatus.setVisibility(View.GONE);
    }

    private void clearTooltips() {
        Iterator<PendingTooltip> iterator = tooltipQueue.iterator();
        while (iterator.hasNext()) {
            PendingTooltip pendingTooltip = iterator.next();
            if (pendingTooltip != null) {
                pendingTooltip.timer.dispose();
                pendingTooltip.tooltip.dismiss();
            }
        }
        tooltipQueue.clear();
    }

    private void showDate(Message message, MessageViewHolder holder) {
        clearTooltips();
        Tooltip tooltip = new Tooltip.Builder(holder.mViewWrapper)
                .setText(SharedCrisp.getInstance().getDateFormat().date(message.timestamp))
                .setArrowHeight(context.getResources().getDimension(R.dimen.chat_message_tooltip_arrow_height))
                .setBackgroundColor(context.getResources().getColor(R.color.crisp_tooltip_background))
                .setCornerRadius(context.getResources().getDimension(R.dimen.chat_message_tooltip_radius))
                .setTextColor(context.getResources().getColor(R.color.crisp_white))
                .setMargin(context.getResources().getDimension(R.dimen.chat_message_tooltip_margin))
                .setPadding(context.getResources().getDimension(R.dimen.chat_message_tooltip_padding))
                .show();

        Disposable timer = Observable
                .timer(5, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<Long>() {
                    @Override
                    public void accept(@NonNull Long aLong) throws Exception {
                        clearTooltips();
                    }
                });

        tooltipQueue.add(new PendingTooltip(tooltip, timer));
    }

    @Override
    public int getItemViewType(int position) {
        if ((isComposing() && position == getItemCount() - 1) || getItemCount() == 0) {
            return VIEW_TYPE_TEXT_OPERATOR;
        }

        Message message = messages.get(position);

        if (message.from.contains("operator")) {
            if (message.type.equals("text"))
                return VIEW_TYPE_TEXT_OPERATOR;
            else if (message.hasMedia() && message.type.equals("file"))
                return VIEW_TYPE_IMAGE_OPERATOR;
            else if (!message.hasMedia() && message.type.equals("file"))
                return VIEW_TYPE_FILE_OPERATOR;
            else {
                return VIEW_TYPE_ANIMATION_OPERATOR;
            }
        } else {
            if (message.type.equals("text"))
                return VIEW_TYPE_TEXT_VISITOR;
            else if (message.hasMedia() && message.type.equals("file"))
                return VIEW_TYPE_IMAGE_VISITOR;
            else if (!message.hasMedia() && message.type.equals("file"))
                return VIEW_TYPE_FILE_VISITOR;
            else {
                return VIEW_TYPE_ANIMATION_VISITOR;
            }
        }
    }

    @Override
    public int getItemCount() {
        if (messages == null) {
            return 0;
        }
        if (isComposing()) {
            return messages.size() + 1;
        }
        return messages.size();
    }

    public boolean isComposing() {
        return compose != null && compose.type.equals("start");
    }

    public void setCompose(MessageCompose compose) {
        this.compose = compose;
    }

    class MessageViewHolder extends RecyclerView.ViewHolder {
        public CrispParsedTextView mParsedText;
        public CardView mViewWrapper;
        public View mViewMargin;
        public View mViewCompose;
        public View mViewPreview;
        public TextView mTextPreview;
        public View mViewGif;
        public ImageView mImageGif;
        public View mViewImage;
        public ImageView mImageImage;
        public View mViewDownload;
        public TextView mTextDownload;
        public View mButtonDownload;
        public View mViewStamp;
        public ImageView mViewStampStatus;
        public View mViewStampSending;
        public ViewGroup parent;

        public MessageViewHolder(View itemView) {
            super(itemView);

            mViewWrapper = (CardView) itemView.findViewById(R.id.crisp_item_message_wrapper);
            mParsedText = (CrispParsedTextView) itemView.findViewById(R.id.crisp_item_message_text);
            mViewCompose = itemView.findViewById(R.id.crisp_item_message_compose);
            mViewMargin = itemView.findViewById(R.id.crisp_item_message_margin);
            mViewPreview = itemView.findViewById(R.id.crisp_item_message_preview);
            mTextPreview = (TextView) itemView.findViewById(R.id.crisp_item_message_preview_text);
            mViewGif = itemView.findViewById(R.id.crisp_item_message_gif);
            mImageGif = (ImageView) itemView.findViewById(R.id.crisp_item_message_gif_image);
            mViewImage = itemView.findViewById(R.id.crisp_item_message_image);
            mImageImage = (ImageView) itemView.findViewById(R.id.crisp_item_message_image_image);
            mViewDownload = itemView.findViewById(R.id.crisp_item_message_download);
            mTextDownload = (TextView) itemView.findViewById(R.id.crisp_item_message_download_text);
            mButtonDownload = itemView.findViewById(R.id.crisp_item_message_download_button);
            mViewStamp = itemView.findViewById(R.id.chat_message_stamp);
            mViewStampStatus = (ImageView) itemView.findViewById(R.id.chat_message_stamp_status);
            mViewStampSending = itemView.findViewById(R.id.chat_message_stamp_sending);
            parent = (ViewGroup) itemView;
        }
    }

    class VisitorViewHolder extends MessageViewHolder {
        public VisitorViewHolder(View itemView) {
            super(itemView);
        }
    }

    class OperatorViewHolder extends MessageViewHolder {
        public CircleImageView mImageAvatar;

        public OperatorViewHolder(View itemView) {
            super(itemView);

            mImageAvatar = (CircleImageView) itemView.findViewById(R.id.crisp_item_message_avatar);
        }
    }

    class PendingTooltip {
        Tooltip tooltip;
        Disposable timer;

        PendingTooltip(Tooltip tooltip, Disposable timer) {
            this.tooltip = tooltip;
            this.timer = timer;
        }
    }
}