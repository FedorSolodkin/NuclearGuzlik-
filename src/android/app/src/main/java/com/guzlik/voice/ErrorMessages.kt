package com.guzlik.voice

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Переводит технические исключения в короткие сообщения на русском.
// Логика простая: смотрим на тип исключения → мапим на понятную категорию.
// Не пытаемся угадать все возможные причины — покрываем 90% реальных случаев.
object ErrorMessages {

    fun humanize(t: Throwable?): String = when (t) {
        null -> "Что-то пошло не так"
        is UnknownHostException,
        is ConnectException -> "Нет соединения с интернетом"
        is SocketTimeoutException -> "Сервер не отвечает — попробуйте позже"
        is SSLException -> "Проблема с защищённым соединением"
        is FileNotFoundException -> "Файл не найден"
        is SecurityException -> "Нет нужных разрешений"
        is OutOfMemoryError -> "Не хватает памяти"
        is IOException -> "Проблема с файлом или сетью"
        is IllegalStateException -> "Операция сейчас недоступна"
        else -> t.message?.takeIf { it.isNotBlank() }?.take(80) ?: "Неожиданная ошибка"
    }
}
