package domain

import no.nav.domain.KontorId
import no.nav.domain.OppfolgingsperiodeId
import java.time.OffsetDateTime

/* Arena-kontor med oppfolgingsperiodeId men uten navn */
data class ArenaKontorUtvidet(
    val kontorId: KontorId,
    /* Migreringer har ikke oppfølgingsperioder, alle andre typer endringer skal ha periode */
    val oppfolgingsperiodeId: OppfolgingsperiodeId?,
    val sistEndretDatoArena: OffsetDateTime?,
)
