package ru.pachan.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ResponseException {

    public static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static void notFound(String message) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

}
