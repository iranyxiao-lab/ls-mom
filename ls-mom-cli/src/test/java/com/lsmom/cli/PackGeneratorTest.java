package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.pack.ChangeSetPipeline;
import com.lsmom.pack.PackFixture;
import com.lsmom.pack.PackInstaller;
import com.lsmom.pack.PackSource;
import com.lsmom.pack.PackTester;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/** spec ai/ls-ir-conversion「由 LS-IR 生成产物」AI-02：生成物能编译、在临时数据库安装并通过生成的测试，并打包为 ChangeSet。 */
class PackGeneratorTest {

    @Test
    void AI02_新台账LS_IR生成物可编译_安装并通过生成的测试_打包为ChangeSet() throws Exception {
        JsonNode ir = LsIrTest.sample();
        assertThat(LsIr.validate(ir)).isEmpty();
        Path out = Files.createTempDirectory("lsir-gen");
        PackGenerator.Output o = PackGenerator.generate(ir);
        PackGenerator.write(o, out);

        // 生成的 Pack 目录
        assertThat(o.pack()).containsKeys("pack.yaml", "concepts/permit.yaml", "operations/permit-lifecycle.yaml", "rules/approveByHuman.yaml",
            "views/permit-form.json", "metrics/permit-count.yaml", "migrations/V1__permit_location_index.sql", "tests/scenarios/01.yaml",
            "provenance.json", "docs/permissions.yaml");
        assertThat(o.pack().get("views/permit-form.json")).contains("\"widget\" : \"userRef\"").contains("\"type\" : \"subTable\"")
            .contains("\"label\" : \"动火\"");
        assertThat(o.pack().get("docs/permissions.yaml")).contains("acme:permit:approve").contains("crud:acme.permit.Permit:list");

        // 1. 编译：Operation 骨架与 JUnit 测试
        Path classes = Files.createDirectories(out.resolve("classes"));
        List<String> sources;
        try (Stream<Path> s = Files.walk(out.resolve("java"))) {
            sources = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        assertThat(sources).hasSize(2).anyMatch(p -> p.endsWith("ExtendPermitOperation.java")).anyMatch(p -> p.endsWith("GeneratedPackTest.java"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-classpath", System.getProperty("java.class.path"), "-proc:none",
            "-encoding", "UTF-8"));
        args.addAll(sources);
        ByteArrayOutputStream diag = new ByteArrayOutputStream();
        assertThat(javac.run(null, diag, diag, args.toArray(String[]::new))).as(diag.toString()).isZero();
        QualityReport.recordRun(out, "compile", "PASSED", null);

        // 2. 在临时数据库安装并运行黄金场景
        PackFixture f = new PackFixture();
        PackInstaller.Result r = f.installer.install(PackSource.fromDirectory(out.resolve("pack")), null);
        assertThat(r.status()).isEqualTo("INSTALLED");
        QualityReport.recordRun(out, "install", "PASSED", null);
        PackTester.Report rep = f.tester.run("acme.permit");
        rep.regression().forEach(c -> assertThat(c.passed()).as(c.name() + "：" + c.message()).isTrue());
        assertThat(rep.passed()).isTrue();
        assertThat(f.core.jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'idx_acme_permit_location'", Integer.class)).isEqualTo(1);

        // 3. 运行生成的 JUnit 测试
        System.setProperty("lsmom.generated.pack", out.resolve("pack").toString());
        try (URLClassLoader cl = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> test = cl.loadClass("acme.permit.GeneratedPackTest");
            LauncherDiscoveryRequest req = request().selectors(selectClass(test)).build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener l = new SummaryGeneratingListener();
            launcher.execute(req, l);
            TestExecutionSummary sum = l.getSummary();
            assertThat(sum.getTestsSucceededCount()).as(sum.getFailures().toString()).isEqualTo(1);
            assertThat(sum.getTotalFailureCount()).isZero();
            QualityReport.recordRun(out, "test", "PASSED", null);
        } finally {
            System.clearProperty("lsmom.generated.pack");
        }

        // 4. ChangeSet 制品：zip 含 sha256 清单，提交后通过校验阶段
        PackSource zip = PackSource.fromZip(Files.readAllBytes(out.resolve("changeset/acme.permit-1.0.0.zip")));
        assertThat(zip.digest()).isEqualTo(o.digest());
        ChangeSetPipeline.ChangeSet cs = f.pipeline.submit(zip, null, "AI", null);
        assertThat(cs.status()).isEqualTo("DRAFT");
        assertThat(cs.stages()).anySatisfy(s -> assertThat(s.status()).isEqualTo("PASSED"));

        // 报告：本次生成的指标
        JsonNode rpt = LsIr.JSON.readTree(Files.readString(out.resolve("report.json")));
        assertThat(rpt.path("validation").asText()).isEqualTo("PASSED");
        assertThat(rpt.path("manualEdits").get(0).path("reason").asText()).isEqualTo("补充作业类型枚举");
        assertThat(rpt.path("requirementToUatHours").asDouble()).isEqualTo(8.5);

        // 质量指标报告：本次 + 一次首轮测试失败的生成
        Path other = Files.createTempDirectory("lsir-gen2");
        PackGenerator.write(o, other);
        QualityReport.recordRun(other, "compile", "PASSED", null);
        QualityReport.recordRun(other, "install", "PASSED", null);
        QualityReport.recordRun(other, "test", "FAILED", "场景数据缺必填");
        QualityReport.recordRun(other, "test", "PASSED", null);
        Path qf = out.resolve("quality.json");
        ByteArrayOutputStream qo = new ByteArrayOutputStream();
        assertThat(LsMomCli.run(new String[]{"quality", out.toString(), other.toString(), "--out", qf.toString()}, new java.io.PrintStream(qo),
            new java.io.PrintStream(qo))).isZero();
        JsonNode q = LsIr.JSON.readTree(Files.readString(qf));
        assertThat(q.path("runs").asInt()).isEqualTo(2);
        assertThat(q.path("validationPassRate").asDouble()).isEqualTo(1.0);
        assertThat(q.path("compileAndInstallFirstPassRate").asDouble()).isEqualTo(1.0);
        assertThat(q.path("testFirstPassRate").asDouble()).isEqualTo(0.5);
        assertThat(q.path("manualEdits").path("count").asInt()).isEqualTo(2);
        assertThat(q.path("manualEdits").path("lines").asInt()).isEqualTo(6);
        assertThat(q.path("manualEdits").path("reasons").path("补充作业类型枚举").asInt()).isEqualTo(2);
        assertThat(q.path("reuseRatio").asDouble()).isZero();
        assertThat(q.path("requirementToUatHoursAvg").asDouble()).isEqualTo(8.5);
    }

    @Test
    void 命令行generate_校验失败时不生成() throws Exception {
        Path out = Files.createTempDirectory("lsir-cli");
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        assertThat(LsMomCli.run(new String[]{"generate", LsIrTest.SAMPLE.toString(), "--out", out.toString()}, new java.io.PrintStream(o),
            new java.io.PrintStream(e))).as(e.toString()).isZero();
        assertThat(out.resolve("pack/pack.yaml")).exists();
        assertThat(out.resolve("changeset/acme.permit-1.0.0.zip")).exists();
        assertThat(o.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("sha256");

        com.fasterxml.jackson.databind.node.ObjectNode bad = (com.fasterxml.jackson.databind.node.ObjectNode) LsIrTest.sample();
        bad.remove("provenance");
        Path badFile = Files.createTempFile("ir", ".json");
        Files.writeString(badFile, bad.toString());
        Path out2 = Files.createTempDirectory("lsir-cli");
        assertThat(LsMomCli.run(new String[]{"generate", badFile.toString(), "--out", out2.toString()}, new java.io.PrintStream(o),
            new java.io.PrintStream(e))).isEqualTo(1);
        assertThat(out2.resolve("pack")).doesNotExist();
    }
}
