package com.example.freeformlauncher

import android.graphics.Rect
import android.os.IBinder
import kotlin.system.exitProcess

/**
 * shell 権限の app_process から実行するエントリポイント（負の top を試す実験用）。
 *
 * `am task resize` は left/top が負だと "Error: bad top arg" で拒否するため、
 * 同じ IActivityTaskManager.resizeTask をサンドボックス外の shell プロセスから直接呼ぶ。
 * 使い方: CLASSPATH=<apk> app_process / com.example.freeformlauncher.ResizeMain <taskId> <l> <t> <r> <b>
 */
object ResizeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 5) { "usage: ResizeMain <taskId> <left> <top> <right> <bottom>" }
            val v = args.map { it.toInt() }
            val rect = Rect(v[1], v[2], v[3], v[4])

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            // am task resize と同じ resize mode を使う
            val mode = runCatching {
                Class.forName("android.app.ActivityTaskManager")
                    .getField("RESIZE_MODE_SYSTEM_SCREEN_ROTATION").getInt(null)
            }.getOrDefault(0)
            atm.javaClass
                .getMethod("resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType)
                .invoke(atm, v[0], rect, mode)
            println("resizeTask(${v[0]}, ${rect.toShortString()}, mode=$mode) called")
            exitProcess(0)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            System.err.println("Error: $cause")
            exitProcess(1)
        }
    }
}
