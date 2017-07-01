# Crisp Android SDK

Integrate the Crisp Livechat in a native Android app.

## Prerequisites

Your activities should extend AppCompatActivity instead on Activity

## Setup

First, add the Crisp Maven replository
```groovy
repositories {
  maven {
    url  "http://dl.bintray.com/crispim/crisp-maven"
  }
}
```

Then, add the Crisp SDK in your dependencies:

```groovy
compile 'im.crisp:crisp-sdk:0.0.4'
```

Initialize the library in your [Application subclass](http://developer.android.com/reference/android/app/Application.html):
```java
public class Initializer extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Replace it with your WEBSITE_ID
        Crisp.initialize(this, "b7399616-babb-4075-861f-b0ca5eb1d0ea");
        
        // You can also set a custom color
        // Crisp.getChat().setPrimaryColor("#9012FE");
    }
}
```

## Include Crisp in your views

You can embed the CrispBubble in a FrameLayout, or a CoordinatorLayout. Place it preferably in the bottom right corner.

```xml
<im.crisp.sdk.ui.CrispBubble
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|right"
        android:layout_margin="18dp" />
```

## Create a custom button

```java
final AppCompatActivity ctx = this;
Button button = (Button) findViewById(R.id.button);
button.setOnClickListener(new View.OnClickListener() {
	@Override
	public void onClick(View v) {
		Crisp.getChat().open(ctx);
	}
});
```

## Availables APIs:

* `Crisp.getChat().setPrimaryColor("#9012FE");`
* `Crisp.getChat().setPrimaryDarkColor("#9012FE");`
* `Crisp.getChat().open(ctx);`
* `Crisp.getChat().close();`
* `Crisp.getUser().setEmail("john@doe.com");`
* `Crisp.getUser().setNickname("John");`
* `Crisp.getUser().setPhone("+44164642242");`
* `Crisp.getUser().setAvatar("https://pbs.twimg.com/profile_images/782474226020200448/zDo-gAo0_400x400.jpg");`
* `Crisp.getUser().getEmail();`
* `Crisp.getUser().getNickname();`
* `Crisp.getUser().getPhone();`
* `Crisp.getUser().getAvatar("https://pbs.twimg.com/profile_images/782474226020200448/zDo-gAo0_400x400.jpg");`
