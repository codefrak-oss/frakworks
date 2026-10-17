package com.codefrak.engine.github

import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/** The `RestClient` every GitHub REST call starts from: the base URL, the two version headers and the optional meter. */
internal fun gitHubRestClient(apiUrl: String, meter: GitHubCallMeter?): RestClient =
    RestClient.builder().baseUrl(apiUrl)
        .defaultHeader("Accept", "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .requestInterceptors { list -> meter?.let { list.add(it) } }
        .build()

/** Every page of a list endpoint, 100 at a time. */
internal fun <T> pages(fetch: (Int) -> List<T>): List<T> {
    val all = mutableListOf<T>()
    var page = 1
    do { val batch = fetch(page++); all += batch } while (batch.size == 100)
    return all
}

/** The repo's authorized GET, with an explicit token (for a probe that must not re-resolve the repo's own). */
internal fun RestClient.getAs(token: String, repo: String, path: String, vararg extra: Any): RestClient.ResponseSpec {
    val (owner, name) = splitRepo(repo)
    return get().uri(path, owner, name, *extra).header("Authorization", "Bearer $token").retrieve()
}

/** The repo's authorized GET, with the repo's own token ([RepoTokens.forRepo]). */
internal fun RestClient.get(tokens: RepoTokens, path: String, repo: String, vararg extra: Any): RestClient.ResponseSpec =
    getAs(tokens.forRepo(repo), repo, path, *extra)

/** The repo's authorized write: the path's `{owner}`/`{name}`, the repo's own token, a JSON body. */
internal fun RestClient.RequestBodyUriSpec.send(tokens: RepoTokens, path: String, repo: String, body: Any): RestClient.ResponseSpec {
    val token = tokens.forRepo(repo)
    val (owner, name) = splitRepo(repo)
    return uri(path, owner, name)
        .header("Authorization", "Bearer $token")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
}

/** `read()`, or null for GitHub's 404; any other status is rethrown. */
internal fun <T> missingIsNull(read: () -> T): T? = try { read() } catch (e: HttpClientErrorException.NotFound) { null }

/** A GraphQL query's `data`; GitHub's `errors` are thrown, with `what` to say what was read, except `NOT_FOUND` ones when `missingOk`. */
internal fun RestClient.graphql(tokens: RepoTokens, repo: String, query: String, variables: Map<String, Any?>, what: String, missingOk: Boolean = false): Map<*, *> {
    val token = tokens.forRepo(repo)
    val response = post().uri("/graphql").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("query" to query, "variables" to variables))
        .retrieve().body<Map<String, Any?>>() ?: error("GitHub returned nothing for $what")
    (response["errors"] as? List<*>)?.takeUnless { errors -> missingOk && errors.all { (it as? Map<*, *>)?.get("type") == "NOT_FOUND" } }
        ?.let { error("GitHub GraphQL: $it") }
    return response["data"] as? Map<*, *> ?: error("GitHub returned no data for $what")
}
