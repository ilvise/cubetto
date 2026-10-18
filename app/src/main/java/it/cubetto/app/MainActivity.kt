package it.cubetto.app

import android.Manifest
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import it.cubetto.app.ui.AppViewModel
import it.cubetto.app.ui.CubettoRoot
import it.cubetto.app.ui.LaunchEvent
import it.cubetto.app.ui.theme.CubettoTheme

class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()
    private val event = mutableStateOf<LaunchEvent?>(null)
    private val container get() = (application as CubettoApp).container

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CubettoTheme {
                CubettoRoot(vm, event.value) { event.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        container.nfc.attach(this)
    }

    override fun onPause() {
        container.nfc.detach(this)
        super.onPause()
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                URL.find(text)?.value?.let { event.value = LaunchEvent.Shared(it) }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                container.usb.refresh()
                event.value = LaunchEvent.UsbAttached
            }
        }
    }

    companion object {
        private val URL = Regex("""https?://\S+""")
    }
}
