package im.crisp.sdk.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;

import im.crisp.sdk.Crisp;
import im.crisp.sdk.R;

/**
 * Created by baptistejamin on 29/12/2017.
 */

public class CrispFragment extends Fragment {
    private static final String TAG = CrispFragment.class.getSimpleName();

    public static final int INPUT_FILE_REQUEST_CODE = 1;

    static WebView mWebView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private String mCameraPhotoPath;

    private static LinkedList<String> commandQueue = new LinkedList<String>();

    public static boolean isLoaded = false;

    public CrispFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.crisp_view, container, false);

        // Get reference of WebView from layout/activity_main.xml
        mWebView = (WebView) rootView.findViewById(R.id.crisp_view_webview);

        setUpWebViewDefaults(mWebView);

        // Check whether we're recreating a previously destroyed instance
        if (savedInstanceState != null) {
            // Restore the previous URL and history stack
            mWebView.restoreState(savedInstanceState);
        }

        // Set the web view client
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                isLoaded = true;
                flushQueue();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("mailto")) {
                    handleMailToLink(url);
                    return true;
                }

                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = "";
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    url = request.getUrl().toString();
                }

                if (url.startsWith("mailto")) {
                    handleMailToLink(url);
                    return true;
                }

                if (url.startsWith("tel:")) {
                    handleTelToLink(url);
                    return true;
                }

                if (url.startsWith("intent")) {
                    handleIntentToLink(url);
                    return true;
                }


                return false;
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            public boolean onShowFileChooser(
                    WebView webView, ValueCallback<Uri[]> filePathCallback,
                    WebChromeClient.FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (takePictureIntent.resolveActivity(getActivity().getPackageManager()) != null) {
                    // Create the File where the photo should go
                    File photoFile = null;
                    try {
                        photoFile = createImageFile();
                        takePictureIntent.putExtra("PhotoPath", mCameraPhotoPath);
                    } catch (IOException ex) {
                        // Error occurred while creating the File
                        //Log.e(TAG, "Unable to create Image File", ex);
                    }

                    // Continue only if the File was successfully created
                    if (photoFile != null) {
                        mCameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                                Uri.fromFile(photoFile));
                    } else {
                        takePictureIntent = null;
                    }
                }

                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentSelectionIntent.setType("image/*");

                Intent[] intentArray;
                if (takePictureIntent != null) {
                    intentArray = new Intent[]{takePictureIntent};
                } else {
                    intentArray = new Intent[0];
                }

                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Image Chooser");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);

                startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE);

                return true;
            }
        });

        mWebView.loadUrl("file:///android_asset/index.html");

        load();
        return rootView;
    }

    /**
     * More info this method can be found at
     * http://developer.android.com/training/camera/photobasics.html
     *
     * @return
     * @throws IOException
     */
    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File imageFile = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );
        return imageFile;
    }

    /**
     * Convenience method to set some generic defaults for a
     * given WebView
     *
     * @param webView
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void setUpWebViewDefaults(WebView webView) {
        WebSettings settings = webView.getSettings();

        // Enable Javascript
        settings.setJavaScriptEnabled(true);

        // Use WideViewport and Zoom out if there is no viewport defined
        settings.setUseWideViewPort(true);

        settings.setLoadWithOverviewMode(true);

        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);



        // Enable pinch to zoom without the zoom buttons
        settings.setBuiltInZoomControls(true);

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.HONEYCOMB) {
            // Hide the zoom controls for HONEYCOMB+
            settings.setDisplayZoomControls(false);
        }

        // Enable remote debugging via chrome://inspect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // We set the WebViewClient to ensure links are consumed by the WebView rather
        // than passed to a browser if it can
        mWebView.setWebViewClient(new WebViewClient());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != INPUT_FILE_REQUEST_CODE || mFilePathCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        Uri[] results = null;

        // Check that the response is a good one
        if (resultCode == Activity.RESULT_OK) {
            if (data == null) {
                // If there is not data, then we may have taken a photo
                if (mCameraPhotoPath != null) {
                    results = new Uri[]{Uri.parse(mCameraPhotoPath)};
                }
            } else {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
        }

        mFilePathCallback.onReceiveValue(results);
        mFilePathCallback = null;
        return;
    }

    @Override
    public void onDetach() {
        isLoaded = false;
        super.onDetach();
    }

    protected void handleIntentToLink(String url) {
        try {
            Intent intent= Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            startActivity(intent);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
        }
    }

    protected void handleTelToLink(String url) {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse(url));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
        }
    }

    protected void handleMailToLink(String url) {
        // Initialize a new intent which action is send
        Intent intent = new Intent(Intent.ACTION_SEND);


        // For only email app handle this intent
        intent.setData(Uri.parse("mailto:"));

        intent.setType("plain/text");

        // Empty the text view
        // Extract the email address from mailto url
        String to = url.split("[:?]")[1];
        if (!TextUtils.isEmpty(to)) {
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
        }


        // Extract the subject
        if (url.contains("subject=")) {
            String subject = url.split("subject=")[1];
            if (!TextUtils.isEmpty(subject)) {
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
        }

        // Extract the body
        if (url.contains("body=")) {
            String body = url.split("body=")[1];
            if (!TextUtils.isEmpty(body)) {
                body = body.split("&")[0];
                // Encode the body text
                try {
                    body = URLDecoder.decode(body, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
                // Put the mail body into intent
                intent.putExtra(Intent.EXTRA_TEXT, body);
            }
        }

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
        }
    }

    static void callJavascript(String script) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            mWebView.evaluateJavascript(script, null);
        } else {
            mWebView.loadUrl("javascript:" + script);
        }
    }

    static void flushQueue() {
        Iterator iterator = commandQueue.iterator();
        while (iterator.hasNext()) {
            String script = (String) iterator.next();
            callJavascript(script);
        }
        commandQueue.clear();
    }

    public static void load() {
        if (Crisp.getInstance() == null) {
            Log.e("Crisp", "============================================");
            Log.e("Crisp", "Please instantiate Crisp from your Application class");
            Log.e("Crisp", "============================================");
        }

        if (Crisp.getInstance().getTokenId() != null && !Crisp.getInstance().getTokenId().isEmpty()) {
            execute("window.CRISP_TOKEN_ID = \"" + Crisp.getInstance().getTokenId() + "\";");
        }

        if (Crisp.getInstance().getWebsiteId() != null) {
            execute("window.CRISP_WEBSITE_ID = \"" + Crisp.getInstance().getWebsiteId() + "\";");
        }

        if (Crisp.getInstance().getLocale() != null) {
            execute("window.CRISP_RUNTIME_CONFIG.locale = \"" + Crisp.getInstance().getLocale() + "\";");
        }

        execute("initialize()");
    }

    public static void execute(String script) {
        commandQueue.add(script);
        if (isLoaded) {
            flushQueue();
        }
    }
}
