package com.lsmom.core.document;

import java.io.IOException;
import java.io.InputStream;

/** 读取对象存储文件内容（默认实现走 RuoYi OSS 模块），便于测试替换。 */
public interface DocumentContentSource {

    InputStream open(long ossId) throws IOException;
}
