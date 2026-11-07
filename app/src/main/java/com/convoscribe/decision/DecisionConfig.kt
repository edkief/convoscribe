package com.convoscribe.decision

/**
 * The decision service in use (issue #101): a laya-serve base URL (where
 * `/v1/systemone` lives), its key (blank: none) and the laya model to ask
 * for (blank: laya picks one by language). [baseUrl] is blank when there
 * is none.
 */
data class DecisionConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String
)
