package com.delivery.order

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CriarPedidoRequest(val itemResumo: String = "Pedido padrão")

@Serializable
data class CriarPedidoResponse(
    val orderId: String,
    val mensagem: String,
    val emFila: Boolean
)

@Serializable
data class PedidoCozinhaResposta(val fase: String, val orderId: String? = null)

@Serializable
data class PedidoCobrancaResposta(val finalized: Boolean, val orderId: String? = null)

@Serializable
data class OrderStatusView(
    val orderId: String,
    val status: String,
    val mensagem: String?,
    val itemResumo: String?
)

@Serializable
data class PrepareToKitchen(val orderId: String, val itemSummary: String? = null)

private data class RegistroLocal(
    val id: UUID,
    val itemResumo: String,
    var fluxo: String
)

private val pedidos = ConcurrentHashMap<UUID, RegistroLocal>()

fun Application.module() {
    val kitchenBase =
        (System.getenv("KITCHEN_URL") ?: "http://127.0.0.1:8082").trimEnd('/')
    val billingBase =
        (System.getenv("BILLING_URL") ?: "http://127.0.0.1:8083").trimEnd('/')

    val clientCurto = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 2_000
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

    val clientLStatus = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000
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

    install(CORS) { anyHost() }
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
        get("/orders/{orderId}") {
            val id = runCatching { UUID.fromString(call.parameters["orderId"]!!) }.getOrNull()
            if (id == null) {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "orderId inválido"))
            }
            val local = pedidos[id]
            if (local == null) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("erro" to "Pedido não encontrado no Order Service")
                )
            }
            val kitchenUrl = "$kitchenBase/kitchen/orders/${id}"
            val billingUrl = "$billingBase/billing/orders/${id}"
            val cozinha: PedidoCozinhaResposta? = runCatching {
                clientLStatus.get(kitchenUrl).body<PedidoCozinhaResposta>()
            }.getOrNull()
            val cobranca: PedidoCobrancaResposta? = runCatching {
                clientLStatus.get(billingUrl).body<PedidoCobrancaResposta>()
            }.getOrNull()
            val status =
                mapearStatus(
                    cozinha = cozinha?.fase,
                    cobrancaFinal = cobranca?.finalized == true,
                    local = local.fluxo
                )
            val msg =
                when (status) {
                    "FINALIZADO" -> "Cobrança concluída; pedido encerrado."
                    "EM_FILA" -> "O Order Service teve timeout ao contatar a cozinha; pedido segue no fluxo se o M2 recebeu a requisição."
                    "PREPARANDO" -> "Cozinha em etapa de preparo ou reenvio para cobrança."
                    "COBRANCA_ERRO" -> "Cozinha concluiu, mas a cobrança falhou após retentativas."
                    "PENDENTE" -> "Pedido registrado; aguardando confirmação do fluxo."
                    else -> null
                }
            println(
                "[M1] Status agregado para $id: $status (cozinha=$cozinha, billing=${cobranca?.finalized})"
            )
            call.respond(
                OrderStatusView(
                    orderId = id.toString(),
                    status = status,
                    mensagem = msg,
                    itemResumo = local.itemResumo
                )
            )
        }

        post("/orders") {
            val body = runCatching { call.receive<CriarPedidoRequest>() }.getOrNull()
            val id = UUID.randomUUID()
            val resumo = body?.itemResumo ?: "Pedido padrão"
            pedidos[id] = RegistroLocal(id = id, itemResumo = resumo, fluxo = "PENDENTE")
            println("[M1] Novo pedido $id enfileirado localmente: $resumo")
            val prepareUrl = "$kitchenBase/prepare"
            try {
                val response = clientCurto.post(prepareUrl) {
                    setBody(PrepareToKitchen(orderId = id.toString(), itemSummary = resumo))
                }
                if (response.status.isSuccess()) {
                    pedidos[id] = RegistroLocal(id, resumo, "ACEITO_PELA_COZINHA")
                    println("[M1] M2 respondeu a tempo (HTTP) para o pedido $id")
                    call.respond(
                        HttpStatusCode.Created,
                        CriarPedidoResponse(
                            orderId = id.toString(),
                            mensagem = "Pedido recebido pela cozinha dentro do tempo limite do cliente HTTP.",
                            emFila = false
                        )
                    )
                } else {
                    println(
                        "[M1] M2 respondeu fora de sucesso (${response.status}); ativando fallback 202"
                    )
                    pedidos[id] = RegistroLocal(id, resumo, "EM_FILA")
                    call.respond(
                        HttpStatusCode.Accepted,
                        CriarPedidoResponse(
                            orderId = id.toString(),
                            mensagem = "Pedido em fila (Cozinha Ocupada)",
                            emFila = true
                        )
                    )
                }
            } catch (e: Exception) {
                println(
                    "[M1] Falha/timeout (2s) ao chamar a cozinha: ${e::class.simpleName} — ${e.message}"
                )
                pedidos[id] = RegistroLocal(id, resumo, "EM_FILA")
                println("[M1] Fallback: pedido $id em fila (cozinha lenta ou indisponível).")
                call.respond(
                    HttpStatusCode.Accepted,
                    CriarPedidoResponse(
                        orderId = id.toString(),
                        mensagem = "Pedido em fila (Cozinha Ocupada)",
                        emFila = true
                    )
                )
            }
        }
    }
}

private fun mapearStatus(
    cozinha: String?,
    cobrancaFinal: Boolean,
    local: String
): String {
    if (cobrancaFinal) return "FINALIZADO"
    when (cozinha) {
        "PREPARANDO" -> return "PREPARANDO"
        "FALHA_COBRANCA" -> return "COBRANCA_ERRO"
        "FINALIZADO_COZINHA" -> return "PREPARANDO"
    }
    if (local == "EM_FILA") return "EM_FILA"
    return "PENDENTE"
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    println("[M1] Order Service iniciado na porta $port")
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}
