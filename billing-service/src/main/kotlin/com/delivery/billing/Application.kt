package com.delivery.billing

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinalizeRequest(val orderId: String)

@Serializable
data class FinalizeResponse(val orderId: String, val message: String, val duplicate: Boolean = false)

@Serializable
data class BillingStatusResponse(val orderId: String, val finalized: Boolean)

private val processedOrderIds = ConcurrentHashMap.newKeySet<UUID>()

fun Application.module() {
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
        get("/billing/orders/{orderId}") {
            val id = runCatching { UUID.fromString(call.parameters["orderId"]!!) }.getOrNull()
            if (id == null) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("erro" to "orderId inválido")
                )
            }
            val finalized = id in processedOrderIds
            call.respond(BillingStatusResponse(id.toString(), finalized))
        }

        post("/finalize") {
            val body = runCatching { call.receive<FinalizeRequest>() }.getOrNull()
            if (body == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("erro" to "JSON inválido")
                )
            }
            val orderId = runCatching { UUID.fromString(body.orderId) }.getOrNull()
            if (orderId == null) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("erro" to "orderId inválido")
                )
            }
            val isNew = processedOrderIds.add(orderId)
            if (!isNew) {
                println("[M3] Duplicata detectada, ignorando requisição para o pedido $orderId")
                call.respond(
                    FinalizeResponse(
                        orderId = orderId.toString(),
                        message = "Cobrança idempotente: pedido já finalizado",
                        duplicate = true
                    )
                )
            } else {
                println("[M3] Pedido $orderId finalizado (cobrança registrada).")
                call.respond(
                    FinalizeResponse(
                        orderId = orderId.toString(),
                        message = "Cobrança concluída com sucesso",
                        duplicate = false
                    )
                )
            }
        }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8083
    println("[M3] Billing Service iniciado na porta $port")
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}
