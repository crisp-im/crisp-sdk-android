package im.crisp.sdk.services.wrappers;

import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;

import im.crisp.sdk.R;
import im.crisp.sdk.SharedCrisp;
import im.crisp.sdk.ui.CrispFragment;

/**
 * Created by baptistejamin on 19/05/2017.
 */

public class Chat {
    SharedCrisp crisp;
    private String primaryColor;
    private String primaryDarkColor;

    CrispFragment mCrispDialog;

    public Chat(SharedCrisp crisp) {
        this.crisp = crisp;
    }

    public String getPrimaryColor() {
        if (primaryColor == null) {
            return "#" + Integer.toHexString(ContextCompat.getColor(crisp.getContext(), R.color.crisp_primary));
        }
        return primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public String getPrimaryDarkColor() {
        if (primaryDarkColor == null) {
            return "#" + Integer.toHexString(ContextCompat.getColor(crisp.getContext(), R.color.crisp_primary));
        }
        return primaryColor;
    }

    public void setPrimaryDarkColor(String primaryDarkColor) {
        this.primaryDarkColor = primaryDarkColor;
    }

    public void open(AppCompatActivity localContext) {
        mCrispDialog = new CrispFragment();

        mCrispDialog.setStyle(CrispFragment.STYLE_NORMAL, R.style.CrispFragmentTheme);
        mCrispDialog.show(localContext.getSupportFragmentManager(), "Crisp");
    }

    public void close() {
        if (mCrispDialog != null && mCrispDialog.isVisible()) {
            mCrispDialog.dismiss();
        }
    }


}
