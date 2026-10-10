package com.stockmarketanalyzer.backend.common;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException instrument(String symbol) {
        return new ResourceNotFoundException("No instrument found for symbol '%s'".formatted(symbol));
    }
}
