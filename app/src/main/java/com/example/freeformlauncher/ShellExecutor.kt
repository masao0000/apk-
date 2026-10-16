package com.example.freeformlauncher

import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class CmdResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val ok get() = exitCode == 0 && !timedOut
    /** am は失敗しても exit=0 を返すことがあるため、出力中の "Error" も見る */
    val hasError get() = (stdout + stderr).contains("Error")
    fun format() = buildString {
        append("$ ").append(command).append("\n")
        append("exit=").append(exitCode).append(if (timedOut) " (timeout)" else "").append("\n")
        if (stdout.isNotBlank()) append(stdout.trimEnd()).append("\n")
        if (stderr.isNotBlank()) append("[stderr] ").append(stderr.trimEnd()).append("\n")
    }
}

/** Shizuku 経由で shell 権限のコマンドを実行する。 */
object ShellExecutor {
    // Shizuku.newProcess は API 13 で private 化されているためリフレクションで呼ぶ（minify 無効前提）
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    private fun Throwable.root(): Throwable = when (this) {
        is InvocationTargetException -> targetException ?: this
        is ExecutionException -> cause ?: this
        else -> this
    }

    fun run(command: String, timeoutSec: Long = 15): CmdResult {
        val p = try {
            newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as ShizukuRemoteProcess
        } catch (t: Throwable) {
            return CmdResult(command, -1, "", t.root().toString())
        }
        val out = StringBuffer()
        val err = StringBuffer()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // 行単位で読むので、タイムアウト時も途中までの出力が残る
            val outF = pool.submit { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
            val errF = pool.submit { p.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }
            // Process.waitFor(timeout) は ShizukuRemoteProcess では IllegalArgumentException
            // （"process hasn't exited"）になるため、専用の waitForTimeout を使う
            val finished = p.waitForTimeout(timeoutSec, TimeUnit.SECONDS)
            val exit = if (finished) p.exitValue() else { runCatching { p.destroy() }; -1 }
            fun Future<*>.await() = try { get(2, TimeUnit.SECONDS) } catch (t: Throwable) { cancel(true) }
            outF.await(); errF.await()
            return CmdResult(command, exit, out.toString(), err.toString(), timedOut = !finished)
        } catch (t: Throwable) {
            runCatching { p.destroy() }
            return CmdResult(command, -1, out.toString(), err.toString() + t.root().toString())
        } finally {
            pool.shutdownNow()
        }
    }
}
