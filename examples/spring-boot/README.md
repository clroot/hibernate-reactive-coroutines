# Spring Boot Example

This application demonstrates the public Spring Boot integration against PostgreSQL:

- coroutine repositories discovered by auto-configuration
- `@Transactional` write and read-only workflows
- Spring Data auditing with a `ReactiveAuditorAware`
- explicit lazy association loading through `TransactionalAwareSessionProvider.fetch`
- the same association loaded with `LEFT JOIN FETCH`
- a derived query with sorting and `Page`

Start PostgreSQL with the defaults from `application.yaml`, then run:

```shell
./gradlew :examples:spring-boot:run
curl --request POST http://localhost:8080/demo
```

The response is a deterministic summary of the persisted and queried data. CI checks the complete
response so a source-compatibility or integration regression fails the build.
