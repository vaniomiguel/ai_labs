package com.example.ollama.outsmart;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers Outsmart settings without adding beans to the existing chat feature. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutsmartProperties.class)
class OutsmartConfiguration {
}
