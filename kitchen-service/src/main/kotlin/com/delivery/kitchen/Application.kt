package com.delivery.kitchen

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PrepareRequest(val orderId: String, val itemSummary: String? = null)

@Serializable
data class KitchenStatusResponse(
    val orderId: String,
    val fase: String,
    val detalhe: String? = null
)

@Serializable
data class FinalizeRequestForBilling(val orderId: String)

private val kitchenState = ConcurrentHashMap<UUID, String>()

fun kitchenPhase(orderId: UUID): String = kitchenState[orderId] ?: "DESCONHECIDO"

fun Application.module() {
    val billingBase =
        (System.getenv("BILLING_URL") ?: "http://127.0.0.1:8083").trimEnd('/')

    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
        }
        install(ClientContentNegotiation) {
            json(
                Json {
                    isLenient = true
                    ignoreUnknownKeys = true
                }
            )
        }
    }

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }

    routing {
        get("/kitchen/orders/{orderId}") {
            val id = runCatching { UUID.fromString(call.parameters["orderId"]!!) }.getOrNull()
            if (id == null) {
                return@get call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("erro" to "orderId inválido")
                )
            }
            val fase = kitchenPhase(id)
            call.respond(
                KitchenStatusResponse(
                    orderId = id.toString(),
                    fase = fase,
                    detalhe = null
                )
            )
        }

        post("/prepare") {
            val body = runCatching { call.receive<PrepareRequest>() }.getOrNull()
            if (body == null) {
                return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("erro" to "JSON inválido")
                )
            }
            val orderId = runCatching { UUID.fromString(body.orderId) }.getOrNull()
            if (orderId == null) {
                return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    mapOf("erro" to "orderId inválido")
                )
            }

            withContext(NonCancellable) {
                kitchenState[orderId] = "PREPARANDO"
                println("[M2] Pedido $orderId aceito, preparo iniciado.")

                val delayMs = Random.nextLong(3_000, 7_001)
                println("[M2] Simulando cozinha: aguardando ${delayMs}ms")
                delay(delayMs)

                val finalizePath = "$billingBase/finalize"
                var sucesso = false
                for (tentativa in 1..3) {
                    try {
                        println("[M2] Enviando pedido $orderId para cobrança (tentativa $tentativa/3)...")
                        val response = httpClient.post(finalizePath) {
                            setBody(FinalizeRequestForBilling(orderId.toString()))
                        }
                        if (response.status.isSuccess()) {
                            val texto = runCatching { response.bodyAsText() }.getOrNull()
                            println("[M2] Resposta M3: ${texto?.take(200)}")
                            sucesso = true
                            break
                        } else {
                            println("[M2] M3 respondeu com status: ${response.status}")
                        }
                    } catch (e: Exception) {
                        println("[M2] Falha na chamada ao M3: ${e.message}")
                    }
                    if (tentativa < 3) {
                        println("[M2] Tentando Retry $tentativa/3...")
                        delay(1_000)
                    }
                }

                if (sucesso) {
                    kitchenState[orderId] = "FINALIZADO_COZINHA"
                    println("[M2] Pedido $orderId concluído (encaminhado / confirmado com M3).")
                    call.respond(
                        mapOf(
                            "orderId" to orderId.toString(),
                            "message" to "Preparo e encaminhamento para cobrança concluídos"
                        )
                    )
                } else {
                    kitchenState[orderId] = "FALHA_COBRANCA"
                    println("[M2] Pedido $orderId: esgotaram-se as tentativas com o M3.")
                    call.respond(
                        io.ktor.http.HttpStatusCode.GatewayTimeout,
                        mapOf(
                            "orderId" to orderId.toString(),
                            "message" to "Cobrança indisponível após 3 tentativas"
                        )
                    )
                }
            }
        }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8082
    println("[M2] Kitchen Service iniciado na porta $port")
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}
