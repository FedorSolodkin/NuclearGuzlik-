package com.example.voicemind

/**
 * Потоковый ресемплер в 16 кГц моно для [AudioDecoder]: усреднение по окну
 * шириной в исходный шаг (простой anti-aliasing при понижении частоты) +
 * линейная интерполяция при повышении. Для распознавания речи этого хватает:
 * полезная полоса речи ниже 8 кГц. Буферы можно подавать любого размера —
 * состояние между вызовами сохраняется.
 */
class PcmResampler16k {
    private var pos = 0.0          // позиция следующего выходного сэмпла во входном потоке
    private var consumed = 0L      // сколько входных сэмплов пришло до текущего буфера
    private var prev = 0f

    fun process(input: FloatArray, srcRate: Int, out: ShortArrayBuilder) {
        if (input.isEmpty()) return
        if (srcRate == 16_000) {
            for (v in input) out.add(v)
            consumed += input.size
            pos = consumed.toDouble()
            return
        }
        val step = srcRate / 16_000.0
        val half = (step / 2).coerceAtLeast(0.5)
        while (true) {
            val local = pos - consumed
            if (local + half >= input.size) break
            val v = if (step > 1.0) {
                val from = maxOf(0, kotlin.math.floor(local - half).toInt())
                val to = minOf(input.size - 1, kotlin.math.ceil(local + half).toInt())
                var sum = 0f
                for (i in from..to) sum += input[i]
                sum / (to - from + 1)
            } else {
                val i = kotlin.math.floor(local).toInt()
                val frac = (local - i).toFloat()
                val a = if (i < 0) prev else input[i]
                val b = input[minOf(i + 1, input.size - 1)]
                a + (b - a) * frac
            }
            out.add(v)
            pos += step
        }
        prev = input.last()
        consumed += input.size
    }
}

/**
 * Растущий ShortArray без боксинга.
 *
 * С [sink] — потоковый режим: каждые [sinkChunk] сэмплов отдаются наружу и буфер
 * переиспользуется, так что длина файла на память не влияет (импорт часовой записи
 * раньше падал с OutOfMemoryError: весь звук собирался в один массив ~245 МБ).
 * Без [sink] копит всё; [initial] — ожидаемая длина, чтобы не расти удвоением.
 */
class ShortArrayBuilder(
    initial: Int = 16_000 * 60,
    private val sink: ((ShortArray) -> Unit)? = null,
    private val sinkChunk: Int = 1_600,
) {
    private var buf = ShortArray(if (sink != null) sinkChunk else initial.coerceAtLeast(16_000))
    var size = 0
        private set
    /** Сколько сэмплов прошло всего (в потоковом режиме — включая уже отданные). */
    var total = 0L
        private set

    fun add(v: Float) {
        if (size == buf.size) {
            if (sink != null) { sink.invoke(buf.copyOf(size)); size = 0 }
            else buf = buf.copyOf(buf.size + maxOf(buf.size / 2, 16_000))   // ×1,5, а не ×2: меньше пик памяти
        }
        buf[size++] = v.toInt().coerceIn(-32768, 32767).toShort()
        total++
    }

    /** Потоковый режим: отдать остаток. */
    fun flush() {
        if (sink != null && size > 0) { sink.invoke(buf.copyOf(size)); size = 0 }
    }

    fun toArray(): ShortArray = if (size == buf.size) buf else buf.copyOf(size)
}
