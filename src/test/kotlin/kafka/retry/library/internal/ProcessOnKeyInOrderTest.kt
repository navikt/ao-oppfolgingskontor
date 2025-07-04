package kafka.retry.library.internal

import io.kotest.matchers.shouldBe
import no.nav.kafka.processor.Commit
import no.nav.kafka.retry.library.internal.FailedMessage
import no.nav.kafka.retry.library.internal.RetryableFail
import no.nav.kafka.retry.library.internal.Success
import no.nav.kafka.retry.library.internal.proccessInOrderOnKey
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class ProcessOnKeyInOrderTest {

    val simpleFailedMessage = FailedMessage(
        id = Long.MAX_VALUE,
        messageKeyText = "key1",
        messageKeyBytes = "key1".toByteArray(),
        messageValue = "value1".toByteArray(),
        queueTimestamp = OffsetDateTime.now(),
        retryCount = 0,
        lastAttemptTimestamp = null,
        failureReason = "-"
    )

    @Test
    fun `should process in order on key on given order`() {
        listOf(
            simpleFailedMessage.copy(id = 2),
            simpleFailedMessage.copy(id = 1),
            simpleFailedMessage.copy(id = 3),
        ).proccessInOrderOnKey {
            Success<String, String, Unit, Unit>(it, Commit)
        }.map { it.msg.id } shouldBe listOf(2L, 1L, 3L)
    }

    @Test
    fun `should stop processing key-messages on first retryable message`() {
        listOf(
            simpleFailedMessage.copy(id = 2, failureReason = "Fail on retry"),
            simpleFailedMessage.copy(id = 1, failureReason = "Success on retry"),
            simpleFailedMessage.copy(id = 3, failureReason = "Success on retry"),
        ).proccessInOrderOnKey {
            if (it.failureReason == "Fail on retry") {
                RetryableFail(it, error = Exception("Retry"))
            } else {
                Success<String, String, Unit, Unit>(it, Commit)
            }
        }.map { it.msg.id } shouldBe listOf(2L)
    }

    @Test
    fun `should process all messages on key even if last message is retryable`() {
        listOf(
            simpleFailedMessage.copy(id = 2, failureReason = "Success on retry"),
            simpleFailedMessage.copy(id = 3, failureReason = "Success on retry"),
            simpleFailedMessage.copy(id = 1, failureReason = "Fail on retry"),
        ).proccessInOrderOnKey {
            if (it.failureReason == "Fail on retry") {
                RetryableFail(it, error = Exception("Retry"))
            } else {
                Success<String, String, Unit, Unit>(it, Commit)
            }
        }.map { it.msg.id } shouldBe listOf(2L, 3L, 1L)
    }
}