package no.nav.kafka.processor

import java.time.Instant
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecord

enum class Endringstype {
    OPPRETTET,
    KORRIGERT,
    ANNULLERT,
    OPPHOERT
}

data class LeesahDto(
        val hendelseId: String,
        val personidenter: List<String>,
        val master: String,
        val opprettet: Instant,
        val opplysningstype: String,
        val endringstype: Endringstype,
        val tidligereHendelseId: String? = null,
        val adressebeskyttelse: Any? = null,
        val doedfoedtBarn: Any? = null,
        val doedsfall: Any? = null,
        val falskIdentitet: Any? = null,
        val foedsel: Any? = null,
        val foedselsdato: Any? = null,
        val forelderBarnRelasjon: Any? = null,
        val familierelasjon: Any? = null,
        val sivilstand: Any? = null,
        val vergemaalEllerFremtidsfullmakt: Any? = null,
        val utflyttingFraNorge: Any? = null,
        val innflyttingTilNorge: Any? = null,
        val folkeregisteridentifikator: Any? = null,
        val navn: Any? = null,
        val sikkerhetstiltak: Any? = null,
        val statsborgerskap: Any? = null,
        val telefonnummer: Any? = null,
        val kontaktadresse: Any? = null,
        val bostedsadresse: Any? = null
) : SpecificRecord {

    companion object {
        private val SCHEMA =
                Schema.parse(
                        """
            {
                "type": "record",
                "name": "Personhendelse",
                "namespace": "no.nav.person.pdl.leesah",
                "fields": [
                    {"name": "hendelseId", "type": "string"},
                    {"name": "personidenter", "type": {"type": "array", "items": "string"}},
                    {"name": "master", "type": "string"},
                    {"name": "opprettet", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "opplysningstype", "type": "string"},
                    {"name": "endringstype", "type": {"type": "enum", "name": "Endringstype", "symbols": ["OPPRETTET", "KORRIGERT", "ANNULLERT", "OPPHOERT"]}},
                    {"name": "tidligereHendelseId", "type": ["null", "string"], "default": null},
                    {"name": "adressebeskyttelse", "type": ["null", "Adressebeskyttelse"], "default": null},
                    {"name": "doedfoedtBarn", "type": ["null", "DoedfoedtBarn"], "default": null},
                    {"name": "doedsfall", "type": ["null", "Doedsfall"], "default": null},
                    {"name": "falskIdentitet", "type": ["null", "FalskIdentitet"], "default": null},
                    {"name": "foedsel", "type": ["null", "Foedsel"], "default": null},
                    {"name": "foedselsdato", "type": ["null", "Foedselsdato"], "default": null},
                    {"name": "forelderBarnRelasjon", "type": ["null", "ForelderBarnRelasjon"], "default": null},
                    {"name": "familierelasjon", "type": ["null", "Familierelasjon"], "default": null},
                    {"name": "sivilstand", "type": ["null", "Sivilstand"], "default": null},
                    {"name": "vergemaalEllerFremtidsfullmakt", "type": ["null", "VergemaalEllerFremtidsfullmakt"], "default": null},
                    {"name": "utflyttingFraNorge", "type": ["null", "UtflyttingFraNorge"], "default": null},
                    {"name": "innflyttingTilNorge", "type": ["null", "InnflyttingTilNorge"], "default": null},
                    {"name": "folkeregisteridentifikator", "type": ["null", "Folkeregisteridentifikator"], "default": null},
                    {"name": "navn", "type": ["null", "Navn"], "default": null},
                    {"name": "sikkerhetstiltak", "type": ["null", "Sikkerhetstiltak"], "default": null},
                    {"name": "statsborgerskap", "type": ["null", "Statsborgerskap"], "default": null},
                    {"name": "telefonnummer", "type": ["null", "Telefonnummer"], "default": null},
                    {"name": "kontaktadresse", "type": ["null", "Kontaktadresse"], "default": null},
                    {"name": "bostedsadresse", "type": ["null", "Bostedsadresse"], "default": null}
                ]
            }
        """.trimIndent()
                )
    }

    override fun getSchema(): Schema = SCHEMA

    override fun get(i: Int): Any? {
        return when (i) {
            0 -> hendelseId
            1 -> personidenter
            2 -> master
            3 -> opprettet.toEpochMilli()
            4 -> opplysningstype
            5 -> endringstype.name
            6 -> tidligereHendelseId
            7 -> adressebeskyttelse
            8 -> doedfoedtBarn
            9 -> doedsfall
            10 -> falskIdentitet
            11 -> foedsel
            12 -> foedselsdato
            13 -> forelderBarnRelasjon
            14 -> familierelasjon
            15 -> sivilstand
            16 -> vergemaalEllerFremtidsfullmakt
            17 -> utflyttingFraNorge
            18 -> innflyttingTilNorge
            19 -> folkeregisteridentifikator
            20 -> navn
            21 -> sikkerhetstiltak
            22 -> statsborgerskap
            23 -> telefonnummer
            24 -> kontaktadresse
            25 -> bostedsadresse
            else -> throw IndexOutOfBoundsException("Index $i out of bounds for LeesahDto")
        }
    }

    override fun put(i: Int, v: Any?) {
        throw UnsupportedOperationException("LeesahDto is immutable")
    }
}
