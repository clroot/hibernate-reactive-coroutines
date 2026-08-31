package io.clroot.hibernate.reactive.spring.boot.autoconfigure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReactiveConnectionUrlTest : FunSpec({

    test("removes consumed SSL and schema parameters") {
        ReactiveConnectionUrl.fromJdbc(
            "jdbc:postgresql://localhost:5432/orders" +
                "?sslmode=verify-full&application_name=orders&currentSchema=tenant",
        ) shouldBe "postgresql://localhost:5432/orders?application_name=orders"
    }

    test("removes the query delimiter when every parameter is consumed") {
        ReactiveConnectionUrl.fromJdbc(
            "jdbc:postgresql://localhost:5432/orders?currentSchema=tenant&sslmode=require",
        ) shouldBe "postgresql://localhost:5432/orders"
    }

    test("removes every duplicate consumed parameter and preserves unrelated parameters") {
        ReactiveConnectionUrl.fromJdbc(
            "jdbc:postgresql://localhost:5432/orders" +
                "?targetServerType=primary&sslmode=require&sslmode=verify-ca&connectTimeout=10",
        ) shouldBe
            "postgresql://localhost:5432/orders?targetServerType=primary&connectTimeout=10"
    }

    test("does not remove consumed parameter names found inside values or longer names") {
        ReactiveConnectionUrl.fromJdbc(
            "jdbc:postgresql://localhost:5432/orders" +
                "?options=sslmode%3Drequire&mysslmode=prefer&schema=currentSchema",
        ) shouldBe
            "postgresql://localhost:5432/orders" +
                "?options=sslmode%3Drequire&mysslmode=prefer&schema=currentSchema"
    }

    test("strips only the leading JDBC prefix when no query exists") {
        ReactiveConnectionUrl.fromJdbc(
            "jdbc:postgresql://localhost:5432/jdbc:archive",
        ) shouldBe "postgresql://localhost:5432/jdbc:archive"
    }

    test("preserves query parameters for non-PostgreSQL URLs") {
        ReactiveConnectionUrl.fromJdbc(
            "jdbc:mysql://localhost:3306/orders?currentSchema=tenant&sslmode=require",
        ) shouldBe "mysql://localhost:3306/orders?currentSchema=tenant&sslmode=require"
    }
})
