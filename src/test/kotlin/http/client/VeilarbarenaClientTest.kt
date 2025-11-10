package http.client

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlin.test.Test

class VeilarbarenaClientTest {

    @Test
    fun veilarbarenaClientTest() {
        runBlocking {
            val mockEngine = MockEngine { request ->
                respond(
                    content = ByteReadChannel("""{"ip":"127.0.0.1"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val apiClient = ApiClient(mockEngine)

            Assert.assertEquals("127.0.0.1", apiClient.getIp().ip)
        }
    }
}