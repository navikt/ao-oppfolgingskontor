package kafka.out

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.domain.events.AOKontorEndret
import no.nav.utils.ZonedDateTimeSerializer
import org.apache.kafka.streams.processor.api.Record
import java.time.ZonedDateTime
import kotlin.String

@Serializable
data class AOKontorEndretRecord(
    val fnr: String,
    val kontorId: String,
    val endretAv: String,
    val endretAvType: String,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val endretTidspunkt: ZonedDateTime,
)

fun AOKontorEndret.toRecord(): Record<String, String> {
    val now = ZonedDateTime.now()
    return Record(this.tilordning.fnr.value,
        Json.encodeToString(AOKontorEndretRecord(
            fnr = this.tilordning.fnr.value,
            kontorId = this.tilordning.kontorId.id,
            endretAv = this.registrant.getIdent(),
            endretAvType = this.registrant.getType(),
            endretTidspunkt = now,
        )),
        now.toEpochSecond()
    )
}