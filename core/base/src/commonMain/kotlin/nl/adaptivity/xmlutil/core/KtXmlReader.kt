/*
 * Copyright (c) 2024-2026.
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

@file:MustUseReturnValues

package nl.adaptivity.xmlutil.core

import nl.adaptivity.xmlutil.EventType.*
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.impl.DefaultEntityMap
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.core.impl.multiplatform.assert
import nl.adaptivity.xmlutil.core.impl.multiplatform.ifAssertions
import nl.adaptivity.xmlutil.core.internal.AbstractKtXmlReader
import nl.adaptivity.xmlutil.core.internal.isNameChar11
import nl.adaptivity.xmlutil.core.internal.isNameStartChar
import nl.adaptivity.xmlutil.isXmlWhitespace
import kotlin.jvm.JvmStatic

private const val BUF_SIZE = 4096
private const val ALT_BUF_SIZE = 512
private const val outputBufLeft = 0

@XmlUtilInternal
public interface InputBuffer {
    public val offset: Int

    public val line: Int

    public val lastColumnStart: Int

    public val column: Int get() = offset - lastColumnStart


    /**
     * Mark the start or resumption of a sequence that will be copied to string later. By default
     * this will just store the start position. It however also triggers handling of special cases,
     * that may trigger the use of a StringBuilder to store the sequence:
     *  - A line ending involving a '\r' (must be exposed as '\n')
     *  - Buffer swaps
     */
    public fun startOrResumeCopySequence()

    /**
     * Finish/finalise a copy sequence. This means it cannot be appended to anymore
     */
    public fun finalizeCopySequence(): CharSequence

    /**
     * Pause a copy sequence. This means that reading will not add further tokens to the sequence.
     */
    public fun pauseCopySequence()

    /**
     * Read tokens into the sequence up to the expected delimiters. The delimiters will
     * also be consumed.
     *
     * If an end of stream is encountered this function should throw an exception.
     *
     * @param delimiter The expected delimiter.
     * @param pauseOnDelimiter Pause the sequence on observation of the delimiter.
     * @param consumeDelimiter If true, the delimiter will be consumed.
     */
    public fun addDelimitedToCopySequence(delimiter: String, pauseOnDelimiter: Boolean = true, consumeDelimiter: Boolean = true)

    /** Try to read the next character without increasing the position  */
    public fun peek(): Int

    /**
     * Determine whether the following characters match the expected character sequence)
     */
    public fun peek(expected: CharSequence): Boolean

    /** Does never read more than needed  */
    public fun read(): Int

    public fun readChar(): Char {
        val c = read()
        if (c < 0) error("Unexpected EOF")
        return c.toChar()
    }

    /**
     * Read the current character to the copy buffer.
     */
    public fun readToCopyBuffer()
}

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

    override var line: Int = 0

    override var lastColumnStart: Int = 0
        set

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

    /**
     * Mark the start of a sequence that will be copied to string later. By default this will
     * just store the start position. It however also triggers handling of special cases, that
     * may trigger the use of a StringBuilder to store the sequence:
     *  - A line ending involving a '\r' (must be exposed as '\n')
     *  - Buffer swaps
     */
    override fun startOrResumeCopySequence() {
        check(copySequenceStart < 0) { "Copy sequence already started" }
        copySequenceStart = srcBufPos
    }

    override fun pauseCopySequence() {
        check(copySequenceStart>=0) { "Copy sequence not active (either not started or already suspended)" }
        val b = copyBuilder ?: StringBuilder(offset - copySequenceStart).also { copyBuilder = it }
        b.appendRange(bufLeft, copySequenceStart, srcBufPos)
        copySequenceStart = -2 // mark as paused
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

    override fun addDelimitedToCopySequence(
        delimiter: String,
        pauseOnDelimiter: Boolean,
        consumeDelimiter: Boolean
    ) {
        require(copySequenceStart>=0) { "Copy sequence not active (either not started or suspended)" }
        while (true) { // loop over multiple subsequent buffers.
            if (peek(delimiter)) {
                if (pauseOnDelimiter) pauseCopySequence()
                if (consumeDelimiter) srcBufPos += delimiter.length // this should work even if we cross the buffer size
                return
            } else {
                readToCopyBuffer()
            }
        }
    }

    /** Try to read the next character without increasing the position  */
    override fun peek(): Int {
        // In this case we *may* need the right buffer, otherwise not
        // optimize this implementation for the "happy" path
        val current = srcBufPos
        if (current >= srcBufCount) return -1
        val c = when {
            current >= BUF_SIZE -> bufRight[current - BUF_SIZE]
            else -> bufLeft[current]
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
        val l = expected.length
        if (srcBufPos + l >= srcBufCount) return false // must be end of file
        when {
            srcBufPos + l <=BUF_SIZE -> { // most common
                val start = srcBufPos
                return (0..<l).all { i -> expected[i] == bufLeft[start + i] }
            }

            srcBufPos < BUF_SIZE -> { // overlap, a bit more likely than the last option
                val split = l - (BUF_SIZE - srcBufPos)
                val start = srcBufPos
                return (0..<split).all { i -> expected[i] == bufLeft[start + i] } &&
                        (split..<l).all { i -> expected[i] == bufRight[i - split] }
            }

            else -> { // strings only in the right buffer
                val start = srcBufPos - BUF_SIZE
                return (0..<l).all { i -> expected[i] == bufRight[start + i] }
            }
        }
    }

    /** Does never read more than needed  */
    override fun read(): Int {
        // In this case we *may* need the right buffer, otherwise not
        // optimize this implementation for the "happy" path
        val initPos = srcBufPos
        if (initPos >= srcBufCount) return -1

        if (initPos >= BUF_SIZE) swapInputBuffer() // swap if needed. Note that peek will work
        // correctly

        val r = when (val char = bufLeft[srcBufPos++]) {
            '\r' -> { // as \r is always transformed to \n, this requires a stringBuilder.
                val n = srcBufPos + 1
                if (copySequenceStart>=0) {
                    (copyBuilder ?: StringBuilder(16).also { copyBuilder = it })
                        .appendRange(bufLeft, copySequenceStart, srcBufPos)
                        .append('\n')
                    copySequenceStart = n
                }
                if (peek() == '\n'.code) {
                    srcBufPos = n
                }

                line += 1
                lastColumnStart = offset
                '\n'
            }

            '\n' -> {
                if (peek() == '\r'.code) {
                    val n = srcBufPos + 1
                    if (copySequenceStart>=0) {
                        (copyBuilder ?: StringBuilder(16).also { copyBuilder = it })
                            .appendRange(bufLeft, copySequenceStart, srcBufPos)
                        copySequenceStart = n
                    }
                    srcBufPos = n
                }

                line += 1
                lastColumnStart = offset
                '\n'
            }

            else -> char
        }
        return r.code
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

}

@ExperimentalXmlUtilApi
/**
 * @param reader Reader for the input
 * @param encoding The encoding to record, note this doesn't impact the actual parsing (that is handled in the reader)
 * @param relaxed If `true` ignore various syntax and namespace errors
 * @param expandEntities true if entities are expanded as text, rather than exposed as entities. Note that unresolved entities
 *              will cause an exception in expanding mode.
 */
public class KtXmlReader internal constructor(
    private val reader: Reader,
    encoding: String?,
    relaxed: Boolean = false,
    expandEntities: Boolean = false,
) : AbstractKtXmlReader(encoding, relaxed, expandEntities) {

    public constructor(reader: Reader, relaxed: Boolean = false) :
            this(reader, null, relaxed)

    public constructor(reader: Reader, expandEntities: Boolean, relaxed: Boolean = false) :
            this(reader, null, relaxed, expandEntities)

    override val inputBuffer: SwappedInputBuffer = SwappedInputBuffer(reader)

    private var srcBufPos: Int
        get() = inputBuffer.srcBufPos
        set(value) {
            inputBuffer.srcBufPos = value
        }

    private var srcBufCount: Int
        get() = inputBuffer.srcBufCount
        set(value) {
            inputBuffer.srcBufCount = value
        }

    //    private val srcBuf = CharArray(8192)
    private var bufLeft
        get() = inputBuffer.bufLeft
        set(value) {
            inputBuffer.bufLeft = value
        }

    private var bufRight: CharArray
        get() = inputBuffer.bufRight
        set(value) {
            inputBuffer.bufRight = value
        }

    /** Target buffer for storing incoming text (including aggregated resolved entities)  */
    private var outputBuf = CharArray(ALT_BUF_SIZE)

    /** Write position   */
    private var outputBufRight = 0

    override var line: Int
        get() = inputBuffer.line
        set(value) { inputBuffer.line = value }
    override var lastColumnStart: Int
        get() = inputBuffer.lastColumnStart
        set(value) { inputBuffer.lastColumnStart = value }
    override val offset: Int
        get() = inputBuffer.offset

    /* precondition: &lt;/ consumed */
    override fun parseEndTag() {
        if (depth == 0) {
            error("element stack empty")
            _eventType = COMMENT
            return
        }

        readAssert('<') // '<'
        readAssert('/') // '/'

        resetOutputBuffer()
        val spIdx = depth - 1
        val expectedPrefix = elementStack[spIdx].prefix //?: exception("Missing prefix")
        val expectedLocalName = elementStack[spIdx].localName ?: exception("Missing localname")
        val expectedLength = (expectedPrefix?.run { length + 1 } ?: 0) + expectedLocalName.length

        // fast path implementation that just verifies the tags
        // (rather than parsing them directly without that knowledge of expectation)
        if (expectedPrefix != null) {
            readAssert(expectedPrefix) { "Expected prefix '$expectedPrefix' for closing tag" }
            readAssert(':')
        }
        readAssert(expectedLocalName) { "Expect local part '$expectedLocalName' for closing tag" }
        skipWS()
        readAssert('>')
    }

    override fun get(): String {
        return outputBuf.concatToString(outputBufLeft, outputBufRight)
    }

    override fun popOutput() {
        ifAssertions {
            assert(outputBufRight>0) {
                "Pop before buffer"
            }
        }
        --outputBufRight
    }

    override fun resetOutputBuffer() {
        // Do not reset it for speed reasons
        // if (outputBuf.size != ALT_BUF_SIZE) {
        //     outputBuf = CharArray(ALT_BUF_SIZE)
        // }
        outputBufRight = 0
    }

    override fun setOutputBuffer(output: CharSequence) {
        // TODO should be replaced at some point
        outputBufRight = 0
        push(output)
    }

    private fun pushRange(buffer: CharArray, start: Int, endExcl: Int) {
        val count = endExcl - start
        val outRight = outputBufRight
        val minSizeNeeded = outRight + count
        if (minSizeNeeded >= outputBuf.size) {
            growOutputBuf(minSizeNeeded)
        }

        buffer.copyInto(outputBuf, outRight, start, endExcl)
        outputBufRight = outRight + count
    }

    override fun push(s: CharSequence) {
        val minSizeNeeded = outputBufRight + s.length
        if (minSizeNeeded > outputBuf.size) {
            growOutputBuf(minSizeNeeded)
        }

        for (c in s) {
            outputBuf[outputBufRight++] = c
        }
    }

    override fun pushChar(c: Char) {
        val newPos = outputBufRight

        // +1 for surrogates; if we don't have enough space in
        if (newPos >= outputBuf.size) growOutputBuf()

        outputBuf[outputBufRight++] = c
    }

    private fun pushCodePoint(c: Int) {
        if (c < 0) error("UNEXPECTED EOF")

        val newPos = outputBufRight

        if (newPos + 1 >= outputBuf.size) { // +1 for surrogates; if we don't have enough space in
            growOutputBuf()
        }

        if (c > 0xffff) { // This comparison works as surrogates are in the 0xd800-0xdfff range
            // write high Unicode value as surrogate pair
            val offset = c - 0x010000
            outputBuf[outputBufRight++] = ((offset ushr 10) + 0xd800).toChar() // high surrogate
            outputBuf[outputBufRight++] = ((offset and 0x3ff) + 0xdc00).toChar() // low surrogate
        } else {
            outputBuf[outputBufRight++] = c.toChar()
        }
    }

    /**
     * result: if the setName parameter is set,
     * the name of the entity is stored in "name"
     */
    override fun pushEntity(expandEntities: Boolean) {
        readAssert('&')
        val first = peek(0)

        when {
            first == '#'.code -> pushCharEntity()
            first < 0 -> error(UNEXPECTED_EOF)
            else -> pushRefEntity(expandEntities)
        }
    }

    private fun pushRefEntity(expandEntities: Boolean) {
        isUnresolvedEntity = false
        val first = read()
        val codeBuilder = StringBuilder(8)

        if (!isNameStartChar(first.toChar())) {
            error("Entity reference does not start with name char &${get()}${first.toChar()}")
            return
        }
        codeBuilder.append(first.toChar())

        while (true) {
            val c = peek(0)
            if (c == ';'.code) {
                readAssert(';')
                break
            }
            if (!isNameChar11(c.toChar())) {
                error("unterminated entity ref ($codeBuilder)")

                return
            }
            codeBuilder.append(read().toChar())
        }

        val code = codeBuilder.toString()//get(pos)
        if (_eventType == ENTITY_REF) {
            entityName = code
        }

        val result = DefaultEntityMap[code]
        isUnresolvedEntity = result == null
        when {
            result != null -> push(result)
            expandEntities -> exception("Unknown entity \"&$code;\" in entity expanding mode")
        }
    }

    private fun pushCharEntity() {
        readAssert('#') // #
        val codeBuilder = StringBuilder(8)

        var isHex = false

        when (val first = read()) {
            'x'.code -> isHex = true // hex char refs
            in '0'.code..'9'.code -> {
                codeBuilder.append(first.toChar())
            }

            else -> error("Unexpected start of numeric entity reference '&${first.toChar()}'")
        }

        while (true) {
            when (val c = peek(0)) {
                -1 -> error(UNEXPECTED_EOF)
                ';'.code -> {
                    readAssert(';')
                    break
                }

                in 'a'.code..'f'.code, // allow hex
                in 'A'.code..'F'.code, // allow hex
                in '0'.code..'9'.code -> codeBuilder.append(read().toChar())

                else -> {
                    error("Unexpected content in numeric entity reference: ${c.toChar()} (in $codeBuilder")
                    break
                }
            }
        }
        val code = codeBuilder.toString()//get(pos)

        if (_eventType == ENTITY_REF) entityName = code

        val cp = if (isHex) code.toInt(16) else code.toInt()
        pushCodePoint(cp)
        return
    }

    /**
     * General text push/parse algorithm.
     * Content:
     * '<': parse to any token (for nextToken ())
     * ']': CDATA section
     * Attributes:
     * '"': parse to quote
     * NO LONGER SUPPORTED - use pushTextWsDelim ' ': parse to whitespace or '>'
     */
    override fun pushText(delimiter: Char) {
        var bufCount = srcBufCount
        var innerLoopEnd = minOf(bufCount, BUF_SIZE)
        var curPos = srcBufPos

        // shortcircuit text not starting with whitespace
        if (curPos < innerLoopEnd && !isXmlWhitespace(bufLeft[curPos])) {
            return pushRegularText(delimiter, resolveEntities = expandEntities)
        }

        var left: Int = curPos
        var right: Int = -1
        var notFinished = true

        outer@ while (curPos < bufCount && notFinished) { // loop through all buffer iterations
            var continueInNonWSMode = false
            inner@ while (curPos < innerLoopEnd) {
                when (bufLeft[curPos]) {
                    '\r' -> {
                        // pushRange doesn't do normalization, so use push the preceding chars,
                        // then handle the CR separately
                        if (right > left + 1) pushRange(bufLeft, left, right)
                        right = -1
                        val peekChar = when (curPos + 1) {
                            bufCount ->
                                '\u0000'

                            BUF_SIZE ->
                                bufRight[0]

                            else -> bufLeft[curPos + 1]
                        }
                        if (peekChar != '\n') {
                            pushChar('\n')
                            incLine() // Increase positions here
                        }
                        left = curPos + 1
                        ++curPos
                    }

                    '\n' -> {
                        incLine()
                        ++curPos
                    }

                    ' ', '\t' -> {
                        incCol()
                        ++curPos
                    }

                    delimiter -> {
                        notFinished = false
                        right = curPos
                        break@inner // outer will actually give the result.
                    }

                    else -> {
                        continueInNonWSMode = true
                        right = curPos
                        break@inner
                    }
                }
            }

            if (curPos == innerLoopEnd) right = curPos

            if (right > left) {
                pushRange(bufLeft, left, right) // ws delimited is never WS
                right = -1
            }

            if (curPos == BUF_SIZE) { // swap the buffers
                srcBufPos = curPos
                swapInputBuffer()
                right = -1 //set it to -1 in all cases as at this point we probably parsed nothing
                curPos = srcBufPos
                bufCount = srcBufCount
                innerLoopEnd = minOf(bufCount, BUF_SIZE)
            }

            if (continueInNonWSMode) {
                srcBufPos = curPos
                return pushRegularText(delimiter, resolveEntities = expandEntities)
            }

            left = curPos
        }

        // We didn't return through pushNonWSText, so it is WS
        _isWhitespace = true
        srcBufPos = curPos
    }

    /**
     * Specialisation of pushText that does not recognize whitespace (thus able to be used at that point)
     * @param delimiter The "stopping" delimiter
     * @param resolveEntities Whether entities should be resolved directly (in attributes) or exposed as entity
     *                        references (content text if expandEntities is false).
     */
    override fun pushRegularText(delimiter: Char, resolveEntities: Boolean) {
        var bufCount = srcBufCount
        var innerLoopEnd = minOf(bufCount, BUF_SIZE)
        var curPos = srcBufPos

        var left: Int = curPos
        var right: Int = -1
        var notFinished = true

        outer@ while (curPos < bufCount && notFinished) { // loop through all buffer iterations
            inner@ while (curPos < innerLoopEnd) {
                when (bufLeft[curPos]) {
                    delimiter -> {
                        notFinished = false
                        right = curPos
                        break@inner // outer will actually give the result.
                    }

                    '\r' -> {
                        pushRange(bufLeft, left, curPos)

                        val nextIsCR = when (val next = curPos + 1) {
                            bufCount -> false // EOF
                            BUF_SIZE -> bufRight[0] == '\n' // EOB, look at right buffer
                            else -> bufLeft[next] == '\n'
                        }

                        if (nextIsCR) {
                            incLine(2)
                            curPos += 2
                        } else {
                            incLine()
                            curPos += 1
                        }
                        pushChar('\n')
                        right = -1
                        left = curPos
                    }

                    '\n' -> {
                        incLine()
                        ++curPos
                    }

                    '&' -> when {
                        !resolveEntities -> {
                            right = curPos
                            notFinished = false
                            break@inner
                        }

                        left == curPos -> { // start with entity
                            srcBufPos = curPos
                            pushEntity()
                            curPos = srcBufPos
                            left = curPos
                        }

                        else -> { // read all items before entity (then after it will hit the other case)
                            right = curPos
                            break@inner
                        }
                    }

                    else -> {
                        incCol()
                        ++curPos
                    }
                }
            }

            if (curPos == innerLoopEnd) {
                right = curPos
            }

            if (right > 0) {
                pushRange(bufLeft, left, right) // ws delimited is never WS
                right = -1
            }

            if (curPos >= BUF_SIZE) { // swap the buffers, use ge to allow for extra '\n' after '\r'
                srcBufPos = curPos
                swapInputBuffer()
                curPos = srcBufPos
                bufCount = srcBufCount
                innerLoopEnd = minOf(bufCount, BUF_SIZE)
            }
            left = curPos

        }
        _isWhitespace = false
        srcBufPos = curPos
    }

    /**
     * Specialisation of pushText that does not recognize whitespace (thus able to be used at that point)
     * @param delimiter The "stopping" delimiter
     */
    override fun pushAttributeValue(delimiter: Char) {
        var bufCount = srcBufCount
        var innerLoopEnd = minOf(bufCount, BUF_SIZE)
        var curPos = srcBufPos

        var left: Int = curPos
        var right: Int = -1
        var notFinished = true

        outer@ while (curPos < bufCount && notFinished) { // loop through all buffer iterations
            inner@ while (curPos < innerLoopEnd) {
                when (val c = bufLeft[curPos]) {
                    delimiter -> {
                        notFinished = false
                        right = curPos
                        break@inner // outer will actually give the result.
                    }

                    '\r', '\n', '\t' -> {
                        pushRange(bufLeft, left, curPos)

                        val nextIsCR = when {
                            c != '\r' -> false
                            else -> when (val next = curPos + 1) {
                                bufCount -> false // EOF
                                BUF_SIZE -> bufRight[0] == '\n' // EOB, look at right buffer
                                else -> bufLeft[next] == '\n'
                            }
                        }

                        if (nextIsCR) {
                            incLine(2)
                            curPos += 2
                        } else {
                            if (c != '\t') incLine()
                            curPos += 1
                        }
                        pushChar(' ')
                        right = -1
                        left = curPos
                    }


                    '&' -> when {
                        left == curPos -> { // start with entity
                            srcBufPos = curPos
                            pushEntity(expandEntities = true) // always expand attribute values
                            curPos = srcBufPos
                            left = curPos
                        }

                        else -> { // read all items before entity (then after it will hit the other case)
                            right = curPos
                            break@inner
                        }
                    }

                    else -> {
                        incCol()
                        ++curPos
                    }
                }
            }

            if (curPos == innerLoopEnd) {
                right = curPos
            }

            if (right > 0) {
                pushRange(bufLeft, left, right) // ws delimited is never WS
                right = -1
            }

            if (curPos >= BUF_SIZE) { // swap the buffers, use ge to allow for extra '\n' after '\r'
                srcBufPos = curPos
                swapInputBuffer()
                curPos = srcBufPos
                bufCount = srcBufCount
                innerLoopEnd = minOf(bufCount, BUF_SIZE)
            }
            left = curPos

        }
        _isWhitespace = false
        srcBufPos = curPos
    }

    /** Push attribute delimited by whitespace */
    override fun pushWSDelimAttrValue() {
        var bufCount = srcBufCount
        var leftEnd = minOf(bufCount, BUF_SIZE)
        var left: Int
        var right: Int
        var curPos = srcBufPos
        var notFinished = true

        outer@ while (curPos < bufCount && notFinished) { // loop through all buffer iterations
            left = curPos
            right = -1

            inner@ while (curPos < leftEnd) {
                when (bufLeft[curPos]) {
                    '\r' -> {
                        srcBufPos = curPos
                        if (peek() == '\n'.code) {
                            ++srcBufPos
                        }
                        right = curPos
                        curPos = srcBufPos
                        notFinished = false
                        break@inner
                    }

                    ' ', '\t', '\n', '>' -> {
                        right = curPos
                        ++curPos
                        notFinished = false
                        break@inner
                    }

                    '&' -> when (left) {
                        curPos -> { // start with entity
                            pushEntity()
                            curPos = srcBufPos
                        }

                        else -> { // read all items before entity (then after it will hit the other case)
                            right = curPos
                            break@inner
                        }
                    }

                    else -> ++curPos
                }
            }
            if (right > 0) {
                pushRange(bufLeft, left, right) // ws delimited is never WS
            }

            if (curPos == BUF_SIZE) { // swap the buffers
                srcBufPos = curPos
                swapInputBuffer()
                curPos = srcBufPos
                bufCount = srcBufCount
                leftEnd = minOf(bufCount, BUF_SIZE)
            }
        }
        srcBufPos = curPos
    }

    /** Does never read more than needed  */
    override fun peek(): Int {
        return inputBuffer.peek()
    }

    override fun read(): Int {
        return inputBuffer.read()
    }

    override fun readAndPush(): Char {
        val pos = srcBufPos
        if (pos >= srcBufCount) exception(UNEXPECTED_EOF)

        val nextSrcPos = pos + 1
        if (nextSrcPos >= BUF_SIZE) { // +1 to also account for CRLF across the boundary
            return readAcross().also(::pushChar).toChar() // use the slow path for this case
        }

        var outRight = outputBufRight
        if (outRight >= outputBuf.size) {
            growOutputBuf(outRight - outputBufLeft)
        }

        val bufLeft = bufLeft

        val result: Char
        when (val ch = bufLeft[pos]) {
            '\r' -> {
                srcBufPos = when {
                    nextSrcPos < srcBufCount && bufLeft[nextSrcPos] == '\n' -> {
                        incLine(2)
                        nextSrcPos + 1
                    }

                    else -> {
                        incLine()
                        nextSrcPos
                    }
                }

                outputBuf[outRight++] = '\n'
                result = '\n'
            }

            '\n' -> {
                srcBufPos = nextSrcPos
                incLine()
                outputBuf[outRight++] = '\n' // it is
                result = '\n'
            }

            else -> {
                incCol()
                srcBufPos = nextSrcPos
                outputBuf[outRight++] = ch
                result = ch
            }
        }
        outputBufRight = outRight
        return result
    }

    private fun growOutputBuf(minNeeded: Int = outputBufRight) {
        val newSize = maxOf(outputBuf.size * 2, (minNeeded * 5) / 4)
        outputBuf = outputBuf.copyOf(newSize)
    }

    private fun swapInputBuffer() {
        val oldLeft = bufLeft
        bufLeft = bufRight
        bufRight = oldLeft
        srcBufPos -= BUF_SIZE
        val rightBufCount = srcBufCount - BUF_SIZE
        if (rightBufCount >= BUF_SIZE) {
            val newRead = reader.readUntilFullOrEOF(bufRight)
            srcBufCount = when {
                newRead < 0 -> rightBufCount
                else -> rightBufCount + newRead
            }
        } else {
            srcBufCount = rightBufCount
        }
    }

    private fun readAcross(): Int {
        var pos = srcBufPos
        if (pos >= BUF_SIZE) {
            swapInputBuffer()
            pos -= BUF_SIZE
        }

        val next = pos + 1
        when (val ch = bufLeft[pos]) {
            '\u0000' -> { // should not happen at end of file (or really generally at all)
                srcBufPos = next
                return readAcross() // just recurse
            }

            '\r' -> {
                bufLeft[srcBufPos] = '\n'
                if (next < srcBufCount && getBuf(next) == '\n') {
                    setBuf(next, '\u0000')
                    srcBufPos = next + 1
                    incLine(2)
                } else {
                    srcBufPos = next
                    incLine()
                }
                return '\n'.code
            }

            '\n' -> {
                srcBufPos = next
                incLine()
                return '\n'.code
            }

            else -> {
                incCol()
                srcBufPos = next
                return ch.code
            }
        }
    }

    /** Does never read more than needed  */
    override fun peek(pos: Int): Int {
        // In this case we *may* need the right buffer, otherwise not
        // optimize this implementation for the "happy" path
        if (srcBufPos + (pos shl 2 + 1) >= BUF_SIZE) return peekAcross(pos)
        var current = srcBufPos
        var peekCount = pos

        while (current < srcBufCount) {
            var chr: Char = bufLeft[current]
            when (chr) {
                '\r' -> {
                    chr = '\n' // update the char
                    bufLeft[current] = '\n' // replace it with LF (\n)
                    if (bufLeft[current + 1] == '\r') {
                        // Note also as we are separated from the edge of the buffer setting this is valid even
                        // beyond the end of the file
                        bufLeft[current++] = '\u0000' // 0 is not a valid XML CHAR, so we can skip it
                    }
                }

                else -> ++current
            }
            if (peekCount-- == 0) return chr.code
        }
        return -1
    }

    /**
     * Pessimistic implementation of peek that allows checks across into the "right" buffer
     */
    private fun peekAcross(pos: Int): Int {
        var current = srcBufPos
        var peekCount = pos

        while (current < srcBufCount) {
            var chr: Char = getBuf(current)
            when (chr) {
                '\r' -> {
                    chr = '\n' // update the char
                    if (current + 1 < srcBufCount && getBuf(current + 1) == '\n') {
                        current += 2
                    } else {
                        ++current
                    }
                }

                else -> ++current
            }
            if (peekCount-- == 0) return chr.code
        }
        return -1
    }

    private fun getBuf(pos: Int): Char {
        val split = pos - BUF_SIZE
        return when {
            split < 0 -> bufLeft[pos]
            else -> bufRight[split]
        }
    }

    private fun setBuf(pos: Int, value: Char) {
        val split = pos - BUF_SIZE
        when {
            split < 0 -> bufLeft[pos] = value
            else -> bufRight[split] = value
        }
    }

    @Suppress("DuplicatedCode")
    override fun readName(): String {
        var left = srcBufPos

        var bufEnd: Int
        run {
            val cnt = srcBufCount
            if (BUF_SIZE < cnt) {
                if (left == BUF_SIZE) {
                    swapInputBuffer()
                    left = 0
                    bufEnd = minOf(BUF_SIZE, srcBufCount)
                } else {
                    bufEnd = BUF_SIZE
                }
            } else {
                if (left >= cnt) exception(UNEXPECTED_EOF)
                bufEnd = cnt
            }
        }

        var srcBuf = bufLeft

        if (!isNameStartChar(srcBuf[left])) error("name expected, found: $srcBuf[left]")

        var right = left + 1

        while (true) {
            if (right == bufEnd) {
                pushRange(srcBuf, left, right)
                if (bufEnd >= srcBufCount) error(UNEXPECTED_EOF)
                srcBufPos = right // this is not technically needed, but this should be infrequent anytime
                swapInputBuffer()
                bufEnd = minOf(BUF_SIZE, srcBufCount)
                if (bufEnd == 0) break // end of file
                left = 0
                right = 0
                srcBuf = bufLeft
            }
            when {
                isNameChar11(srcBuf[right]) -> right += 1
                else -> {
                    pushRange(srcBuf, left, right)
                    break
                }
            }
        }
        srcBufPos = right
        return get()
    }

    @Suppress("DuplicatedCode")
    override fun readCName() {
        var left = srcBufPos

        val cnt = srcBufCount

        var bufEnd: Int = when {
            BUF_SIZE >= cnt -> {
                if (left >= cnt) exception(UNEXPECTED_EOF)
                cnt
            }

            left == BUF_SIZE -> {
                swapInputBuffer()
                left = 0
                minOf(BUF_SIZE, srcBufCount)
            }

            else -> BUF_SIZE
        }

        var srcBuf = bufLeft

        srcBuf[left].let { c ->
            if (c == ':' || !isNameStartChar(c)) error("name expected, found: $c")
        }

        var right = left + 1

        var prefix: String? = null

        while (true) {
            if (right == bufEnd) {
                pushRange(srcBuf, left, right)
                if (bufEnd >= srcBufCount) error(UNEXPECTED_EOF)
                srcBufPos = right // this is not technically needed, but this should be infrequent anytime
                swapInputBuffer()
                bufEnd = minOf(BUF_SIZE, srcBufCount)
                if (bufEnd == 0) break // end of file
                left = 0
                right = 0
                srcBuf = bufLeft
            }
            when (val c = srcBuf[right]) {
                ':' -> if (PROCESS_NAMESPACES) {
                    pushRange(srcBuf, left, right)
                    right += 1
                    left = right
                    prefix = get()
                    resetOutputBuffer()
                } else {
                    right += 1
                }

                else -> when {
                    isNameChar11(c) -> right += 1
                    else -> {
                        pushRange(srcBuf, left, right)
                        break
                    }
                }
            }
        }
        srcBufPos = right
        readPrefix = prefix
        readLocalname = get()
    }


    private fun getPositionDescription(): String {
        val et = this._eventType ?: return ("<!--Parsing not started yet-->")

        val buf = StringBuilder(et.name)
        buf.append(' ')
        when {
            et == START_ELEMENT || et == END_ELEMENT -> {
                if (isSelfClosing) buf.append("(empty) ")
                buf.append('<')
                if (et == END_ELEMENT) buf.append('/')
                if (elementStack[depth - 1].prefix != null) buf.append("{$namespaceURI}$prefix:")
                buf.append(name)

                for (x in 0 until attributeCount) {
                    buf.append(' ')
                    val a = attribute(x)
                    if (a.namespace != null) {
                        buf.append('{').append(a.namespace).append('}').append(a.prefix).append(':')
                    }
                    buf.append("${a.localName}='${a.value}'")
                }

                buf.append('>')
            }

            et == IGNORABLE_WHITESPACE -> {}

            et != TEXT -> buf.append(text)

            _isWhitespace -> buf.append(
                "(whitespace)"
            )

            else -> { // nonwhitespace text
                var textCpy = text
                if (textCpy.length > 16) textCpy = textCpy.take(16) + "..."
                buf.append(textCpy)
            }
        }
        if (offset >= 0) {
            buf.append("@$line:$column [$offset] in ")
        }
        buf.append(reader.toString())
        return buf.toString()
    }

    override fun toString(): String {
        return "KtXmlReader [${getPositionDescription()}]"
    }

    private companion object {
        const val PROCESS_NAMESPACES = true


        @JvmStatic
        protected fun Reader.readUntilFullOrEOF(buffer: CharArray): Int {
            val bufSize = buffer.size
            var totalRead: Int = read(buffer, 0, bufSize)
            if (totalRead < 0) return -1
            while (totalRead < bufSize) {
                val lastRead = read(buffer, totalRead, bufSize - totalRead)
                if (lastRead < 0) return totalRead
                totalRead += lastRead
            }
            return totalRead
        }

    }


}
