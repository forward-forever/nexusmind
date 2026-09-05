package com.wude.nexusmind.knowledge.parser;

import java.nio.file.Path;
import java.util.Set;

public interface DocumentParser {

    Set<String> supportedExtensions();

    ParsedDocument parse(Path documentPath);
}
