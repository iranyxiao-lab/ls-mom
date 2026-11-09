package com.lsmom.pack;

import com.lsmom.core.error.LsBizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 标准 Pack：随产品发布、放在类路径 {@code lsmom-packs/<Pack 编码>/} 下（如 pack-production 模块中的轻生产 Pack）。
 * 启动时（核心 Concept、状态机、内置指标登记之后）逐个安装或升级；内容未变时安装器直接跳过。
 * 已安装更高版本（降级）或已停用的 Pack 不动；安装失败只记录日志，Pack 状态在应用中心可见，不阻止应用启动。
 */
public class StandardPacks implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(StandardPacks.class);
    static final String ROOT = "lsmom-packs/";

    private final PackInstaller installer;
    private final PackStore store;
    private final ResourcePatternResolver resolver;

    public StandardPacks(PackInstaller installer, PackStore store) {
        this(installer, store, new PathMatchingResourcePatternResolver());
    }

    public StandardPacks(PackInstaller installer, PackStore store, ResourcePatternResolver resolver) {
        this.installer = installer;
        this.store = store;
        this.resolver = resolver;
    }

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public void run(ApplicationArguments args) {
        installAll();
    }

    public List<PackInstaller.Result> installAll() {
        List<PackInstaller.Result> results = new ArrayList<>();
        List<PackSource> sources = discover();
        // 依赖顺序：被依赖的先装
        List<PackManifest> manifests = sources.stream().map(PackManifest::parse).toList();
        List<String> order = DependencyPlanner.plan(manifests, store.installed()).stream().map(PackManifest::code).toList();
        for (String code : order) {
            PackSource src = sources.get(indexOf(manifests, code));
            var existing = store.find(code);
            if (existing.isPresent() && "DISABLED".equals(existing.get().status())) {
                log.info("标准 Pack {} 已停用，跳过安装", code);
                continue;
            }
            try {
                PackInstaller.Result r = installer.install(src, null);
                if (!"UNCHANGED".equals(r.status())) log.info("标准 Pack {} {}：{}", code, r.version(), r.status());
                results.add(r);
            } catch (LsBizException e) {
                if ("PACK_DOWNGRADE".equals(e.getCode())) {
                    log.info("标准 Pack {} 已安装更高版本，跳过：{}", code, e.getMessage());
                } else {
                    log.error("标准 Pack {} 安装失败：{} {}", code, e.getCode(), e.getMessage());
                }
            }
        }
        return results;
    }

    private static int indexOf(List<PackManifest> ms, String code) {
        for (int i = 0; i < ms.size(); i++) if (ms.get(i).code().equals(code)) return i;
        throw new IllegalStateException(code);
    }

    /** 类路径下的全部标准 Pack 源。 */
    public List<PackSource> discover() {
        List<PackSource> out = new ArrayList<>();
        try {
            for (Resource manifest : resolver.getResources("classpath*:" + ROOT + "*/" + PackSource.MANIFEST)) {
                String url = manifest.getURL().toString();
                String base = url.substring(0, url.length() - PackSource.MANIFEST.length());
                String code = base.substring(base.lastIndexOf(ROOT) + ROOT.length(), base.length() - 1);
                Map<String, byte[]> files = new TreeMap<>();
                for (Resource r : resolver.getResources("classpath*:" + ROOT + code + "/**")) {
                    String u = r.getURL().toString();
                    if (!u.startsWith(base) || u.endsWith("/") || !r.isReadable()) continue;
                    try (InputStream in = r.getInputStream()) {
                        files.put(u.substring(base.length()), in.readAllBytes());
                    } catch (IOException e) {
                        // 目录条目在部分类加载器上可读但无内容，忽略
                    }
                }
                out.add(PackSource.of(files));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取标准 Pack 失败", e);
        }
        return out;
    }
}
