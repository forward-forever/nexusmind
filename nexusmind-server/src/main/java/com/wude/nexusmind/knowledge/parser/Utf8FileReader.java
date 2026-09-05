package com.wude.nexusmind.knowledge.parser;

import com.wude.nexusmind.knowledge.exception.DocumentParsingException;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class Utf8FileReader {

    private Utf8FileReader() {
    }

    static String read(Path path, String documentType) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException exception) {
            throw new DocumentParsingException(documentType + " file is not valid UTF-8", exception);
        } catch (IOException exception) {
            throw new DocumentParsingException("Failed to read " + documentType + " document", exception);
        }
    }
}
