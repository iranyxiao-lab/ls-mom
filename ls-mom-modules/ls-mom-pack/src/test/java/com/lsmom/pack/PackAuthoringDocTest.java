package com.lsmom.pack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 12.7：按 docs/pack-authoring.md 编写的示例客户 Pack（packs/examples/acme-visitor）可打包、安装，场景与越权测试通过。 */
class PackAuthoringDocTest {

    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    private static final Path EXAMPLE = ROOT.resolve("packs/examples/acme-visitor");

    @Test
    void 示例客户Pack按文档打包并安装成功() throws Exception {
        PackFixture f = new PackFixture();
        // 共享测试库：换成唯一命名空间（表名随之变化）
        String ns = PackFixture.code("visitor");
        String table = "c_" + ns.replace('.', '_') + "_visit";
        Map<String, byte[]> files = new LinkedHashMap<>();
        PackSource.fromDirectory(EXAMPLE).files().forEach((k, v) -> files.put(k, new String(v, StandardCharsets.UTF_8)
            .replace("acme.visitor", ns).replace("c_acme_visitor_visit", table).replace("idx_acme_visitor_visit_host", "idx_" + table + "_host")
            .getBytes(StandardCharsets.UTF_8)));
        PackSource src = PackSource.of(files);
        // 文档示例本身可解析（命名空间、清单声明与目录一致）
        PackContents original = PackContents.parse(PackSource.fromDirectory(EXAMPLE));
        assertThat(original.manifest().code()).isEqualTo("acme.visitor");
        assertThat(original.migrations()).extracting(PackContents.Migration::filename).containsExactly("V1__host_index.sql");

        PackInstaller.Result r = f.installer.install(PackSource.fromZip(src.toZip()), 1L);
        assertThat(r.status()).isEqualTo("INSTALLED");
        assertThat(f.store.get(ns).manifest().path("entries").size()).isEqualTo(1);
        PackTester.Report rep = f.tester.run(ns);
        assertThat(rep.regression()).singleElement().satisfies(c -> assertThat(c.passed()).as(c.message()).isTrue());
        assertThat(rep.privilege()).hasSize(3).allMatch(PackTester.Case::passed);
        assertThat(f.core.jdbc.queryForObject("select count(*) from pg_indexes where indexname = ?", Integer.class, "idx_" + table + "_host"))
            .isEqualTo(1);
    }

    @Test
    void Python打包脚本生成的zip可被平台读取() throws Exception {
        Path out = Files.createTempFile("visitor", ".zip");
        Process p = new ProcessBuilder("python3", ROOT.resolve("scripts/pack-build.py").toString(), EXAMPLE.toString(), out.toString())
            .redirectErrorStream(true).start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isZero();
        PackSource fromPy = PackSource.fromZip(Files.readAllBytes(out));
        assertThat(fromPy.digest()).isEqualTo(PackSource.fromDirectory(EXAMPLE).digest());
        Files.deleteIfExists(out);
    }
}
