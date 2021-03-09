![Crisp](https://raw.githubusercontent.com/crisp-im/crisp-sdk-android/master/docs/img/logo_blue.png)

Chat with app users, integrate your favorite tools, and deliver a great customer experience.

# Crisp Android SDK

![Crisp screenshot](https://raw.githubusercontent.com/crisp-im/crisp-sdk-android/master/docs/img/crisp_screenshot.png)

 [ ![Download](https://api.bintray.com/packages/crispim/crisp-maven/crisp-sdk-android/images/download.svg) ](https://bintray.com/crispim/crisp-maven/crisp-sdk-android/_latestVersion)
[![Twitter](https://img.shields.io/badge/twitter-@crisp_im-blue.svg?style=flat)](http://twitter.com/crisp_im)

## How to use

### Video tutorial


<p align="left">
  <a href="https://www.youtube.com/watch?v=g5H0F-eTWwc">
    <img alt="Play Introduction Video" src="https://img.youtube.com/vi/g5H0F-eTWwc/0.jpg" width="560">
  </a>
</p>

### 1. Get your Website ID

Your website ID can be found in the Crisp App URL:

- https://app.crisp.chat/website/[WEBISTE_ID]/inbox/

Crisp Website ID is an UUID like e30a04ee-f81c-4935-b8d8-5fa55831b1c0

### 2. Setup Bintray

Add our bintray in your repositories
```groovy
repositories {
    // Keep your prevous repositories
    maven {
        url 'https://dl.bintray.com/crispim/crisp-maven'
    }
}
```

### 3. Add Crisp Dependency Bintray

Add the Crisp SDK in your dependencies:

```groovy
dependencies {
    implementation 'im.crisp:crisp-sdk:1.0.1'
}
```

### 4. Setup Multidex 

Configure your app for multidex:
```groovy
android {
    defaultConfig {
        multiDexEnabled true
    }
}
dependencies {
    // If you're using AndroidX
    implementation 'androidx.multidex:multidex:2.0.1'
    // If you're not using AndroidX
    implementation 'com.android.support:multidex:1.0.3'
}
```

### 5. Initiate Appplicatation class


Initialize the library in your [Application subclass](http://developer.android.com/reference/android/app/Application.html):
```java
public class Initializer extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();

        // Replace it with your WEBSITE_ID
        // Retrieve it using https://app.crisp.chat/website/[YOUR_WEBSITE_ID]/
        Crisp.configure(getApplicationContext(), "7598bf86-9ebb-46bc-8c61-be8929bbf93d");
    }
}
```

### 5. Include Crisp in your Activity


You can for instance start Crisp after a click on a button
```java
Intent crispIntent = new Intent(this, ChatActivity.class);
startActivity(crispIntent);
```

## Availables APIs:

Work in progresss
