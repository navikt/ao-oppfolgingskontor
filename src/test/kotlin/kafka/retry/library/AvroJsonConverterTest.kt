package kafka.retry.library

import io.kotest.matchers.shouldBe
import no.nav.kafka.retry.library.AvroJsonConverter
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.person.pdl.leesah.adressebeskyttelse.Adressebeskyttelse
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import org.junit.jupiter.api.Test

import java.time.Instant

class AvroJsonConverterTest {

@Test
fun `should convert Avro to Json()`() {
 val adressebeskyttelse = Adressebeskyttelse.newBuilder()
  .setGradering(Gradering.STRENGT_FORTROLIG)
  .build()
 val personhendelse = Personhendelse.newBuilder()
  .setHendelseId("41350fcd-ac60-4c86-8ff6-e585fb6edc36")
  .setPersonidenter(listOf("1234567890"))
  .setMaster("1234567890")
  .setOpprettet(Instant.ofEpochMilli(1752132330760))
  .setOpplysningstype("adressebeskyttelse")
  .setEndringstype(Endringstype.OPPRETTET)
  .setAdressebeskyttelse(adressebeskyttelse)
  .build()

 val actual = AvroJsonConverter.convertAvroToJson(personhendelse)

 actual shouldBe """
            {
              "hendelseId" : "41350fcd-ac60-4c86-8ff6-e585fb6edc36",
              "personidenter" : [ "1234567890" ],
              "master" : "1234567890",
              "opprettet" : 1752132330760,
              "opplysningstype" : "adressebeskyttelse",
              "endringstype" : "OPPRETTET",
              "tidligereHendelseId" : null,
              "adressebeskyttelse" : {
                "no.nav.person.pdl.leesah.adressebeskyttelse.Adressebeskyttelse" : {
                  "gradering" : "STRENGT_FORTROLIG"
                }
              },
              "doedsfall" : null,
              "falskIdentitet" : null,
              "foedsel" : null,
              "foedselsdato" : null,
              "utflyttingFraNorge" : null,
              "InnflyttingTilNorge" : null,
              "Folkeregisteridentifikator" : null,
              "sikkerhetstiltak" : null,
              "statsborgerskap" : null,
              "kontaktadresse" : null,
              "bostedsadresse" : null
            }
        """.trimIndent()
 }

@Test
    fun `should return empty json when Avro is null`() {
    val actual = AvroJsonConverter.convertAvroToJson(null)
    actual shouldBe null
    }
}