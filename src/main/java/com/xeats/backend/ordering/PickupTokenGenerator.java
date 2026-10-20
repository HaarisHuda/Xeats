package com.xeats.backend.ordering;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/** Generates uniformly random 50-bit, human-readable Crockford Base32 pickup codes. */
@Component
public class PickupTokenGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String nextCode() {
        char[] code = new char[10];
        for (int index = 0; index < code.length; index++) {
            code[index] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
