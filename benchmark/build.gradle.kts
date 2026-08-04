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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.benchmark)
    kotlin("multiplatform")
    id("projectPlugin")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.allopen)
//    alias(libs.plugins.dokka)
//    alias(libs.plugins.jmh)
    signing
}

config {
    generateJavaModules = false
    kotlinApiVersion = KotlinVersion.DEFAULT
    generalJvmTarget = JvmTarget.JVM_17
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.core)
                implementation(projects.xmlschema)
                implementation(projects.schemaTests)
                implementation(projects.serialization)
                implementation(projects.testutil)
                implementation(libs.benchmark.runtime)
                implementation(libs.datetime)
            }
        }
        jvmMain {
            dependencies {
                implementation(projects.coreJdk)
//                implementation(libs.jmhCore)
                implementation(kotlin("test-junit5"))
            }
        }
        jvmTest {
            dependencies {
                runtimeOnly(libs.junit.engine)
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

benchmark {
    targets {
        register("jvm")
    }
    configurations {
        create("micro") {
            include("nl.adaptivity.xmlutil.benchmark.ParserMicroBenchmark")
        }
        create("parsing") {
            include("nl.adaptivity.xmlutil.benchmark.Parsing")
        }
        create("deserialization") {
            include("nl.adaptivity.xmlutil.benchmark.Deserialization")
        }
        create("deserializationFast") {
            include("nl.adaptivity.xmlutil.benchmark.Deserialization.testDeserializeGenericSpeedRetainedXml")
            include("nl.adaptivity.xmlutil.benchmark.Deserialization.testDeserializeNoparseRetained")
        }
        create("serialization") {
            include("nl.adaptivity.xmlutil.benchmark.Serialization")
        }
        create("smallXml") {
            include(
                "nl.adaptivity.xmlutil.benchmark.ParserMicroBenchmark.parseSmallXml.*"
            )
        }
        create("repeatedTags") {
            include(
                "nl\\.adaptivity\\.xmlutil\\.benchmark\\.ParserMicroBenchmark\\.parseRepeatedTags$"
            )
            include(
                "nl\\.adaptivity\\.xmlutil\\.benchmark\\.ParserMicroBenchmark\\.parseRepeatedTagsInputStream$"
            )
            advanced("jvmForks", 2)
        }
        create("repeatedTagsVerify") {
            include(
                "nl\\.adaptivity\\.xmlutil\\.benchmark\\" +
                        ".ParserMicroBenchmark\\.parseRepeatedTags$"
            )

            param("count", "100", "5000")

            warmups = 5
            iterations = 10
            advanced("jvmForks", 3)
        }
        create("repeatedTags5000Verify") {
            include(
                "nl\\.adaptivity\\.xmlutil\\.benchmark\\." +
                        "ParserMicroBenchmark\\.parseRepeatedTags$"
            )
            include(
                "nl\\.adaptivity\\.xmlutil\\.benchmark\\." +
                        "ParserMicroBenchmark\\.parseRepeatedTagsInputStream$"
            )

            param("count", "5000")

            warmups = 5
            iterations = 10
            advanced("jvmForks", 3)
        }
    }
}

allOpen {
    annotations("org.openjdk.jmh.annotations.State", "kotlinx.benchmark.State")
}

//jmh {
//    jmhVersion = libs.versions.jmh.core
//}
