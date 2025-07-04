package kafka.processor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.config.*
import io.mockk.every
import io.mockk.mockk
import no.nav.kafka.processor.LeesahAvroSerdes
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LeesahAvroSerdesTest {

 private lateinit var config: ApplicationConfig
 private lateinit var serdes: LeesahAvroSerdes

 @BeforeEach
 fun setup() {
  config = mockk(relaxed = true)
  every { config.property("kafka.schema-registry").getString() } returns "http://localhost:8081"
  every { config.property("kafka.schema-registry-user").getString() } returns "testuser"
  every { config.property("kafka.schema-registry-password").getString() } returns "testpassword"
  serdes = LeesahAvroSerdes(config)
 }

 @Test
 fun `should configure properties from config`() {
  serdes.schemaRegistryUrl shouldBe "http://localhost:8081"
  serdes.schemaRegistryUser shouldBe "testuser"
  serdes.schemaRegistryPassword shouldBe "testpassword"
 }

 @Test
 fun `should initialize serdes`() {
  serdes.keyAvroSerde shouldNotBe null
  serdes.valueAvroSerde shouldNotBe null
 }
}