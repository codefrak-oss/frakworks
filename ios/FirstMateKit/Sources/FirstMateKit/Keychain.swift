import Foundation
import Security

/// The pairing (engine URL and first-mate token, account `pairing`) and the HITLP server (URL and surface credential,
/// account `hitlp`) as generic-password items, readable after first unlock on this device only.
public enum Keychain {
    static let service = "com.codefrak.firstmate"
    static let pairingAccount = "pairing"
    static let hitlpAccount = "hitlp"

    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    public static func save(_ pairing: Pairing) throws { try save(pairing, account: pairingAccount) }
    public static func load() -> Pairing? { load(Pairing.self, account: pairingAccount) }
    public static func clear() { clear(account: pairingAccount) }

    public static func saveHitlp(_ config: HitlpConfig) throws { try save(config, account: hitlpAccount) }
    public static func loadHitlp() -> HitlpConfig? { load(HitlpConfig.self, account: hitlpAccount) }
    public static func clearHitlp() { clear(account: hitlpAccount) }

    static func save<T: Encodable>(_ value: T, account: String) throws {
        let data = try JSONEncoder().encode(value)
        SecItemDelete(query(account) as CFDictionary)
        var add = query(account)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    static func load<T: Decodable>(_ type: T.Type, account: String) -> T? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    static func clear(account: String) { SecItemDelete(query(account) as CFDictionary) }
}
