package com.pastebin.pastecreate.service;

import java.math.BigInteger;
import java.util.UUID;

public class Base62GeneratorService {

    private static final String BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static String generateKey(int length) {
        UUID uuid = UUID.randomUUID();

        // Convert UUID to BigInteger
        BigInteger bigInt = new BigInteger(uuid.toString().replace("-", ""), 16);

        // Encode to base62
        String encoded = encodeBase62(bigInt);

        // Return desired length
        return encoded.substring(0, length);
    }

    private static String encodeBase62(BigInteger value) {
        StringBuilder sb = new StringBuilder();

        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = value.divideAndRemainder(BigInteger.valueOf(62));
            sb.append(BASE62.charAt(divRem[1].intValue()));
            value = divRem[0];
        }

        return sb.reverse().toString();
    }
}