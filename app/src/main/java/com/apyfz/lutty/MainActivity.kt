package com.apyfz.lutty

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apyfz.lutty.media.CodecProbe
import com.apyfz.lutty.ui.EditorScreen
import com.apyfz.lutty.ui.EditorViewModel
import com.apyfz.lutty.ui.LutBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The interface is always dark, whatever the system is set to.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        CodecProbe.logAll()
        setContent {
            LutBoxTheme {
                val vm: EditorViewModel = viewModel()
                EditorScreen(vm)
            }
        }
    }
}
