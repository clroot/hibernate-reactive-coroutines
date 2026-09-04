package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.repository.query.parser.Part

class ParameterBinderTest : DescribeSpec({

    describe("ParameterBinder") {

        context("Direct binder") {
            it("returns values unchanged") {
                val binder = ParameterBinder.Direct

                binder.bind("test") shouldBe "test"
                binder.bind(123) shouldBe 123
                binder.bind(true) shouldBe true
            }

            it("returns null unchanged") {
                val binder = ParameterBinder.Direct

                binder.bind(null).shouldBeNull()
            }
        }

        context("collection binders") {
            it("passes empty collections through so Hibernate can normalize them for the dialect") {
                ParameterBinder.InCollection.bind(emptyList<String>()) shouldBe emptyList<String>()
                ParameterBinder.NotInCollection.bind(emptyList<String>()) shouldBe emptyList<String>()
            }

            it("rejects null collections") {
                shouldThrow<IllegalArgumentException> { ParameterBinder.InCollection.bind(null) }
                shouldThrow<IllegalArgumentException> { ParameterBinder.NotInCollection.bind(null) }
            }

            it("rejects non-collection values") {
                shouldThrow<IllegalArgumentException> { ParameterBinder.InCollection.bind("not-a-collection") }
            }
        }

        context("Containing binder") {
            it("adds % to both sides of a value") {
                val binder = ParameterBinder.Containing

                binder.bind("test") shouldBe "%test%"
                binder.bind("hello world") shouldBe "%hello world%"
            }

            it("returns null for null") {
                val binder = ParameterBinder.Containing

                binder.bind(null).shouldBeNull()
            }
        }

        context("StartingWith binder") {
            it("adds % after a value") {
                val binder = ParameterBinder.StartingWith

                binder.bind("test") shouldBe "test%"
                binder.bind("prefix") shouldBe "prefix%"
            }

            it("returns null for null") {
                val binder = ParameterBinder.StartingWith

                binder.bind(null).shouldBeNull()
            }
        }

        context("EndingWith binder") {
            it("adds % before a value") {
                val binder = ParameterBinder.EndingWith

                binder.bind("test") shouldBe "%test"
                binder.bind("suffix") shouldBe "%suffix"
            }

            it("returns null for null") {
                val binder = ParameterBinder.EndingWith

                binder.bind(null).shouldBeNull()
            }
        }

        context("forType factory method") {
            it("returns Containing for CONTAINING") {
                ParameterBinder.forType(Part.Type.CONTAINING) shouldBe ParameterBinder.Containing
            }

            it("returns Containing for NOT_CONTAINING") {
                ParameterBinder.forType(Part.Type.NOT_CONTAINING) shouldBe ParameterBinder.Containing
            }

            it("returns StartingWith for STARTING_WITH") {
                ParameterBinder.forType(Part.Type.STARTING_WITH) shouldBe ParameterBinder.StartingWith
            }

            it("returns EndingWith for ENDING_WITH") {
                ParameterBinder.forType(Part.Type.ENDING_WITH) shouldBe ParameterBinder.EndingWith
            }

            it("returns Direct for other types") {
                ParameterBinder.forType(Part.Type.SIMPLE_PROPERTY) shouldBe ParameterBinder.Direct
                ParameterBinder.forType(Part.Type.BETWEEN) shouldBe ParameterBinder.Direct
                ParameterBinder.forType(Part.Type.GREATER_THAN) shouldBe ParameterBinder.Direct
            }

            it("returns collection binders for IN types") {
                ParameterBinder.forType(Part.Type.IN) shouldBe ParameterBinder.InCollection
                ParameterBinder.forType(Part.Type.NOT_IN) shouldBe ParameterBinder.NotInCollection
            }
        }
    }
})
