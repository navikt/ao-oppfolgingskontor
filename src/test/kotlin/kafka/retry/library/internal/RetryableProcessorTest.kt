package kafka.retry.library.internal

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.mockk.*
import kafka.retry.TestLockProvider
import no.nav.db.flywayMigrate
import no.nav.kafka.processor.Commit
import no.nav.kafka.retry.library.AvroJsonConverter
import no.nav.kafka.retry.library.MaxRetries
import no.nav.kafka.retry.library.RetryConfig
import no.nav.kafka.retry.library.internal.FailedMessage
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.kafka.retry.library.internal.RetryMetrics
import no.nav.kafka.retry.library.internal.RetryableProcessor
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.person.pdl.leesah.adressebeskyttelse.Adressebeskyttelse
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.utils.TestDb
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.processor.Punctuator
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Enhetstester for RetryableProcessor.
 *
 * Det viste seg vanskelig å bruke TopologyTestDriver på grunn av manglende støtte for custom state stores.
 */
class RetryableProcessorTest {

 // Mocks for alle avhengigheter
 private lateinit var mockedContext: ProcessorContext<Unit, Unit>
 private lateinit var mockedStore: FailedMessageRepository
 private lateinit var mockedMetrics: RetryMetrics

 // Selve prosessoren som testes
 private lateinit var processor: RetryableProcessor<String, String, Unit, Unit>

 // Egen test for Avro-meldinger
 private lateinit var avroProcessor: RetryableProcessor<String, Personhendelse, Unit, Unit>

 // For å fange opp den scheduled Punctuation-lambdaen
 private val punctuationCallback = slot<Punctuator>()

 private val config =
  RetryConfig(retryInterval = Duration.ofMinutes(1), maxRetries = MaxRetries.Finite(2), stateStoreName = "test-store")
 private val inputTopicName = "input-topic"

 @BeforeEach
 fun setup() {
  flywayMigrate(TestDb.postgres)
  // --- 1. Lag Mocks ---
  mockedContext = mockk(relaxed = true)
  mockedStore = mockk(relaxed = true)

  // --- 2. Konfigurer Mock-oppførsel  ---

  // Når context.schedule blir kalt, fang opp lambdaen (Consumer) som sendes inn
  every { mockedContext.schedule(any(), any(), capture(punctuationCallback)) } returns mockk()

  // --- 3. Lag en instans av prosessoren som skal testes ---
  processor = RetryableProcessor<String, String, Unit, Unit>(
   config = config,
   keyInSerializer = Serdes.String().serializer(),
   valueInSerializer = Serdes.String().serializer(),
   keyInDeserializer = Serdes.String().deserializer(),
   valueInDeserializer = Serdes.String().deserializer(),
   topic = inputTopicName,
   repository = mockedStore, // Dummy mock, ikke brukt direkte av prosessoren
   // Definer en kontrollerbar forretningslogikk for testen
   businessLogic = { record ->
    if (record.value().contains("FAIL")) {
     throw RuntimeException("Simulated failure")
    }
    Commit
   },
   TestLockProvider
  )

  // --- 4. Initialiser prosessoren ---
  processor.init(mockedContext)

  // --- 5. Bytt ut metrikk-instans med en mock ---
  // Dette gjør verifisering av metrikk-kall mye enklere.
  mockedMetrics = mockk(relaxed = true)
  val metricsField = processor.javaClass.getDeclaredField("metrics")
  metricsField.isAccessible = true
  metricsField.set(processor, mockedMetrics)
 }

 @Test
 fun `should process successfully when store is empty and logic succeeds`() {
  // Arrange
  every { mockedStore.hasFailedMessages("key1") } returns false

  // Act
  processor.process(Record("key1", "good-value", 0L))

  // Assert
  verify(exactly = 0) { mockedStore.enqueue(any(), any(), any(), any()) }
  verify(exactly = 0) { mockedMetrics.messageEnqueued() }
 }

 @Test
 fun `should enqueue when business logic fails`() {
  // Arrange
  every { mockedStore.hasFailedMessages("key1") } returns false

  // Act
  processor.process(Record("key1", "value-with-FAIL", 0L))

  // Assert
  verify(exactly = 1) {
   mockedStore.enqueue(
    eq("key1"),
    any(),
    any(),
    match { it.contains("Simulated failure") }
   )
  }
  verify(exactly = 1) { mockedMetrics.messageEnqueued() }
 }

 @Test
 fun `should enqueue when store already has failures for the key`() {
  // Arrange
  every { mockedStore.hasFailedMessages("key1") } returns true

  // Act
  processor.process(Record("key1", "good-value-but-blocked", 0L))

  // Assert
  verify { mockedStore.enqueue(eq("key1"), any(), any(), match { it.contains("Queued behind") }) }
  verify { mockedMetrics.messageEnqueued() }
 }

 @Test
 fun `punctuation should attempt to retry and succeed`() {
  // Arrange
  val realTimestamp = OffsetDateTime.now()
  val failedMessage = FailedMessage(1L, "key1", "key1".toByteArray(), "value".toByteArray(), realTimestamp, 0)
  every { mockedStore.getBatchToRetry(any()) } returns listOf(failedMessage)

  // Act
  if (punctuationCallback.isCaptured) {
   punctuationCallback.captured.punctuate(System.currentTimeMillis())
  } else {
   throw AssertionError("Punctuation callback was not captured")
  }

  // Assert: Nå vil verifiseringen lykkes!
  verify { mockedMetrics.updateCurrentFailedMessagesGauge() }
  verify { mockedMetrics.retryAttempted() }
  verify { mockedStore.delete(1L) }
  verify { mockedMetrics.retrySucceeded() }
 }

 @Test
 fun `punctuation should attempt to retry and fail again`() {
  // Arrange
  val realTimestamp = OffsetDateTime.now()
  // Meldingen inneholder "FAIL" for å trigge feil i businessLogic
  val failedMessage = FailedMessage(1L, "key1", "key1".toByteArray(), "value-with-FAIL".toByteArray(), realTimestamp, 1)
  every { mockedStore.getBatchToRetry(any()) } returns listOf(failedMessage)

  // Act
  punctuationCallback.captured.punctuate(System.currentTimeMillis())

  // Assert
  verify { mockedMetrics.retryAttempted() }
  verify { mockedStore.updateAfterFailedAttempt(1L, any()) }
  verify { mockedMetrics.retryFailed() }
 }

 @Test
 fun `punctuation should dead-letter message if max retries is exceeded`() {
  // Arrange
  val realTimestamp = OffsetDateTime.now()
  val failedMessage =
   FailedMessage(1L, "key1", "key1".toByteArray(), "value".toByteArray(), realTimestamp, retryCount = 2)
  every { mockedStore.getBatchToRetry(any()) } returns listOf(failedMessage)

  // Act
  punctuationCallback.captured.punctuate(System.currentTimeMillis())

  // Assert
  verify { mockedMetrics.retryAttempted() }
  verify { mockedMetrics.messageDeadLettered() }
  verify { mockedStore.delete(1L) }
 }

 @Test
 fun `avro meldinger skal lagre en menneskelig lesbar verdi ved feil`() {

  val schemaRegistryClient = MockSchemaRegistryClient()

  val valueSerdeConfig = mapOf(
   AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to "mock://schema-registry",
   "specific.avro.reader" to true
  )
  val valueAvroSerde: SpecificAvroSerde<Personhendelse> = SpecificAvroSerde<Personhendelse>(schemaRegistryClient)
   .apply {
    configure(
     valueSerdeConfig,
     false
    )
   }

  avroProcessor = RetryableProcessor<String, Personhendelse, Unit, Unit>(
   config = config,
   keyInSerializer = Serdes.String().serializer(),
   valueInSerializer = valueAvroSerde.serializer(),
   keyInDeserializer = Serdes.String().deserializer(),
   valueInDeserializer = valueAvroSerde.deserializer(),
   topic = inputTopicName,
   repository = mockedStore, // Dummy mock, ikke brukt direkte av prosessoren
   // Definer en kontrollerbar forretningslogikk for testen
   businessLogic = { record ->
    if (record.value().master == "feil") {
     throw RuntimeException("Simulated failure")
    }
    Commit
   },
   TestLockProvider
  )
  avroProcessor.init(mockedContext)
  mockedMetrics = mockk(relaxed = true)
  val metricsField = avroProcessor.javaClass.getDeclaredField("metrics")
  metricsField.isAccessible = true
  metricsField.set(avroProcessor, mockedMetrics)

  // Arrange
  every { mockedStore.hasFailedMessages("key1") } returns false

  // Act

  val adressebeskyttelse = Adressebeskyttelse.newBuilder()
   .setGradering(Gradering.STRENGT_FORTROLIG)
   .build()
  val personhendelse = Personhendelse.newBuilder()
   .setHendelseId("41350fcd-ac60-4c86-8ff6-e585fb6edc36")
   .setPersonidenter(listOf("1234567890"))
   .setMaster("feil")
   .setOpprettet(Instant.ofEpochMilli(1752132330760))
   .setOpplysningstype("adressebeskyttelse")
   .setEndringstype(Endringstype.OPPRETTET)
   .setAdressebeskyttelse(adressebeskyttelse)
   .build()


  avroProcessor.process(Record("key1", personhendelse, 0L))

  // Assert
  verify(exactly = 1) {
   mockedStore.enqueue(
    eq("key1"),
    any(),
    any(),
    any(),
    any()
   )
  }
  verify(exactly = 1) { mockedMetrics.messageEnqueued() }

  // Assert
  val valueBytes = valueAvroSerde.serializer().serialize(inputTopicName, personhendelse )
  val humanReadableValue = AvroJsonConverter.convertAvroToJson(personhendelse, true)
  verify { mockedStore.enqueue("key1", "key1".toByteArray(), valueBytes, any(), humanReadableValue) }
 }
}

