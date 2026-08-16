package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

import org.junit.jupiter.api.Test;

final class Rfc8291WebPushPayloadEncoderTest {
    @Test
    void reproducesTheCompleteRfc8291AppendixAEncoding() throws Exception {
        byte[] salt = decode("DGv6ra1nlYgDCS1FRnbzlw");
        SecureRandom deterministic = new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) { System.arraycopy(salt, 0, bytes, 0, bytes.length); }
        };
        KeyPair applicationServer = keyPair(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
                "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        Rfc8291WebPushPayloadEncoder encoder =
                new Rfc8291WebPushPayloadEncoder(deterministic, () -> applicationServer);

        byte[] actual = encoder.encode(
                decode("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24"),
                decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                decode("BTBZMqHH6r4Tts7J_aSIgg"));

        assertArrayEquals(decode(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"),
                actual);
    }

    @Test
    void rejectsUnboundedPlaintextAndMalformedSubscriptionSecrets() {
        Rfc8291WebPushPayloadEncoder encoder = new Rfc8291WebPushPayloadEncoder();
        byte[] publicKey = new byte[65]; publicKey[0] = 0x04;
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(new byte[0], publicKey, new byte[16]));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(new byte[3_994], publicKey, new byte[16]));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(new byte[] {1}, new byte[64], new byte[16]));
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(new byte[] {1}, publicKey, new byte[15]));
    }

    private static KeyPair keyPair(String encodedPublic, String encodedPrivate) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("EC");
        java.security.AlgorithmParameters algorithm = java.security.AlgorithmParameters.getInstance("EC");
        algorithm.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec parameters = algorithm.getParameterSpec(ECParameterSpec.class);
        byte[] publicBytes = decode(encodedPublic);
        ECPublicKey publicKey = (ECPublicKey) factory.generatePublic(new ECPublicKeySpec(new ECPoint(
                new BigInteger(1, java.util.Arrays.copyOfRange(publicBytes, 1, 33)),
                new BigInteger(1, java.util.Arrays.copyOfRange(publicBytes, 33, 65))), parameters));
        return new KeyPair(publicKey, factory.generatePrivate(
                new ECPrivateKeySpec(new BigInteger(1, decode(encodedPrivate)), parameters)));
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
