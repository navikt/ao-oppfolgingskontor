package no.nav.http.client.tokenexchange

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory


fun ApplicationEnvironment.getNaisTokenEndpoint(): String {
    return config.property("auth.naisTokenEndpoint").getString()
}

class ExpirableToken(
    val token: String,
    val expiresAt: Long,
    val tokenType: String
)

val expiry: Expiry<String, ExpirableToken> = object : Expiry<String, ExpirableToken> {
    val leeway = 1000L // 1 second leeway to avoid issues with clock skew
    override fun expireAfterCreate(
        key: String,
        token: ExpirableToken,
        currentTime: Long // Nanoseconds since epoch
    ): Long {
        return (token.expiresAt + leeway) - currentTime
    }

    override fun expireAfterUpdate(
        key: String,
        token: ExpirableToken,
        currentTime: Long,
        currentDuration: Long // Nanoseconds since epoch
    ): Long {
        return (token.expiresAt + leeway) - currentTime
    }

    override fun expireAfterRead(
        key: String,
        token: ExpirableToken,
        currentTime: Long,
        currentDuration: Long // Nanoseconds since epoch
    ): Long {
        return (token.expiresAt + leeway) - currentTime
    }
}


fun TexasTokenResponseDto.toExpirableToken(): ExpirableToken {
    return ExpirableToken(
        token = this.accessToken,
        expiresAt = System.currentTimeMillis() + (expiresIn * 1000),
        tokenType = tokenType
    )
}

fun TexasTokenResponseDto.toTexasTokenSuccessResult(): TexasTokenSuccessResult {
    return TexasTokenSuccessResult(
        accessToken = this.accessToken,
        expiresIn = this.expiresIn,
        tokenType = this.tokenType
    )
}

fun ExpirableToken.toTexasTokenResponse(): TexasTokenSuccessResult {
    return TexasTokenSuccessResult(
        accessToken = this.token,
        expiresIn = ((this.expiresAt - System.currentTimeMillis()) / 1000).toInt(),
        tokenType = this.tokenType
    )
}

class TexasSystemTokenClient(
    private val tokenEndpoint: String,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(Logging)
        install(ContentNegotiation) {
            json()
        }
    },
) {
    private val cache = Caffeine.newBuilder()
        .expireAfter(expiry)
        .build<String, ExpirableToken>()

    val logger = LoggerFactory.getLogger(this::class.java)

    fun tokenProvider(scope: String): ProvideToken {
        return { getToken(scope) }
    }

    suspend fun getToken(target: String): TexasTokenResponse {
        val cachedValue = cache.getIfPresent(target)
        if (cachedValue != null) {
            logger.info("Bruker cache for Texas token for app: $target")
            return cachedValue.toTexasTokenResponse()
        }

        val texasTokenRequest = TexasTokenRequest(
            identityProvider = "azuread",
            target = target,
        )
        try {
            val response =
                httpClient.post(tokenEndpoint) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(texasTokenRequest)
                }

            if (!response.status.isSuccess()) {
                logger.error("Kall for autentisering mot Texas feilet - HTTP ${response.status.value} - ${response.bodyAsText()}")
                return TexasTokenFailedResult("Kall for autentisering mot Texas feilet - HTTP ${response.status.value} - ${response.bodyAsText()} ")
            }
            return response.body<TexasTokenResponseDto>()
                .also { cache.put(target, it.toExpirableToken()) }
                .toTexasTokenSuccessResult()
        } catch (e: Throwable) {
            logger.error("Kall for autentisering mot Texas feilet: ${e.message ?: "Ukjent feil"}", e)
            return TexasTokenFailedResult("Kall for autentisering mot Texas feilet: ${e.message ?: "Ukjent feil"}")
        }

    }
}

@Serializable
data class TexasTokenRequest(
    @SerialName("identity_provider") val identityProvider: String,
    val target: String
)

@Serializable
data class TexasTokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int, // in seconds
    @SerialName("token_type") val tokenType: String,
)

sealed class TexasTokenResponse
data class TexasTokenFailedResult(val errorMessage: String) : TexasTokenResponse()
data class TexasTokenSuccessResult(
    val accessToken: String,
    val expiresIn: Int, // in seconds
    val tokenType: String,
) : TexasTokenResponse() {
    init {
        require(!accessToken.startsWith("Bearer")) { "accessToken must not be prefixed with bearer" }
        require(accessToken.isNotEmpty()) { "accessToken must not be empty" }
    }
}