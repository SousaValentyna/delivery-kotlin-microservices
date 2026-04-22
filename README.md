# Sistema de delivery (microsserviços)

Sistema de pedidos com **três microsserviços independentes** (Kotlin + Ktor) e **frontend** estático (Vue 3 e Axios) servido por **Nginx**. Cada serviço possui o próprio `build.gradle.kts`, artefato e contêiner — não há módulo compartilhado de negócio (desacoplamento em nível de código e implantação).

## Arquitetura

| Serviço | Porta (host / interna) | Responsabilidade |
|--------|-------------------------|-----------------|
| **M1 – Order** | 8081 | `POST /orders` — cria o pedido e chama a cozinha com **timeout de 2s**. Em timeout ou falha, responde **202** e mensagem: *"Pedido em fila (Cozinha Ocupada)"*. `GET /orders/{id}` agrega o estado consultando M2 e M3 (leitura orquestrada, não banco compartilhado). |
| **M2 – Kitchen** | 8082 | `POST /prepare` — atraso aleatório **3–7s**, depois chama o M3 com **até 3 tentativas** e **1s** entre elas. Estados expostos em `GET /kitchen/orders/{id}`. |
| **M3 – Billing** | 8083 | `POST /finalize` — **idempotência** por `orderId` (UUID) em memória: duplicatas são ignoradas com log. `GET /billing/orders/{id}`. |
| **Frontend** | 8080 → Nginx 80 | Formulário de pedido e dashboard com **polling a cada 2s**. API via `/api/...` (proxy no Nginx). |

### Fluxo lógico

1. O cliente envia o pedido ao **M1**.
2. O **M1** chama o **M2** (`/prepare`) com timeout de 2s. O **M2** simula a cozinha (3–7s) e só então responde ao chamador; por isso, na prática, o M1 quase sempre estoura o timeout e devolve **202** com o pedido *em fila* — a cozinha continua o processamento (trecho com `NonCancellable` evita que o motor cancele a lógica crítica).
3. O **M2** chama o **M3** (`/finalize`) com retentativas; o **M3** evita efeito duplicado de cobrança se o M2 reenviar o mesmo `orderId`.
4. O painel lê o estado “consolidado” no **M1** (`GET /orders/{id}`), que reúne as leituras de M2 e M3.

**Rede Docker:** `delivery-net` (bridge) — todos os contêineres usam a mesma rede, conforme `docker-compose.yml`.

## Pré-requisitos

- Docker e Docker Compose (plugin v2: `docker compose` — ou a CLI `docker-compose` v1, como no exemplo abaixo)

## Subir tudo

Na raiz do repositório:

```bash
docker-compose up --build
```

(Em ambientes com Compose V2, `docker compose up --build` é equivalente.)

- Frontend: <http://localhost:8080>
- Order: <http://localhost:8081>
- Kitchen: <http://localhost:8082>
- Billing: <http://localhost:8083>

### Variáveis de ambiente (já no Compose)

| Variável | Onde | Significado |
|----------|------|-------------|
| `KITCHEN_URL` | M1 | Base URL do M2 (ex.: `http://kitchen-service:8082`) |
| `BILLING_URL` | M1, M2 | Base URL do M3 |
| `PORT` | Cada serviço | Porta do servidor Netty (8081 / 8082 / 8083) |

## Desenvolvimento local (fora do Docker)

Em cada pasta `*-service`, com JDK 17 e `./gradlew`:

```bash
cd billing-service && ./gradlew run
cd kitchen-service && ./gradlew run   # em outro terminal, com M3 de pé
cd order-service && ./gradlew run
```

Ajuste `KITCHEN_URL` e `BILLING_URL` se os hosts não forem `127.0.0.1`.

## Endpoints (resumo)

- **M1:** `POST /orders`, `GET /orders/{orderId}`
- **M2:** `POST /prepare`, `GET /kitchen/orders/{orderId}`
- **M3:** `POST /finalize`, `GET /billing/orders/{orderId}`

Nginx encaminha `http://localhost:8080/api/...` para o M1, por exemplo: `http://localhost:8080/api/orders` → M1 `http://order-service:8081/orders`.

## Logs

Os três serviços escrevem no stdout linhas com prefixo `[M1]`, `[M2]`, `[M3]` (ex.: retentativas da cozinha e idempotência na cobrança). Use `docker compose logs -f` para acompanhar.

## Estrutura de pastas

```
order-service/      # M1
kitchen-service/    # M2
billing-service/    # M3
frontend/           # index.html (Vue + Axios via CDN)
nginx/              # config do reverse proxy
docker-compose.yml
```

Licença: uso de exemplo do projeto; adapte à política do seu repositório.
