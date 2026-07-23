package io.clroot.hibernate.reactive.test.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@JvmInline
value class ValueEntityId(
    val value: Long,
)

@Entity
@Table(name = "value_id_entity")
class ValueIdEntity(
    @Id
    val id: ValueEntityId,
    var name: String,
)
