package kafka.processor

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import no.nav.kafka.processor.TypedKafkaAvroDeserializer
import org.apache.kafka.common.errors.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TypedKafkaAvroDeserializerTest {

 @get:Rule
 val mockkRule = MockKRule(this)

 @MockK
 private lateinit var innerMock: io.confluent.kafka.serializers.KafkaAvroDeserializer

 private lateinit var deserializer: TypedKafkaAvroDeserializer<String>

 @Before
 fun setUp() {
  deserializer = TypedKafkaAvroDeserializer(String::class.java)

  val field = deserializer::class.java.getDeclaredField("inner")
  field.isAccessible = true
  field.set(deserializer, innerMock)
 }

 @Test
 fun `configure should delegate to inner deserializer`() {
  val configs = mapOf("schema.registry.url" to "http://mock-registry")
  val isKey = false

  // Expect the call and do nothing
  every { innerMock.configure(any(), any()) } just runs

  // Perform action
  deserializer.configure(configs, isKey)

  // Verify interaction
  verify(exactly = 1) { innerMock.configure(configs, isKey) }
 }

 @Test
 fun `deserialize should return casted object when inner deserializer returns correct type`() {
  val topic = "test-topic"
  val data = "some-data".toByteArray()
  val expectedString = "deserialized-string"

  // Define mock behavior: when deserialize is called, return a string
  every { innerMock.deserialize(topic, data) } returns expectedString

  // Perform the action
  val result = deserializer.deserialize(topic, data)

  // Verify the result
  assertEquals(expectedString, result)
 }

 @Test(expected = SerializationException::class)
 fun `deserialize should throw SerializationException when inner deserializer returns wrong type`() {
  val topic = "test-topic"
  val data = "some-data".toByteArray()
  val wrongTypeObject = 42L // A Long instead of a String

  // Define mock behavior: return the wrong type
  every { innerMock.deserialize(topic, data) } returns wrongTypeObject

  // Perform the action. JUnit 4 will catch the expected exception.
  deserializer.deserialize(topic, data)
 }

 @Test
 fun `deserialize should return null when data is null`() {
  // Perform the action with null data
  val result = deserializer.deserialize("any-topic", null)

  // Verify that the result is null and that the inner mock was never called
  assertNull(result)
  verify(exactly = 0) { innerMock.deserialize(any(), any()) }
 }
}