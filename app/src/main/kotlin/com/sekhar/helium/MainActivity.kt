package com.sekhar.helium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sekhar.helium.core.ui.theme.HeliumColors
import com.sekhar.helium.core.ui.theme.HeliumTheme
import com.sekhar.helium.ui.HeliumApp

/**
 * The app's only Activity.
 *
 * Everything else is Compose, so there is a single back stack and a single
 * `ViewModelStore` owner for the editor — which is what lets the editor survive
 * configuration changes and process death without any special handling.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as HeliumApplication).container
        setContent {
            HeliumTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = HeliumColors.Background,
                ) {
                    HeliumApp(container = container)
                }
            }
        }
    }
}
