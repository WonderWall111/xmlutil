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

import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class SmallXmlState {
    val xml = BenchmarkData.smallXml
}

@State(Scope.Benchmark)
open class SmallXmlBytesState {
    val bytes = BenchmarkData.smallXml.encodeToByteArray()
}

@State(Scope.Benchmark)
open class RepeatedTagsState {

    @Param("100", "1000", "5000", "10000")
    var count: Int = 0

    lateinit var xml: String

    @Setup
    fun setup() {
        xml = BenchmarkData.repeatedTags(count)
    }
}

@State(Scope.Benchmark)
open class RepeatedTagsBytesState {

    @Param("100", "1000", "5000", "10000")
    var count: Int = 0

    lateinit var bytes: ByteArray

    @Setup
    fun setup() {
        bytes = BenchmarkData.repeatedTags(count).encodeToByteArray()
    }
}

@State(Scope.Benchmark)
open class LargeTextState {

    @Param("100", "1000", "5000", "10000")
    var size: Int = 0

    lateinit var xml: String

    @Setup
    fun setup() {
        xml = BenchmarkData.largeText(size)
    }
}

@State(Scope.Benchmark)
open class AttributeState {

    @Param("10", "100", "1000")
    var count: Int = 0

    lateinit var xml: String

    @Setup
    fun setup() {
        xml = BenchmarkData.attributeHeavy(count)
    }
}

@State(Scope.Benchmark)
open class NamespaceState {
    val xml = BenchmarkData.namespaceHeavy(
        BenchmarkData.REPEATED_TAG_COUNT
    )
}
