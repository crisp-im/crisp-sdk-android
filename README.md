![Crisp](https://raw.githubusercontent.com/crisp-im/crisp-sdk-android/master/docs/img/logo_blue.png)

Chat with app users, integrate your favorite tools, and deliver a great customer experience.

# Crisp Android SDK

![Crisp screenshot](https://raw.githubusercontent.com/crisp-im/crisp-sdk-android/master/docs/img/crisp_screenshot.png)

 [ ![Download](https://api.bintray.com/packages/crispim/crisp-maven/crisp-sdk-android/images/download.svg) ](https://bintray.com/crispim/crisp-maven/crisp-sdk-android/_latestVersion)
[![Twitter](https://img.shields.io/badge/twitter-@crisp_im-blue.svg?style=flat)](http://twitter.com/crisp_im)

## Setup

Add the Crisp SDK in your dependencies:

```groovy
compile 'im.crisp:crisp-sdk:0.1.1'
```

Initialize the library in your [Application subclass](http://developer.android.com/reference/android/app/Application.html):
```java
public class Initializer extends Application {

    @Override
    public void onCreate() {
        super.onCreate();


        Crisp.initialize(this);
        // Replace it with your WEBSITE_ID
        // Retrieve it using https://app.crisp.chat/website/[YOUR_WEBSITE_ID]/
        Crisp.getInstance().setWebsiteId("7598bf86-9ebb-46bc-8c61-be8929bbf93d");
    }
}
```

## Include Crisp in your views

You can embed the CrispFragment in your Activity
```xml
<fragment
    android:name="im.crisp.sdk.ui.CrispFragment"
    android:tag="crisp_fragment"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:layout_margin="0dp" />
```

## Availables APIs:

* `Crisp.User.setEmail("john.doe@gmail.com");`
* `Crisp.User.setNickname("John Doe");`
* `Crisp.User.setPhone("003370123456789");`
* `Crisp.User.setAvatar("https://pbs.twimg.com/profile_images/782474226020200448/zDo-gAo0_400x400.jpg");`
* `Crisp.Session.setData("key", "value");`
* `Crisp.Session.setSegments("segment");`
* `Crisp.Session.setSegments("segment1", "segment2");`
