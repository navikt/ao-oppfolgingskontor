package kafka.out

import java.time.ZonedDateTime
import kafka.producers.OppfolgingEndretTilordningMelding
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.events.AOKontorEndret
import org.apache.kafka.streams.processor.api.Record

fun AOKontorEndret.toOppfolgingEndretTilordningMeldingRecord(): Record<OppfolgingsperiodeId, OppfolgingEndretTilordningMelding> {
    return Record(
        this.tilordning.oppfolgingsperiodeId,
        OppfolgingEndretTilordningMelding(
            kontorId = this.tilordning.kontorId.id,
            oppfolgingsperiodeId = this.tilordning.oppfolgingsperiodeId.value.toString(),
            ident = this.tilordning.fnr.value,
            kontorEndringsType = this.kontorEndringsType(),
        ),
        ZonedDateTime.now().toEpochSecond(),
    )
}