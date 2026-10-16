package com.example.freeformlauncher

import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

data class CmdResult(val command: String, val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0
    fun format() = buildString {
        append("$ ").append(command).append("\n")
        append("exit=").append(exitCode).append("\n")
        if (stdout.isNotBlank()) append(stdout.trimEnd()).append("\n")
        if (stderr.isNotBlank()) append("[stderr] ").append(stderr.trimEnd()).append("\n")
    }
}

/** Shizuku 経由で shell 権限のコマンドを実行する。 */
object ShellExecutor {
    // Shizuku.newProcess は API 13 で private 化されているためリフレクションで呼ぶ
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    fun run(command: String, timeoutSec: Long = 15): CmdResult {
        return try {
            val p = newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
            val tErr = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
            // Shizuku の RemoteProcess は waitFor(timeout) 非対応（exitValue が
            // IllegalArgumentException を投げる）ため、waitFor() を別スレッドで待つ
            var exit = -1
            val waiter = Thread { exit = p.waitFor() }.apply { start() }
            waiter.join(TimeUnit.SECONDS.toMillis(timeoutSec))
            val finished = !waiter.isAlive
            if (!finished) p.destroy()
            tOut.join(1000); tErr.join(1000)
            CmdResult(command, exit, out.toString(),
                err.toString() + if (finished) "" else "\n(timeout)")
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            CmdResult(command, -1, "", cause.toString())
        }
    }
}
