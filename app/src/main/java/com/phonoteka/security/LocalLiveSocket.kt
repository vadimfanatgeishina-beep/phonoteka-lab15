package com.phonoteka.security

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import java.io.Closeable
import java.util.UUID

data class LiveSocketMetrics(val connected: Boolean = false, val attempts: Int = 0, val reconnects: Int = 0,
    val sent: Int = 0, val received: Int = 0, val rejected: Int = 0, val terminalError: String? = null,
    /** ЛР15: короткий хеш пользователя, к которому привязано соединение (не сам userId). */
    val boundUserTag: String? = null)

/**
 * Own localhost only. Lifecycle owner calls close; no background/global coroutine scope is used.
 * ЛР15: если передан [userBinding], каждая попытка подключения:
 *  - берёт текущего пользователя (нет пользователя — нет подключения);
 *  - строит канал /lab15/u/<хеш>/<uuid> и одноразовый билет на 30 с, привязанный к каналу и версии сессии;
 *  - подписывает и проверяет кадры ключом ЭТОГО пользователя;
 *  - при смене/выходе пользователя рвёт соединение и НЕ переподключается.
 */
class LocalLiveSocket(private val client: OkHttpClient, private val signer: HmacTextFrameSigner,
    private val scope: CoroutineScope, private val backoffMillis: Long = 1000, private val maxReconnects: Int = 5,
    private val userBinding: SocketUserBinding? = null) : Closeable {
    private val mutable = MutableStateFlow(LiveSocketMetrics())
    val metrics: StateFlow<LiveSocketMetrics> = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var activeChannel: String? = null

    fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        require(request.url.scheme == "https" && request.url.host == "localhost" && request.url.username.isEmpty() && request.url.password.isEmpty())
        require(request.url.encodedPath == "/ws" && request.url.query == null)
        close()
        mutable.value = LiveSocketMetrics()
        job = scope.launch {
            for (attempt in 0..maxReconnects) {
                ensureActive()
                if (attempt > 0) delay(LocalReconnectPolicy.delayMillis(attempt, backoffMillis))
                val principal = userBinding?.currentPrincipal?.invoke()
                if (userBinding != null && principal == null) {
                    mutable.update { it.copy(connected = false, terminalError = USER_REQUIRED) }
                    return@launch
                }
                val ended = CompletableDeferred<Unit>()
                var userChanged = false
                val channel = principal?.let { UserBoundSocketKeys.channelFor(it) } ?: "/lab15/events/${UUID.randomUUID()}"
                val frameSigner = principal?.let { userBinding!!.keys.frameSigner(it.userId, userBinding.clockSeconds) } ?: signer
                val builder = request.newBuilder().header("X-Lab-Channel", channel)
                if (principal != null) builder.header(SocketTicketCodec.HEADER, userBinding!!.tickets.issue(principal, channel))
                val guard = FrameReplayGuard()
                activeChannel = channel
                mutable.update { it.copy(attempts = it.attempts + 1, reconnects = attempt, terminalError = null,
                    boundUserTag = principal?.let { p -> UserBoundSocketKeys.userTag(p.userId) }) }
                fun stillSameUser(): Boolean = principal == null || userBinding?.currentPrincipal?.invoke() == principal
                val connection = client.newWebSocket(builder.build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!isActive || activeChannel != channel) { webSocket.cancel(); return }
                        if (!stillSameUser()) { userChanged = true; webSocket.cancel(); ended.complete(Unit); return }
                        mutable.update { it.copy(connected = true) }
                        val sent = webSocket.send(frameSigner.sign("$channel/request", "local metric request").toWire())
                        if (sent) mutable.update { it.copy(sent = it.sent + 1) }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!isActive || activeChannel != channel) return
                        if (!stillSameUser()) { userChanged = true; webSocket.cancel(); ended.complete(Unit); return }
                        val frame = SignedTextFrame.parse(text)
                        val result = frame?.let { frameSigner.verify(it, "$channel/response", guard) }
                        if (result == FrameVerificationResult.ACCEPTED) mutable.update { it.copy(received = it.received + 1) }
                        else {
                            mutable.update { it.copy(rejected = it.rejected + 1) }
                            // An untrusted frame is terminal for this transport. Abort rather
                            // than waiting indefinitely for a hostile/non-acknowledging peer.
                            webSocket.cancel()
                            ended.complete(Unit)
                        }
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(Unit) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (isActive && activeChannel == channel) mutable.update { it.copy(terminalError = "Local WSS connection failed") }
                        ended.complete(Unit)
                    }
                })
                socket = connection
                try { ended.await() } finally {
                    connection.cancel()
                    if (socket === connection) socket = null
                    if (activeChannel == channel) mutable.update { it.copy(connected = false) }
                }
                if (userChanged || !stillSameUser()) {
                    // Новый пользователь не должен унаследовать чужое соединение через автопереподключение.
                    mutable.update { it.copy(connected = false, terminalError = USER_CHANGED) }
                    return@launch
                }
            }
        }
    }

    /** Вызывается при выходе/смене роли/блокировке: немедленно рвёт соединение. */
    fun onPrincipalChanged() { close(); mutable.update { it.copy(terminalError = USER_CHANGED, boundUserTag = null) } }

    override fun close() { job?.cancel(); job = null; socket?.cancel(); socket = null; activeChannel = null; mutable.update { it.copy(connected = false) } }

    companion object {
        const val USER_REQUIRED = "Нет активного пользователя: войдите, чтобы подключиться"
        const val USER_CHANGED = "Пользователь или сессия сменились — соединение закрыто"
    }
}
