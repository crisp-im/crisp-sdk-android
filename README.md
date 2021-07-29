```
allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
 
 

	dependencies {
	        implementation 'com.github.nilevia:crisp-sdk-android:1.0.5-niel'
	}
```
 
 

## Get your website ID

Your website ID can be found in the Crisp App URL:

- https://app.crisp.chat/website/[WEBISTE_ID]/inbox/

Crisp Website ID is an UUID like e30a04ee-f81c-4935-b8d8-5fa55831b1c0

## Setup

Add the Crisp SDK in your dependencies:

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

* `Crisp.setLocale("it")`
* `Crisp.User.setEmail("john.doe@gmail.com");`
* `Crisp.User.setNickname("John Doe");`
* `Crisp.User.setPhone("003370123456789");`
* `Crisp.User.setAvatar("https://pbs.twimg.com/profile_images/782474226020200448/zDo-gAo0_400x400.jpg");`
* `Crisp.Session.setData("key", "value");`
* `Crisp.Session.setData(HashMap<String, String>);`
* `Crisp.Session.setSegments("segment");`
* `Crisp.Session.setSegments("segment1", "segment2");`
* `Crisp.Session.pushEvent("signup", JSONObject, "blue");`
* `Crisp.Session.reset();`
