package io.clroot.hibernate.reactive.spring.boot.repository

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class RepositoryComponentScannerTest : DescribeSpec({

    describe("RepositoryComponentScanner") {

        context("interface scanning") {

            it("finds repository interfaces with HibernateReactiveRepositoryTypeFilter") {
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

            it("finds no components without an include filter") {
                val scanner = RepositoryComponentScanner()

                val candidates = scanner.findCandidateComponents(
                    "io.clroot.hibernate.reactive.test",
                )

                candidates.isEmpty() shouldBe true
            }
        }
    }
})
