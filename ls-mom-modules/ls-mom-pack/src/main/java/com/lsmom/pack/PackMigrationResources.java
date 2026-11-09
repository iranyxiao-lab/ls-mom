package com.lsmom.pack;

import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;

import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** 把 Pack 内 migrations/ 目录的脚本作为 Flyway 迁移资源。 */
final class PackMigrationResources implements ResourceProvider {

    private final List<PackContents.Migration> scripts;

    PackMigrationResources(List<PackContents.Migration> scripts) {
        this.scripts = scripts;
    }

    @Override
    public LoadableResource getResource(String name) {
        return scripts.stream().filter(s -> s.filename().equals(name)).findFirst().map(PackMigrationResources::resource).orElse(null);
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        return scripts.stream().filter(s -> s.filename().startsWith(prefix) && Arrays.stream(suffixes).anyMatch(x -> s.filename().endsWith(x)))
            .map(PackMigrationResources::resource).toList();
    }

    private static LoadableResource resource(PackContents.Migration s) {
        return new LoadableResource() {
            @Override
            public Reader read() {
                return new StringReader(s.sql());
            }

            @Override
            public String getAbsolutePath() {
                return "pack:" + s.path();
            }

            @Override
            public String getAbsolutePathOnDisk() {
                return getAbsolutePath();
            }

            @Override
            public String getFilename() {
                return s.filename();
            }

            @Override
            public String getRelativePath() {
                return s.filename();
            }
        };
    }
}
