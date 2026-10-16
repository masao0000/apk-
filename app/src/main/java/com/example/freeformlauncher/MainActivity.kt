package com.example.freeformlauncher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
    @Volatile private var binderReady = false

    private val binderListener = Shizuku.OnBinderReceivedListener { binderReady = true; refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { binderReady = false; refresh() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private fun refresh() {
        shizukuState = try {
            when {
                !Shizuku.pingBinder() -> "Shizuku が起動していません"
                !binderReady -> "確認中…" // 接続処理中。完了すると binderListener から再度呼ばれる
                Shizuku.isPreV11() -> "Shizuku が古すぎます"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "許可済み"
                else -> "未許可"
            }
        } catch (e: Exception) {
            "エラー: $e"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        refresh()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Screen() } } }
    }

    override fun onResume() {
        super.onResume()
        refresh() // Shizuku アプリ側で起動・許可して戻ってきた場合に反映
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
        val activity = remember(pkg) { FreeformLauncher.resolveActivity(this, pkg) }
        var left by rememberSaveable { mutableStateOf("4") }
        var top by rememberSaveable { mutableStateOf("4") }
        var right by rememberSaveable { mutableStateOf("4") }
        var bottom by rememberSaveable { mutableStateOf("4") }
        var avoidStatusBar by rememberSaveable { mutableStateOf(true) }
        var running by remember { mutableStateOf(false) }
        var report by remember { mutableStateOf<LaunchReport?>(null) }

        val values = listOf(left, top, right, bottom).map { it.toIntOrNull() }
        val inputOk = values.all { it != null }
        val topNegative = (values[1] ?: 0) < 0

        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Shizuku: $shizukuState")
            if (shizukuState == "未許可") {
                Button(onClick = { try { Shizuku.requestPermission(0) } catch (e: Exception) { refresh() } }) {
                    Text("権限をリクエスト")
                }
            }

            Text("対象: $pkg")
            Text("Activity: ${activity ?: "（未インストール）"}")

            Text("余白 (px)  ※上はマイナス可（実験）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MarginField("左", left, false) { left = it }
                MarginField("上", top, true) { top = it }
                TextButton(onClick = { top = if (top.startsWith("-")) top.drop(1) else "-$top" }) { Text("±") }
                MarginField("右", right, false) { right = it }
                MarginField("下", bottom, false) { bottom = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = avoidStatusBar, onCheckedChange = { avoidStatusBar = it }, enabled = !topNegative)
                Text("上端をステータスバーの下にそろえる（OS が下へずらすのを防ぐ）")
            }
            if (topNegative) {
                Text(
                    "上がマイナスのときは am ではなく app_process から直接リサイズします（実験）。" +
                        "OS が上端を補正した場合は「適用」に実際の値が出ます。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            fun run(block: () -> LaunchReport) {
                running = true
                report = null
                scope.launch {
                    report = withContext(Dispatchers.IO) {
                        try { block() } catch (t: Throwable) {
                            LaunchReport(false, "内部エラー: $t", log = t.stackTraceToString())
                        }
                    }
                    running = false
                }
            }
            val ready = !running && shizukuState == "許可済み"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ready && inputOk, onClick = {
                    val m = Margins(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
                    val avoid = avoidStatusBar
                    run { FreeformLauncher.launch(this@MainActivity, pkg, m, avoid) }
                }) { Text(if (running) "実行中…" else "起動") }
                Button(enabled = ready && inputOk, onClick = {
                    val m = Margins(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
                    val avoid = avoidStatusBar
                    run { FreeformLauncher.launch(this@MainActivity, pkg, m, avoid, startApp = false) }
                }) { Text("リサイズのみ") }
                OutlinedButton(enabled = ready, onClick = {
                    run { FreeformLauncher.diagnose(this@MainActivity, pkg) }
                }) { Text("診断") }
            }
            Text(
                "「リサイズのみ」：対象アプリを手でフローティングウィンドウにしてから押すと、そのウィンドウを指定サイズにします。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!inputOk) Text("余白に数値を入力してください", color = MaterialTheme.colorScheme.error)

            report?.let { r ->
                Text(r.message, color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                r.requested?.let { Text("指定: ${it.toShortString()}") }
                r.applied?.let { Text("適用: ${it.toShortString()}") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ログ", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { copyToClipboard(r) }) { Text("ログをコピー") }
                }
                SelectionContainer {
                    Text(r.log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }

    private fun copyToClipboard(r: LaunchReport) {
        val text = buildString {
            appendLine(r.message)
            r.requested?.let { appendLine("指定: ${it.toShortString()}") }
            r.applied?.let { appendLine("適用: ${it.toShortString()}") }
            append(r.log)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", text))
        Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun MarginField(label: String, value: String, allowNegative: Boolean, onChange: (String) -> Unit) {
        val pattern = if (allowNegative) Regex("-?\\d{0,4}") else Regex("\\d{0,4}")
        OutlinedTextField(
            value, { if (pattern.matches(it)) onChange(it) }, Modifier.width(90.dp),
            label = { Text(label) }, singleLine = true, isError = value.toIntOrNull() == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
