package io.clroot.hibernate.reactive.ktor

import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import io.vertx.core.Vertx

internal object KtorDependencyInjectionBridge {
    fun install(application: Application, resources: HibernateReactiveResources) {
        application.dependencies {
            // Mutiny.SessionFactory is intentionally available through HibernateReactiveResources,
            // not as a direct DI value: Ktor DI auto-closes AutoCloseable dependencies and would
            // violate the plugin's external-resource ownership rules.
            provide<HibernateReactiveResources> { resources } cleanup { }
            provide<ReactiveSessionProvider> { resources.sessionProvider } cleanup { }
            provide<ReactiveTransactionExecutor> { resources.transactionExecutor } cleanup { }
            provide<Vertx> { resources.vertx } cleanup { }
        }
    }
}
