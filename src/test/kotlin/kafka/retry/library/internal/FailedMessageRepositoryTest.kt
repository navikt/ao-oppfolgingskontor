package kafka.retry.library.internal

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.kafka.retry.library.internal.FailedMessageRepository
import no.nav.utils.TestDb
import org.flywaydb.core.Flyway
import org.junit.Before
import org.junit.Test
import javax.sql.DataSource
import kotlin.text.toByteArray

class FailedMessageRepositoryTest {
    private val topic = "test-topic"
    private var dataSource: DataSource = TestDb.postgres
    private var repository: FailedMessageRepository = FailedMessageRepository( topic)

    @Before
    fun createTable() {
        /* Flyway migrering hvis vi ikke legge denne koden i et ekstern bibliotek */
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        // Sørg for at tabellen er ren før hver test
        dataSource.connection.use { conn ->
            conn.createStatement().execute("DELETE FROM failed_messages")
        }
        /*
        dataSource.connection.use { conn ->
            conn.createStatement().execute("DROP TABLE IF EXISTS failed_messages")
            conn.createStatement().execute("""
                  CREATE TABLE failed_messages (
                      id BIGSERIAL PRIMARY KEY,
                      message_key VARCHAR(255) NOT NULL,
                      message_value BYTEA NOT NULL,
                      queue_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      last_attempt_timestamp TIMESTAMPTZ,
                      retry_count INT NOT NULL DEFAULT 0,
                      failure_reason TEXT
                  )
              """)
        }
        */

    }

    @Test
    fun `should enqueue and correctly check for failed messages`() {
        val key = "test-key"
        val otherKey = "other-key"
        val value = "test-value".toByteArray()

        repository.hasFailedMessages(key) shouldBe false
        repository.countTotalFailedMessages() shouldBe 0


        repository.enqueue(key, value, key.toByteArray(), "Initial failure")

        repository.hasFailedMessages(key) shouldBe true
        repository.hasFailedMessages(otherKey) shouldBe false

        repository.countTotalFailedMessages() shouldBe 1
    }

    @Test
    fun `should retrieve batch to retry in FIFO order`() {
        repository.enqueue("key1", "key1".toByteArray(), "val1".toByteArray(), "fail1")
        Thread.sleep(10) // Sørg for unik timestamp
        repository.enqueue("key2", "key2".toByteArray(), "val2".toByteArray(), "fail2")

        val batch = repository.getBatchToRetry(5)

        batch.size shouldBe 2
        batch[0].messageKeyText shouldBe "key1"
        batch[1].messageKeyText shouldBe "key2"
    }

    @Test
    fun `getBatchToRetry should return first failed message on key`() {
        repository.enqueue("key", "key".toByteArray(), "first".toByteArray(), "first-fail")
        repository.enqueue("key", "key".toByteArray(), "second".toByteArray(), "second-fail")

        val batch = repository.getBatchToRetry(2)

        batch.size shouldBe 2
        batch[0].failureReason shouldBe "first-fail"

        repository.delete(batch[0].id)

        repository.getBatchToRetry(2).size shouldBe 1
    }

    @Test
    fun `should delete a message by id`() {
        repository.enqueue("key-to-delete", "key-to-delete".toByteArray(), "val".toByteArray(), "failure")
        val message = repository.getBatchToRetry(1).first()

        repository.delete(message.id)

        repository.countTotalFailedMessages() shouldBe 0

    }

    @Test
    fun `should update a message after a failed attempt`() {
        repository.enqueue("key-to-update", "key-to-update".toByteArray(), "val".toByteArray(), "Initial failure")
        val message = repository.getBatchToRetry(1).first()

        repository.updateAfterFailedAttempt(message.id, "Second failure")

        val updatedMessage = repository.getBatchToRetry(1).first()

        updatedMessage.retryCount shouldBe 1
        updatedMessage.failureReason shouldBe "Second failure"
        updatedMessage.lastAttemptTimestamp shouldNotBe null
    }


}