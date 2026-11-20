package im.crisp.sdk.internal.notification

import android.app.Activity

// The screens of an app, declared in the manifest of the tests, see CrispNotificationTapTest

/** A standard launcher `Activity`. */
class TestLauncherActivity : Activity()

/** A `singleTask` launcher `Activity`. */
class TestSingleTaskLauncherActivity : Activity()

/** A `singleInstance` launcher `Activity`, alone in its task. */
class TestSingleInstanceLauncherActivity : Activity()

/** A launcher `Activity` with its own `taskAffinity`, other than the package. */
class TestAffinityLauncherActivity : Activity()

/** A screen of the app, opened above its launcher `Activity`. */
class TestScreenActivity : Activity()
