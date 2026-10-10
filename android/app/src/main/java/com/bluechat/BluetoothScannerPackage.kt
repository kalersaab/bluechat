package com.bluechat

import com.android.bluechat.NativeBluetoothScannerSpec
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class BluetoothScannerPackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      if (name == NativeBluetoothScannerSpec.NAME) {
        BluetoothScannerModule(reactContext)
      } else {
        null
      }

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
      ReactModuleInfoProvider {
        mapOf(
            NativeBluetoothScannerSpec.NAME to
                ReactModuleInfo(
                    NativeBluetoothScannerSpec.NAME,
                    BluetoothScannerModule::class.java.name,
                    false,
                    false,
                    false,
                    true,
                )
        )
      }
}
