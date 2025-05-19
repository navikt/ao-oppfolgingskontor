package no.nav.http.graphql.schemas

import kotlinx.serialization.Serializable

@Serializable
data class AlleKontorQueryDto(
    val kontorId: String,
    val kontorNavn: String,
)