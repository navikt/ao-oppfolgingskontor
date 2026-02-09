package no.nav.domain

import kotlinx.serialization.Serializable

@Serializable
data class KontorNavn(
    val navn: String,
)