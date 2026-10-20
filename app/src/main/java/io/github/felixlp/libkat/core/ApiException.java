package io.github.felixlp.libkat.core;

import java.io.IOException;

public class ApiException extends IOException {
    public final int code;

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }
}
