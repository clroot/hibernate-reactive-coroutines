package io.clroot.hibernate.reactive.ktor

import io.clroot.hibernate.reactive.ReactiveSessionProvider
import io.clroot.hibernate.reactive.ReactiveTransactionExecutor
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import io.vertx.core.Vertx
import org.hibernate.reactive.mutiny.Mutiny
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/** Application-scoped Hibernate Reactive infrastructure and repository registry. */
public class HibernateReactiveResources internal constructor(
    /** Hibernate Reactive session factory used by the application. */
    public val sessionFactory: Mutiny.SessionFactory,
    /** Coroutine session operations used by repositories and application code. */
    public val sessionProvider: ReactiveSessionProvider,
    /** Explicit service-layer transaction executor. */
    public val transactionExecutor: ReactiveTransactionExecutor,
    /** Vert.x instance used by Hibernate Reactive. */
    public val vertx: Vertx,
    private val repositories: Map<Class<*>, Any>,
    private val closeSessionFactory: Boolean,
    private val closeVertx: Boolean,
) {
    private val closed = AtomicBoolean(false)

    /** Returns the singleton proxy registered for [repositoryInterface]. */
    public fun <R : Any> repository(repositoryInterface: KClass<R>): R =
        repository(repositoryInterface.java)

    /** Returns the singleton proxy registered for [repositoryInterface]. */
    @Suppress("UNCHECKED_CAST")
    public fun <R : Any> repository(repositoryInterface: Class<R>): R =
        repositories[repositoryInterface] as? R
            ?: throw IllegalArgumentException(
                "Repository is not registered: ${repositoryInterface.name}",
            )

    /** Reified repository lookup. */
    public inline fun <reified R : Any> repository(): R = repository(R::class)

    internal fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        if (closeSessionFactory) {
            try {
                sessionFactory.close()
            } catch (exception: Throwable) {
                failure = exception
            }
        }
        if (closeVertx) {
            try {
                vertx.closeBlocking()
            } catch (exception: Throwable) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        failure?.let { throw it }
    }
}

internal fun Vertx.closeBlocking() {
    close()
        .toCompletionStage()
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS)
}

internal val HibernateReactiveResourcesKey: AttributeKey<HibernateReactiveResources> =
    AttributeKey("HibernateReactiveResources")

/** Returns the Hibernate Reactive resources installed in this application. */
public val Application.hibernateReactive: HibernateReactiveResources
    get() = attributes.getOrNull(HibernateReactiveResourcesKey)
        ?: error("HibernateReactive is not installed in this application")

/** Application-scoped Hibernate Reactive session factory. */
public val Application.hibernateSessionFactory: Mutiny.SessionFactory
    get() = hibernateReactive.sessionFactory

/** Application-scoped coroutine session provider. */
public val Application.hibernateSessionProvider: ReactiveSessionProvider
    get() = hibernateReactive.sessionProvider

/** Application-scoped explicit transaction executor. */
public val Application.hibernateTransactionExecutor: ReactiveTransactionExecutor
    get() = hibernateReactive.transactionExecutor

/** Returns a repository registered with [HibernateReactive]. */
public fun <R : Any> Application.hibernateRepository(repositoryInterface: KClass<R>): R =
    hibernateReactive.repository(repositoryInterface)

/** Reified repository lookup for an application. */
public inline fun <reified R : Any> Application.hibernateRepository(): R =
    hibernateReactive.repository()
