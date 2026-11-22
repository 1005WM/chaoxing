// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Testing
@testable import ChaoxingCore

@Suite("Protocol crypto regression tests")
struct CryptoRegressionTests {
    // Independent OpenSSL AES-128-CBC/PKCS7 vectors with the public protocol key as IV.
    @Test func loginEncryptionMatchesIndependentVectors() throws {
        #expect(try ChaoxingCrypto.encryptLoginField("test-account-001") == "l4yAQPAzbLzfFxr9mqXAJ/2nEzt/bhpxJkywgDme93Y=")
        #expect(try ChaoxingCrypto.encryptLoginField("学习用-password") == "GaDwrMeYCYfXqmR728+wOWUmjioVVFmq+BzxT7Px6R0=")
    }

    @Test func invalidAESKeyLengthIsRejectedInsteadOfTruncated() throws {
        for key in ["", "x", "abcdefghijklmnopq", "中文密钥"] {
            #expect(throws: ChaoxingError.self) {
                try ChaoxingCrypto.encryptLoginField("synthetic", key: key)
            }
        }
    }

    // Independent OpenSSL DES-ECB/PKCS7 fixture; not an account credential.
    @Test func imPasswordDecryptsIndependentVectorAndPreservesPlainInput() {
        #expect(ChaoxingCrypto.decryptIMPasswordDES("f3174b9efad13cd1f6d76ffc946082f35290812e720226f5") == "Synthetic-IM-password")
        #expect(ChaoxingCrypto.decryptIMPasswordDES("plain-synthetic-password") == "plain-synthetic-password")
        let ciphertext = "f3174b9efad13cd1f6d76ffc946082f35290812e720226f5"
        #expect(ChaoxingCrypto.decryptIMPasswordDES(ciphertext, key: "x") == ciphertext)
    }
}
