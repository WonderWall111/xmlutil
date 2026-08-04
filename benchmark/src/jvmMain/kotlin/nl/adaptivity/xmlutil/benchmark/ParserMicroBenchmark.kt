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

package nl.adaptivity.xmlutil.benchmark

import nl.adaptivity.xmlutil.core.KtXmlReader
import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlinx.benchmark.*
import nl.adaptivity.xmlutil.EventType
import org.openjdk.jmh.annotations.Fork
import java.io.ByteArrayInputStream

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
open class ParserMicroBenchmark {

    @Benchmark
    fun parseSmallXml(
        state: SmallXmlState,
        bh: Blackhole
    ) {
        KtXmlReader(StringReader(state.xml)).use { reader ->
            consumeReader(reader, bh)
        }
    }

    @Benchmark
    fun parseSmallXmlInputStream(
        state: SmallXmlBytesState,
        bh: Blackhole
    ) {
        KtXmlReader(ByteArrayInputStream(state.bytes)).use { reader ->
            consumeReader(reader, bh)
        }
    }

    private fun consumeReader(
        reader: KtXmlReader,
        bh: Blackhole
    ) {
        while (reader.hasNext()) {
            val event = reader.next()
            bh.consume(event)

            when (event) {
                EventType.START_ELEMENT,
                EventType.END_ELEMENT -> {
                    bh.consume(reader.localName)
                    bh.consume(reader.prefix)
                    bh.consume(reader.namespaceURI)
                }

                EventType.TEXT -> {
                    bh.consume(reader.text)
                }

                else -> Unit
            }
        }
    }

    @Benchmark
    fun parseRepeatedTags(
        state: RepeatedTagsState,
        bh: Blackhole
    ) {
        KtXmlReader(StringReader(state.xml)).use {
            consumeReader(it, bh)
        }
    }

    @Benchmark
    fun parseRepeatedTagsInputStream(
        state: RepeatedTagsBytesState,
        bh: Blackhole
    ) {
        KtXmlReader(ByteArrayInputStream(state.bytes)).use {
            consumeReader(it, bh)
        }
    }

    @Benchmark
    fun parseLargeText(state: LargeTextState, bh: Blackhole) {
        KtXmlReader(StringReader(state.xml)).use { reader ->
            while (reader.hasNext()) {
                val event = reader.next()
                bh.consume(event)

                if (event == EventType.TEXT) {
                    bh.consume(reader.text)
                }
            }
        }
    }

    @Benchmark
    fun parseAttributes(state: AttributeState, bh: Blackhole) {
        KtXmlReader(StringReader(state.xml)).use { reader ->
            while (reader.hasNext()) {
                val event = reader.next()
                bh.consume(event)

                if (event == EventType.START_ELEMENT) {
                    for (i in 0 until reader.attributeCount) {
                        bh.consume(reader.getAttributeValue(i))
                    }
                }
            }
        }
    }

    @Benchmark
    fun parseNamespaces(state: NamespaceState, bh: Blackhole) {
        KtXmlReader(StringReader(state.xml)).use { reader ->
            while (reader.hasNext()) {
                val event = reader.next()
                bh.consume(event)

                if (event == EventType.START_ELEMENT) {
                    bh.consume(reader.localName)
                    bh.consume(reader.prefix)
                    bh.consume(reader.namespaceURI)
                }
            }
        }
    }
}
