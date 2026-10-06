package tv.p2160.core.m2ts

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import java.io.EOFException
import kotlin.math.max
import kotlin.math.min

/**
 * «Логический» вид M2TS-потока для TsExtractor: из каждого 192-байтного пакета
 * (4 байта TP_extra_header + 188 байт TS) наружу отдаются только 188 байт TS.
 *
 * Логическая позиция L ↔ физическая P: пакет k = L / 188, смещение o = L % 188,
 * P = phase + 192·k + o, где phase — физическое смещение sync-байта (обычно 4).
 * Байты заголовков и всё, что до первого пакета, просто выбрасываются.
 *
 * Физику читаем крупными блоками и складываем «очищенные» байты в собственный буфер:
 * так нет 188-байтных системных вызовов, а peek (нужен TsDurationReader и бинарному поиску)
 * работает поверх того же буфера. Физический ввод при этом может убежать вперёд —
 * это безопасно, т.к. продолжение загрузки (новый ExtractorInput с той же физической позиции)
 * распознаётся в [bind], а любой seek сбрасывает буфер.
 */
@OptIn(UnstableApi::class)
internal class M2tsPacketInput(private val phase: Int) : ExtractorInput {

    private var source: ExtractorInput? = null
    /** Физическая позиция следующего непрочитанного байта [source]. */
    private var sourcePosition = 0L
    private var invalidated = true

    private var buffer = ByteArray(INITIAL_BUFFER_SIZE)
    private var bufferStart = 0
    private var bufferEnd = 0
    private var peekOffset = 0
    /** Логическая позиция байта buffer[bufferStart]. */
    private var position = 0L

    private val scratch = ByteArray(PHYSICAL_CHUNK)

    /** Привязать физический ввод перед очередным read() экстрактора. */
    fun bind(input: ExtractorInput) {
        if (input === source && !invalidated) return
        if (!invalidated && source != null && input.position == sourcePosition) {
            // Тот же поток, просто загрузчик переоткрыл источник с того места, где мы остановились.
            source = input
            return
        }
        source = input
        sourcePosition = input.position
        position = toLogical(sourcePosition)
        bufferStart = 0
        bufferEnd = 0
        peekOffset = 0
        invalidated = false
    }

    /** Следующий read() придёт с новым вводом (seek) — буфер недействителен. */
    fun invalidate() {
        invalidated = true
    }

    fun toLogical(physical: Long): Long {
        val rel = physical - phase
        if (rel <= 0) return 0
        val packet = rel / M2TS_PACKET_SIZE
        val offset = rel % M2TS_PACKET_SIZE
        return if (offset < TS_PACKET_SIZE) packet * TS_PACKET_SIZE + offset else (packet + 1) * TS_PACKET_SIZE
    }

    fun toPhysical(logical: Long): Long {
        if (logical <= 0) return phase.toLong()
        return phase + (logical / TS_PACKET_SIZE) * M2TS_PACKET_SIZE + logical % TS_PACKET_SIZE
    }

    private fun logicalLength(physicalLength: Long): Long {
        if (physicalLength == C.LENGTH_UNSET.toLong()) return C.LENGTH_UNSET.toLong()
        val rel = physicalLength - phase
        if (rel <= 0) return 0
        return (rel / M2TS_PACKET_SIZE) * TS_PACKET_SIZE + min(rel % M2TS_PACKET_SIZE, TS_PACKET_SIZE.toLong())
    }

    private val available get() = bufferEnd - bufferStart

    /** Дочитать физику, пока в буфере не станет [required] логических байт. false — конец ввода. */
    private fun fill(required: Int): Boolean {
        val src = checkNotNull(source)
        while (available < required) {
            val read = src.read(scratch, 0, scratch.size)
            if (read == C.RESULT_END_OF_INPUT) return false
            ensureCapacity(read)
            var i = 0
            while (i < read) {
                if (sourcePosition < phase) {
                    val skip = min((phase - sourcePosition).toInt(), read - i)
                    i += skip
                    sourcePosition += skip
                    continue
                }
                val inPacket = ((sourcePosition - phase) % M2TS_PACKET_SIZE).toInt()
                if (inPacket < TS_PACKET_SIZE) {
                    val take = min(TS_PACKET_SIZE - inPacket, read - i)
                    System.arraycopy(scratch, i, buffer, bufferEnd, take)
                    bufferEnd += take
                    i += take
                    sourcePosition += take
                } else {
                    val skip = min(M2TS_PACKET_SIZE - inPacket, read - i)
                    i += skip
                    sourcePosition += skip
                }
            }
        }
        return true
    }

    private fun ensureCapacity(extra: Int) {
        if (bufferEnd + extra <= buffer.size) return
        val live = available
        if (live + extra <= buffer.size && bufferStart > 0) {
            System.arraycopy(buffer, bufferStart, buffer, 0, live)
        } else {
            val grown = ByteArray(max(buffer.size * 2, live + extra))
            System.arraycopy(buffer, bufferStart, grown, 0, live)
            buffer = grown
        }
        bufferStart = 0
        bufferEnd = live
    }

    private fun consume(length: Int) {
        bufferStart += length
        position += length
        peekOffset = max(0, peekOffset - length)
        if (bufferStart == bufferEnd) {
            bufferStart = 0
            bufferEnd = 0
        }
    }

    // ExtractorInput.

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (available == 0 && !fill(1)) return C.RESULT_END_OF_INPUT
        val n = min(length, available)
        System.arraycopy(buffer, bufferStart, target, offset, n)
        consume(n)
        return n
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (!fillFully(length, allowEndOfInput, consumedBefore = 0)) return false
        System.arraycopy(buffer, bufferStart, target, offset, length)
        consume(length)
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        if (length == 0) return 0
        if (available == 0 && !fill(1)) return C.RESULT_END_OF_INPUT
        val n = min(length, available)
        consume(n)
        return n
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        if (!fillFully(length, allowEndOfInput, consumedBefore = 0)) return false
        consume(length)
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (available - peekOffset <= 0 && !fill(peekOffset + 1)) return C.RESULT_END_OF_INPUT
        val n = min(length, available - peekOffset)
        System.arraycopy(buffer, bufferStart + peekOffset, target, offset, n)
        peekOffset += n
        return n
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (!advancePeekPosition(length, allowEndOfInput)) return false
        System.arraycopy(buffer, bufferStart + peekOffset - length, target, offset, length)
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        if (!fillFully(peekOffset + length, allowEndOfInput, consumedBefore = peekOffset)) return false
        peekOffset += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
        peekOffset = 0
    }

    override fun getPeekPosition(): Long = position + peekOffset

    override fun getPosition(): Long = position

    override fun getLength(): Long = logicalLength(source?.length ?: C.LENGTH_UNSET.toLong())

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        invalidated = true
        checkNotNull(source).setRetryPosition(toPhysical(position), e)
    }

    /**
     * Семантика DefaultExtractorInput: если за [consumedBefore] нет ни байта и allowEndOfInput — false,
     * если данные оборвались на середине — EOFException.
     */
    private fun fillFully(total: Int, allowEndOfInput: Boolean, consumedBefore: Int): Boolean {
        if (fill(total)) return true
        if (allowEndOfInput && available <= consumedBefore) return false
        throw EOFException()
    }

    companion object {
        const val TS_PACKET_SIZE = 188
        const val M2TS_PACKET_SIZE = 192
        private const val INITIAL_BUFFER_SIZE = 256 * 1024
        private const val PHYSICAL_CHUNK = M2TS_PACKET_SIZE * 64
    }
}
