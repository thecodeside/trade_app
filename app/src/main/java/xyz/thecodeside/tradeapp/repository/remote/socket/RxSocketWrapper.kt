package xyz.thecodeside.tradeapp.repository.remote.socket

import android.util.Log
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.processors.BehaviorProcessor
import io.reactivex.processors.PublishProcessor
import okhttp3.*
import okio.ByteString
import xyz.thecodeside.tradeapp.helpers.Logger
import java.util.concurrent.TimeUnit

class RxSocketWrapper(
        private val logger: Logger,
        private val token: String
) {

    private val NORMAL_CLOSURE_STATUS = 1000
    private val NORMAL_CLOSURE_REASON = "DISCONNECT"

    private val AUTHORIZATION_KEY = "Authorization"
    private val AUTH_PREFIX = "Bearer "

    private val client = OkHttpClient()

    private var socket: WebSocket? = null
    private var status: Status = Status.DISCONNECTED

    private val stateFlowable = BehaviorProcessor.create<Status>().toSerialized()
    private val messageFlowable = PublishProcessor.create<String>().toSerialized()

    fun connect(address: String): Flowable<Status> {
        return Flowable
                .fromCallable {
                    //reuse existing connection, only really connect if DISCONNECTED
                    if (status == Status.DISCONNECTED) {
                        connectSocket(address)
                    }
                }
                .flatMap { stateFlowable }
    }

    fun observeSocketMessages(): Flowable<String> = messageFlowable

    fun disconnect() {
        socket?.close(NORMAL_CLOSURE_STATUS, NORMAL_CLOSURE_REASON)
    }

    fun send(message: String) {
        Log.i("Socket  SEND", message)
        socket?.send(message)
    }

    private fun connectSocket(address: String){
        val request = Request.Builder()
                .url(address)
                .addHeader(AUTHORIZATION_KEY, AUTH_PREFIX + token)
                .build()

        socket = client.newWebSocket(request, socketClusterListener)

        client.dispatcher().executorService().shutdown()
    }

    private fun reconnect(request: Request?) {
        Single.timer(RECONNECT_DELAY_MILLISECONDS, TimeUnit.MILLISECONDS)
                .subscribe({ socket = client.newWebSocket(request, socketClusterListener) }, {})
    }

    private val RECONNECT_DELAY_MILLISECONDS = 500L

    private val socketClusterListener = object: WebSocketListener() {

        override fun onOpen(webSocket: WebSocket?, response: Response?) {
            super.onOpen(webSocket, response)
            setStatus(Status.CONNECTED)
        }

        override fun onFailure(webSocket: WebSocket?, t: Throwable, response: Response?) {
            super.onFailure(webSocket, t, response)
            handleError(t)
        }

        override fun onClosing(webSocket: WebSocket?, code: Int, reason: String?) {
            super.onClosing(webSocket, code, reason)
            setStatus(Status.DISCONNECTING)
        }

        override fun onMessage(webSocket: WebSocket?, text: String?) {
            super.onMessage(webSocket, text)
            messageFlowable.onNext(text)
        }

        override fun onMessage(webSocket: WebSocket?, bytes: ByteString?) {
            super.onMessage(webSocket, bytes)
        }

        override fun onClosed(webSocket: WebSocket?, code: Int, reason: String?) {
            super.onClosed(webSocket, code, reason)
            if(code == NORMAL_CLOSURE_STATUS) clear()
            else reconnect(webSocket?.request())
        }

        private fun handleError(throwable: Throwable) {
            logger.logException(throwable)
            clear()
        }

        private fun clear() {
            socket = null
            setStatus(Status.DISCONNECTED)
        }

    }

    private fun setStatus(status: Status) {
        this.status = status
        stateFlowable.onNext(status)
    }

    enum class Status {
        DISCONNECTED,
        DISCONNECTING,
        CONNECTING,
        CONNECTED,
        READY
    }


}