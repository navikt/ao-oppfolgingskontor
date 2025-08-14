package no.nav.utils

import no.nav.db.Fnr
import kotlin.random.Random

fun randomFnr(): Fnr {
    val randomDigits = (1..11).map { (0..9).random() }.joinToString("")
    return Fnr(randomDigits)
}

fun randomTopicName(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..10)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}