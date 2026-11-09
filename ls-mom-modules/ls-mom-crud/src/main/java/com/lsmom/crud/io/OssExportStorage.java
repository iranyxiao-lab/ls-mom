package com.lsmom.crud.io;

import org.dromara.common.oss.entity.UploadResult;
import org.dromara.common.oss.factory.OssFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** 导出文件写入默认 OSS 配置的对象存储（SeaweedFS / S3）。 */
@Component
public class OssExportStorage implements ExportStorage {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    public String put(String fileName, byte[] content) {
        UploadResult r = OssFactory.instance().uploadSuffix(content, ".xlsx", XLSX);
        return r.getFilename();
    }

    @Override
    public InputStream open(String key) throws IOException {
        return OssFactory.instance().getObjectContent(key);
    }
}
