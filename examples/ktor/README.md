# Ktor Example

This application demonstrates the public Ktor integration against PostgreSQL:

- explicit repository registration and lookup
- explicit `transactional` and `readOnly` workflows
- auditing through the plugin's `ReactiveAuditorAware`
- an association loaded with `LEFT JOIN FETCH`
- a derived query with Jakarta Data sorting and `Page`

Start PostgreSQL with the default `DB_*` values, then run:

```shell
./gradlew :examples:ktor:run
curl --request POST http://localhost:8081/demo
```

The response is a deterministic summary of the persisted and queried data. CI checks the complete
response so a source-compatibility or integration regression fails the build.
