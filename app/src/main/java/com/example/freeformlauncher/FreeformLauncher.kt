package com.example.freeformlauncher

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager

data class Margins(val left: Int = 4, val top: Int = 4, val right: Int = 4, val bottom: Int = 4)

data class LaunchReport(
    val success: Boolean,
    val message: String,
    val requested: Rect? = null,
    val applied: Rect? = null,
    val log: String = "",
)

/** am stack list の taskId 行 1 つ分 */
data class TaskEntry(val id: Int, val component: String, val bounds: Rect?, val userId: Int?, val mode: String?)

/**
 * 1. am start --windowingMode 5 でフリーフォーム起動
 * 2. am stack list から taskId とウィンドウモードを取得
 * 3. am task resize（上が負のときは app_process 経由）で「画面 - 余白」に変更
 * 4. 実際に適用された矩形を taskId で読み戻す
 */
object FreeformLauncher {
    const val DEFAULT_PACKAGE = "com.linecorp.LGTMTM" // LINE ディズニー ツムツム

    private val rootTaskRe = Regex("""^\s*RootTask id=""")
    private val modeRe = Regex("""\bmWindowingMode=([\w-]+)""")
    private val taskLine = Regex("""taskId=(\d+):\s*([^\s/]+)/(\S+)""")
    private val boundsRe = Regex("""bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
    private val userRe = Regex("""userId=(\d+)""")

    fun resolveActivity(context: Context, pkg: String): String? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className

    /** 起動せずに、対象タスクの状態と OS のフリーフォーム関連情報をログに出す */
    fun diagnose(context: Context, pkg: String): LaunchReport {
        val log = StringBuilder()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        log.append("app=$version sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL} ${Build.DISPLAY}\n\n")
        val stack = ShellExecutor.run("am stack list")
        val tasks = parseStack(stack.stdout).filter { it.component.startsWith("$pkg/") }
        log.append("対象タスク:\n")
        if (tasks.isEmpty()) log.append("  （なし）\n")
        tasks.forEach { log.append("  id=${it.id} mode=${it.mode} bounds=${it.bounds?.toShortString()} ${it.component}\n") }
        log.append("\n$ am stack list（対象部分）\n")
        tasks.forEach { log.append(excerpt(stack.stdout, it.id)).append("\n") }
        if (tasks.isEmpty()) log.append(excerpt(stack.stdout, null)).append("\n")
        listOf(
            "settings get global enable_freeform_support",
            "settings get global force_resizable_activities",
            "pm has-feature android.software.freeform_window_management",
            "getprop persist.wm.debug.desktop_mode_enforce_device_restrictions",
            "dumpsys activity activities | grep -iE 'mWindowingMode=|freeform|desktop' | head -40",
        ).forEach { log.append(ShellExecutor.run(it).format()).append('\n') }
        val t = tasks.firstOrNull()
        return LaunchReport(t != null, if (t != null) "診断完了 (mode=${t.mode})" else "対象アプリのタスクがありません", applied = t?.bounds, log = log.toString())
    }

    fun launch(context: Context, pkg: String, margins: Margins, avoidStatusBar: Boolean, startApp: Boolean = true): LaunchReport {
        val log = StringBuilder()
        fun exec(cmd: String, timeoutSec: Long = 15) =
            ShellExecutor.run(cmd, timeoutSec).also { log.append(it.format()).append('\n') }
        fun fail(msg: String, requested: Rect? = null, applied: Rect? = null) =
            LaunchReport(false, msg, requested, applied, log.toString())

        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        log.append("app=$version sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL} ${Build.DISPLAY}\n")

        val activity = resolveActivity(context, pkg) ?: return fail("起動 Activity が見つかりません: $pkg")

        // 画面全体（現在の回転を反映）とシステムバー領域
        val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
        val screen = metrics.bounds
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val stableTop = screen.top + bars.top
        val target = Rect(
            screen.left + margins.left, screen.top + margins.top,
            screen.right - margins.right, screen.bottom - margins.bottom,
        )
        log.append("screen=${screen.toShortString()} systemBars=$bars\n")
        // AOSP の freeform は上端をステータスバー下端より上に置けず、はみ出すと
        // ウィンドウ全体を下へずらす（縮めない）ので、下端が画面外に出る
        if (avoidStatusBar && margins.top >= 0 && target.top < stableTop) {
            log.append("top ${target.top} -> $stableTop（ステータスバーを避ける）\n")
            target.top = stableTop
        }
        log.append("target=${target.toShortString()}\n\n")

        val minPx = (220 * context.resources.displayMetrics.density).toInt()
        if (target.width() < minPx || target.height() < minPx)
            return fail("余白が大きすぎます（最小 ${minPx}px 四方）", target)

        // ホームのアイコンと同じ Intent で起動し、既存タスクがあれば前面に出すだけにする
        if (startApp) {
        val start = exec(
            "am start -W --windowingMode 5 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-f 0x10200000 -n '$pkg/$activity'",
            timeoutSec = 60,
        )
        // -W はタイムアウトしても起動自体は進んでいるので続行する
        if (start.hasError || (start.exitCode == -1 && !start.timedOut)) return fail("am start に失敗しました", target)
        }

        var task: TaskEntry? = null
        var lastStack = ""
        for (i in 0 until 15) {
            lastStack = ShellExecutor.run("am stack list").stdout
            task = pickTask(parseStack(lastStack), pkg, activity)
            if (task != null) break
            Thread.sleep(300)
        }
        log.append("$ am stack list（抜粋）\n").append(excerpt(lastStack, task?.id)).append("\n\n")
        val found = task ?: return fail("taskId を取得できませんでした", target)
        log.append("task: id=${found.id} mode=${found.mode} bounds=${found.bounds?.toShortString()}\n\n")

        if (found.mode == "fullscreen" || found.mode == "pinned") {
            exec("settings get global enable_freeform_support")
            exec("pm has-feature android.software.freeform_window_management")
            return fail(
                "フリーフォームになっていません (mode=${found.mode})。" +
                    if (startApp) "OS が起動時の指定を無視しました。手でフローティングにしてから「リサイズのみ」を試してください"
                    else "対象アプリをフローティングウィンドウにしてから実行してください",
                target, found.bounds,
            )
        }

        fun resizeTask(rect: Rect): CmdResult {
            val (l, t, r, b) = listOf(rect.left, rect.top, rect.right, rect.bottom)
            return if (t < 0) {
                // am task resize は負の top を拒否するため、shell 権限の app_process から直接呼ぶ
                exec(
                    "CLASSPATH='${context.applicationInfo.sourceDir}' app_process / " +
                        "${ResizeMain::class.java.name} ${found.id} $l $t $r $b"
                )
            } else {
                exec("am task resize ${found.id} $l $t $r $b")
            }
        }

        // OS がウィンドウを下へずらして下端がはみ出したら、その分だけ下端を上げて再試行する
        val before = found.bounds
        var request = Rect(target)
        var applied: Rect? = null
        var mode = found.mode
        for (attempt in 1..3) {
            val resize = resizeTask(request)
            if (!resize.ok || resize.hasError) return fail("リサイズに失敗しました", target, applied ?: before)
            val prev = if (attempt == 1) before else applied
            var after: TaskEntry? = null
            for (i in 0 until 10) {
                Thread.sleep(200)
                lastStack = ShellExecutor.run("am stack list").stdout
                after = parseStack(lastStack).firstOrNull { it.id == found.id }
                val ab = after?.bounds
                if (ab == request || (ab != null && ab != prev)) break
            }
            log.append("$ am stack list（リサイズ後・抜粋）\n").append(excerpt(lastStack, found.id)).append("\n\n")
            if (after == null) return fail("リサイズ後にタスクが見つかりません", target)
            mode = after.mode
            val ab = after.bounds ?: return fail("適用矩形を取得できませんでした", target)
            applied = ab
            val overflow = ab.bottom - target.bottom
            if (overflow <= 0 || ab.top <= request.top) break
            val next = Rect(request.left, request.top, request.right, request.bottom - overflow)
            if (next.height() < minPx) break
            log.append("OS が上端を ${ab.top - request.top}px 下へずらし、下端が ${overflow}px はみ出したため、高さを縮めて再試行\n\n")
            request = next
        }

        val ab = applied!!
        val inside = ab.top >= target.top && ab.bottom <= target.bottom && ab.left >= target.left && ab.right <= target.right
        return when {
            ab == target -> LaunchReport(true, "リサイズ完了（指定どおり）", target, ab, log.toString())
            ab.bottom > target.bottom ->
                fail("OS がウィンドウを下へずらし、下端が ${ab.bottom - target.bottom}px はみ出しています (mode=$mode)", target, ab)
            inside -> LaunchReport(true, "リサイズ完了（画面内に収まるよう補正）", target, ab, log.toString())
            ab == before -> fail("矩形が変化しませんでした（リサイズ未反映）mode=$mode", target, ab)
            else -> LaunchReport(true, "リサイズ完了（OS により補正されました）", target, ab, log.toString())
        }
    }

    /** am stack list の出力をタスク単位に分解する（ウィンドウモードは所属 RootTask のもの） */
    fun parseStack(output: String): List<TaskEntry> {
        val result = mutableListOf<TaskEntry>()
        var mode: String? = null
        for (line in output.lineSequence()) {
            if (rootTaskRe.containsMatchIn(line)) { mode = null; continue }
            val m = taskLine.find(line)
            if (m == null) {
                modeRe.find(line)?.let { mode = it.groupValues[1] }
                continue
            }
            val tail = line.substring(m.range.last + 1)
            val b = boundsRe.find(tail)?.groupValues?.drop(1)?.map { it.toInt() }
            result += TaskEntry(
                id = m.groupValues[1].toInt(),
                component = "${m.groupValues[2]}/${m.groupValues[3]}",
                bounds = b?.let { Rect(it[0], it[1], it[2], it[3]) },
                userId = userRe.find(tail)?.groupValues?.get(1)?.toInt(),
                mode = mode,
            )
        }
        return result
    }

    /** 同じパッケージのタスクが複数あるとき、自ユーザー・起動 Activity 一致・freeform を優先 */
    private fun pickTask(all: List<TaskEntry>, pkg: String, activity: String): TaskEntry? {
        val myUser = android.os.Process.myUid() / 100000
        val full = "$pkg/$activity"
        val short = "$pkg/" + activity.removePrefix(pkg)
        val mine = all.filter { it.component.startsWith("$pkg/") && (it.userId == null || it.userId == myUser) }
        val exact = mine.filter { it.component == full || it.component == short }
        return exact.firstOrNull { it.mode == "freeform" } ?: exact.firstOrNull() ?: mine.firstOrNull()
    }

    /** ログ用に、対象タスクを含む RootTask ブロックだけを切り出す */
    private fun excerpt(output: String, taskId: Int?): String {
        val blocks = output.split(Regex("""(?m)^(?=\s*RootTask id=)"""))
        val hit = taskId?.let { id -> blocks.firstOrNull { it.contains("taskId=$id:") } }
        val text = (hit ?: output).trimEnd()
        return if (text.length > 4000) text.take(4000) + "\n…（省略）" else text
    }
}
