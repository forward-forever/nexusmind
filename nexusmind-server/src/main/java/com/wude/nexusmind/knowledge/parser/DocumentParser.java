package com.wude.nexusmind.knowledge.parser;

import java.nio.file.Path;
import java.util.Set;

public interface DocumentParser {

    /**
     * 获取支持的文件扩展名
     * @return 支持的文件扩展名
     */
    Set<String> supportedExtensions();

    /**
     * 解析文档
     * @param documentPath 文档路径
     * @return 解析后的文档
     */
    ParsedDocument parse(Path documentPath);
}
