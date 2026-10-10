package com.bluechat

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "bluechat"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // With edge-to-edge enabled (edgeToEdgeEnabled=true / targetSdk 35+), the window is no longer
    // resized for the soft keyboard, so windowSoftInputMode="adjustResize" is a no-op and inputs
    // get covered or the layout collapses. Apply the IME inset as bottom padding ourselves.
    val rootView = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
      val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
      view.setPadding(
          view.paddingLeft,
          view.paddingTop,
          view.paddingRight,
          ime.bottom,
      )
      insets
    }
  }
}
