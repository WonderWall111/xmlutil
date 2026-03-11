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
import nl.adaptivity.xmlutil.core.impl.DefaultEntityMap
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.core.impl.multiplatform.assert
import nl.adaptivity.xmlutil.core.impl.multiplatform.ifAssertions
import nl.adaptivity.xmlutil.core.internal.AbstractKtXmlReader
import nl.adaptivity.xmlutil.core.internal.SwappedInputBuffer
import kotlin.jvm.JvmStatic

private const val BUF_SIZE = 4096
private const val ALT_BUF_SIZE = 512
private const val outputBufLeft = 0

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

    override fun resolveEntity(entityName: CharSequence): String? {
        return DefaultEntityMap[entityName.toString()]
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
