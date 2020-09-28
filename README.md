![Crisp](https://raw.githubusercontent.com/crisp-im/crisp-sdk-android/master/docs/img/logo_blue.png)

Chat with app users, integrate your favorite tools, and deliver a great customer experience.

# Crisp Android SDK

![Crisp screenshot](https://raw.githubusercontent.com/crisp-im/crisp-sdk-android/master/docs/img/crisp_screenshot.png)

 [ ![Download](https://api.bintray.com/packages/crispim/crisp-maven/crisp-sdk-android/images/download.svg) ](https://bintray.com/crispim/crisp-maven/crisp-sdk-android/_latestVersion)
[![Twitter](https://img.shields.io/badge/twitter-@crisp_im-blue.svg?style=flat)](http://twitter.com/crisp_im)

## Get your website ID

Your website ID can be found in the Crisp App URL:

- https://app.crisp.chat/website/[WEBISTE_ID]/inbox/

Crisp Website ID is an UUID like e30a04ee-f81c-4935-b8d8-5fa55831b1c0

## Setup

Add our bintray in your repositories
```groovy
repositories {
    // Keep your prevous repositories
    maven {
        url 'https://dl.bintray.com/crispim/crisp-maven'
    }
}
```

Add the Crisp SDK in your dependencies:

```groovy
implementation 'im.crisp:crisp-sdk:1.0.0beta0'
```

Initialize the library in your [Application subclass](http://developer.android.com/reference/android/app/Application.html):
```java
public class Initializer extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Replace it with your WEBSITE_ID
        // Retrieve it using https://app.crisp.chat/website/[YOUR_WEBSITE_ID]/
        Crisp.configure("7598bf86-9ebb-46bc-8c61-be8929bbf93d");
    }
}
```

## Include Crisp in your activities

You can for instance start Crisp after a click on a button
```java
Intent crispIntent = new Intent(this, ChatActivity.class);
startActivity(crispIntent);
```

## Availables APIs:

Work in progresss