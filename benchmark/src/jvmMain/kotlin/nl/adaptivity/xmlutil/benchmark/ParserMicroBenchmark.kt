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
import org.openjdk.jmh.annotations.*
import java.io.StringReader
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
open class ParserMicroBenchmark {

    private val smallXml = """
        <book>
            <title>XML Guide</title>
        </book>
    """.trimIndent()

    @Benchmark
    fun parseSmallXml() {
        val reader = KtXmlReader(StringReader(smallXml))

        while (reader.hasNext()) {
            reader.next()
        }
    }
    private val repeatedTags = buildString {
        append("<items>")
        repeat(100) {
            append("<item>value</item>")
        }
        append("</items>")
    }

    @Benchmark
    fun parseRepeatedTags() {
        val reader = KtXmlReader(StringReader(repeatedTags))

        while (reader.hasNext()) {
            reader.next()
        }
    }
    private val largeTextXml = """
    <description>${"x".repeat(5000)}</description>
""".trimIndent()

    @Benchmark
    fun parseLargeText() {
        val reader = KtXmlReader(StringReader(largeTextXml))

        while (reader.hasNext()) {
            reader.next()
        }
    }
}
