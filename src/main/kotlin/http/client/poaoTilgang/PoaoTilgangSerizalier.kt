package no.nav.http.client.poaoTilgang

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import no.nav.poao_tilgang.api.dto.response.Diskresjonskode
import no.nav.poao_tilgang.api.dto.response.TilgangsattributterResponse

@Serializable
private data class TilgangsattributterSurrogate(
    val kontor: String?,
    val skjermet: Boolean,
    val diskresjonskode: Diskresjonskode?,
)

object PoaoTilgangSerizalier: KSerializer<TilgangsattributterResponse> {
    override val descriptor: SerialDescriptor
        get() = TilgangsattributterSurrogate.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: TilgangsattributterResponse
    ) {
        val surrogate = TilgangsattributterSurrogate(
            kontor = value.kontor,
            skjermet = value.skjermet,
            diskresjonskode = value.diskresjonskode
        )
        encoder.encodeSerializableValue(TilgangsattributterSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): TilgangsattributterResponse {
        val surrogate = decoder.decodeSerializableValue(TilgangsattributterSurrogate.serializer())
        return TilgangsattributterResponse(
            kontor = surrogate.kontor,
            skjermet = surrogate.skjermet,
            diskresjonskode = surrogate.diskresjonskode
        )
    }
}