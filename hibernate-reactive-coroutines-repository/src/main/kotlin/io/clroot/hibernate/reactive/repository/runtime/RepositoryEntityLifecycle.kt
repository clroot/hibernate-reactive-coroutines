package io.clroot.hibernate.reactive.repository.runtime

/**
 * Adapter hook for integration-specific entity state and lifecycle behavior.
 *
 * Returning `null` from [isNew] delegates the decision to the runtime's Jakarta Persistence
 * identifier/version rules. [beforeSave] is invoked after the final state decision and before
 * Hibernate receives the entity.
 */
public interface RepositoryEntityLifecycle {
    public fun isNew(entity: Any): Boolean? = null

    public suspend fun beforeSave(entity: Any, isNew: Boolean) {}

    public companion object {
        /** Lifecycle with no integration-specific behavior. */
        public val NONE: RepositoryEntityLifecycle = object : RepositoryEntityLifecycle {}
    }
}
