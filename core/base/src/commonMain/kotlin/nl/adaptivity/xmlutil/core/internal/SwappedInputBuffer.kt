/*
 * Copyright (c) 2026.
 *
 * This file is part of xmlutil.
 *
 * This file is licenced to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance
 * with the License.  You should have  received a copy of the license
 * with the source distribution. Alternatively, you may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package nl.adaptivity.xmlutil.core.internal

import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.InputBuffer
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.core.impl.multiplatform.assert
import nl.adaptivity.xmlutil.core.impl.multiplatform.ifAssertions


private const val BUF_SIZE = 4096

@XmlUtilInternal
public class SwappedInputBuffer(public val reader: Reader): InputBuffer {

    /** Current position in the buffer */
    internal var srcBufPos: Int = 0

    /** Combined count of characters in the buffer */
    internal var srcBufCount: Int = 0

    /** Current buffer to parse from */
    internal var bufLeft = CharArray(BUF_SIZE)

    /** Next pending buffer */
    internal var bufRight: CharArray// = CharArray(BUF_SIZE)

    /**
     * Rather than update offset on each character read, we only update it on buffer swap.
     */
    private var offsetBase = 0

    override val offset: Int
        get() = offsetBase + srcBufPos

    override var line: Int = 1

    private var lastColumnStart: Int = 0
        set

    override val column: Int get() = offset - lastColumnStart + 1 // first char is 1, not 0

    init { // Read the first buffers on creation, rather than delayed
        var cnt = readUntilFullOrEOF(bufLeft)
        require(cnt >= 0) { "Trying to parse an empty file (that is not valid XML)" }
        if (cnt < BUF_SIZE) {
            bufRight = CharArray(0)
            srcBufCount = cnt
        } else {
            val newRight = CharArray(BUF_SIZE)
            bufRight = newRight
            cnt = readUntilFullOrEOF(newRight).coerceAtLeast(0) // in case the EOF is exactly at the boundary
            srcBufCount = BUF_SIZE + cnt
        }

        if (bufLeft[0].code == 0x0feff) {
            srcBufPos = 1 /* drop BOM */
            lastColumnStart = 1
        }
    }

    private fun swapInputBuffer() {
        if (copySequenceStart >= 0) { // make the copy sequence still work
            val b = copyBuilder ?: StringBuilder(16).also { copyBuilder = it }
            val p = srcBufPos
            if (p<= BUF_SIZE) {
                b.appendRange(bufLeft, copySequenceStart, p)
            } else {
                b.appendRange(bufLeft, copySequenceStart, BUF_SIZE)
                b.appendRange(bufRight, 0, (p - BUF_SIZE))
            }
            copySequenceStart = 0
        }
        val rightBufCount = srcBufCount - BUF_SIZE
        if (rightBufCount < 0) error("End of stream while swapping inputs")

        val oldLeft = bufLeft
        bufLeft = bufRight
        bufRight = oldLeft

        srcBufPos -= BUF_SIZE
        offsetBase += BUF_SIZE

        if (rightBufCount >= BUF_SIZE) {
            val newRead = readUntilFullOrEOF(bufRight)
            srcBufCount = when {
                newRead < 0 -> rightBufCount
                else -> rightBufCount + newRead
            }
        } else {
            srcBufCount = rightBufCount
        }
    }

    private var copySequenceStart = -1
    private var copyBuilder: StringBuilder? = null

    override val copySequenceState: State
        get() = when {
            copySequenceStart >= 0 -> State.ACTIVE
            copySequenceStart == -1 -> State.INACTIVE
            else -> State.PAUSED
        }

    override fun startCopySequence() {
/*
        ifAssertions {
            assert(copySequenceStart < 0 && copyBuilder == null) { "Copy sequence already started" }
        }
*/
        copySequenceStart = srcBufPos
    }

    override fun flushCopySequence() {
        if (copySequenceStart >= 0) {
            val b = copyBuilder ?: StringBuilder(offset - copySequenceStart).also { copyBuilder = it }
            b.appendRange(bufLeft, copySequenceStart, srcBufPos)
            copySequenceStart = srcBufPos
        }
    }

    override fun pauseCopySequence() {
        check(copySequenceStart>=0) { "Copy sequence not active (either not started or already suspended)" }
        val b = copyBuilder ?: StringBuilder(offset - copySequenceStart).also { copyBuilder = it }
        b.appendRange(bufLeft, copySequenceStart, srcBufPos)
        copySequenceStart = -2 // mark as paused
    }

    override fun resumeCopySequence() {
        check(copySequenceStart < -1 && copyBuilder != null) { "Copy sequence is not paused" }
        copySequenceStart = srcBufPos
    }

    override fun finalizeCopySequence(): String {
        val b = copyBuilder
        val start = copySequenceStart
        copySequenceStart = -1
        copyBuilder = null

        when {
            b == null && (start < 0) -> error("No copy sequence started")

            b == null -> return bufLeft.concatToString(start, srcBufPos)

            start >= 0 ->
                return b.appendRange(bufLeft, start, srcBufPos).toString()

            else -> return b.toString()
        }
    }

    override fun readToCopyBuffer() {
        if (read() < 0) error("End of stream while adding character to copy buffer")
    }

    /**
     * Helper function that ensures a copy builder. It also ensures that the pending writes are
     * added to ensure there is no reordering.
     */
    private fun ensureCopyBuilder(sizeHint: Int = 16): StringBuilder {
        val b = copyBuilder ?: StringBuilder(sizeHint).also { copyBuilder = it }

        if (copySequenceStart >= 0 && srcBufPos > copySequenceStart) { // active. Needs temporary pause
            b.appendRange(bufLeft, copySequenceStart, srcBufPos)
            copySequenceStart = srcBufPos
        }
        return b
    }

    override fun addToCopySequence(char: Char) {
        this.ifAssertions {
            assert(copySequenceState != State.INACTIVE) {
                "Copy sequence not active (either not started or suspended)"
            }
        }

        val b = ensureCopyBuilder()

        b.append(char)
    }

    override fun addToCopySequence(seq: CharSequence) {
        this.ifAssertions {
            assert(copySequenceStart >= 0 || copyBuilder != null) {
                "Copy sequence not active (either not started or suspended)"
            }
        }

        val b = ensureCopyBuilder()

        b.append(seq)
    }

    override fun appendSubRangeToSequence(start: Int, end: Int) {
        val bufStart = start - offsetBase
        val bufEnd = end - offsetBase
        if (copySequenceStart == bufEnd && srcBufPos == bufEnd) {
            copySequenceStart = bufStart
            return
        }

        val builder = ensureCopyBuilder(end - start)

        builder.apply {
            if (copySequenceStart >=0) { // this is guaranteed to be only in the left buffer
                appendRange(bufLeft, copySequenceStart, srcBufPos)
            }
            when {
                bufStart >= BUF_SIZE ->
                    appendRange(bufRight, bufStart - BUF_SIZE, bufEnd - BUF_SIZE)

                bufEnd <= BUF_SIZE ->
                    appendRange(bufLeft, bufStart, bufEnd)

                else -> {
                    appendRange(bufLeft, bufStart, BUF_SIZE)
                    appendRange(bufRight, 0, (bufEnd - BUF_SIZE))
                }
            }
        }





        super.appendSubRangeToSequence(start, end)
    }

    override fun readSubRange(start: Int, end: Int): CharSequence {
        val bufStart = start - offsetBase
        val bufEnd = end - offsetBase
        if (bufEnd >= srcBufCount) error("End of file in subrange")
        return buildString {
            when {
                bufStart >= BUF_SIZE ->
                    appendRange(bufRight, bufStart - BUF_SIZE, bufEnd - BUF_SIZE)

                bufEnd <= BUF_SIZE ->
                    appendRange(bufLeft, bufStart, bufEnd)

                else -> {
                    appendRange(bufLeft, bufStart, BUF_SIZE)
                    appendRange(bufRight, 0, (bufEnd - BUF_SIZE))
                }
            }
        }
    }

    /** Try to read the next character without increasing the position  */
    override fun peek(): Int {
        return peekCommon(srcBufPos)
    }

    override fun peek(offset: Int): Int {
        return peekCommon(srcBufPos + offset)
    }

    private fun peekCommon(bufPos: Int): Int {
        // end of buffer. This implies bufPos < 2 * BUF_SIZE
        if (bufPos >= srcBufCount) return -1
        val c = when {
            bufPos >= BUF_SIZE -> bufRight[bufPos - BUF_SIZE]
            else -> bufLeft[bufPos]
        }

        return when (c) {
            '\r' -> '\n'.code
            else -> c.code
        }
    }

    /**
     * This implementation matches, but also assumes that the expected sequence is not such large
     * as to not require range checks for the buffers (only end of file).
     */
    override fun peek(expected: CharSequence): Boolean {
        return peekCommon(srcBufPos, expected)
    }

    /**
     * This implementation matches, but also assumes that the expected sequence is not such large
     * as to not require range checks for the buffers (only end of file).
     */
    override fun peek(offset: Int, expected: CharSequence): Boolean {
        return peekCommon(srcBufPos + offset, expected)
    }

    private fun peekCommon(bufStart: Int, expected: CharSequence): Boolean {
        val l = expected.length
        if (bufStart + l > srcBufCount) return false // must be end of file
        when {
            bufStart + l <= BUF_SIZE -> { // most common
                val start = bufStart
                return (0..<l).all { i -> expected[i] == bufLeft[start + i] }
            }

            bufStart < BUF_SIZE -> { // overlap, a bit more likely than the last option
                val split = l - (BUF_SIZE - bufStart)
                return (0..<split).all { i -> expected[i] == bufLeft[bufStart + i] } &&
                        (split..<l).all { i -> expected[i] == bufRight[i - split] }
            }

            else -> { // strings only in the right buffer
                val start = bufStart - BUF_SIZE
                return (0..<l).all { i -> expected[i] == bufRight[start + i] }
            }
        }
    }

    override fun skip(count: Int) {
        val newPos = srcBufPos + count
        // allow skipping just past the last character.
        if (newPos > srcBufCount) error("End of file while skipping")
        srcBufPos = newPos
        if (newPos >= BUF_SIZE) swapInputBuffer()
    }

    override fun markPeekedAsRead() {
        fun handleLineEnd(complement: Int, oldPos: Int) {
            if (peek(1) == complement) {
                pauseCopySequence()
                addToCopySequence('\n')
                srcBufPos = oldPos + 2
                resumeCopySequence()
            } else {
                if (copySequenceState == State.ACTIVE && complement == '\n'.code) {
                    pauseCopySequence()
                    addToCopySequence('\n')
                    srcBufPos = oldPos + 1
                    resumeCopySequence()
                } else {
                    srcBufPos = oldPos + 1
                }
            }

            line += 1
            lastColumnStart = offset
        }

        val oldPos = srcBufPos
        val peeked = if (oldPos < BUF_SIZE) bufLeft[oldPos] else bufRight[oldPos - BUF_SIZE]
        when (peeked) {
            '\r' -> handleLineEnd('\n'.code, oldPos)
            '\n' -> handleLineEnd('\r'.code, oldPos)
            else -> srcBufPos = oldPos + 1
        }
        if (srcBufPos >= BUF_SIZE) swapInputBuffer()
    }

    /** Does never read more than needed  */
    override fun read(): Int {
        fun handleLineEnd(complement: Int, oldPos: Int) {
            val inc = if(peek(2) == complement) 2 else 1
            val newPos = oldPos + inc
            if (copySequenceState == State.ACTIVE && (inc == 2 || complement == '\n'.code)) {
                pauseCopySequence()
                addToCopySequence('\n')
                srcBufPos = newPos
                resumeCopySequence()
            } else {
                srcBufPos = newPos
            }
            lastColumnStart = offsetBase + newPos
            line += 1
        }

        // In this case we *may* need the right buffer, otherwise not
        // optimize this implementation for the "happy" path
        var oldPos = srcBufPos
        if (oldPos >= srcBufCount) return -1

        if (oldPos >= BUF_SIZE) {
            // swap if needed. Note that peek will work
            // correctly and we can get a little further for line endings.
            swapInputBuffer()
            oldPos = srcBufPos // need to update oldPos
        }

        when (val char = bufLeft[oldPos]) {
            '\r' -> { // as \r is always transformed to \n, this requires a stringBuilder.
                handleLineEnd('\n'.code, oldPos)
                return '\n'.code
            }

            '\n' -> {
                handleLineEnd('\r'.code, oldPos)
                return '\n'.code
            }

            else -> {
                srcBufPos = oldPos + 1
                return char.code
            }
        }
    }


    private fun readUntilFullOrEOF(buffer: CharArray): Int {
        val bufSize = buffer.size
        var totalRead: Int = reader.read(buffer, 0, bufSize)
        if (totalRead < 0) return -1
        while (totalRead < bufSize) {
            val lastRead = reader.read(buffer, totalRead, bufSize - totalRead)
            if (lastRead < 0) return totalRead
            totalRead += lastRead
        }
        return totalRead
    }

    override fun toString(): String {
        return buildString {
            append("SwappedInputBuffer(")
            append("Next = '")
                .append(bufLeft.concatToString(srcBufPos, (srcBufPos + 10).coerceAtMost(srcBufCount).coerceAtMost(BUF_SIZE)))
                .append("', output buffer = ")
            val b = copyBuilder



            if (copySequenceStart < 0 && b == null) {
                append("null)")
            } else {
                append('\'')
                if (b!=null) append(b)
                if (copySequenceStart>=0) appendRange(bufLeft, copySequenceStart, srcBufPos)
                append("')")
            }
        }
    }

    private typealias State = InputBuffer.State
}
