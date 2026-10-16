package com.example.freeformlauncher

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private var shizukuState by mutableStateOf("確認中…")

    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private fun refresh() {
        shizukuState = when {
            !Shizuku.pingBinder() -> "Shizuku が起動していません"
            Shizuku.isPreV11() -> "Shizuku が古すぎます"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "許可済み"
            else -> "未許可"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Screen() } } }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        val pkg = FreeformLauncher.DEFAULT_PACKAGE
        var left by remember { mutableStateOf("4") }
        var top by remember { mutableStateOf("4") }
        var right by remember { mutableStateOf("4") }
        var bottom by remember { mutableStateOf("4") }
        var running by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<LaunchReport?>(null) }

        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Shizuku: $shizukuState")
            if (shizukuState == "未許可") Button(onClick = { Shizuku.requestPermission(0) }) { Text("権限をリクエスト") }

            Text("対象: $pkg")
            Text("Activity: ${FreeformLauncher.resolveActivity(this@MainActivity, pkg) ?: "（未インストール）"}")

            Text("余白 (px)  ※上はマイナス可")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarginField("左", left, false) { left = it }
                MarginField("上", top, true) { top = it }
                MarginField("右", right, false) { right = it }
                MarginField("下", bottom, false) { bottom = it }
            }

            Button(enabled = !running && shizukuState == "許可済み", onClick = {
                running = true
                val m = Margins(left.toIntOrNull() ?: 0, top.toIntOrNull() ?: 0,
                    right.toIntOrNull() ?: 0, bottom.toIntOrNull() ?: 0)
                scope.launch {
                    report = withContext(Dispatchers.IO) { FreeformLauncher.launch(this@MainActivity, pkg, m) }
                    running = false
                }
            }) { Text(if (running) "実行中…" else "起動") }

            report?.let { r ->
                Text(r.message, color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                r.requested?.let { Text("指定: ${it.toShortString()}") }
                r.applied?.let { Text("適用: ${it.toShortString()}") }
                Text("ログ", style = MaterialTheme.typography.titleSmall)
                Text(r.log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun MarginField(label: String, value: String, allowNegative: Boolean, onChange: (String) -> Unit) {
        val pattern = if (allowNegative) Regex("-?\\d{0,4}") else Regex("\\d{0,4}")
        OutlinedTextField(value, { if (pattern.matches(it)) onChange(it) }, Modifier.width(90.dp),
            label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
}
