package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mediapicker.data.loader.WeChatImageLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("WeChat Picker", appName)
  }

  @Test
  fun `verify image loader memory bounds`() {
    val loader = WeChatImageLoader.instance
    val snapshot = loader.getMemorySnapshot()
    org.junit.Assert.assertTrue(snapshot.usedHeapMb > 0f)
    org.junit.Assert.assertTrue(snapshot.isBoundedAndSafe)
  }
}
