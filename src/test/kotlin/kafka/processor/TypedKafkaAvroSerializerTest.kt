package kafka.processor

import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import no.nav.kafka.processor.TypedKafkaAvroSerializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

class TypedKafkaAvroSerializerTest {

 private var innerMock: KafkaAvroSerializer = mockk()
 private lateinit var serializer: TypedKafkaAvroSerializer<String>

 @BeforeEach
 fun setUp() {
  // Create the instance to be tested
  serializer = TypedKafkaAvroSerializer()

  // Replace the real 'inner' instance with our mock using reflection
  val field = serializer::class.java.getDeclaredField("inner")
  field.isAccessible = true
  field.set(serializer, innerMock)
 }

 @Test
 fun `configure should delegate call to inner serializer`() {
  val configs = mapOf("schema.registry.url" to "http://mock-registry")
  val isKey = true

  // Set up the mock to expect the call and do nothing
  every { innerMock.configure(any(), any()) } just runs

  // Perform the action
  serializer.configure(configs, isKey)

  // Verify that the inner mock's configure method was called exactly once with the correct parameters
  verify(exactly = 1) { innerMock.configure(configs, isKey) }
 }

 @Test
 fun `serialize should delegate call to inner serializer and return its result`() {
  val topic = "test-topic"
  val dataToSerialize = "my-test-string"
  val expectedBytes = dataToSerialize.toByteArray()

  // When the inner mock's serialize is called, return our expected byte array
  every { innerMock.serialize(topic, dataToSerialize) } returns expectedBytes

  // Perform the action
  val resultBytes = serializer.serialize(topic, dataToSerialize)

  // Verify that the result is what the inner mock returned
  resultBytes shouldNotBe null
  assertContentEquals(expectedBytes, resultBytes)

  // Verify that the inner mock's serialize method was called
  verify(exactly = 1) { innerMock.serialize(topic, dataToSerialize) }
 }

 @Test
 fun `serialize should handle null data by delegating it`() {
  val topic = "test-topic"

  // Set up the mock to handle null data
  every { innerMock.serialize(topic, null) } returns null

  // Perform the action
  val result = serializer.serialize(topic, null)

  // Verify the result and the interaction
  assert(result == null)
  verify(exactly = 1) { innerMock.serialize(topic, null) }
 }
}