package io.clroot.examples.springboot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringBootExample

fun main(args: Array<String>) {
    runApplication<SpringBootExample>(*args)
}
