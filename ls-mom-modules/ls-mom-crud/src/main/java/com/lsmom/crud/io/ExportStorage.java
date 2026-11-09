package com.lsmom.crud.io;

import java.io.IOException;
import java.io.InputStream;

/** 导出文件存储（生产经 RuoYi OSS 写入对象存储）。 */
public interface ExportStorage {

    /** 保存文件，返回对象键。 */
    String put(String fileName, byte[] content) throws IOException;

    InputStream open(String key) throws IOException;
}
