package io.clroot.hibernate.reactive.test

import io.clroot.hibernate.reactive.spring.boot.repository.EnableHibernateReactiveRepositories
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
@EnableHibernateReactiveRepositories(basePackages = ["io.clroot.hibernate.reactive.test"])
class TestApplication
