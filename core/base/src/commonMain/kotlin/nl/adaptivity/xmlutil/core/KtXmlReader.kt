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
        if (output.length > outputBuf.size) growOutputBuf(output.length)

        var i = 0
        for (c in output) {
            outputBuf[i++] = c
        }
        outputBufRight = output.length
    }

    override fun pushChar(c: Char) {
        val newPos = outputBufRight

        // +1 for surrogates; if we don't have enough space in
        if (newPos >= outputBuf.size) growOutputBuf()

        outputBuf[outputBufRight++] = c
    }

    /** Does never read more than needed  */
    override fun peek(): Int {
        return inputBuffer.peek()
    }

    override fun read(): Int {
        return inputBuffer.read()
    }

    private fun growOutputBuf(minNeeded: Int = outputBufRight) {
        val newSize = maxOf(outputBuf.size * 2, (minNeeded * 5) / 4)
        outputBuf = outputBuf.copyOf(newSize)
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

        return buildString {
            append(et.name)
            append(' ')
            when {
                et == START_ELEMENT || et == END_ELEMENT -> {
                    if (isSelfClosing) append("(empty) ")

                    append('<')
                    if (et == END_ELEMENT) append('/')

                    if (elementStack[depth - 1].prefix != null) append("{$namespaceURI}$prefix:")
                    append(name)

                    for (x in 0 until attributeCount) {
                        append(' ')
                        val a = attribute(x)
                        if (a.namespace != null) {
                            append('{').append(a.namespace).append('}').append(a.prefix).append(':')
                        }
                        append("${a.localName}='${a.value}'")
                    }

                    append('>')
                }

                et == IGNORABLE_WHITESPACE -> {}

                et != TEXT -> append(text)

                _isWhitespace -> append(
                    "(whitespace)"
                )

                else -> { // nonwhitespace text
                    var textCpy = text
                    if (textCpy.length > 16) textCpy = textCpy.take(16) + "..."
                    append(textCpy)
                }
            }
            if (inputBuffer.offset >= 0) {
                append(inputBuffer.locationInfo).append (" in ")
            }
            append(reader.toString())
        }
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
