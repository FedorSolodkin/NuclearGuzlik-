package com.example.voicemind

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Потоковое AES-GCM шифрование блоками по [CHUNK] байт: большие файлы (часовая
 * запись, архив копии) не держатся в памяти целиком.
 *
 * Формат: блоки [int длина][12 байт IV][шифротекст+тег]. В AAD каждого блока —
 * его номер и флаг «последний», поэтому переставить блоки или отрезать хвост
 * незаметно нельзя. Пустой вход — один пустой последний блок.
 *
 * Ключ может быть и из Android Keystore (vault), и обычный (пароль копии).
 */
object ChunkedAead {
    const val CHUNK = 256 * 1024
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun aad(index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(5).putInt(index).put(if (last) 1 else 0).array()

    fun encrypt(key: SecretKey, input: InputStream, output: OutputStream) {
        val out = DataOutputStream(output)
        var buf = ByteArray(CHUNK)
        var cur = readFully(input, buf)
        var index = 0
        while (true) {
            val next = ByteArray(CHUNK)
            val nextLen = if (cur == CHUNK) readFully(input, next) else 0
            val last = nextLen == 0
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(index, last))
            val ct = cipher.doFinal(buf, 0, cur)
            out.writeInt(ct.size)
            out.write(cipher.iv)
            out.write(ct)
            if (last) break
            buf = next
            cur = nextLen
            index++
        }
        out.flush()
    }

    /** Бросает исключение при неверном ключе/пароле или повреждённых данных. */
    fun decrypt(key: SecretKey, input: InputStream, output: OutputStream) {
        val inp = DataInputStream(input)
        var index = 0
        var len = inp.readInt()
        while (true) {
            require(len in 0..(CHUNK + 64)) { "bad chunk length" }
            val iv = ByteArray(IV_LEN).also { inp.readFully(it) }
            val ct = ByteArray(len).also { inp.readFully(it) }
            val nextLen = try { inp.readInt() } catch (_: EOFException) { -1 }
            val last = nextLen < 0
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(index, last))
            output.write(cipher.doFinal(ct))
            if (last) break
            len = nextLen
            index++
        }
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
