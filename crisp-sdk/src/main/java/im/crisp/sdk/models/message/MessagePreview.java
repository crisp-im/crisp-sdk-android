package im.crisp.sdk.models.message;

import com.google.gson.annotations.SerializedName;

/**
 * Created by baptistejamin on 28/04/2017.
 */

public class MessagePreview {

    @SerializedName("url")
    private String url;

    @SerializedName("preview")
    private MessagePreviewImage preview;

    @SerializedName("title")
    private String title;

    @SerializedName("website")
    private String website;

    public MessagePreview() {

    }

    public MessagePreview(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public MessagePreviewImage getPreview() {
        return preview;
    }

    public void setPreview(MessagePreviewImage preview) {
        this.preview = preview;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public class MessagePreviewImage {

        @SerializedName("image")
        private String image;
        @SerializedName("excerpt")
        private String excerpt;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getExcerpt() {
            return excerpt;
        }

        public void setExcerpt(String excerpt) {
            this.excerpt = excerpt;
        }
    }
}
