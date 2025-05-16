package no.nav.db.dto

import no.nav.db.entity.ArbeidsOppfolgingKontorEntity


class ArbeidsoppfolgingDBKontor(
    kontorId: String,
    metadata: KontorMetadata,
): DBKontor(kontorId, metadata)

fun ArbeidsOppfolgingKontorEntity.toArbeidsoppfolgingKontor(): ArbeidsoppfolgingDBKontor {
    return ArbeidsoppfolgingDBKontor(
        kontorId = this.kontorId,
        metadata = KontorMetadata(
            createdAt = this.createdAt,
            updatedAt = this.updatedAt
        )
    )
}