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

import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.core.CopySequenceMarker
import nl.adaptivity.xmlutil.core.InputBuffer

internal class InjectingInputBuffer(val base: InputBuffer): InputBuffer {

    private val stack = mutableListOf<Elem>()

    override val offset: Int
        get() = stack.lastOrNull()?.pos ?: base.offset

    override val line: Int
        get() = if (stack.isEmpty()) base.line else 1

    override val column: Int
        get() = stack.lastOrNull ()?.pos ?: base.column

    private var isCopySequencePaused: Boolean = false

    override fun startCopySequence() {
        base.startCopySequence()
        if (stack.isNotEmpty()) {
            context(CopySequenceMarker) { base.pauseCopySequence() }
        }
    }

    context(_: CopySequenceMarker)
    override fun pauseCopySequence() {
        isCopySequencePaused = true
        base.pauseCopySequence()
    }

    context(_: CopySequenceMarker)
    override fun resumeCopySequence() {
        isCopySequencePaused = false
        if (stack.isEmpty()) {
            context(CopySequenceMarker) { base.resumeCopySequence() }
        }
    }

    override fun finalizeCopySequence(): CharSequence {
        return base.finalizeCopySequence()
    }

    context(_: CopySequenceMarker)
    override fun addToCopySequence(char: Char) {
        base.addToCopySequence(char)
    }

    context(_: CopySequenceMarker)
    override fun addDelimitedToCopySequence(
        delimiter: String,
        pauseOnDelimiter: Boolean,
        consumeDelimiter: Boolean
    ) {
        when {
            stack.isEmpty() -> base.addDelimitedToCopySequence(delimiter, pauseOnDelimiter, consumeDelimiter)
            else -> super.addDelimitedToCopySequence(delimiter, pauseOnDelimiter, consumeDelimiter)
        }
    }

    override fun skip(count: Int) {
        for (i in 0 until count) check(read()>=0) { "Unexpected end of stream" }
    }

    context(_: CopySequenceMarker)
    override fun readToCopyBuffer() {
        val _ = readChar()
    }

    override fun readSubRange(start: Int, end: Int): CharSequence {
        return stack.lastOrNull()?.source?.subSequence(start, end) ?: base.readSubRange(start, end)
    }

    override fun peek(offset: Int): Int {
        val lastStack = stack.lastOrNull() ?: return base.peek(offset)
        if (lastStack.pos + offset < lastStack.source.length) {
            return lastStack.source[lastStack.pos + offset].code
        }

        var stackPos = stack.size - 2
        var remainingOffset = offset - (lastStack.source.length - lastStack.pos)

        while (stackPos >= 0 && remainingOffset > (stack[stackPos].let { s -> s.source.length - s.pos })) {
            val s = stack[stackPos]
            remainingOffset -= (s.source.length - s.pos)
            stackPos -= 1
        }
        if (stackPos >= 0) {
            val s = stack[stackPos]
            return s.source[s.pos + remainingOffset].code
        }

        return base.peek(remainingOffset)
    }

    override fun read(): Int {
        val lastStack = stack.lastOrNull() ?: return base.read()
        val pos = lastStack.pos
        val r = lastStack.source[pos].code
        if (! isCopySequencePaused && r>=0) {
            context(CopySequenceMarker) {
                base.addToCopySequence(r.toChar())
            }
        }

        val newPos = pos + 1
        when {
            newPos < lastStack.source.length -> lastStack.pos = newPos
            else -> stack.removeLast()
        }
        // pausing doesn't pause the underlying buffer if there is a stack, so it must
        if (isCopySequencePaused && stack.isEmpty()) {
            context(CopySequenceMarker) { base.pauseCopySequence() }
        }

        return r
    }

    private class Elem(var pos: Int, val entityName: String, val source: CharSequence, val entityLocationInfo: XmlReader.LocationInfo?)
}
