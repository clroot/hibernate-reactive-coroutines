package io.clroot.hibernate.reactive.spring.boot.transaction

import io.clroot.hibernate.reactive.TransactionMode
import io.kotest.core.spec.style.DescribeSpec
import io.vertx.core.Context
import org.hibernate.reactive.mutiny.Mutiny

class MutinySessionHolderAbiTest : DescribeSpec({

    describe("MutinySessionHolder binary compatibility") {
        it("retains the 2.0.0 JVM constructor bridges") {
            val defaultConstructorMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

            MutinySessionHolder::class.java.getConstructor(
                Mutiny.Session::class.java,
                Context::class.java,
                TransactionMode::class.java,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                defaultConstructorMarker,
            )
            MutinySessionHolder::class.java.getConstructor(
                Mutiny.Session::class.java,
                Context::class.java,
                TransactionMode::class.java,
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                java.lang.Integer.TYPE,
                defaultConstructorMarker,
            )
        }
    }
})
