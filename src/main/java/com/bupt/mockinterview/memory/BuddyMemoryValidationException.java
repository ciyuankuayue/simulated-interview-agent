package com.bupt.mockinterview.memory;

/** Invalid user-editable memory input; mapped to 400 only by its own API controller. */
public class BuddyMemoryValidationException extends RuntimeException {
    public BuddyMemoryValidationException(String message) {
        super(message);
    }
}
