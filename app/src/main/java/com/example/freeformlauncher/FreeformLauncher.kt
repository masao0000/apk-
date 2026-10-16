package com.example.freeformlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.WindowManager

data class Margins(val left: Int = 4, val top: Int = 4, val right: Int = 4, val bottom: Int = 4)

data class LaunchReport(
    val success: Boolean,
    val message: String,
    val requested: Rect? = null,
    val applied: Rect? = null,
    val log: String = "",
)

/**
 * 1. am start --windowingMode 5 でフリーフォーム起動
 * 2. am stack list から taskId を取得
 * 3. am task resize で画面サイズ - 余白 の矩形に変更
 * 4. 実際に適用された矩形を読み戻す
 */
object FreeformLauncher {
    const val DEFAULT_PACKAGE = "com.linecorp.LGTMTM" // LINE ディズニー ツムツム

    private val taskLine = Regex("""taskId=(\d+):\s*([^\s/]+)/(\S+)""")
    private val boundsRe = Regex("""bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")

    fun resolveActivity(context: Context, pkg: String): String? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className

    /** 現在の回転を反映した画面全体の矩形 */
    fun screenBounds(context: Context): Rect =
        context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds

    fun launch(context: Context, pkg: String, margins: Margins): LaunchReport {
        val log = StringBuilder()
        fun exec(cmd: String) = ShellExecutor.run(cmd).also { log.append(it.format()).append('\n') }
        fun fail(msg: String) = LaunchReport(false, msg, log = log.toString())

        val activity = resolveActivity(context, pkg) ?: return fail("起動 Activity が見つかりません: $pkg")
        val screen = screenBounds(context)
        val target = Rect(
            screen.left + margins.left, screen.top + margins.top,
            screen.right - margins.right, screen.bottom - margins.bottom,
        )
        log.append("screen=$screen target=$target\n\n")

        val start = exec("am start -W --windowingMode 5 -n $pkg/$activity")
        if (!start.ok || start.stdout.contains("Error")) return fail("am start に失敗しました")

        // タスクが現れるまで少し待つ
        var taskId: Int? = null
        repeat(10) {
            taskId = findTask(exec("am stack list").stdout, pkg)?.first
            if (taskId != null) return@repeat
            Thread.sleep(300)
        }
        val id = taskId ?: return fail("taskId を取得できませんでした")

        val resize = exec("am task resize $id ${target.left} ${target.top} ${target.right} ${target.bottom}")
        if (!resize.ok || resize.stderr.isNotBlank() || resize.stdout.contains("Error"))
            return fail("am task resize に失敗しました")

        Thread.sleep(300)
        val applied = findTask(exec("am stack list").stdout, pkg)?.second
        val msg = when {
            applied == null -> "リサイズ完了（適用矩形は取得できず）"
            applied == target -> "リサイズ完了（指定どおり）"
            else -> "リサイズ完了（OS により補正されました）"
        }
        return LaunchReport(true, msg, target, applied, log.toString())
    }

    /** am stack list の出力から pkg のタスク (taskId, bounds) を探す */
    private fun findTask(output: String, pkg: String): Pair<Int, Rect?>? {
        for (line in output.lineSequence()) {
            val m = taskLine.find(line) ?: continue
            if (m.groupValues[2] != pkg) continue
            val b = boundsRe.find(line, m.range.last)?.groupValues?.drop(1)?.map { it.toInt() }
            return m.groupValues[1].toInt() to b?.let { Rect(it[0], it[1], it[2], it[3]) }
        }
        return null
    }
}
