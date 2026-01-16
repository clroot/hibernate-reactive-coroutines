package io.clroot.hibernate.reactive.spring.boot.repository.query

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.data.repository.query.parser.Part

/**
 * ParameterBinder 유닛 테스트.
 *
 * LIKE 패턴 변환 등 파라미터 바인딩 로직을 검증합니다.
 */
class ParameterBinderTest : DescribeSpec({

    describe("ParameterBinder") {

        context("Direct binder") {
            it("값을 그대로 반환한다") {
                val binder = ParameterBinder.Direct

                binder.bind("test") shouldBe "test"
                binder.bind(123) shouldBe 123
                binder.bind(true) shouldBe true
            }

            it("null을 그대로 반환한다") {
                val binder = ParameterBinder.Direct

                binder.bind(null).shouldBeNull()
            }
        }

        context("Containing binder") {
            it("값 양쪽에 %를 추가한다") {
                val binder = ParameterBinder.Containing

                binder.bind("test") shouldBe "%test%"
                binder.bind("hello world") shouldBe "%hello world%"
            }

            it("null이면 null을 반환한다") {
                val binder = ParameterBinder.Containing

                binder.bind(null).shouldBeNull()
            }
        }

        context("StartingWith binder") {
            it("값 뒤에 %를 추가한다") {
                val binder = ParameterBinder.StartingWith

                binder.bind("test") shouldBe "test%"
                binder.bind("prefix") shouldBe "prefix%"
            }

            it("null이면 null을 반환한다") {
                val binder = ParameterBinder.StartingWith

                binder.bind(null).shouldBeNull()
            }
        }

        context("EndingWith binder") {
            it("값 앞에 %를 추가한다") {
                val binder = ParameterBinder.EndingWith

                binder.bind("test") shouldBe "%test"
                binder.bind("suffix") shouldBe "%suffix"
            }

            it("null이면 null을 반환한다") {
                val binder = ParameterBinder.EndingWith

                binder.bind(null).shouldBeNull()
            }
        }

        context("forType 팩토리 메서드") {
            it("CONTAINING 타입에 Containing 반환") {
                ParameterBinder.forType(Part.Type.CONTAINING) shouldBe ParameterBinder.Containing
            }

            it("NOT_CONTAINING 타입에 Containing 반환") {
                ParameterBinder.forType(Part.Type.NOT_CONTAINING) shouldBe ParameterBinder.Containing
            }

            it("STARTING_WITH 타입에 StartingWith 반환") {
                ParameterBinder.forType(Part.Type.STARTING_WITH) shouldBe ParameterBinder.StartingWith
            }

            it("ENDING_WITH 타입에 EndingWith 반환") {
                ParameterBinder.forType(Part.Type.ENDING_WITH) shouldBe ParameterBinder.EndingWith
            }

            it("다른 타입에는 Direct 반환") {
                ParameterBinder.forType(Part.Type.SIMPLE_PROPERTY) shouldBe ParameterBinder.Direct
                ParameterBinder.forType(Part.Type.BETWEEN) shouldBe ParameterBinder.Direct
                ParameterBinder.forType(Part.Type.GREATER_THAN) shouldBe ParameterBinder.Direct
                ParameterBinder.forType(Part.Type.IN) shouldBe ParameterBinder.Direct
            }
        }
    }
})
