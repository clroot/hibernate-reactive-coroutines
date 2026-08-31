package io.clroot.hibernate.reactive.spring.boot.repository

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class RepositoryComponentScannerTest : DescribeSpec({

    describe("RepositoryComponentScanner") {

        context("인터페이스 스캔") {

            it("HibernateReactiveRepositoryTypeFilter와 함께 사용하면 Repository 인터페이스를 찾는다") {
                val scanner = RepositoryComponentScanner().apply {
                    addIncludeFilter(HibernateReactiveRepositoryTypeFilter())
                }

                val candidates = scanner.findCandidateComponents(
                    "io.clroot.hibernate.reactive.test",
                )

                candidates.shouldNotBeEmpty()

                val classNames = candidates.mapNotNull { it.beanClassName }
                classNames.any { it.contains("Repository") } shouldBe true
            }

            it("필터 없이 스캔하면 아무것도 찾지 않는다") {
                val scanner = RepositoryComponentScanner()

                val candidates = scanner.findCandidateComponents(
                    "io.clroot.hibernate.reactive.test",
                )

                candidates.isEmpty() shouldBe true
            }
        }
    }
})
