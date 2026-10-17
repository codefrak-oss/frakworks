package com.codefrak.engine.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.body
import java.util.Base64

/** GitHub's git tree calls, each with the repo's token ([RepoTokens]). */
@Component
class RestRepoContents(
    private val tokens: RepoTokens,
    @Value("\${engine.github.api-url:https://api.github.com}") apiUrl: String,
    /** Counts each call and keeps the rate-limit headers ([GitHubCallMeter]); null (unit tests) counts nothing. */
    meter: GitHubCallMeter? = null,
) : RepoContents {
    private val client = gitHubRestClient(apiUrl, meter)

    /**
     * One GraphQL query for the tree at `HEAD:<path>` (HEAD is the default branch): each entry's name and oid.
     * The repo's token reads GraphQL on target repos already ([ticket]), so no new scope.
     */
    override fun treeEntries(repo: String, path: String): List<TreeEntry> {
        val (owner, name) = splitRepo(repo)
        val query = "query(\$o:String!,\$n:String!,\$e:String!){repository(owner:\$o,name:\$n){object(expression:\$e){... on Tree{entries{name oid type}}}}}"
        val data = client.graphql(tokens, repo, query, mapOf("o" to owner, "n" to name, "e" to "HEAD:$path"), "$repo $path")
        val tree = (data["repository"] as? Map<*, *>)?.get("object") as? Map<*, *> ?: return emptyList()
        return (tree["entries"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "blob" }
            .map { TreeEntry(it["name"] as String, it["oid"] as String) }
    }

    /** One GraphQL query, an aliased `object(oid:)` per blob, at most 50 a query. */
    override fun blobTexts(repo: String, oids: Collection<String>): Map<String, String> {
        val (owner, name) = splitRepo(repo)
        return oids.distinct().chunked(50).flatMap { chunk ->
            val params = chunk.indices.joinToString("") { ",\$b$it:GitObjectID!" }
            val fields = chunk.indices.joinToString(" ") { "b$it:object(oid:\$b$it){... on Blob{text}}" }
            val query = "query(\$o:String!,\$n:String!$params){repository(owner:\$o,name:\$n){$fields}}"
            val vars = mapOf("o" to owner, "n" to name) + chunk.mapIndexed { i, oid -> "b$i" to oid }
            val repository = client.graphql(tokens, repo, query, vars, "$repo blobs")["repository"] as? Map<*, *> ?: return@flatMap emptyList<Pair<String, String>>()
            chunk.mapIndexedNotNull { i, oid -> ((repository["b$i"] as? Map<*, *>)?.get("text") as? String)?.let { oid to it } }
        }.toMap()
    }

    /**
     * `GET /repos/{r}/commits?sha=<ref>&per_page=1`: the first commit's SHA (no `sha`: the default branch's head).
     * The ref is a query parameter, so a branch with a `/` in it needs no path encoding. GitHub answers a ref it
     * cannot resolve 404 (422 for some malformed ones, 409 for an empty repo): null.
     */
    override fun commitSha(repo: String, ref: String?): String? = try {
        val commits = if (ref == null) client.get(tokens, "/repos/{owner}/{name}/commits?per_page=1", repo).body<List<Map<String, Any?>>>()
            else client.get(tokens, "/repos/{owner}/{name}/commits?sha={ref}&per_page=1", repo, ref).body<List<Map<String, Any?>>>()
        commits.orEmpty().firstOrNull()?.get("sha") as? String
    } catch (e: HttpClientErrorException) {
        if (e.statusCode.value() in setOf(404, 409, 422)) null else throw e
    }

    /** `GET /repos/{r}/git/trees/{sha}?recursive=1`: every entry's path, type and SHA, and GitHub's `truncated`. */
    override fun repoTree(repo: String, sha: String): RepoTree {
        val tree = client.get(tokens, "/repos/{owner}/{name}/git/trees/{sha}?recursive=1", repo, sha).body<Map<String, Any?>>()
            ?: error("GitHub returned no tree for $repo@$sha")
        return RepoTree(tree["truncated"] == true, (tree["tree"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { e ->
            val path = e["path"] as? String ?: return@mapNotNull null
            RepoTreeEntry(path, e["type"] as? String ?: "blob", e["sha"] as? String ?: "")
        })
    }

    /**
     * `GET /repos/{r}/contents/{path}?ref=` with GitHub's raw media type, as bytes; each path segment is a URI
     * variable, so a name with a space or a `%` in it is encoded rather than read as part of the template.
     */
    override fun fileBytes(repo: String, path: String, ref: String): ByteArray? = missingIsNull {
        val token = tokens.forRepo(repo)
        val (owner, name) = splitRepo(repo)
        val segments = segmentsOf(path)
        val template = "/repos/{owner}/{name}/contents/" + segments.indices.joinToString("/") { "{s$it}" } + "?ref={ref}"
        client.get().uri(template, owner, name, *segments.toTypedArray(), ref)
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github.raw+json")
            .retrieve().body<ByteArray>() ?: ByteArray(0)
    }

    /** `GET /repos/{r}/contents/{path}` (JSON): the base64 `content` decoded, and the blob `sha`. */
    override fun file(repo: String, path: String): RepoFile? = missingIsNull {
        val (owner, name) = splitRepo(repo)
        val segments = segmentsOf(path)
        val body = client.get().uri(contentsTemplate(segments), owner, name, *segments.toTypedArray())
            .header("Authorization", "Bearer ${tokens.forRepo(repo)}").header("Accept", "application/vnd.github+json")
            .retrieve().body<Map<String, Any?>>() ?: error("GitHub returned nothing for $repo $path")
        val content = (body["content"] as? String ?: error("$repo $path is not a file")).replace("\n", "")
        RepoFile(String(Base64.getDecoder().decode(content), Charsets.UTF_8), body["sha"] as String)
    }

    /** `PUT /repos/{r}/contents/{path}` with `sha` (an update, never a create) and the author `<login>@users.noreply.github.com`. */
    override fun putFile(repo: String, path: String, text: String, sha: String, message: String, authorLogin: String): String {
        val (owner, name) = splitRepo(repo)
        val segments = segmentsOf(path)
        val body = mapOf(
            "message" to message, "sha" to sha,
            "content" to Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)),
            "author" to mapOf("name" to authorLogin, "email" to "$authorLogin@users.noreply.github.com"),
        )
        val answer = client.put().uri(contentsTemplate(segments), owner, name, *segments.toTypedArray())
            .header("Authorization", "Bearer ${tokens.forRepo(repo)}").contentType(MediaType.APPLICATION_JSON).body(body)
            .retrieve().body<Map<String, Any?>>() ?: error("GitHub returned nothing for the commit to $repo $path")
        return (answer["commit"] as? Map<*, *>)?.get("sha") as? String ?: error("GitHub named no commit for $repo $path")
    }

    /** [path]'s segments; an empty, `.` or `..` one is refused, so no path leaves the named repo's contents route. */
    private fun segmentsOf(path: String): List<String> = path.split('/').also { segments ->
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "bad contents path $path: no empty, . or .. segment" }
    }

    /** The contents path with each segment a URI variable after `{owner}` and `{name}`. */
    private fun contentsTemplate(segments: List<String>) =
        "/repos/{owner}/{name}/contents/" + segments.indices.joinToString("/") { "{s$it}" }
}
