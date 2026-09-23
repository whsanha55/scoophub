package com.scoophub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ScoophubApplication

fun main(args: Array<String>) {
    runApplication<ScoophubApplication>(*args)
}
