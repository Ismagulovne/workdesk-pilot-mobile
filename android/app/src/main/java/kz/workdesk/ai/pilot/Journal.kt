package kz.workdesk.ai.pilot

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал действий: JSON Lines по дням, без введённого текста и без снимков. Хранится 30 дней. */
class Journal(ctx: Context) {
    val dir = File(ctx.filesDir, "journal").apply { mkdirs() }
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    init {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    @Synchronized
    private fun append(o: JSONObject) {
        val now = Date()
        o.put("ts", ts.format(now))
        File(dir, "${day.format(now)}.jsonl").appendText(o.toString() + "\n")
    }

    fun write(event: String, detail: String) = append(JSONObject().put("event", event).put("detail", detail))

    fun action(tool: String, args: JSONObject, res: Res) {
        val safe = JSONObject(args.toString())
        if (tool == "type") {
            val t = safe.optString("text")
            safe.remove("text")
            safe.put("text_len", t.codePointCount(0, t.length))
        }
        val first = res.content.optJSONObject(0)
        val msg = if (first?.optString("type") == "text") first.optString("text").take(200) else "image"
        append(JSONObject().put("tool", tool).put("args", safe).put("ok", !res.isError).put("result", msg))
    }

    fun tail(n: Int): List<String> =
        dir.listFiles()?.sortedBy { it.name }?.lastOrNull()?.readLines()?.takeLast(n) ?: emptyList()
}
