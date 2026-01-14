package com.ne_bknn.adbinstaller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ne_bknn.adbinstaller.logging.LogStore
import com.ne_bknn.adbinstaller.ui.theme.ADBInstallerTheme

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ADBInstallerTheme {
                LogScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen() {
    val context = LocalContext.current
    val store = remember { LogStore(context.applicationContext) }
    var text by remember { mutableStateOf(store.readText()) }

    fun refresh() {
        text = store.readText()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Logs (${BuildConfig.GIT_SHA})") })
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { refresh() }) { Text("Refresh") }

                Button(
                    enabled = text.isNotBlank(),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ADBInstaller log", text))
                    },
                ) { Text("Copy") }

                Button(
                    onClick = {
                        val share = store.buildShareIntent()
                        if (share != null) context.startActivity(android.content.Intent.createChooser(share, "Share log"))
                    },
                ) { Text("Share") }

                Button(
                    onClick = {
                        store.clear()
                        refresh()
                    },
                ) { Text("Clear") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                text = if (text.isBlank()) "(empty)" else text,
            )
        }
    }
}


