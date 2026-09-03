package io.clroot.hibernate.reactive.repository.query.derived

import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.Optional

/** Parses Spring-Data-style repository method names without depending on Spring Data. */
public object DerivedQueryParser {
    private val prefixPattern = Regex(
        "^(find|read|get|query|search|stream|count|exists|delete|remove)((\\p{Lu}.*?))??By",
    )
    private val limitPattern = Regex(
        "^(find|read|get|query|search|stream)(Distinct)?(First|Top)(\\d*)?(\\p{Lu}.*?)??By",
    )
    private val allIgnoreCasePattern = Regex("AllIgnor(?:ing|e)Case")
    private val ignoreCasePattern = Regex("Ignor(?:ing|e)Case")
    private val orderBlockPattern = Regex("(?<=Asc|Desc)(?=\\p{Lu})")
    private val directionPattern = Regex("(.+?)(Asc|Desc)?$")

    /** Parse [methodName] and validate all referenced properties against [entityType]. */
    public fun parse(methodName: String, entityType: Class<*>): DerivedQuery {
        require(methodName.isNotBlank()) { "Method name must not be blank" }

        val source = methodName.substringBefore('-')
        val prefix = prefixPattern.find(source)
        val subjectSource = prefix?.value
        var predicateSource = prefix?.let { source.substring(it.range.last + 1) } ?: source

        val subject = when {
            subjectSource?.startsWith("count") == true -> QuerySubject.COUNT
            subjectSource?.startsWith("exists") == true -> QuerySubject.EXISTS
            subjectSource?.startsWith("delete") == true || subjectSource?.startsWith("remove") == true -> {
                QuerySubject.DELETE
            }
            else -> QuerySubject.FIND
        }
        val distinct = subjectSource?.contains("Distinct") == true
        val limit = subjectSource?.let { sourcePrefix ->
            limitPattern.find(sourcePrefix)?.groupValues?.get(4)?.let { digits ->
                if (digits.isEmpty()) 1 else digits.toInt()
            }
        }

        val allIgnoreCaseMatch = allIgnoreCasePattern.find(predicateSource)
        val allIgnoreCase = allIgnoreCaseMatch != null
        if (allIgnoreCaseMatch != null) {
            predicateSource = predicateSource.removeRange(allIgnoreCaseMatch.range)
        }

        val orderSplit = splitAtKeyword(predicateSource, "OrderBy")
        require(orderSplit.size <= 2) { "OrderBy must not be used more than once in a method name" }

        val predicate = parsePredicate(orderSplit.firstOrNull().orEmpty(), entityType, allIgnoreCase)
        val orderBy = orderSplit.getOrNull(1)?.let { parseOrderBy(it, entityType) }.orEmpty()

        return DerivedQuery(
            subject = subject,
            distinct = distinct,
            predicate = predicate,
            orderBy = orderBy,
            limit = limit,
        )
    }

    private fun parsePredicate(
        source: String,
        entityType: Class<*>,
        allIgnoreCase: Boolean,
    ): PredicateGroup {
        val disjuncts = splitAtKeyword(source, "Or")
            .filter(String::isNotBlank)
            .map { orPart ->
                val predicates = splitAtKeyword(orPart, "And")
                    .filter(String::isNotBlank)
                    .map { parsePart(it, entityType, allIgnoreCase) }
                Conjunction(predicates)
            }
        return PredicateGroup(disjuncts)
    }

    private fun parsePart(
        source: String,
        entityType: Class<*>,
        allIgnoreCase: Boolean,
    ): QueryPredicate {
        var ignoreCase = if (allIgnoreCase) IgnoreCaseMode.WHEN_POSSIBLE else IgnoreCaseMode.NEVER
        val ignoreCaseMatch = ignoreCasePattern.find(source)
        val withoutIgnoreCase = if (ignoreCaseMatch != null) {
            ignoreCase = IgnoreCaseMode.ALWAYS
            source.removeRange(ignoreCaseMatch.range)
        } else {
            source
        }

        val operatorMatch = OPERATORS.firstNotNullOfOrNull { candidate ->
            candidate.keywords.firstOrNull(withoutIgnoreCase::endsWith)?.let { candidate.operator to it }
        }
        val operator = operatorMatch?.first ?: PredicateOperator.EQUALS
        val keyword = operatorMatch?.second
        val decapitalized = decapitalize(withoutIgnoreCase)
        val propertySource = keyword?.let(decapitalized::removeSuffix) ?: decapitalized
        val property = PropertyPathResolver.resolve(propertySource, entityType)

        return QueryPredicate(property, operator, ignoreCase)
    }

    private fun parseOrderBy(source: String, entityType: Class<*>): List<QueryOrder> {
        if (source.isBlank()) return emptyList()

        return source.split(orderBlockPattern).map { part ->
            val match = directionPattern.matchEntire(part)
                ?: throw IllegalArgumentException("Invalid order syntax for part $part")
            val propertySource = match.groupValues[1]
            val directionSource = match.groupValues[2]
            if (propertySource in setOf("Asc", "Desc") && directionSource.isEmpty()) {
                throw IllegalArgumentException("Invalid order syntax for part $part")
            }
            val property = PropertyPathResolver.resolve(propertySource, entityType)
            QueryOrder(
                property = property.value,
                direction = if (directionSource == "Desc") SortDirection.DESC else SortDirection.ASC,
            )
        }
    }

    private fun splitAtKeyword(source: String, keyword: String): List<String> {
        if (source.isEmpty()) return listOf("")

        val result = mutableListOf<String>()
        var start = 0
        var index = source.indexOf(keyword)
        while (index >= 0) {
            val nextIndex = index + keyword.length
            val hasKeywordBoundary = nextIndex < source.length && source[nextIndex].isKeywordBoundary()
            if (hasKeywordBoundary) {
                result += source.substring(start, index)
                start = nextIndex
            }
            index = source.indexOf(keyword, index + 1)
        }
        result += source.substring(start)
        return result
    }

    private fun Char.isKeywordBoundary(): Boolean = isUpperCase() || code > 0x7f

    private data class OperatorCandidate(
        val operator: PredicateOperator,
        val keywords: List<String>,
    )

    // Ordering intentionally matches Spring Data's Part.Type lookup order.
    private val OPERATORS = listOf(
        OperatorCandidate(PredicateOperator.IS_NOT_NULL, listOf("IsNotNull", "NotNull")),
        OperatorCandidate(PredicateOperator.IS_NULL, listOf("IsNull", "Null")),
        OperatorCandidate(PredicateOperator.BETWEEN, listOf("IsBetween", "Between")),
        OperatorCandidate(PredicateOperator.LESS_THAN, listOf("IsLessThan", "LessThan")),
        OperatorCandidate(PredicateOperator.LESS_THAN_EQUAL, listOf("IsLessThanEqual", "LessThanEqual")),
        OperatorCandidate(PredicateOperator.GREATER_THAN, listOf("IsGreaterThan", "GreaterThan")),
        OperatorCandidate(PredicateOperator.GREATER_THAN_EQUAL, listOf("IsGreaterThanEqual", "GreaterThanEqual")),
        OperatorCandidate(PredicateOperator.BEFORE, listOf("IsBefore", "Before")),
        OperatorCandidate(PredicateOperator.AFTER, listOf("IsAfter", "After")),
        OperatorCandidate(PredicateOperator.NOT_LIKE, listOf("IsNotLike", "NotLike")),
        OperatorCandidate(PredicateOperator.LIKE, listOf("IsLike", "Like")),
        OperatorCandidate(PredicateOperator.STARTING_WITH, listOf("IsStartingWith", "StartingWith", "StartsWith")),
        OperatorCandidate(PredicateOperator.ENDING_WITH, listOf("IsEndingWith", "EndingWith", "EndsWith")),
        OperatorCandidate(PredicateOperator.IS_NOT_EMPTY, listOf("IsNotEmpty", "NotEmpty")),
        OperatorCandidate(PredicateOperator.IS_EMPTY, listOf("IsEmpty", "Empty")),
        OperatorCandidate(
            PredicateOperator.NOT_CONTAINING,
            listOf("IsNotContaining", "NotContaining", "NotContains"),
        ),
        OperatorCandidate(PredicateOperator.CONTAINING, listOf("IsContaining", "Containing", "Contains")),
        OperatorCandidate(PredicateOperator.NOT_IN, listOf("IsNotIn", "NotIn")),
        OperatorCandidate(PredicateOperator.IN, listOf("IsIn", "In")),
        OperatorCandidate(PredicateOperator.NEAR, listOf("IsNear", "Near")),
        OperatorCandidate(PredicateOperator.WITHIN, listOf("IsWithin", "Within")),
        OperatorCandidate(PredicateOperator.REGEX, listOf("MatchesRegex", "Matches", "Regex")),
        OperatorCandidate(PredicateOperator.EXISTS, listOf("Exists")),
        OperatorCandidate(PredicateOperator.TRUE, listOf("IsTrue", "True")),
        OperatorCandidate(PredicateOperator.FALSE, listOf("IsFalse", "False")),
        OperatorCandidate(PredicateOperator.NOT_EQUALS, listOf("IsNot", "Not")),
        OperatorCandidate(PredicateOperator.EQUALS, listOf("Is", "Equals")),
    )
}

private object PropertyPathResolver {
    private val propertiesByType = object : ClassValue<Map<String, ResolvedProperty>>() {
        override fun computeValue(type: Class<*>): Map<String, ResolvedProperty> = inspectProperties(type)
    }

    fun resolve(source: String, entityType: Class<*>): PropertyPath {
        require(source.isNotBlank()) { "Property path must not be empty" }

        val explicitParts = source.split('.', '_').filter(String::isNotEmpty)
        require(explicitParts.isNotEmpty()) { "Property path must not be empty" }

        var currentType = entityType
        val segments = mutableListOf<String>()
        var leafType = entityType
        for (part in explicitParts) {
            val resolved = resolveCamelCase(part, currentType, segments)
            segments += resolved.segments
            currentType = resolved.leafType
            leafType = resolved.leafType
        }
        return PropertyPath(segments.joinToString("."), leafType)
    }

    private fun resolveCamelCase(
        source: String,
        owningType: Class<*>,
        resolvedBase: List<String>,
    ): ResolvedPath {
        findProperty(source, owningType)?.let { property ->
            return ResolvedPath(listOf(property.name), property.actualType)
        }

        val splitPositions = source.indices
            .filter { it > 0 && source[it].isUpperCase() }
            .asReversed()

        for (position in splitPositions) {
            val head = source.substring(0, position)
            val tail = source.substring(position)
            val property = findProperty(head, owningType) ?: continue
            try {
                val nested = resolveCamelCase(tail, property.actualType, resolvedBase + property.name)
                return ResolvedPath(listOf(property.name) + nested.segments, nested.leafType)
            } catch (_: IllegalArgumentException) {
                // Try the next, shorter head just like Spring Data's longest-property-first lookup.
            }
        }

        val candidate = decapitalize(source)
        val base = (resolvedBase + candidate).joinToString(".")
        throw IllegalArgumentException(
            "No property '$candidate' found on ${owningType.name} while resolving '$base'",
        )
    }

    private fun findProperty(source: String, type: Class<*>): ResolvedProperty? {
        val properties = propertiesByType.get(type)
        return properties[decapitalize(source)] ?: properties[uncapitalize(source)]
    }

    private fun inspectProperties(type: Class<*>): Map<String, ResolvedProperty> {
        val properties = linkedMapOf<String, ResolvedProperty>()

        hierarchy(type).forEach { current ->
            current.declaredFields
                .asSequence()
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    properties.putIfAbsent(
                        field.name,
                        ResolvedProperty(field.name, actualClass(field.genericType)),
                    )
                }

            current.declaredMethods
                .asSequence()
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) || it.parameterCount != 0 }
                .forEach { method ->
                    val name = when {
                        method.name.startsWith("get") && method.name.length > 3 && method.returnType != Void.TYPE -> {
                            decapitalize(method.name.substring(3))
                        }
                        method.name.startsWith("is") && method.name.length > 2 &&
                            (method.returnType == Boolean::class.javaPrimitiveType ||
                                method.returnType == Boolean::class.javaObjectType) -> {
                            decapitalize(method.name.substring(2))
                        }
                        current.isRecord && current.recordComponents.any { it.name == method.name } -> method.name
                        else -> null
                    }
                    if (name != null && name != "class") {
                        properties.putIfAbsent(name, ResolvedProperty(name, actualClass(method.genericReturnType)))
                    }
                }
        }
        return properties
    }

    private fun hierarchy(type: Class<*>): Sequence<Class<*>> = sequence {
        val visited = mutableSetOf<Class<*>>()
        val pending = ArrayDeque<Class<*>>()
        pending += type
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            yield(current)
            current.superclass?.takeUnless { it == Any::class.java }?.let(pending::addLast)
            current.interfaces.forEach(pending::addLast)
        }
    }

    private fun actualClass(type: Type): Class<*> = when (type) {
        is Class<*> -> if (type.isArray) type.componentType else type
        is ParameterizedType -> {
            val raw = type.rawType as? Class<*> ?: Any::class.java
            val argument = when {
                Map::class.java.isAssignableFrom(raw) -> type.actualTypeArguments.getOrNull(1)
                Iterable::class.java.isAssignableFrom(raw) || Optional::class.java.isAssignableFrom(raw) -> {
                    type.actualTypeArguments.firstOrNull()
                }
                else -> null
            }
            argument?.let(::actualClass) ?: raw
        }
        is WildcardType -> type.lowerBounds.firstOrNull()?.let(::actualClass)
            ?: type.upperBounds.firstOrNull()?.let(::actualClass)
            ?: Any::class.java
        is GenericArrayType -> actualClass(type.genericComponentType)
        else -> Any::class.java
    }

    private data class ResolvedProperty(val name: String, val actualType: Class<*>)
    private data class ResolvedPath(val segments: List<String>, val leafType: Class<*>)
}

private fun decapitalize(value: String): String = when {
    value.isEmpty() -> value
    value.length > 1 && value[0].isUpperCase() && value[1].isUpperCase() -> value
    else -> value.replaceFirstChar(Char::lowercase)
}

private fun uncapitalize(value: String): String = value.replaceFirstChar(Char::lowercase)
