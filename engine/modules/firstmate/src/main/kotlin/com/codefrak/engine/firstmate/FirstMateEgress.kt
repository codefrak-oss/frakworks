package com.codefrak.engine.firstmate

import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder
import io.fabric8.kubernetes.api.model.IntOrString
import java.net.InetAddress
import java.net.URI

/** One address and TCP port a session pod may reach beyond the sandbox, for the repo MCP server [name]. */
data class EgressHole(val name: String, val ip: String, val port: Int)

/**
 * A session pod's way to its repo's MCP servers on the private network (engine/README.md, "First-mate sessions"). The
 * `firstmate-sandbox` NetworkPolicy (deploy/k8s/base/networkpolicy.yaml) lets a pod reach the public internet but no
 * private range, so a repo server on the LAN (say `http://desk.example.com:7781/mcp`) never connects. For each http/sse repo
 * server whose host resolves to a private address the engine opens exactly that address and port, for that one pod: a
 * NetworkPolicy named like the pod, selecting it by [FIRST_MATE_SESSION_LABEL]. Policies add up, so the sandbox stays as
 * it is and no other pod, a Smith's or another session's, gets the hole. The address is resolved when the pod starts.
 */
object FirstMateEgress {
    /** The holes [mcp]'s repo servers need: their host's private addresses, with the URL's port. Engine servers and stdio are skipped. */
    fun holes(mcp: FirstMateMcp, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): List<EgressHole> =
        mcp.servers.flatMap { (name, entry) ->
            if (name in FirstMateMcpConfig.ENGINE_SERVERS) return@flatMap emptyList()
            val uri = (entry["url"] as? String)?.let { runCatching { URI(it) }.getOrNull() } ?: return@flatMap emptyList()
            val host = uri.host ?: return@flatMap emptyList()
            val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
            val addrs = runCatching { resolve(host) }.getOrDefault(emptyList())
            addrs.filter { private(it) }.map { EgressHole(name, it.hostAddress, port) }.distinct()
        }

    /** Whether [a] is in a range the sandbox blocks: RFC 1918, link-local, or CGNAT/Tailscale (100.64/10). IPv4 only. */
    fun private(a: InetAddress): Boolean {
        val b = a.address
        if (b.size != 4) return false
        val o0 = b[0].toInt() and 0xff; val o1 = b[1].toInt() and 0xff
        return o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) || (o0 == 169 && o1 == 254) || (o0 == 100 && o1 in 64..127)
    }

    /** The pod's own egress policy for [holes], named [name] (the pod's name), selecting only that session's pod. */
    fun policy(name: String, sessionId: String, holes: List<EgressHole>): NetworkPolicy =
        NetworkPolicyBuilder()
            .withNewMetadata().withName(name).withNamespace(FIRST_MATE_NAMESPACE)
                .addToLabels(FIRST_MATE_SESSION_LABEL, sessionId).endMetadata()
            .withNewSpec()
                .withNewPodSelector().addToMatchLabels(FIRST_MATE_SESSION_LABEL, sessionId).endPodSelector()
                .withPolicyTypes("Egress")
                .withEgress(holes.map { h ->
                    NetworkPolicyEgressRuleBuilder()
                        .addNewTo().withNewIpBlock().withCidr("${h.ip}/32").endIpBlock().endTo()
                        .addNewPort().withProtocol("TCP").withPort(IntOrString(h.port)).endPort()
                        .build()
                })
            .endSpec()
            .build()

}
