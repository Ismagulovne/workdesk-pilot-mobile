package kz.workdesk.ai.pilot

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Связь с мостом на ПК (прототип: ws:// в локальной сети) или с релеем workdesk.kz (wss://, тот же протокол).
 * Протокол — docs/PROTOCOL.md: hello → welcome; call → result; stopped.
 */
class Link(
    private val svc: PilotService,
    private val url: String,
    private val code: String,
    private val onState: (String) -> Unit,
) : WebSocketListener() {
    companion object { const val PROTO = 1; const val VERSION = "0.1.0" }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUs = false

    fun open() {
        onState("подключение…")
        ws = client.newWebSocket(Request.Builder().url(url).build(), this)
    }

    fun close() {
        closedByUs = true
        ws?.close(1000, "bye")
        worker.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    fun sendStopped() {
        ws?.send(JSONObject().put("t", "stopped").toString())
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val hello = JSONObject()
            .put("t", "hello").put("proto", PROTO).put("code", code)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT).put("app", VERSION)
        webSocket.send(hello.toString())
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (m.optString("t")) {
            "welcome" -> onState("подключено к ПК")
            "call" -> {
                val id = m.opt("id")
                val name = m.optString("name")
                val args = m.optJSONObject("args") ?: JSONObject()
                worker.execute {
                    val res = svc.executor.run(name, args)
                    svc.journal.action(name, args, res)
                    val out = JSONObject().put("t", "result").put("id", id)
                        .put("content", res.content).put("isError", res.isError)
                    webSocket.send(out.toString())
                }
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        svc.release("связь закрыта")
        onState(
            when (code) {
                4401 -> "неверный код — проверьте код на ПК"
                4001 -> "подключился другой телефон"
                else -> if (closedByUs) "не подключено" else "связь закрыта ($code)"
            },
        )
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        svc.release("обрыв связи")
        if (!closedByUs) onState("нет связи: ${t.message ?: t.javaClass.simpleName}")
    }
}
