package utils

sealed class Outcome<out T> {
    data class Success<out T>(val data: T) : Outcome<T>()
    data class Failure(val exception: Exception) : Outcome<Nothing>()
}