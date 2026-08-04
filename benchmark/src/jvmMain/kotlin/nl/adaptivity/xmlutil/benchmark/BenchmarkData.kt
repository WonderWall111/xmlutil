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

object BenchmarkData {
    const val REPEATED_TAG_COUNT = 5_000
    const val LARGE_TEXT_SIZE = 5_000
    const val ATTRIBUTE_COUNT = 1_000
    val smallXml = """
        <book>
            <title>XML Guide</title>
        </book>
    """.trimIndent()

    fun repeatedTags(count: Int): String = buildString {
        append("<items>")
        repeat(count) {
            append("<item>value</item>")
        }
        append("</items>")
    }

    fun largeText(size: Int): String =
        "<description>${"x".repeat(size)}</description>"

    fun attributeHeavy(count: Int): String = buildString {
        append("<items>")
        repeat(count) {
            append("""<item a="1" b="2" c="3" d="4"/>""")
        }
        append("</items>")
    }

    fun namespaceHeavy(count: Int): String = buildString {
        append("""<ns:items xmlns:ns="urn:test">""")
        repeat(count) {
            append("""<ns:item>value</ns:item>""")
        }
        append("</ns:items>")
    }
}
