package com.scoophub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ScoophubApplication

fun main(args: Array<String>) {
    runApplication<ScoophubApplication>(*args)
}
