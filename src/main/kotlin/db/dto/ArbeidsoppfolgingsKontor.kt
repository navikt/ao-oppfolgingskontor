package no.nav.db.dto

import no.nav.db.entity.ArbeidsOppfolgingKontorEntity


class ArbeidsoppfolgingKontor(
    kontorId: String,
    metadata: KontorMetadata,
): Kontor(kontorId, metadata)

fun ArbeidsOppfolgingKontorEntity.toArbeidsoppfolgingKontor(): ArbeidsoppfolgingKontor {
    return ArbeidsoppfolgingKontor(
        kontorId = this.kontorId,
        metadata = KontorMetadata(
            createdAt = this.createdAt,
            updatedAt = this.updatedAt
        )
    )
}