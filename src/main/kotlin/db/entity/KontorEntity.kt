package no.nav.db.entity

import no.nav.domain.KontorId

sealed interface KontorEntity {
    fun getKontorId(): KontorId
}