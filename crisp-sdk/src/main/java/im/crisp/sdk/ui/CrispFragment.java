package im.crisp.sdk.ui;

import android.Manifest;
import android.animation.Animator;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.support.v4.app.DialogFragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.daimajia.androidanimations.library.Techniques;
import com.daimajia.androidanimations.library.YoYo;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.jakewharton.rxbinding2.widget.RxTextView;

import net.gotev.uploadservice.BinaryUploadRequest;
import net.gotev.uploadservice.Logger;
import net.gotev.uploadservice.ServerResponse;
import net.gotev.uploadservice.UploadInfo;
import net.gotev.uploadservice.UploadNotificationConfig;
import net.gotev.uploadservice.UploadStatusDelegate;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.w3c.dom.Text;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import im.crisp.sdk.*;
import im.crisp.sdk.models.BucketGenerated;
import im.crisp.sdk.models.message.Message;
import im.crisp.sdk.models.message.MessageAcknowledge;
import im.crisp.sdk.models.message.MessageCompose;
import im.crisp.sdk.models.session.SessionJoined;
import im.crisp.sdk.ui.internals.adapters.CrispMessagesAdapter;
import im.crisp.sdk.ui.internals.views.CrispMediaView;
import im.crisp.sdk.utils.CrispJson;
import io.reactivex.Observable;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.RuntimePermissions;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

/**
 * Created by baptistejamin on 27/04/2017.
 */

@RuntimePermissions
public class CrispFragment extends DialogFragment implements CrispMediaView.CrispMediaViewCallback {

    TextView mToolBarTextTitle;
    TextView mToolbarTextActivity;
    EditText mTextInput;
    ImageButton mButtonAttach;
    ImageButton mButtonSend;
    ImageButton mButtonSmiley;
    CrispMediaView mMediaView;
    Toolbar mToolbar;
    ImageButton mToolbarBack;
    View mViewFile;
    ProgressBar mProgressFile;
    RecyclerView mRecyclerMessages;
    CrispMessagesAdapter mRecyclerAdapter;
    String primaryColor;
    String primaryDarkColor;

    Uri filePath;
    String fileName;
    String fileType;

    static int FILE_CODE = 42;

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.crisp_view, null);

        mToolbar = (Toolbar) view.findViewById(R.id.crisp_view_toolbar);
        mToolbarBack = (ImageButton) view.findViewById(R.id.crisp_view_toolbar_back);
        mToolbarBack.setOnClickListener((View v) -> dismiss());
        mToolBarTextTitle = (TextView) view.findViewById(R.id.crisp_view_toolbar_title);
        mToolbarTextActivity = (TextView) view.findViewById(R.id.crisp_view_toolbar_activity);
        mTextInput = (EditText) view.findViewById(R.id.crisp_input_text);
        mTextInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mTextInput.getText().length() > 0 && mButtonSend.getVisibility() == GONE) {
                    showSend();
                } else if (mTextInput.getText().length() == 0 && mButtonAttach.getVisibility() == GONE) {
                    showAttach();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });
        mButtonAttach = (ImageButton) view.findViewById(R.id.crisp_button_attach);
        mButtonAttach.setOnClickListener(v -> CrispFragmentPermissionsDispatcher.handleSendFileWithCheck(this));

        mButtonSend = (ImageButton) view.findViewById(R.id.crisp_button_send);
        mButtonSend.setOnClickListener(v -> sendTextMessage());

        mMediaView = (CrispMediaView) view.findViewById(R.id.crisp_view_media);
        mMediaView.setMediaViewCallback(this);

        mButtonSmiley = (ImageButton) view.findViewById(R.id.crisp_button_smiley);
        mButtonSmiley.setOnClickListener(v -> {
            mButtonSmiley.setSelected(!mButtonSmiley.isSelected());

            if (mButtonSmiley.isSelected()) {
                mMediaView.display();
            } else {
                mMediaView.hide();
            }
        });

        mViewFile = view.findViewById(R.id.crisp_frame_file);

        mProgressFile = (ProgressBar) view.findViewById(R.id.crisp_progress_file);

        mRecyclerMessages = (RecyclerView) view.findViewById(R.id.crisp_view_message);
        mRecyclerMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerAdapter = new CrispMessagesAdapter(getContext());
        mRecyclerMessages.setAdapter(mRecyclerAdapter);

        handleMagicType();

        primaryColor = Crisp.getChat().getPrimaryColor();
        primaryDarkColor = Crisp.getChat().getPrimaryDarkColor();

        bindTheme();
        bindText();

        syncMessages(false);
        updateHeader();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        markAllAsRead();
    }

    public void bindTheme() {
        if (primaryColor != null) {
            mToolbar.setBackgroundDrawable(new ColorDrawable(Color.parseColor(primaryColor)));
            mButtonSmiley.setColorFilter(Color.parseColor(primaryColor));
            mButtonSend.setColorFilter(Color.parseColor(primaryColor));
            mButtonAttach.setColorFilter(Color.parseColor(primaryColor));
            mRecyclerAdapter.setPrimaryColor(primaryColor);
            mRecyclerAdapter.notifyDataSetChanged();
            mMediaView.setPrimaryColor(primaryColor);
        }

        if (primaryDarkColor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getDialog().getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                getDialog().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                getDialog().getWindow().setStatusBarColor(Color.parseColor(primaryDarkColor));
            }
        }
    }

    public void bindText() {
        SessionJoined session = SharedCrisp.getInstance().getContextStore().getSession();
        if (session == null) {
            return;
        }

        if (session.settings == null || session.settings.textTheme == null) {
            return;
        }

        int id = getResources().getIdentifier("theme_text_" + session.settings.textTheme + "_chat", "string", getContext().getPackageName());

        if (id == 0) {
            return;
        }

        mToolBarTextTitle.setText(id);
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    public void updateHeader() {
        SessionJoined session = SharedCrisp.getInstance().getContextStore().getSession();
        if (session == null) {
            return;
        }

        if (session.responseMetrics == null) {
            return;
        }
        mToolbarTextActivity.setText(getResources().getString(
                R.string.chat_header_ongoing_status_metrics,
                SharedCrisp.getInstance().getDateFormat().duration(session.responseMetrics.mean)
        ));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        CrispFragmentPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CODE && resultCode == Activity.RESULT_OK) {
            Uri picked = data.getData();

            if (picked == null)
                return;


            if (picked.toString().startsWith("content://")) {
                Cursor cursor = null;
                try {
                    cursor = getActivity().getContentResolver().query(picked, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                    }
                } finally {
                    cursor.close();
                }
            } else if (picked.toString().startsWith("file://")) {
                File myFile = new File(picked.toString());
                fileName = myFile.getName();
            }

            filePath = picked;

            fileType = getActivity().getContentResolver().getType(picked);

            SharedCrisp.getInstance().getSocket().getBucket().generateBucket(fileName, fileType);

            mViewFile.setVisibility(VISIBLE);
        }
    }

    private void showSend() {
        YoYo.with(Techniques.SlideInUp)
                .duration(350).withListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                mButtonSend.setVisibility(VISIBLE);
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
        }).playOn(mButtonSend);
        YoYo.with(Techniques.SlideOutUp)
                .duration(350).withListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mButtonAttach.setVisibility(GONE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        }).playOn(mButtonAttach);
    }

    private void showAttach() {
        YoYo.with(Techniques.SlideOutDown)
                .duration(350).withListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mButtonSend.setVisibility(GONE);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        }).playOn(mButtonSend);
        YoYo.with(Techniques.SlideInDown)
                .duration(350).withListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                mButtonAttach.setVisibility(VISIBLE);
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
        }).playOn(mButtonAttach);
    }

    private void sendTextMessage() {
        if (mTextInput.getText().length() > 0) {
            SharedCrisp.getInstance().getSocket().getMessage().sendTextMessage(mTextInput.getText().toString());
        }
        mTextInput.getText().clear();
    }

    private void handleMagicType() {
        Observable<String> obs = RxTextView.textChanges(mTextInput)
                .debounce(300, TimeUnit.MILLISECONDS)
                .map(charSequence -> charSequence.toString());

        obs.subscribe(string -> {
            if (string != null && string.length() > 0) {
                SharedCrisp.getInstance().getSocket().getMessage().composeSend("start", string);
            } else {
                SharedCrisp.getInstance().getSocket().getMessage().composeSend("stop", null);
            }
        });
    }

    @NeedsPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    void handleSendFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_CODE);
    }


    public void uploadFile(String signedUrl, final String ressourceUrl) {
        try {

            File outputDir = getContext().getCacheDir();
            File file = File.createTempFile("SharedCrisp", "tmp", outputDir);
            FileOutputStream fos = new FileOutputStream(file);

            InputStream is = getContext().getContentResolver().openInputStream(filePath);
            byte[] buffer = new byte[1024];
            int len = 0;
            try {
                len = is.read(buffer);
                while (len != -1) {
                    fos.write(buffer, 0, len);
                    len = is.read(buffer);
                }

                fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            String uploadId =
                    new BinaryUploadRequest(getContext(), signedUrl)
                            .setMethod("PUT")
                            .addHeader("Content-Disposition", "attachment")
                            .addHeader("Content-Type", fileType)
                            .setFileToUpload(file.getPath())
                            .setNotificationConfig(
                                    new UploadNotificationConfig()
                                            .setAutoClearOnSuccess(true)
                            )
                            .setMaxRetries(2)
                            .setDelegate(new UploadStatusDelegate() {
                                @Override
                                public void onProgress(UploadInfo uploadInfo) {
                                    mProgressFile.setProgress(uploadInfo.getProgressPercent());
                                }

                                @Override
                                public void onError(UploadInfo uploadInfo, Exception exception) {
                                    file.delete();
                                    mViewFile.setVisibility(GONE);
                                }

                                @Override
                                public void onCompleted(UploadInfo uploadInfo, ServerResponse serverResponse) {
                                    JsonObject fileObject = new JsonObject();
                                    fileObject.add("name", new JsonPrimitive(fileName));
                                    fileObject.add("type", new JsonPrimitive(fileType));
                                    fileObject.add("url", new JsonPrimitive(ressourceUrl));
                                    SharedCrisp.getInstance().getSocket().getMessage().sendFileMessage(fileObject);
                                    file.delete();
                                    mViewFile.setVisibility(GONE);
                                }

                                @Override
                                public void onCancelled(UploadInfo uploadInfo) {
                                    file.delete();
                                    mViewFile.setVisibility(GONE);
                                }
                            })
                            .startUpload();
        } catch (Exception exc) {

        }
    }

    private void syncMessages(boolean scroll) {
        ArrayList messages = SharedCrisp.getInstance().getContextStore().getMessages();
        if (messages != null) {
            mRecyclerAdapter.setMessages(SharedCrisp.getInstance().getContextStore().getMessages());
            mRecyclerAdapter.notifyDataSetChanged();
            if (scroll)
                mRecyclerMessages.smoothScrollToPosition(mRecyclerMessages.getAdapter().getItemCount());
            else
                mRecyclerMessages.scrollToPosition(mRecyclerMessages.getAdapter().getItemCount() - 1);
        }
    }

    private void markAllAsRead() {
        SharedCrisp.getInstance().getSocket().getMessage().acknowledgeMessagesRead(
                SharedCrisp.getInstance().getContextStore().getUnreadMessages()
        );
        SharedCrisp.getInstance().getContextStore().clearUnreadMessages();
        EventBus.getDefault().post(new MessageAcknowledge());
    }

    @Override
    public void onMediaHidden() {
        mButtonSmiley.setSelected(false);
    }

    @Override
    public void onSmileySelected(String smiley) {
        mTextInput.setText(mTextInput.getText() + smiley);
        mTextInput.setSelection(mTextInput.getText().length());
        mMediaView.hide();
    }

    @Override
    public void onGifSelected(String gif) {
        mMediaView.hide();
        SharedCrisp.getInstance().getSocket().getMessage().sendAnimationMessage(gif);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(BucketGenerated event) {
        uploadFile(event.url.signed, event.url.resource);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(SessionJoined event) {
        syncMessages(true);
        updateHeader();
        bindText();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(List<Message> event) {
        syncMessages(true);
        markAllAsRead();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Message event) {
        syncMessages(true);
        markAllAsRead();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(MessageAcknowledge event) {
        syncMessages(true);
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(MessageCompose event) {
        mRecyclerMessages.smoothScrollToPosition(mRecyclerMessages.getAdapter().getItemCount());
        mRecyclerAdapter.setCompose(event);
        mRecyclerAdapter.notifyDataSetChanged();
    }
}
