package com.lsmom.core.document;

import org.dromara.common.oss.factory.OssFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** 经 RuoYi OSS 模块（S3 协议）读取文件，按文件上传时的存储配置定位客户端。 */
@Component
public class OssDocumentContentSource implements DocumentContentSource {

    private final JdbcTemplate jdbc;

    public OssDocumentContentSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InputStream open(long ossId) throws IOException {
        Map<String, Object> row = jdbc.queryForMap("select file_name, url, service from sys_oss where oss_id = ?", ossId);
        return OssFactory.instance((String) row.get("service")).getObjectContent((String) row.get("url"));
    }
}
